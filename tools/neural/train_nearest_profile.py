"""Export an immutable nonparametric initializer from qualified TRAIN profiles only.

This is nearest-profile transfer, not a neural network and not a solved result.
The native requested-column corrector and physical audit remain mandatory.
Validation may compare frozen candidates; no validation/test profile is stored.
"""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import re

for name in ("OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "OMP_NUM_THREADS"):
    os.environ[name] = "1"
import numpy as np

from prepare_generalized_evaluation import canonical_input_hash
from train_generalized import (REVISION, derive_design_constraints,
                               design_constraint_rejection, global_features, moments)

MODEL_TYPE = "nearest-profile"
TRANSFER_REVISION = "feed-anchored-rectifying-clamp-1"
MAXIMUM_REFERENCES = 2048
MAXIMUM_BYTES = 32 * 1024 * 1024
BRANCHES = ("LIQUID_ONLY", "TWO_PHASE", "VAPOR_ONLY")
COHORT_POLICIES = ("steam-equipment-counts", "steam-only")


def feature_weights(components=20):
    """Predeclared weights: all fractions equal; thermal/flow/geometry controls each count four times."""
    weights = np.ones(54 + components)
    weights[:17] = 4
    # Equipment positions have a direct effect on the stage profile.
    for p in range(4): weights[17+components+5*p+1:17+components+5*p+3] = 2
    for p in range(3): weights[37+components+3*p+1] = 2
    for p in range(2): weights[46+components+4*p+1] = 2
    return weights


def source_position(node, source_stages, source_feed, target_stages, target_feed):
    if node == 0: return 0.0
    if node == target_stages+1: return float(source_stages+1)
    if node == target_feed: return float(source_feed)
    if node < target_feed: return min(source_feed-1, max(1, node*source_feed/target_feed))
    return source_feed + (node-target_feed)*(source_stages+1-source_feed)/(target_stages+1-target_feed)


def mapped_wet(reference, inp):
    wet = [False] * (inp["stageCount"]+2)
    if not inp.get("steamFeeds"): return wet
    for node in range(1, inp["stageCount"]+1):
        position = source_position(node, reference["stageCount"], reference["feedStageNumber"],
                                   inp["stageCount"], inp["feedStageNumber"])
        wet[node] = reference["wetTrays"][int(math.floor(position+.5))]  # Java Math.round, not Python ties-to-even.
    return wet


def compatible(inp, reference, cohort_policy):
    if bool(inp.get("steamFeeds")) != reference["steamEnabled"]: return False
    if cohort_policy == "steam-equipment-counts" and (
            len(inp.get("pumparounds", [])) != reference["paCount"]
            or len(inp.get("sideDraws", [])) != reference["sideDrawCount"]): return False
    if reference["branch"] == "VAPOR_ONLY" and any(s.get("ratio", 0) > 0 for s in inp["specifications"]): return False
    if inp["feedStageNumber"] > 1 and reference["feedStageNumber"] == 1: return False
    return all(requested == 0 or source > 0 for requested, source in
               zip(inp["feedComponentMolarFlowsMolPerSecond"], reference["componentFeed"]))


def candidate_distances(model, inp, excluded_hash=None):
    normalized = (global_features(inp)-model["globalMean"])/model["globalScale"]
    weights = np.asarray(model["featureWeights"])
    candidates = []
    for reference in model["references"]:
        if reference["inputSha256"] == excluded_hash or not compatible(inp, reference, model["cohortPolicy"]): continue
        difference = normalized-reference["normalizedGlobal"]
        distance = float(np.sum(weights*difference*difference)/weights.sum())
        if np.isfinite(distance) and distance <= model["maximumMeanSquareDistance"]:
            candidates.append((reference, distance))
    return sorted(candidates, key=lambda candidate: (candidate[1], candidate[0]["id"]))


def predict(model, inp):
    """Python parity reference. Only the returned guess is comparable with Java predict; it is not an accepted solve."""
    if (inp["packageId"] != model["packageId"] or inp["componentBasis"]["componentIds"] != model["components"]
            or not model["minimumStages"] <= inp["stageCount"] <= model["maximumStages"]
            or not 1 <= inp["feedStageNumber"] <= inp["stageCount"]): return None
    if design_constraint_rejection(inp, model["designConstraints"]): return None
    x, low, high = global_features(inp), np.asarray(model["globalMin"]), np.asarray(model["globalMax"])
    slack = 1e-9*np.maximum(1, np.maximum(np.abs(low), np.abs(high)))
    if np.any(~np.isfinite(x) | (x < low-slack) | (x > high+slack)): return None
    candidates = candidate_distances(model, inp)
    if not candidates: return None
    nearest = candidates[0]
    wet = mapped_wet(nearest[0], inp)
    selected = []
    for reference, distance in candidates:
        if reference["branch"] == nearest[0]["branch"] and mapped_wet(reference, inp) == wet:
            selected.append((reference, distance))
        if len(selected) == model["neighbors"] or nearest[1] <= 1e-20: break
    weights = [1.0] if len(selected) == 1 else [1/max(1e-8, math.sqrt(distance)) for _, distance in selected]
    weight_sum = sum(weights)
    nodes, components = inp["stageCount"]+2, len(model["components"])
    feed = inp["feedComponentMolarFlowsMolPerSecond"]
    liquid, vapor = np.zeros((nodes, components)), np.zeros((nodes, components))
    temperatures, water = np.zeros(nodes), np.zeros(nodes)
    requested_steam = sum(s["molarFlowMolPerSecond"] for s in inp.get("steamFeeds", []))

    def blend(values, lower, upper, fraction):
        return values[lower] if fraction == 0 else values[lower] + fraction*(values[upper]-values[lower])

    for (reference, _), raw_weight in zip(selected, weights):
        weight = raw_weight/weight_sum
        source_l, source_v = np.asarray(reference["liquid"]), np.asarray(reference["vapor"])
        source_t, source_w = np.asarray(reference["temperatures"]), np.asarray(reference["freeWater"])
        component_scale = np.divide(feed, reference["componentFeed"], out=np.zeros(components), where=np.asarray(reference["componentFeed"]) > 0)
        water_scale = requested_steam/reference["totalSteamFlow"] if reference["totalSteamFlow"] > 0 else 0
        for node in range(nodes):
            position = source_position(node, reference["stageCount"], reference["feedStageNumber"], inp["stageCount"], inp["feedStageNumber"])
            lower, fraction = int(math.floor(position)), position-math.floor(position)
            upper = min(reference["stageCount"]+1, lower+1)
            temperatures[node] += weight*blend(source_t, lower, upper, fraction)
            liquid[node] += weight*component_scale*blend(source_l, lower, upper, fraction)
            vapor[node] += weight*component_scale*blend(source_v, lower, upper, fraction)
            if wet[node]: water[node] += weight*water_scale*blend(source_w, lower, upper, fraction)
    for spec in inp["specifications"]:
        if "kelvin" in spec: temperatures[0] = spec["kelvin"]
    branch = nearest[0]["branch"]
    if branch == "LIQUID_ONLY": vapor[0] = 0
    if branch == "VAPOR_ONLY": liquid[0] = 0
    if (not all(np.isfinite(values).all() for values in (temperatures, liquid, vapor, water))
            or np.any((temperatures < 100) | (temperatures > 1500))
            or any(np.any((values < 0) | (values > 1e8*sum(feed))) for values in (liquid, vapor, water))
            or any(flag and flow <= 0 for flag, flow in zip(wet, water))): return None
    return {"input": copy.deepcopy(inp), "propertyRevision": model["propertyRevision"], "branch": branch,
            "liquid": liquid.tolist(), "vapor": vapor.tolist(), "temperatures": temperatures.tolist(),
            "freeWater": water.tolist(), "wetTrays": wet}


def checked_profile(row):
    inp, seed = row["input"], row["seed"]
    nodes, components = inp["stageCount"]+2, len(inp["componentBasis"]["componentIds"])
    feed = np.asarray(inp["feedComponentMolarFlowsMolPerSecond"], dtype=float)
    if seed["branch"] not in BRANCHES: raise ValueError("Invalid reference branch")
    if "input" in seed and canonical_input_hash(seed["input"]) != canonical_input_hash(inp): raise ValueError("Reference profile belongs to a different requested input")
    if seed["branch"] == "VAPOR_ONLY" and any(s.get("ratio", 0) > 0 for s in inp["specifications"]): raise ValueError("Vapor-only reference with positive reflux")
    if (components != 20 or feed.shape != (20,) or not np.isfinite(feed).all() or np.any(feed < 0) or feed.sum() <= 0
            or not 2 <= inp["stageCount"] <= 64 or not 1 <= inp["feedStageNumber"] <= inp["stageCount"]):
        raise ValueError("Invalid reference geometry or feed")
    arrays = {key: np.asarray(seed[key], dtype=float) for key in ("temperatures", "liquid", "vapor", "freeWater")}
    wet = seed["wetTrays"]
    if any(a.shape != ((nodes, components) if key in ("liquid", "vapor") else (nodes,)) or not np.isfinite(a).all()
           for key, a in arrays.items()): raise ValueError("Invalid reference profile shape or finite values")
    if np.any((arrays["temperatures"] < 100) | (arrays["temperatures"] > 1500)): raise ValueError("Unbounded reference temperature")
    if any(np.any((arrays[key] < 0) | (arrays[key] > 1e8*feed.sum())) for key in ("liquid", "vapor", "freeWater")): raise ValueError("Unbounded reference flow")
    if any(np.any(arrays[key][:, feed == 0] != 0) for key in ("liquid", "vapor")): raise ValueError("Reference has flow on an absent component")
    if not isinstance(wet, list) or len(wet) != nodes or any(type(value) is not bool for value in wet): raise ValueError("Invalid reference wet mask")
    if wet[0] or wet[-1] or any((flag and flow <= 0) or (not flag and flow != 0) for flag, flow in zip(wet, arrays["freeWater"])):
        raise ValueError("Reference free water disagrees with its wet mask")
    if not inp.get("steamFeeds") and (any(wet) or np.any(arrays["freeWater"] != 0)): raise ValueError("Wet reference without authored steam")
    if seed["branch"] == "LIQUID_ONLY" and np.any(arrays["vapor"][0] != 0): raise ValueError("Vapor at a liquid-only terminal")
    if seed["branch"] == "VAPOR_ONLY" and np.any(arrays["liquid"][0] != 0): raise ValueError("Liquid at a vapor-only terminal")
    tc = next(s["kelvin"] for s in inp["specifications"] if "kelvin" in s)
    if abs(arrays["temperatures"][0]-tc) > 1e-7: raise ValueError("Reference changed prescribed condenser temperature")
    return {**{key: value.tolist() for key, value in arrays.items()}, "wetTrays": list(wet)}


def build_model(rows, neighbors=1, cohort_policy="steam-equipment-counts", maximum_distance=None, design=None,
                model_id=None):
    if neighbors not in (1, 3) or cohort_policy not in COHORT_POLICIES: raise ValueError("Invalid nearest profile candidate configuration")
    if maximum_distance is not None and (not math.isfinite(maximum_distance) or maximum_distance <= 0): raise ValueError("Invalid maximum distance")
    if model_id is not None and not re.fullmatch(r"[A-Za-z0-9._:/-]{1,96}", model_id): raise ValueError("Invalid model ID")
    # Read held-out inputs only for an identity/fold contamination check. Never inspect held-out profile targets.
    seen_ids, folds = set(), {}
    for row in rows:
        if str(row["id"]) in seen_ids: raise ValueError("Duplicate source ID")
        seen_ids.add(str(row["id"]))
        signature = canonical_input_hash(row["input"])
        previous = folds.setdefault(signature, row["split"])
        if previous != row["split"]: raise ValueError("Identical authored input crosses folds")
    authored_train = [row for row in rows if row["split"] == "train"]
    train = sorted((row for row in authored_train if row.get("success") is True
                    and row.get("equilibriumQualified") is True and "seed" in row), key=lambda row: str(row["id"]))
    if not train or len(train) > MAXIMUM_REFERENCES: raise ValueError("Need 1..2048 qualified TRAIN reference columns")
    first = train[0]
    components, package = first["input"]["componentBasis"]["componentIds"], first["input"]["packageId"]
    property_revision = first["seed"]["propertyRevision"]
    if len(components) != 20 or len(set(components)) != 20: raise ValueError("Nearest profile candidate requires a fixed twenty-component basis")
    for row in authored_train:
        if row["input"]["packageId"] != package or row["input"]["componentBasis"]["componentIds"] != components:
            raise ValueError("Mixed TRAIN property bases")
    features = np.vstack([global_features(row["input"]) for row in train])
    if not np.isfinite(features).all(): raise ValueError("Nonfinite TRAIN distance features")
    mean, scale = moments(features)
    # All frozen authored inputs may declare the operating domain; no held-out outcome or profile is used.
    domain = np.vstack([global_features(row["input"]) for row in rows])
    references, reference_hashes = [], set()
    for row, x in zip(train, features):
        inp, seed = row["input"], row["seed"]
        if seed["propertyRevision"] != property_revision: raise ValueError("Mixed TRAIN property revisions")
        signature = canonical_input_hash(inp)
        if signature in reference_hashes: raise ValueError("Repeated TRAIN reference input")
        reference_hashes.add(signature)
        profile = checked_profile(row)
        references.append({"id": str(row["id"]), "inputSha256": signature, "sourceSplit": "train",
                           "equilibriumQualified": True, "stageCount": inp["stageCount"], "feedStageNumber": inp["feedStageNumber"],
                           "steamEnabled": bool(inp.get("steamFeeds")), "paCount": len(inp.get("pumparounds", [])),
                           "sideDrawCount": len(inp.get("sideDraws", [])),
                           "totalSteamFlow": sum(s["molarFlowMolPerSecond"] for s in inp.get("steamFeeds", [])),
                           "componentFeed": list(inp["feedComponentMolarFlowsMolPerSecond"]), "branch": seed["branch"],
                           "normalizedGlobal": ((x-mean)/scale).tolist(), **profile})
    model = {"modelType": MODEL_TYPE, "featureRevision": REVISION, "transferRevision": TRANSFER_REVISION,
             "modelId": model_id or f"tjl20-nearest-profile-k{neighbors}-v1", "packageId": package, "propertyRevision": property_revision,
             "components": components, "formulationRevisions": sorted({row["formulationRevision"] for row in train}),
             "minimumStages": min(row["input"]["stageCount"] for row in rows),
             "maximumStages": max(row["input"]["stageCount"] for row in rows),
             "neighbors": neighbors, "cohortPolicy": cohort_policy, "maximumMeanSquareDistance": 1e300,
             "globalMean": mean.tolist(), "globalScale": scale.tolist(), "globalMin": domain.min(0).tolist(), "globalMax": domain.max(0).tolist(),
             "featureWeights": feature_weights().tolist(), "references": references,
             "designConstraints": derive_design_constraints(rows, design), "trainingLabelPolicy": "equilibrium-only"}
    # Leave-one-input-out TRAIN calibration. No validation/test target or distance selects this cutoff.
    distances = []
    for row, reference in zip(train, references):
        candidates = candidate_distances(model, row["input"], reference["inputSha256"])
        if candidates: distances.append(candidates[0][1])
    model["maximumMeanSquareDistance"] = maximum_distance if maximum_distance is not None else max(.5, 1.5*max(distances, default=0))
    report = {"modelType": MODEL_TYPE, "modelId": model["modelId"], "neighbors": neighbors, "cohortPolicy": cohort_policy,
              "referenceColumns": len(references), "referenceIds": [r["id"] for r in references],
              "primitiveStorageBytesJava": primitive_storage_bytes(model),
              "referenceInputSha256": sorted(reference_hashes), "referenceSourceSplits": {"train": len(references)},
              "trainingWaterQualification": dict(Counter(row.get("waterQualification", "UNCLASSIFIED") for row in train)),
              "normalizationFittedOn": "qualified TRAIN reference inputs only; all component fractions receive equal weights",
              "coverageBoundsFrom": "all frozen authored DOE inputs only; optional predeclared physical design pressure envelope; no outcomes",
              "distanceCalibration": "explicit candidate value" if maximum_distance is not None else "1.5 times largest leave-one-input-out TRAIN nearest distance, floored at0.5",
              "calibrationColumnsWithNeighbor": len(distances), "calibrationColumnsWithoutNeighbor": len(train)-len(distances),
              "maximumMeanSquareDistance": model["maximumMeanSquareDistance"], "validationOrTestProfilesStored": False,
              "declines": ["incompatible basis/formulation or outside authored bounds", "unsupported pressure/steam/PA split design",
                           "no same-steam reference (also identical PA/draw counts by default)", "positive reflux with vapor-only reference",
                           "requested rectifying section absent in source", "new component absent from source", "distance cutoff"],
              "transfer": "feed-anchored piecewise interpolation with rectifying clamp; component-specific feed-ratio scaling; same branch and mapped wet mask only",
              "evaluationRequirement": "Native requested-column correction and unchanged physical acceptance; reference retrieval itself is not convergence"}
    return model, report


def primitive_storage_bytes(model):
    doubles, byte_count = 5*len(model["globalMean"])+4, 3*4+1+8
    for reference in model["references"]:
        nodes, components = reference["stageCount"]+2, len(reference["componentFeed"])
        doubles += len(reference["normalizedGlobal"])+components+2*nodes*components+2*nodes+1
        byte_count += 4*4+nodes+2
    return doubles*8+byte_count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="New campaign journal or directory containing cases.jsonl")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--neighbors", type=int, choices=(1, 3), default=1)
    parser.add_argument("--cohort-policy", choices=COHORT_POLICIES, default="steam-equipment-counts")
    parser.add_argument("--maximum-distance", type=float)
    parser.add_argument("--model-id")
    parser.add_argument("--design-file", type=Path)
    args = parser.parse_args()
    source = args.source/"cases.jsonl" if args.source.is_dir() else args.source
    report_path = args.output.with_name(args.output.stem+"-export.json")
    if args.output.exists() or report_path.exists(): raise FileExistsError("Choose fresh nearest-profile artifact/report paths")
    rows = [json.loads(line) for line in source.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    design = json.loads(args.design_file.read_text()) if args.design_file else None
    model, report = build_model(rows, args.neighbors, args.cohort_policy, args.maximum_distance, design, args.model_id)
    payload = json.dumps(model, separators=(",", ":"), allow_nan=False).encode("utf-8")
    if len(payload) > MAXIMUM_BYTES: raise ValueError("Reference library exceeds32MiB; reduce its explicitly selected TRAIN population")
    report.update({"sourceJournalSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                   "modelSha256": hashlib.sha256(payload).hexdigest(), "artifactBytes": len(payload)})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("xb") as target: target.write(payload)
    with report_path.open("x", encoding="utf-8") as target: json.dump(report, target, indent=2, allow_nan=False)
    print(json.dumps({k: v for k, v in report.items() if k not in ("referenceIds", "referenceInputSha256")}, indent=2))


if __name__ == "__main__": main()
