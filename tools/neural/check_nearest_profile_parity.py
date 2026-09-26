"""Prepare TRAIN-origin fixtures and independently check actual Java nearest-profile inference.

The transfer oracle below deliberately does not import train_nearest_profile or
call its prediction, neighbor selection, interpolation, or flow-scaling helpers.
It reads only frozen model references and authored inputs from TRAIN source rows.
"""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import hashlib
import json
import math
from pathlib import Path

from prepare_generalized_evaluation import canonical_input_hash
from train_generalized import global_features, design_constraint_rejection

REVISION = "nearest-profile-parity-1"
ABSOLUTE_TOLERANCE = 1e-9
RELATIVE_TOLERANCE = 1e-10


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def input_coordinates(inp):
    return global_features(inp).tolist()


def read_model(path):
    model = json.loads(Path(path).read_text(encoding="utf-8"))
    if model.get("modelType") != "nearest-profile" or model.get("transferRevision") != "feed-anchored-rectifying-clamp-1":
        raise ValueError("Unsupported frozen nearest-profile contract")
    if not model.get("references") or any(r.get("sourceSplit") != "train" or r.get("equilibriumQualified") is not True for r in model["references"]):
        raise ValueError("Parity model library must contain qualified TRAIN references only")
    return model


def independent_positions(reference, inp):
    source_feed, source_end = reference["feedStageNumber"], reference["stageCount"]+1
    target_feed, target_end = inp["feedStageNumber"], inp["stageCount"]+1
    positions = []
    for node in range(target_end+1):
        if node == 0: location = 0.0
        elif node == target_end: location = float(source_end)
        elif node == target_feed: location = float(source_feed)
        elif node < target_feed:
            location = max(1.0, source_feed*(node/target_feed))
            location = min(float(source_feed-1), location)
        else:
            location = source_feed+(source_end-source_feed)*((node-target_feed)/(target_end-target_feed))
        positions.append(location)
    return positions


