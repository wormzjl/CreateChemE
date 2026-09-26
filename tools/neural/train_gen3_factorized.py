"""Train gen3 phase-total/composition heads on qualified whole-column profiles (NumPy only).

The gen2 encoder is reused unchanged. A feed-fraction prior and normalized phase
composition enforce flow sums without changing the native solver or its audits.
"""
import argparse
from collections import Counter
import copy
import hashlib
import json
import os
from pathlib import Path
import time

for name in ("OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "OMP_NUM_THREADS"):
    os.environ.setdefault(name, "1")
import numpy as np
import train_generalized as base

REVISION = "v3-factorized-stage-1"
TRACE_FLOOR = 1e-10
MAX_LOG_TOTAL = np.log1p(1000)


def targets(row):
    inp, seed = row["input"], row["seed"]
    feed = np.asarray(inp["feedComponentMolarFlowsMolPerSecond"], dtype=float)
    total, c, count = feed.sum(), len(feed), inp["stageCount"]+2
    y = np.zeros((count, 4*c+5), dtype=float)
    fractions = np.zeros((count, 2, c), dtype=float)
    y[:, 0] = seed["temperatures"]
    floor = np.maximum(feed, total*1e-12)*TRACE_FLOOR
    active = feed > 0
    for phase, name in enumerate(("liquid", "vapor")):
        q = np.asarray(seed[name], dtype=float)
        qtotal = q.sum(axis=1)
        y[:, 1+phase] = np.log1p(qtotal/total)
        present = (q >= floor) & active
        y[:, 5+2*c+phase*c:5+3*c+phase*c] = np.where(present, 8, -8)
        valid = qtotal > 0
        fractions[valid, phase] = q[valid] / qtotal[valid, None]
        offset = 3+phase*c
        for n in np.flatnonzero(valid):
            logratio = np.log(np.maximum(q[n, active], floor[active])/qtotal[n]/(feed[active]/total))
            y[n, offset:offset+c][active] = logratio-logratio.mean()
    y[:, 3+2*c] = np.log1p(np.asarray(seed["freeWater"])/(total*1e-8))
    y[:, 4+2*c] = seed["wetTrays"]
    return y, fractions


def dataset(rows):
    x, y, fractions, weight = [], [], [], []
    for row in rows:
        xx = base.node_features(row["input"], row["seed"]["branch"])
        yy, ff = targets(row)
        x.append(xx); y.append(yy); fractions.append(ff); weight.extend([1/len(xx)]*len(xx))
    return np.vstack(x), np.vstack(y), np.concatenate(fractions), np.asarray(weight)


def loss_and_derivative(pred, wanted, raw_target, fractions, feed_fraction, weight, output_mean, output_scale, c):
    """Derivative is with respect to standardized network outputs, including the softmax Jacobian."""
    raw = pred*output_scale+output_mean
    error = pred-wanted
    derivative = np.zeros_like(pred)
    per_node = np.zeros(len(pred), dtype=pred.dtype)
    scalar_indices = [0, 1, 2, 3+2*c, 4+2*c]
    scalar_weights = np.asarray([3., 5., 5., .5, 2.], dtype=pred.dtype)
    per_node += np.sum(error[:, scalar_indices]**2*scalar_weights, axis=1)
    derivative[:, scalar_indices] = 2*error[:, scalar_indices]*scalar_weights
    active = feed_fraction > 0
    logfeed = np.log(np.maximum(feed_fraction, 1e-30))
    for phase in range(2):
        offset = 3+phase*c; support = 5+2*c+phase*c
        mask = active & (raw_target[:, 1+phase] > 0)[:, None]
        logits = raw[:, offset:offset+c]+logfeed
        logits = np.where(active, logits, -1e30)
        logits -= logits.max(axis=1, keepdims=True)
        probability = np.exp(logits); probability /= probability.sum(axis=1, keepdims=True)
        actual = fractions[:, phase]
        valid = actual.sum(axis=1) > 0
        # Bulk composition KL prevents log-space trace accuracy from dominating material traffic.
        kl = np.sum(np.where(actual > 0, actual*(np.log(np.maximum(actual, 1e-30))-np.log(np.maximum(probability, 1e-30))), 0), axis=1)
        per_node += 2*kl*valid
        derivative[:, offset:offset+c] += 2*(probability-actual)*valid[:, None]*output_scale[offset:offset+c]
        # Small centered-log-ratio term keeps dilute-component behavior learnable.
        per_node += .1*np.sum(error[:, offset:offset+c]**2*mask, axis=1)/c
        derivative[:, offset:offset+c] += .2*error[:, offset:offset+c]*mask/c
        scores = raw[:, support:support+c]
        labels = raw_target[:, support:support+c] > 0
        bce = np.logaddexp(0, scores)-labels*scores
        per_node += .2*np.sum(bce*active, axis=1)/c
        logistic = 1/(1+np.exp(-np.clip(scores, -60, 60)))
        derivative[:, support:support+c] += .2*(logistic-labels)*active*output_scale[support:support+c]/c
    normalization = weight.sum()
    derivative *= weight[:, None]/normalization
    return float(np.sum(per_node*weight)/normalization), derivative


def fit_profile(train, validation, args, rng, mean, scale, output_mean, output_scale, c):
    x, raw, fractions, weight = train
    xv, rawv, fv, wv = validation
    xn, y = ((x-mean)/scale).astype(np.float32), ((raw-output_mean)/output_scale).astype(np.float32)
    xvn, yv = ((xv-mean)/scale).astype(np.float32), ((rawv-output_mean)/output_scale).astype(np.float32)
    priors, pv = x[:, 17:17+c].astype(np.float32), xv[:, 17:17+c].astype(np.float32)
    raw, rawv, fractions, fv, weight, wv = [a.astype(np.float32) for a in (raw, rawv, fractions, fv, weight, wv)]
    om, oscale = output_mean.astype(np.float32), output_scale.astype(np.float32)
    params = base.network([x.shape[1], args.hidden_width, args.hidden_width, y.shape[1]], rng)
    first, second = [np.zeros_like(p) for p in params], [np.zeros_like(p) for p in params]
    best, best_loss, best_epoch, step = None, float("inf"), 0, 0
    trace, started = [], time.perf_counter()
    for epoch in range(1, args.epochs+1):
        order = rng.permutation(len(xn))
        for start in range(0, len(xn), args.batch_size):
            ix = order[start:start+args.batch_size]
            activations = base.forward(params, xn[ix]); step += 1
            _, derivative = loss_and_derivative(activations[-1], y[ix], raw[ix], fractions[ix], priors[ix], weight[ix], om, oscale, c)
            base.adam(params, base.gradients(params, activations, derivative), first, second, step,
                      args.learning_rate*(.5 if epoch > args.epochs*.75 else 1))
        if epoch % 5 == 0 or epoch == args.epochs:
            pred = base.forward(params, xvn)[-1]
            loss, _ = loss_and_derivative(pred, yv, rawv, fv, pv, wv, om, oscale, c)
            if not np.isfinite(loss): raise ValueError("Nonfinite factorized validation loss")
            if loss < best_loss: best, best_loss, best_epoch = copy.deepcopy(params), loss, epoch
            item = {"epoch": epoch, "validation_loss": loss, "best_epoch": best_epoch, "elapsed_seconds": time.perf_counter()-started}
            trace.append(item)
            if epoch % 25 == 0: print(json.dumps({"factorized_training": item}), flush=True)
            if args.patience and epoch-best_epoch >= args.patience: break
    return best, {"selected_epoch": best_epoch, "validation_loss": best_loss, "trace": trace}


def decode(inp, raw, branch, presence_threshold):
    feed = np.asarray(inp["feedComponentMolarFlowsMolPerSecond"], dtype=float)
    total, c = feed.sum(), len(feed)
    if raw.shape != (inp["stageCount"]+2, 4*c+5): raise ValueError("output_shape")
    if not np.isfinite(raw).all(): raise ValueError("nonfinite_prediction")
    if np.any(raw[:, 0] < 100) or np.any(raw[:, 0] > 1500): raise ValueError("unbounded_temperature")
    if np.any(raw[:, 1:3] > MAX_LOG_TOTAL): raise ValueError("unbounded_phase_total")
    if np.any(np.abs(raw[:, 3:3+2*c]) > 120) or np.any(np.abs(raw[:, 5+2*c:]) > 120): raise ValueError("unbounded_composition_or_presence")
    if np.any(raw[:, 3+2*c] > 30): raise ValueError("unbounded_water")
    if not np.isfinite(presence_threshold) or not 0 <= presence_threshold <= .5: raise ValueError("invalid_presence_threshold")
    temperature = raw[:, 0].copy()
    water = np.expm1(np.maximum(raw[:, 3+2*c], 0))*total*1e-8
    wet = raw[:, 4+2*c] >= .5; wet[[0, -1]] = False
    if not inp.get("steamFeeds"): wet[:] = False
    water[~wet] = 0
    results = []
    cutoff = -np.inf if presence_threshold == 0 else np.log(presence_threshold/(1-presence_threshold))
    floor = np.maximum(feed, total*1e-12)*TRACE_FLOOR
    for phase in range(2):
        offset, support = 3+phase*c, 5+2*c+phase*c
        phase_total = np.expm1(np.maximum(raw[:, 1+phase], 0))*total
        if (phase == 0 and branch == "VAPOR_ONLY") or (phase == 1 and branch == "LIQUID_ONLY"): phase_total[0] = 0
        logits = raw[:, offset:offset+c]+np.log(np.maximum(feed/total, 1e-300))
        logits[:, feed == 0] = -np.inf
        present = (raw[:, support:support+c] >= cutoff) & (feed > 0)
        missing = ~present.any(axis=1)
        present[np.flatnonzero(missing), np.argmax(logits[missing], axis=1)] = True
        masked = np.where(present, logits, -np.inf)
        probabilities = np.exp(masked-masked.max(axis=1, keepdims=True))
        probabilities /= probabilities.sum(axis=1, keepdims=True)
        flows = probabilities*phase_total[:, None]
        flows[flows < floor] = 0
        retained = flows.sum(axis=1)
        positive = retained > 0
        flows[positive] *= (phase_total[positive]/retained[positive])[:, None]
        results.append(flows)
    for spec in inp["specifications"]:
        if "kelvin" in spec: temperature[0] = spec["kelvin"]
    return {"temperatures": temperature, "liquid": results[0], "vapor": results[1], "freeWater": water, "wetTrays": wet, "branch": branch}


def predict(model, inp):
    g = base.global_features(inp)
    lower, upper = np.asarray(model["globalMin"]), np.asarray(model["globalMax"])
    slack = 1e-9*np.maximum(1, np.maximum(np.abs(lower), np.abs(upper)))
    if np.any(g < lower-slack) or np.any(g > upper+slack): raise ValueError("outside_global_bounds")
    if not model["minimumStages"] <= inp["stageCount"] <= model["maximumStages"]: raise ValueError("outside_stage_bounds")
    if inp["packageId"] != model["packageId"] or inp["componentBasis"]["componentIds"] != model["components"]: raise ValueError("incompatible_property_basis")
    if "designConstraints" in model:
        reason = base.design_constraint_rejection(inp, model["designConstraints"])
        if reason: raise ValueError(reason)
    if model["traceFloorFraction"] != TRACE_FLOOR: raise ValueError("incompatible_trace_floor")
    logits = base.forward(base.import_layers(model["branchLayers"]), ((g-model["globalMean"])/model["globalScale"])[None, :])[-1][0]
    logits[~np.asarray(model["branchesSeen"])] = -np.inf
    if any(s.get("ratio", 0) > 0 for s in inp["specifications"]): logits[2] = -np.inf
    if not np.isfinite(logits).any(): raise ValueError("no_permitted_branch")
    branch = base.BRANCHES[int(np.argmax(logits))]
    x = base.node_features(inp, branch)
    raw = base.forward(base.import_layers(model["nodeLayers"]), (x-model["nodeMean"])/model["nodeScale"])[-1]
    raw = raw*model["outputScale"]+model["outputMean"]
    return decode(inp, raw, branch, model["presenceThreshold"])


def comparisons(model, rows):
    results = []
    for row in rows:
        result = {"id": row["id"], "split": row["split"], "stages": row["input"]["stageCount"]}
        try:
            if row["formulationRevision"] not in model["formulationRevisions"]: raise ValueError("untrained_formulation")
            predicted = predict(model, row["input"])
        except ValueError as rejection:
            results.append({**result, "prediction_supported": False, "rejection_reason": str(rejection)}); continue
        seed, inp = row["seed"], row["input"]
        result.update(prediction_supported=True, branch_correct=predicted["branch"] == seed["branch"],
                      temperature_mae_K=float(np.mean(np.abs(predicted["temperatures"]-seed["temperatures"]))),
                      wet_mask_exact=bool(np.array_equal(predicted["wetTrays"], seed["wetTrays"])))
        feed = np.asarray(inp["feedComponentMolarFlowsMolPerSecond"]); floor = np.maximum(feed, feed.sum()*1e-12)*TRACE_FLOOR
        errors = []
        for phase in ("liquid", "vapor"):
            actual = np.asarray(seed[phase]); q = predicted[phase]
            actual_total, predicted_total = actual.sum(axis=1), q.sum(axis=1)
            present = actual_total > feed.sum()*1e-8
            result[phase+"_total_relative_mae"] = float(np.mean(np.abs(predicted_total[present]-actual_total[present])/actual_total[present])) if present.any() else 0
            result[phase+"_total_feed_normalized_mae"] = float(np.mean(np.abs(predicted_total-actual_total))/feed.sum())
            result[phase+"_support_accuracy"] = float(np.mean((q >= floor) == (actual >= floor)))
            result[phase+"_component_flow_mae_mol_s"] = float(np.mean(np.abs(q-actual)))
            errors.append(np.log1p(q/(np.maximum(feed, feed.sum()*1e-12)*1e-5))-np.log1p(actual/(np.maximum(feed, feed.sum()*1e-12)*1e-5)))
        result["component_flow_log_rmse"] = float(np.sqrt(np.mean(np.concatenate(errors)**2)))
        results.append(result)
    return results


def summary(rows):
    accepted = [r for r in rows if r["prediction_supported"]]
    result = {"count": len(rows), "supported": len(accepted), "rejection_reasons": dict(Counter(r["rejection_reason"] for r in rows if not r["prediction_supported"]))}
    if accepted:
        for key in ("temperature_mae_K", "liquid_total_relative_mae", "vapor_total_relative_mae", "liquid_total_feed_normalized_mae", "vapor_total_feed_normalized_mae", "component_flow_log_rmse"):
            result[key+"_median"] = float(np.median([r[key] for r in accepted]))
        for key in ("branch_correct", "wet_mask_exact", "liquid_support_accuracy", "vapor_support_accuracy"):
            result[key+"_mean"] = float(np.mean([r[key] for r in accepted]))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--output-directory", type=Path)
    parser.add_argument("--epochs", type=int, default=600)
    parser.add_argument("--branch-epochs", type=int, default=600)
    parser.add_argument("--hidden-width", type=int, default=96)
    parser.add_argument("--batch-size", type=int, default=2048)
    parser.add_argument("--learning-rate", type=float, default=.0015)
    parser.add_argument("--patience", type=int, default=100)
    parser.add_argument("--presence-threshold", type=float, default=.02)
    parser.add_argument("--seed", type=int, default=240910)
    parser.add_argument("--model-id", default="tjl20-gen3-factorized-v1")
    parser.add_argument("--design-bounds", action="store_true")
    parser.add_argument("--design-file", type=Path)
    parser.add_argument("--prediction-split", choices=["train", "validation", "test", "all"], default="validation")
    parser.add_argument("--evaluate-model", type=Path)
    args = parser.parse_args()
    if not 1 <= args.epochs <= 20000 or not 1 <= args.branch_epochs <= 20000 or not 8 <= args.hidden_width <= 512 or args.batch_size < 16:
        raise ValueError("Invalid training dimensions or budget")
    if not 0 <= args.presence_threshold <= .5: raise ValueError("Invalid conservative support threshold")
    started = time.perf_counter()
    rows = base.load_rows(args.directory/"cases.jsonl")
    destination = args.output_directory or args.directory
    destination.mkdir(parents=True, exist_ok=True)
    qualified = [r for r in rows if r.get("success") is True and r.get("equilibriumQualified") is True and "seed" in r]
    evaluation = [r for r in qualified if args.prediction_split == "all" or r["split"] == args.prediction_split]
    if args.evaluate_model:
        model = json.loads(args.evaluate_model.read_text())
        results = comparisons(model, evaluation)
        (destination/("factorized-raw-"+args.prediction_split+".jsonl")).write_text("".join(json.dumps(r)+"\n" for r in results))
        print(json.dumps({"frozen_model_sha256": hashlib.sha256(args.evaluate_model.read_bytes()).hexdigest(), "raw": summary(results)}, indent=2)); return
    train = [r for r in qualified if r["split"] == "train"]
    validation = [r for r in qualified if r["split"] == "validation"]
    if len(train) < 12 or len(validation) < 3: raise ValueError("Insufficient qualified train/validation columns")
    first = train[0]; components = first["input"]["componentBasis"]["componentIds"]; c = len(components)
    for row in train+validation:
        if row["input"]["componentBasis"]["componentIds"] != components or row["input"]["packageId"] != first["input"]["packageId"] or row["seed"]["propertyRevision"] != first["seed"]["propertyRevision"]:
            raise ValueError("Mixed component/property basis")
    bounds_rows = rows if args.design_bounds else train
    domain = np.vstack([base.global_features(r["input"]) for r in bounds_rows])
    design_path = args.design_file or args.directory/"design.json"
    if args.design_file and not design_path.exists(): raise ValueError("Missing authored design metadata")
    design = json.loads(design_path.read_text()) if design_path.exists() else None
    constraints = base.derive_design_constraints(bounds_rows, design)
    training, validating = dataset(train), dataset(validation)
    x, y, _, weights = training
    xm, xs = base.moments(x, weights); ym, ys = base.moments(y, weights)
    ys[0] = max(ys[0], 25); ys[1:3] = np.maximum(ys[1:3], .1)
    ys[3:3+2*c] = np.maximum(ys[3:3+2*c], 1)
    ys[3+2*c] = max(ys[3+2*c], 1); ys[4+2*c] = 1
    ym[5+2*c:] = 0; ys[5+2*c:] = 1
    g = np.vstack([base.global_features(r["input"]) for r in train]); gv = np.vstack([base.global_features(r["input"]) for r in validation])
    gm, gs = base.moments(g)
    labels = np.asarray([base.BRANCHES.index(r["seed"]["branch"]) for r in train])
    lv = np.asarray([base.BRANCHES.index(r["seed"]["branch"]) for r in validation])
    rng = np.random.default_rng(args.seed)
    branch, branch_report = base.train_branch(((g-gm)/gs).astype(np.float32), labels, ((gv-gm)/gs).astype(np.float32), lv, args.branch_epochs, rng)
    profile, profile_report = fit_profile(training, validating, args, rng, xm, xs, ym, ys, c)
    model = {"featureRevision": REVISION, "modelId": args.model_id, "packageId": first["input"]["packageId"],
             "propertyRevision": first["seed"]["propertyRevision"], "components": components,
             "formulationRevisions": sorted({r["formulationRevision"] for r in train}),
             "minimumStages": min(r["input"]["stageCount"] for r in bounds_rows), "maximumStages": max(r["input"]["stageCount"] for r in bounds_rows),
             "branchesSeen": [bool(np.any(labels == b)) for b in range(3)], "presenceThreshold": args.presence_threshold, "traceFloorFraction": TRACE_FLOOR,
             "globalMean": gm.tolist(), "globalScale": gs.tolist(), "globalMin": domain.min(0).tolist(), "globalMax": domain.max(0).tolist(),
             "nodeMean": xm.tolist(), "nodeScale": xs.tolist(), "outputMean": ym.tolist(), "outputScale": ys.tolist(),
             "branchLayers": base.export_layers(branch), "nodeLayers": base.export_layers(profile), "designConstraints": constraints,
             "trainingLabelPolicy": "equilibrium-only"}
    model_path = destination/"factorized-model.json"
    model_path.write_text(json.dumps(model, separators=(",", ":"), allow_nan=False))
    results = comparisons(model, evaluation)
    (destination/("factorized-raw-"+args.prediction_split+".jsonl")).write_text("".join(json.dumps(r, allow_nan=False)+"\n" for r in results))
    parity = [{"id": r["id"], "input": r["input"], "seed": r["seed"], "split": "train",
               "global": base.global_features(r["input"]).tolist(), "nodes": base.node_features(r["input"], r["seed"]["branch"]).tolist(),
               "targets": targets(r)[0].tolist()} for r in train[:2]]
    (destination/"factorized-feature-parity.json").write_text(json.dumps(parity, separators=(",", ":"), allow_nan=False))
    report = {"model_id": args.model_id, "feature_revision": REVISION, "train_columns": len(train), "validation_columns": len(validation),
              "train_nodes": len(x), "architecture": "shared stage MLP with separately weighted phase-total heads, feed-prior softmax compositions, and optional conservative seed-support logits",
              "label_policy": "equilibrium-only", "seed": args.seed, "hidden_width": args.hidden_width, "epochs_requested": args.epochs,
              "presence_threshold": args.presence_threshold, "trace_floor_fraction": TRACE_FLOOR, "branch": branch_report, "profile": profile_report,
              "weight_parameters": sum(p.size for p in branch+profile), "parameter_storage_bytes_java": sum(p.size for p in branch+profile)*8,
              "model_file_bytes": model_path.stat().st_size, "elapsed_seconds": time.perf_counter()-started,
              "dataset_sha256": hashlib.sha256((args.directory/"cases.jsonl").read_bytes()).hexdigest(), "model_sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
              "normalization_fitted_on": "training columns only, equal total weight per column", "selection_on": "whole validation columns",
              "test_used_for_training_or_selection": False, "prediction_split": args.prediction_split, "raw": summary(results),
              "training_water_qualification": dict(Counter(r.get("waterQualification", "UNCLASSIFIED") for r in train)),
              "design_constraints": constraints}
    (destination/"factorized-training.json").write_text(json.dumps(report, indent=2, allow_nan=False))
    print(json.dumps({k: v for k, v in report.items() if k != "profile"}, indent=2), flush=True)


if __name__ == "__main__": main()
