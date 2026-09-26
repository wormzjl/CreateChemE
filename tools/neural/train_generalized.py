"""Train a shared stage-conditioned neural initializer using NumPy, with whole-column splits.

The branch classifier uses global inputs. The profile MLP conditions on the branch
and evaluates the same weights at every node, so no output dimension depends on N.
No held-out test targets enter preprocessing, optimization, or model selection.
"""
import argparse
from collections import Counter
import copy
import hashlib
import json
import os
from pathlib import Path
import time

# Bound BLAS parallelism while the Java teacher uses its own worker pool.
for name in ("OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "OMP_NUM_THREADS"):
    os.environ.setdefault(name, "1")
import numpy as np

REVISION = "v3-stage-conditioned-1"
BRANCHES = ("LIQUID_ONLY", "TWO_PHASE", "VAPOR_ONLY")
LOCAL_WIDTH = 22


def water_enthalpy(temperature):
    t = temperature / 1000
    return 1000 * (30.09200*t + 6.832514*t*t/2 + 6.793435*t**3/3
                   - 2.534480*t**4/4 - 0.082139/t - 250.8810 + 241.8264)


def equipment(inp):
    return (sorted(inp.get("pumparounds", []), key=lambda p: (p["returnTray"], p["drawTray"])),
            sorted(inp.get("sideDraws", []), key=lambda p: p["trayNumber"]),
            sorted(inp.get("steamFeeds", []), key=lambda p: p["stageNumber"]))


def global_features(inp):
    feed = np.asarray(inp["feedComponentMolarFlowsMolPerSecond"], dtype=float)
    total, span = feed.sum(), inp["stageCount"] + 1
    pas, draws, steams = equipment(inp)
    if len(pas) > 4 or len(draws) > 3 or len(steams) > 2:
        raise ValueError("Equipment exceeds general feature contract")
    x = np.zeros(54 + len(feed), dtype=float)
    x[:6] = [np.log(total), inp["feedTemperatureKelvin"], inp["topPressurePascal"] / 1e5,
             inp["stagePressureDropPascal"] / 1e3, inp["stageCount"] / 64, inp["feedStageNumber"] / span]
    for spec in inp["specifications"]:
        if "kelvin" in spec: x[6] = spec["kelvin"]
        if "ratio" in spec: x[7] = spec["ratio"]
        if "watts" in spec: x[8] = spec["watts"] / total / 1e3
    for steam in steams:
        x[9] += steam["molarFlowMolPerSecond"] / total
        x[10] += steam["molarFlowMolPerSecond"] * water_enthalpy(steam["temperatureKelvin"]) / total / 1e3
    x[11] = sum(p["dutyWatts"] for p in pas) / total / 1e3
    x[12] = sum(p["molarFlowMolPerSecond"] for p in draws) / total
    x[13:17] = [int(bool(steams)), len(pas) / 4, len(draws) / 3, len(steams) / 2]
    x[17:17+len(feed)] = feed / total
    k = 17 + len(feed)
    for p, pa in enumerate(pas):
        x[k+5*p:k+5*p+5] = [1, pa["returnTray"] / span, pa["drawTray"] / span,
                              pa["dutyWatts"] / total / 1e3, int(pa["split"] == "UNIFORM")]
    k += 20
    for p, draw in enumerate(draws):
        x[k+3*p:k+3*p+3] = [1, draw["trayNumber"] / span, draw["molarFlowMolPerSecond"] / total]
    k += 9
    for p, steam in enumerate(steams):
        x[k+4*p:k+4*p+4] = [1, steam["stageNumber"] / span,
                              steam["molarFlowMolPerSecond"] / total, steam["temperatureKelvin"]]
    return x


def validate_design_constraints(limits):
    if not isinstance(limits, dict): raise ValueError("Invalid design constraints")
    minimum, maximum = limits.get("minimumNodePressurePascal"), limits.get("maximumNodePressurePascal")
    splits = limits.get("pumparoundSplits")
    if (not isinstance(minimum, (int, float)) or not isinstance(maximum, (int, float))
            or not np.isfinite(minimum) or not np.isfinite(maximum) or minimum <= 0 or minimum > maximum
            or not isinstance(limits.get("steamAtSumpOnly", False), bool)
            or not isinstance(splits, list) or len(splits) > 2
            or any(split not in ("UNIFORM", "RETURN_TRAY") for split in splits)
            or len(set(splits)) != len(splits)):
        raise ValueError("Invalid design constraints")


def derive_design_constraints(rows, design=None):
    """Use authored input topology, and declared pressure bounds when supplied; no outcomes are read."""
    inputs = [row["input"] for row in rows]
    minimum = min(inp["topPressurePascal"] for inp in inputs)
    maximum = max(inp["topPressurePascal"] + (inp["stageCount"]-1)*inp["stagePressureDropPascal"] for inp in inputs)
    if design:
        declared = design.get("variationBounds", design.get("ranges", {})).get("topAndAllStagePressuresPascal")
        if declared is not None:
            if not isinstance(declared, list) or len(declared) != 2: raise ValueError("Invalid authored pressure envelope")
            minimum, maximum = declared
    steam_positions = [(steam["stageNumber"], inp["stageCount"]+1) for inp in inputs for steam in inp.get("steamFeeds", [])]
    limits = {"minimumNodePressurePascal": minimum, "maximumNodePressurePascal": maximum,
              "steamAtSumpOnly": bool(steam_positions) and all(position == sump for position, sump in steam_positions),
              "pumparoundSplits": sorted({pa["split"] for inp in inputs for pa in inp.get("pumparounds", [])})}
    validate_design_constraints(limits)
    if any(design_constraint_rejection(inp, limits) for inp in inputs):
        raise ValueError("Authored matrix contains input outside declared design constraints")
    return limits


def design_constraint_rejection(inp, limits):
    validate_design_constraints(limits)
    bottom = inp["topPressurePascal"] + (inp["stageCount"]-1)*inp["stagePressureDropPascal"]
    slack = 1e-9 * max(1, limits["maximumNodePressurePascal"])
    if inp["topPressurePascal"] < limits["minimumNodePressurePascal"]-slack or not np.isfinite(bottom) or bottom > limits["maximumNodePressurePascal"]+slack:
        return "outside_node_pressure_envelope"
    if limits.get("steamAtSumpOnly") and any(steam["stageNumber"] != inp["stageCount"]+1 for steam in inp.get("steamFeeds", [])):
        return "untrained_steam_location"
    if any(pa["split"] not in limits["pumparoundSplits"] for pa in inp.get("pumparounds", [])):
        return "untrained_pumparound_split"
    return None


def node_features(inp, branch):
    g = global_features(inp)
    total = sum(inp["feedComponentMolarFlowsMolPerSecond"])
    count, k = inp["stageCount"] + 2, len(g)
    span = count - 1
    pas, draws, steams = equipment(inp)
    sources = np.zeros((4, count), dtype=float)
    for pa in pas:
        start, end = pa["returnTray"], pa["drawTray"]
        if pa["split"] == "RETURN_TRAY": sources[0, start] += pa["dutyWatts"] / total / 1e3
        else: sources[0, start:end+1] += pa["dutyWatts"] / (end-start+1) / total / 1e3
    for draw in draws: sources[1, draw["trayNumber"]] += draw["molarFlowMolPerSecond"] / total
    for steam in steams:
        sources[2, steam["stageNumber"]] += steam["molarFlowMolPerSecond"] / total
        sources[3, steam["stageNumber"]] += steam["molarFlowMolPerSecond"] * water_enthalpy(steam["temperatureKelvin"]) / total / 1e3
    cumulative = np.cumsum(sources, axis=1)
    x = np.zeros((count, len(g) + LOCAL_WIDTH + 3), dtype=float)
    x[:, :k] = g
    for n in range(count):
        x[n, k:k+7] = [n / span, n / 64, (n-inp["feedStageNumber"]) / span, int(n == 0),
                        int(n == count-1), int(n == inp["feedStageNumber"]),
                        (inp["topPressurePascal"] + max(0, min(n, inp["stageCount"])-1) * inp["stagePressureDropPascal"]) / 1e5]
        for s in range(4): x[n, k+7+3*s:k+10+3*s] = [sources[s, n], cumulative[s, n], cumulative[s, -1]-cumulative[s, n]]
        for j, positions in enumerate(([p["returnTray"] for p in pas], [p["stageNumber"] for p in steams], [p["trayNumber"] for p in draws])):
            x[n, k+19+j] = (n-min(positions, key=lambda position: abs(n-position))) / span if positions else 0
        x[n, k+LOCAL_WIDTH+BRANCHES.index(branch)] = 1
    return x


def targets(row):
    inp, seed = row["input"], row["seed"]
    feed = np.asarray(inp["feedComponentMolarFlowsMolPerSecond"], dtype=float)
    total = feed.sum()
    scale = np.maximum(feed, total * 1e-12) * 1e-5
    return np.column_stack((seed["temperatures"], np.log1p(np.asarray(seed["liquid"]) / scale),
                            np.log1p(np.asarray(seed["vapor"]) / scale),
                            np.log1p(np.asarray(seed["freeWater"]) / (total * 1e-8)), seed["wetTrays"]))


def make_dataset(rows):
    x, y, weights, owners = [], [], [], []
    for i, row in enumerate(rows):
        xx = node_features(row["input"], row["seed"]["branch"])
        x.append(xx); y.append(targets(row))
        weights.extend([1 / len(xx)] * len(xx)); owners.extend([i] * len(xx))
    return np.vstack(x), np.vstack(y), np.asarray(weights), np.asarray(owners)


def moments(values, weights=None, minimum=1e-7):
    mean = np.average(values, axis=0, weights=weights)
    scale = np.sqrt(np.average((values-mean)**2, axis=0, weights=weights))
    return mean, np.where(scale > minimum, scale, 1.0)


def network(widths, rng):
    params = []
    for ins, outs in zip(widths, widths[1:]):
        params.extend([rng.normal(0, np.sqrt(2 / (ins+outs)), (ins, outs)).astype(np.float32), np.zeros(outs, dtype=np.float32)])
    return params


def forward(params, x):
    activations = [x]
    for i in range(0, len(params), 2):
        z = activations[-1] @ params[i] + params[i+1]
        activations.append(np.tanh(z) if i < len(params)-2 else z)
    return activations


def gradients(params, activations, derivative, decay=1e-6):
    grads = [None] * len(params)
    for i in reversed(range(0, len(params), 2)):
        previous = activations[i//2]
        grads[i] = previous.T @ derivative + decay * params[i]
        grads[i+1] = derivative.sum(axis=0)
        if i:
            derivative = (derivative @ params[i].T) * (1-previous*previous)
    return grads


def adam(params, grads, first, second, step, rate):
    for p, g, m, v in zip(params, grads, first, second):
        np.clip(g, -3, 3, out=g)
        m *= .9; m += .1 * g
        v *= .999; v += .001 * g*g
        p -= rate * (m / (1-.9**step)) / (np.sqrt(v / (1-.999**step)) + 1e-8)


def train_branch(x, labels, xv, lv, epochs, rng):
    params = network([x.shape[1], 64, 3], rng)
    first, second = [np.zeros_like(p) for p in params], [np.zeros_like(p) for p in params]
    best, loss_best, epoch_best = None, float("inf"), 0
    for epoch in range(1, epochs+1):
        acts = forward(params, x)
        logits = acts[-1] - acts[-1].max(axis=1, keepdims=True)
        probabilities = np.exp(logits); probabilities /= probabilities.sum(axis=1, keepdims=True)
        probabilities[np.arange(len(x)), labels] -= 1
        adam(params, gradients(params, acts, probabilities / len(x)), first, second, epoch, .003)
        if epoch % 5 == 0 or epoch == epochs:
            pred = forward(params, xv)[-1]
            pred -= pred.max(axis=1, keepdims=True)
            loss = float(np.mean(np.log(np.exp(pred).sum(axis=1))-pred[np.arange(len(xv)), lv]))
            if loss < loss_best: best, loss_best, epoch_best = copy.deepcopy(params), loss, epoch
    accuracy = float(np.mean(np.argmax(forward(best, xv)[-1], axis=1) == lv))
    return best, {"selected_epoch": epoch_best, "validation_cross_entropy": loss_best, "validation_accuracy": accuracy}


def profile_loss(pred, y, weights, output_weights):
    return float(np.sum(weights[:, None] * (pred-y)**2 * output_weights) / (weights.sum() * len(output_weights)))


def train_profile(x, y, weights, xv, yv, wv, args, rng):
    params = network([x.shape[1], args.hidden_width, args.hidden_width, y.shape[1]], rng)
    first, second = [np.zeros_like(p) for p in params], [np.zeros_like(p) for p in params]
    output_weights = np.ones(y.shape[1], dtype=np.float32)
    output_weights[-1] = 5  # Wet activation is sparse, and its discrete state matters to the corrector.
    best, best_loss, best_epoch, step = None, float("inf"), 0, 0
    trace = []
    started = time.perf_counter()
    for epoch in range(1, args.epochs+1):
        order = rng.permutation(len(x))
        for start in range(0, len(x), args.batch_size):
            ix = order[start:start+args.batch_size]
            acts = forward(params, x[ix]); step += 1
            derivative = 2 * (acts[-1]-y[ix]) * weights[ix, None] * output_weights / (weights[ix].sum() * y.shape[1])
            rate = args.learning_rate * (.5 if epoch > args.epochs*.75 else 1)
            adam(params, gradients(params, acts, derivative), first, second, step, rate)
        if epoch % 5 == 0 or epoch == args.epochs:
            pred = forward(params, xv)[-1]
            loss = profile_loss(pred, yv, wv, output_weights)
            if not np.isfinite(loss): raise ValueError("Nonfinite profile training loss")
            if loss < best_loss: best, best_loss, best_epoch = copy.deepcopy(params), loss, epoch
            item = {"epoch": epoch, "validation_loss": loss, "best_epoch": best_epoch, "elapsed_seconds": time.perf_counter()-started}
            trace.append(item)
            if epoch % 25 == 0: print(json.dumps({"profile_training": item}), flush=True)
            if args.patience and epoch-best_epoch >= args.patience: break
    return best, {"selected_epoch": best_epoch, "validation_weighted_normalized_mse": best_loss, "training_trace": trace}


def export_layers(params):
    return [{"weights": params[i].T.tolist(), "bias": params[i+1].tolist(),
             "activation": "tanh" if i < len(params)-2 else "linear"} for i in range(0, len(params), 2)]


def decode(inp, prediction, branch):
    feed = np.asarray(inp["feedComponentMolarFlowsMolPerSecond"], dtype=float)
    scale = np.maximum(feed, feed.sum()*1e-12) * 1e-5
    c = len(feed)
    if not np.isfinite(prediction).all(): raise ValueError("nonfinite_prediction")
    if np.any(prediction[:, 0] < 100) or np.any(prediction[:, 0] > 1500): raise ValueError("unbounded_temperature")
    if np.any(prediction[:, 1:-1] > 30): raise ValueError("unbounded_flow")
    t = prediction[:, 0].copy()
    l = np.expm1(np.maximum(prediction[:, 1:1+c], 0)) * scale
    v = np.expm1(np.maximum(prediction[:, 1+c:1+2*c], 0)) * scale
    l[:, feed == 0] = 0; v[:, feed == 0] = 0
    w = np.expm1(np.maximum(prediction[:, -2], 0)) * feed.sum()*1e-8
    wet = prediction[:, -1] >= .5; wet[[0, -1]] = False
    if not inp.get("steamFeeds"): wet[:] = False
    w[~wet] = 0
    for spec in inp["specifications"]:
        if "kelvin" in spec: t[0] = spec["kelvin"]
    if branch == "LIQUID_ONLY": v[0] = 0
    if branch == "VAPOR_ONLY": l[0] = 0
    return t, l, v, w, wet


def model_predictions(model, rows, branch_params, profile_params):
    results = []
    for row in rows:
        inp = row["input"]
        base = {"id": row["id"], "split": row["split"], "stages": inp["stageCount"]}
        g = global_features(inp)
        low, high = np.asarray(model["globalMin"]), np.asarray(model["globalMax"])
        slack = 1e-9 * np.maximum(1, np.maximum(np.abs(low), np.abs(high)))
        invalid = np.where((g < low-slack) | (g > high+slack) | ~np.isfinite(g))[0]
        reason = None
        if len(invalid): reason = "outside_global_bounds:" + ",".join(map(str, invalid))
        if inp["stageCount"] < model["minimumStages"] or inp["stageCount"] > model["maximumStages"]: reason = "outside_stage_bounds"
        if row["formulationRevision"] not in model["formulationRevisions"]: reason = "untrained_formulation_revision"
        if inp["packageId"] != model["packageId"] or inp["componentBasis"]["componentIds"] != model["components"]: reason = "incompatible_property_basis"
        if "designConstraints" in model:
            reason = design_constraint_rejection(inp, model["designConstraints"]) or reason
        normalized_global = (g-model["globalMean"]) / model["globalScale"]
        if "coverageGuard" in model:
            guard = model["coverageGuard"]
            if np.min(np.mean((np.asarray(guard["centers"])-normalized_global)**2, axis=1)) > guard["maximumNearestMeanSquare"]:
                reason = "outside_correlated_coverage"
        if reason:
            results.append({**base, "prediction_supported": False, "rejection_reason": reason}); continue
        logits = forward(branch_params, normalized_global[None, :])[-1][0]
        logits[~np.asarray(model["branchesSeen"])] = -np.inf
        if any(spec.get("ratio", 0) > 0 for spec in inp["specifications"]): logits[2] = -np.inf
        if not np.isfinite(logits).any():
            results.append({**base, "prediction_supported": False, "rejection_reason": "no_trained_permitted_branch"}); continue
        branch = BRANCHES[int(np.argmax(logits))]
        x = node_features(inp, branch)
        pred = forward(profile_params, (x-model["nodeMean"]) / model["nodeScale"])[-1]
        pred = pred * model["outputScale"] + model["outputMean"]
        try: t, l, v, w, wet = decode(inp, pred, branch)
        except ValueError as invalid:
            results.append({**base, "prediction_supported": False, "rejection_reason": str(invalid)}); continue
        target = row["seed"]
        actual_l, actual_v = np.asarray(target["liquid"]), np.asarray(target["vapor"])
        feed = np.asarray(inp["feedComponentMolarFlowsMolPerSecond"])
        scale = np.maximum(feed, feed.sum()*1e-12) * 1e-5
        log_errors = np.concatenate((np.log1p(l/scale)-np.log1p(actual_l/scale),
                                     np.log1p(v/scale)-np.log1p(actual_v/scale)))
        result = {**base, "prediction_supported": True,
                  "predicted_branch": branch, "teacher_branch": target["branch"], "branch_correct": branch == target["branch"],
                  "temperature_mae_K": float(np.mean(np.abs(t-target["temperatures"]))),
                  "temperature_max_error_K": float(np.max(np.abs(t-target["temperatures"]))),
                  "component_flow_log_rmse": float(np.sqrt(np.mean(log_errors**2))),
                  "flow_mae_mol_s": float(np.mean(np.abs(l-actual_l)+np.abs(v-actual_v))/2),
                  "liquid_total_relative_mae": float(np.mean(np.abs(l.sum(1)-actual_l.sum(1)) / np.maximum(actual_l.sum(1), feed.sum()*1e-8))),
                  "vapor_total_relative_mae": float(np.mean(np.abs(v.sum(1)-actual_v.sum(1)) / np.maximum(actual_v.sum(1), feed.sum()*1e-8))),
                  "wet_mask_exact": bool(np.array_equal(wet, target["wetTrays"])),
                  "water_mae_mol_s": float(np.mean(np.abs(w-target["freeWater"]))),
                  "waterQualification": row.get("waterQualification", "UNCLASSIFIED")}
        results.append(result)
    return results


def prediction_summary(predictions):
    summary = {}
    for split in sorted({row["split"] for row in predictions}):
        selected = [r for r in predictions if r["split"] == split]
        supported = [r for r in selected if r["prediction_supported"]]
        summary[split] = {"count": len(selected), "supported": len(supported),
                          "rejection_reasons": dict(Counter(r["rejection_reason"] for r in selected if not r["prediction_supported"])),
                          "temperature_mae_K_median": float(np.median([r["temperature_mae_K"] for r in supported])) if supported else None,
                          "component_flow_log_rmse_median": float(np.median([r["component_flow_log_rmse"] for r in supported])) if supported else None,
                          "branch_accuracy": float(np.mean([r["branch_correct"] for r in supported])) if supported else None,
                          "wet_mask_exact_rate": float(np.mean([r["wet_mask_exact"] for r in supported])) if supported else None}
    return summary


def import_layers(layers):
    params = []
    for layer in layers: params.extend([np.asarray(layer["weights"], dtype=float).T, np.asarray(layer["bias"], dtype=float)])
    return params


def load_rows(path):
    rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    rows.sort(key=lambda r: str(r["id"]))
    ids = [str(r["id"]) for r in rows]
    if len(set(ids)) != len(ids): raise ValueError("Duplicate column IDs; merge journals before training")
    # Identical authored inputs cannot cross folds under different IDs.
    folds = {}
    for row in rows:
        signature = json.dumps(row["input"], sort_keys=True, separators=(",", ":"))
        previous = folds.setdefault(signature, row["split"])
        if previous != row["split"]: raise ValueError("Identical column input assigned to multiple splits")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--epochs", type=int, default=500)
    parser.add_argument("--branch-epochs", type=int, default=600)
    parser.add_argument("--hidden-width", type=int, default=96)
    parser.add_argument("--batch-size", type=int, default=2048)
    parser.add_argument("--learning-rate", type=float, default=.002)
    parser.add_argument("--patience", type=int, default=100)
    parser.add_argument("--model-id", default="tjl20-general-stage-v1")
    parser.add_argument("--seed", type=int, default=240910)
    parser.add_argument("--label-policy", choices=["current-solver-accepted", "equilibrium-only"], default="equilibrium-only")
    parser.add_argument("--design-bounds", action="store_true", help="Use authored DOE inputs for declared coverage bounds; no outcome labels are used")
    parser.add_argument("--coverage-guard", action="store_true", help="Optional nearest normalized training-column coverage rejection")
    parser.add_argument("--prediction-split", choices=["train", "validation", "test", "all"], default="validation",
                        help="Raw comparison fold; default withholds all test predictions")
    parser.add_argument("--evaluate-model", type=Path, help="Evaluate an already frozen model without training or changing it")
    parser.add_argument("--design-file", type=Path, help="Authored design metadata, including declared compound pressure bounds")
    args = parser.parse_args()
    if not 1 <= args.epochs <= 20000 or not 1 <= args.branch_epochs <= 20000 or not 8 <= args.hidden_width <= 512 or args.batch_size < 16:
        raise ValueError("Invalid training budget or model width")
    started = time.perf_counter()
    path = args.directory / "cases.jsonl"
    rows = load_rows(path)
    if args.evaluate_model:
        model = json.loads(args.evaluate_model.read_text())
        policy = model["trainingLabelPolicy"]
        evaluation = [r for r in rows if r.get("success") is True and "seed" in r
                      and (policy == "current-solver-accepted" or r.get("equilibriumQualified") is True)
                      and (args.prediction_split == "all" or r["split"] == args.prediction_split)]
        predictions = model_predictions(model, evaluation, import_layers(model["branchLayers"]), import_layers(model["nodeLayers"]))
        (args.directory / ("general-raw-"+args.prediction_split+".jsonl")).write_text("".join(json.dumps(r, allow_nan=False)+"\n" for r in predictions))
        print(json.dumps({"frozen_model_sha256": hashlib.sha256(args.evaluate_model.read_bytes()).hexdigest(),
                          "raw_predictions": prediction_summary(predictions)}, indent=2)); return
    accepted = [r for r in rows if r.get("success") is True and "seed" in r
                and (args.label_policy == "current-solver-accepted" or r.get("equilibriumQualified") is True)]
    train = [r for r in accepted if r["split"] == "train"]
    validation = [r for r in accepted if r["split"] == "validation"]
    if len(train) < 12 or len(validation) < 3: raise ValueError("Insufficient accepted train/validation columns")
    first = train[0]
    components = first["input"]["componentBasis"]["componentIds"]
    for row in accepted:
        if row["input"]["componentBasis"]["componentIds"] != components or row["input"]["packageId"] != first["input"]["packageId"]:
            raise ValueError("Incompatible component property basis")
        if row["seed"]["propertyRevision"] != first["seed"]["propertyRevision"]: raise ValueError("Mixed property revisions")
    x, y, weights, owners = make_dataset(train)
    xv, yv, wv, _ = make_dataset(validation)
    xm, xs = moments(x, weights); ym, ys = moments(y, weights)
    ys[0] = max(ys[0], 10.0); ys[1:-1] = np.maximum(ys[1:-1], 1.0); ys[-1] = 1.0
    g = np.vstack([global_features(r["input"]) for r in train])
    gv = np.vstack([global_features(r["input"]) for r in validation])
    gm, gs = moments(g)
    normalized = [np.asarray(a, dtype=np.float32) for a in ((x-xm)/xs, (y-ym)/ys, weights,
                                                           (xv-xm)/xs, (yv-ym)/ys, wv)]
    if not all(np.isfinite(a).all() for a in normalized): raise ValueError("Nonfinite teacher features or targets")
    rng = np.random.default_rng(args.seed)
    labels = np.asarray([BRANCHES.index(r["seed"]["branch"]) for r in train])
    lv = np.asarray([BRANCHES.index(r["seed"]["branch"]) for r in validation])
    branch, branch_report = train_branch(np.asarray((g-gm)/gs, dtype=np.float32), labels,
                                         np.asarray((gv-gm)/gs, dtype=np.float32), lv, args.branch_epochs, rng)
    profile, profile_report = train_profile(*normalized, args, rng)
    bounds_rows = rows if args.design_bounds else train
    domain = np.vstack([global_features(r["input"]) for r in bounds_rows])
    design_path = args.design_file if args.design_file else args.directory / "design.json"
    design = json.loads(design_path.read_text()) if design_path.exists() else None
    if args.design_file and design is None: raise ValueError("Requested authored design metadata does not exist")
    model = {"featureRevision": REVISION, "modelId": args.model_id, "packageId": first["input"]["packageId"],
             "propertyRevision": first["seed"]["propertyRevision"], "components": components,
             "formulationRevisions": sorted({r["formulationRevision"] for r in train}),
             "minimumStages": min(r["input"]["stageCount"] for r in bounds_rows),
             "maximumStages": max(r["input"]["stageCount"] for r in bounds_rows),
             "branchesSeen": [bool(np.any(labels == b)) for b in range(3)],
             "globalMean": gm.tolist(), "globalScale": gs.tolist(), "globalMin": domain.min(0).tolist(), "globalMax": domain.max(0).tolist(),
             "nodeMean": xm.tolist(), "nodeScale": xs.tolist(), "outputMean": ym.tolist(), "outputScale": ys.tolist(),
             "branchLayers": export_layers(branch), "nodeLayers": export_layers(profile), "trainingLabelPolicy": args.label_policy}
    model["designConstraints"] = derive_design_constraints(bounds_rows, design)
    if args.coverage_guard:
        gn, gvn = (g-gm)/gs, (gv-gm)/gs
        nearest = [float(np.min(np.mean((gn-point)**2, axis=1))) for point in gvn]
        model["coverageGuard"] = {"centers": gn.tolist(), "maximumNearestMeanSquare": max(.05, max(nearest)*1.5)}
    model_path = args.directory / "general-model.json"
    model_path.write_text(json.dumps(model, separators=(",", ":"), allow_nan=False))
    # Test predictions are opt-in after the model is frozen. Validation supports further development.
    evaluation = [r for r in accepted if args.prediction_split == "all" or r["split"] == args.prediction_split]
    predictions = model_predictions(model, evaluation, branch, profile)
    (args.directory / "general-raw-predictions.jsonl").write_text("".join(json.dumps(r, allow_nan=False)+"\n" for r in predictions))
    parity = [{"id": r["id"], "global": global_features(r["input"]).tolist(),
               "nodes": node_features(r["input"], r["seed"]["branch"]).tolist(), "targets": targets(r).tolist()}
              for r in train[:2]]
    (args.directory / "general-feature-parity.json").write_text(json.dumps(parity, separators=(",", ":")))
    report = {"model_id": args.model_id, "architecture": "global branch classifier + branch-conditioned shared two-hidden-layer stage MLP",
              "feature_revision": REVISION, "component_treatment": "All fractions variable; identical per-component target transformation",
              "label_policy": args.label_policy, "attempted": len(rows), "accepted_labels": len(accepted), "train_columns": len(train),
              "native_accepted": sum(r.get("success") is True for r in rows),
              "excluded_accepted_labels": sum(r.get("success") is True for r in rows)-len(accepted),
              "validation_columns": len(validation), "test_columns": sum(r["split"] == "test" for r in rows),
              "train_nodes": len(x), "validation_nodes": len(xv), "seed": args.seed, "hidden_width": args.hidden_width,
              "epochs_requested": args.epochs, "batch_size": args.batch_size, "branch": branch_report, "profile": profile_report,
              "weight_parameters": sum(p.size for p in branch+profile), "parameter_storage_bytes_java": sum(p.size for p in branch+profile)*8,
              "model_file_bytes": model_path.stat().st_size, "elapsed_seconds": time.perf_counter()-started,
              "dataset_sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "model_sha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
              "normalization_fitted_on": "training columns only, equal total weight per column",
              "selection_on": "whole validation columns; no tray-level split", "test_used_for_training_or_selection": False,
              "bounds_source": "predeclared matrix input domain, without outcomes" if args.design_bounds else "training input domain",
              "correlated_coverage_guard": args.coverage_guard,
              "design_constraints": model["designConstraints"],
              "design_constraints_source": str(design_path) if design else "authored bound-domain input topology and pressure extrema",
              "training_water_qualification": dict(Counter(r.get("waterQualification", "UNCLASSIFIED") for r in train)),
              "native_accepted_water_qualification": dict(Counter(r.get("waterQualification", "UNCLASSIFIED") for r in rows if r.get("success") is True)),
              "training_branches": dict(Counter(r["seed"]["branch"] for r in train)),
              "prediction_split": args.prediction_split, "raw_predictions": prediction_summary(predictions)}
    (args.directory / "general-training.json").write_text(json.dumps(report, indent=2, allow_nan=False))
    print(json.dumps({k: v for k, v in report.items() if k != "profile"}, indent=2), flush=True)


if __name__ == "__main__":
    main()