def independent_prediction(model, inp):
    """Separate scalar implementation of distance, branch/wet routing, interpolation and component scaling."""
    if (inp["packageId"] != model["packageId"] or inp["componentBasis"]["componentIds"] != model["components"]
            or not model["minimumStages"] <= inp["stageCount"] <= model["maximumStages"]): return None, {}
    if design_constraint_rejection(inp, model["designConstraints"]): return None, {}
    x = input_coordinates(inp)
    for value, low, high in zip(x, model["globalMin"], model["globalMax"]):
        slack = 1e-9*max(1, abs(low), abs(high))
        if not math.isfinite(value) or value < low-slack or value > high+slack: return None, {}
    normalized = [(value-mean)/scale for value, mean, scale in zip(x, model["globalMean"], model["globalScale"])]
    feed = inp["feedComponentMolarFlowsMolPerSecond"]
    steam = sum(s["molarFlowMolPerSecond"] for s in inp.get("steamFeeds", []))
    positive_reflux = any(s.get("ratio", 0) > 0 for s in inp["specifications"])
    ranked = []
    weight_sum = sum(model["featureWeights"])
    for reference in model["references"]:
        if reference["steamEnabled"] != bool(inp.get("steamFeeds")): continue
        if model["cohortPolicy"] == "steam-equipment-counts" and (
                reference["paCount"] != len(inp.get("pumparounds", []))
                or reference["sideDrawCount"] != len(inp.get("sideDraws", []))): continue
        if positive_reflux and reference["branch"] == "VAPOR_ONLY": continue
        if inp["feedStageNumber"] > 1 and reference["feedStageNumber"] == 1: continue
        if any(requested > 0 and original == 0 for requested, original in zip(feed, reference["componentFeed"])): continue
        distance = sum(weight*(requested-original)**2 for weight, requested, original in
                       zip(model["featureWeights"], normalized, reference["normalizedGlobal"]))/weight_sum
        if not math.isfinite(distance) or distance > model["maximumMeanSquareDistance"]: continue
        positions = independent_positions(reference, inp)
        wet = [False]*len(positions)
        if steam > 0:
            for node in range(1, len(wet)-1): wet[node] = reference["wetTrays"][int(math.floor(positions[node]+.5))]
        ranked.append((distance, reference["id"], reference, positions, wet))
    if not ranked: return None, {}
    ranked.sort(key=lambda r: (r[0], r[1]))
    first = ranked[0]
    selected = [r for r in ranked if r[2]["branch"] == first[2]["branch"] and r[4] == first[4]][:model["neighbors"]]
    if first[0] <= 1e-20: selected = [first]
    weights = [1/max(math.sqrt(r[0]), 1e-8) for r in selected] if len(selected) > 1 else [1.]
    weights = [weight/sum(weights) for weight in weights]
    nodes, components = inp["stageCount"]+2, len(feed)
    prediction = {"propertyRevision": model["propertyRevision"], "branch": first[2]["branch"],
                  "temperatures": [0.]*nodes, "freeWater": [0.]*nodes, "wetTrays": first[4],
                  "liquid": [[0.]*components for _ in range(nodes)], "vapor": [[0.]*components for _ in range(nodes)]}
    for (_, _, reference, positions, _), weight in zip(selected, weights):
        for node, position in enumerate(positions):
            lower, fraction = int(math.floor(position)), position-math.floor(position)
            upper = min(reference["stageCount"]+1, lower+1)
            # Convex-combination arithmetic is intentionally separate from Java's a+f*(b-a) formulation.
            prediction["temperatures"][node] += weight*((1-fraction)*reference["temperatures"][lower]+fraction*reference["temperatures"][upper])
            if prediction["wetTrays"][node]:
                prediction["freeWater"][node] += weight*(steam/reference["totalSteamFlow"])*(
                    (1-fraction)*reference["freeWater"][lower]+fraction*reference["freeWater"][upper])
            for component, requested in enumerate(feed):
                if requested == 0: continue
                factor = weight*requested/reference["componentFeed"][component]
                for phase in ("liquid", "vapor"):
                    prediction[phase][node][component] += factor*((1-fraction)*reference[phase][lower][component]+fraction*reference[phase][upper][component])
    prediction["temperatures"][0] = next(s["kelvin"] for s in inp["specifications"] if "kelvin" in s)
    if prediction["branch"] == "LIQUID_ONLY": prediction["vapor"][0] = [0.]*components
    if prediction["branch"] == "VAPOR_ONLY": prediction["liquid"][0] = [0.]*components
    trace = {"referenceIds": [r[1] for r in selected], "referenceInputSha256": [r[2]["inputSha256"] for r in selected],
             "referenceStageCounts": [r[2]["stageCount"] for r in selected], "distances": [r[0] for r in selected],
             "weights": weights, "exactMatch": first[0] <= 1e-20,
             "fractionalInterpolation": any(abs(position-round(position)) > 1e-12 for r in selected for position in r[3]),
             "differentStageCount": any(r[2]["stageCount"] != inp["stageCount"] for r in selected)}
    return prediction, trace


def prepare(source, model_paths, output):
    if output.exists(): raise FileExistsError("Choose a fresh TRAIN parity fixture path")
    models = [read_model(path) for path in model_paths]
    if len(models) != 2 or {m["neighbors"] for m in models} != {1, 3}: raise ValueError("Supply the frozen k1 and k3 candidates")
    model_ids = [m["modelId"] for m in models]
    if len(set(model_ids)) != len(model_ids): raise ValueError("Distinct candidate model IDs required")
    references = [{r["id"]: r for r in model["references"]} for model in models]
    common = set(references[0]).intersection(references[1])
    authored = {}
    with source.open(encoding="utf-8-sig") as journal:
        for line in journal:
            if not line.strip(): continue
            row = json.loads(line)
            if row.get("split") != "train" or str(row["id"]) not in common: continue
            # No source profile targets are read: the already-frozen library supplies the oracle's profiles.
            if row.get("success") is not True or row.get("equilibriumQualified") is not True:
                raise ValueError("Reference source is not qualified TRAIN data")
            key, inp = str(row["id"]), row["input"]
            digest = canonical_input_hash(inp)
            if any(index[key]["inputSha256"] != digest for index in references): raise ValueError("Reference input hash differs from TRAIN source")
            authored[key] = {"input": inp, "formulationRevision": row["formulationRevision"], "inputSha256": digest}
    if set(authored) != common: raise ValueError("Frozen TRAIN reference input missing from journal")
    ordered = sorted(authored)
    chosen = []
    predicates = [lambda i: not i.get("steamFeeds") and not i.get("pumparounds") and not i.get("sideDraws"),
                  lambda i: bool(i.get("steamFeeds")), lambda i: bool(i.get("pumparounds")), lambda i: i["stageCount"] >= 32]
    for predicate in predicates:
        choices = [key for key in ordered if key not in chosen and predicate(authored[key]["input"])]
        if not choices: raise ValueError("TRAIN library lacks one requested exact-match parity category")
        chosen.append(choices[0])
    rows = []

    def fixture_row(key, kind, inp):
        checks = {}
        for model in models:
            prediction, trace = independent_prediction(model, inp)
            if prediction is None: return None
            checks[model["modelId"]] = trace
        source_row = authored[key]
        return {"id": key+"::"+kind, "kind": kind, "input": inp, "inputSha256": canonical_input_hash(inp),
                "formulationRevision": source_row["formulationRevision"],
                "origin": {"sourceId": key, "sourceSplit": "train", "sourceInputSha256": source_row["inputSha256"],
                           "sourceInput": source_row["input"]}, "oracleRouting": checks}

    for key in chosen:
        row = fixture_row(key, "train-exact", copy.deepcopy(authored[key]["input"]))
        if row is None or not all(v["exactMatch"] for v in row["oracleRouting"].values()): raise ValueError("Exact TRAIN fixture is unsupported")
        rows.append(row)
    for key in ordered:
        for direction in (1, -1):
            inp = copy.deepcopy(authored[key]["input"])
            inp["feedComponentMolarFlowsMolPerSecond"] = [value*(1+direction*.0002) for value in inp["feedComponentMolarFlowsMolPerSecond"]]
            inp["feedTemperatureKelvin"] += direction*.03125
            row = fixture_row(key, "train-derived-flow", inp)
            if row is not None and all(not v["exactMatch"] for v in row["oracleRouting"].values()):
                if any(len(v["referenceIds"]) == 3 for v in row["oracleRouting"].values()): break
        else: continue
        rows.append(row); break
    else: raise ValueError("No supported non-exact TRAIN-derived flow fixture exercising k3 blending")
    train_stage_counts = sorted({value["input"]["stageCount"] for value in authored.values()})
    for key in ordered:
        original = authored[key]["input"]
        if original.get("steamFeeds") or original.get("pumparounds") or original.get("sideDraws") or original["feedStageNumber"] == 1: continue
        alternatives = sorted((n for n in train_stage_counts if n != original["stageCount"]), key=lambda n: (abs(n-original["stageCount"]), n))
        for stages in alternatives:
            inp = copy.deepcopy(original); inp["stageCount"] = stages
            inp["feedStageNumber"] = max(1, min(stages, int(math.floor(original["feedStageNumber"]*(stages+1)/(original["stageCount"]+1)+.5))))
            row = fixture_row(key, "train-derived-geometry", inp)
            if row is not None and all(not v["exactMatch"] and v["fractionalInterpolation"] and v["differentStageCount"] for v in row["oracleRouting"].values()): break
        else: continue
        rows.append(row); break
    else: raise ValueError("No supported TRAIN-derived variable-stage interpolation fixture")
    fixture = {"revision": REVISION, "heldOutProfilesUsed": False, "sourceJournalSha256": sha(source),
               "modelSha256ById": {model["modelId"]: sha(path) for model, path in zip(models, model_paths)},
               "sourceSelection": "Four exact TRAIN references; one small TRAIN flow/temperature perturbation; one geometry perturbation using only TRAIN stage counts",
               "trainStageCounts": train_stage_counts, "rows": rows}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as target: json.dump(fixture, target, indent=2, allow_nan=False)
    print(json.dumps({"fixture": str(output), "fixtureSha256": sha(output), "cases": len(rows),
                      "kinds": dict(Counter(r["kind"] for r in rows)), "heldOutProfilesUsed": False}, indent=2))


def verify(model_path, fixture_path, java_path, output=None):
    model, fixture, java = read_model(model_path), json.loads(fixture_path.read_text()), json.loads(java_path.read_text())
    if fixture.get("heldOutProfilesUsed") is not False or java.get("heldOutProfilesUsed") is not False or java.get("nativeSolverInvoked") is not False:
        raise ValueError("TRAIN-only pure inference parity required")
    if sha(model_path) != java["modelSha256"] or sha(model_path) != fixture["modelSha256ById"][model["modelId"]] or sha(fixture_path) != java["fixtureSha256"]:
        raise ValueError("Frozen model or parity fixture changed")
    expected = {r["id"]: r for r in fixture["rows"]}
    if len(expected) != len(fixture["rows"]) or len(java["rows"]) != len(expected): raise ValueError("Missing or duplicate parity case")
    references = {r["id"]: r for r in model["references"]}
    seen, counts, maxima, normalized_maxima, routes = set(), {}, {}, {}, {}

    def compare(name, actual, wanted):
        if isinstance(wanted, list):
            if not isinstance(actual, list) or len(actual) != len(wanted): raise AssertionError(name+" shape mismatch")
            for a, b in zip(actual, wanted): compare(name, a, b)
            return
        error = abs(actual-wanted)
        tolerance = ABSOLUTE_TOLERANCE+RELATIVE_TOLERANCE*max(abs(actual), abs(wanted))
        if not math.isfinite(error) or error > tolerance: raise AssertionError(f"{name}: Java={actual}, independent Python={wanted}")
        counts[name] = counts.get(name, 0)+1; maxima[name] = max(maxima.get(name, 0), error)
        normalized_maxima[name] = max(normalized_maxima.get(name, 0), error/tolerance)

    for row in java["rows"]:
        key = row["id"]
        if key not in expected or key in seen: raise ValueError("Unexpected or repeated Java parity case")
        seen.add(key); reference = expected[key]; origin = reference["origin"]
        if origin["sourceSplit"] != "train" or origin["sourceId"] not in references: raise ValueError("Not a TRAIN reference origin")
        if canonical_input_hash(origin["sourceInput"]) != references[origin["sourceId"]]["inputSha256"]: raise ValueError("TRAIN source hash mismatch")
        if canonical_input_hash(row["input"]) != reference["inputSha256"] or canonical_input_hash(reference["input"]) != reference["inputSha256"]:
            raise ValueError("Parity requested input changed")
        if row["kind"] != reference["kind"] or row["formulationRevision"] != reference["formulationRevision"]:
            raise ValueError("Fixture kind or formulation changed")
        compare("global", row["global"], input_coordinates(reference["input"]))
        prediction, trace = independent_prediction(model, reference["input"])
        if prediction is None or row["predictionSupported"] is not True: raise AssertionError("Selected parity input has no matching supported prediction")
        actual = row["prediction"]
        if actual["branch"] != prediction["branch"] or actual["propertyRevision"] != prediction["propertyRevision"] or actual["wetTrays"] != prediction["wetTrays"]:
            raise AssertionError("Branch, property revision, or wet mask differs")
        for field in ("temperatures", "liquid", "vapor", "freeWater"): compare(field, actual[field], prediction[field])
        routes[key] = trace
    if not any(not trace["exactMatch"] and trace["fractionalInterpolation"] and trace["differentStageCount"] for trace in routes.values()):
        raise AssertionError("Variable-stage non-exact interpolation was not exercised")
    if model["neighbors"] == 3 and not any(len(trace["referenceIds"]) == 3 for trace in routes.values()):
        raise AssertionError("Three-reference averaging was not exercised")
    report = {"passed": True, "modelId": model["modelId"], "modelSha256": sha(model_path), "fixtureSha256": sha(fixture_path),
              "caseCount": len(routes), "fixtureKinds": dict(Counter(r["kind"] for r in fixture["rows"])),
              "absoluteTolerance": ABSOLUTE_TOLERANCE, "relativeTolerance": RELATIVE_TOLERANCE,
              "valuesCompared": counts, "maximumAbsoluteErrors": maxima, "maximumToleranceFractions": normalized_maxima,
              "branchAndWetMasksExact": True, "heldOutProfilesUsed": False, "nativeSolverInvoked": False, "independentRouting": routes}
    if output:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x", encoding="utf-8") as target: json.dump(report, target, indent=2, allow_nan=False)
    print(json.dumps({k: v for k, v in report.items() if k != "independentRouting"}, indent=2))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare")
    preparation.add_argument("--source", type=Path, required=True)
    preparation.add_argument("--model", type=Path, action="append", required=True)
    preparation.add_argument("--output", type=Path, required=True)
    checking = commands.add_parser("verify")
    checking.add_argument("model", type=Path); checking.add_argument("fixture", type=Path); checking.add_argument("java_output", type=Path)
    checking.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.command == "prepare": prepare(args.source, args.model, args.output)
    else: verify(args.model, args.fixture, args.java_output, args.output)


if __name__ == "__main__": main()
