"""Render the final, unbiased test-only coverage visual from complete journals.

The HTML is a separately authored literal fragment template. This script only
embeds its validated aggregate JSON; it never creates markup in a Python string.
No partial evaluation is rendered and no model or routing choice is changed.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import json
from pathlib import Path

from prepare_generalized_evaluation import canonical_input_hash, load_jsonl, sha


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = Path("C:/Users/wormz/.codex/visualizations/2026/09/09/01a085c2-bbcb-7c91-9f67-1754035436ac/generalized-initializer-coverage.html")
TEMPLATE = Path(__file__).with_name("generalized-initializer-coverage.template.html")
EXPECTED_EVALUATION_COUNT = 2312
EXPECTED_TEST_COUNT = 395
QUALIFIED_GRADES = {"DRY_EQUILIBRIUM", "WET_EQUILIBRIUM"}


def complete_journal(path, expected):
    metadata_path = path.parent / "run.json"
    if not metadata_path.exists():
        raise ValueError(f"No final run metadata yet: {metadata_path}")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8-sig"))
    if metadata.get("completed") != expected or metadata.get("caseCount") != expected:
        raise ValueError(f"Final visualization requires all {expected} completed requests: {path}")
    rows, _ = load_jsonl(path)
    if len(rows) != expected or len({str(row["id"]) for row in rows}) != expected:
        raise ValueError("Incomplete or duplicate journal IDs: " + str(path))
    return {str(row["id"]): row for row in rows}, metadata


def qualified(row):
    success = row.get("success") is True
    quality = row.get("equilibriumQualified") is True
    grade = row.get("waterQualification")
    if quality != (success and grade in QUALIFIED_GRADES):
        raise ValueError("Inconsistent water qualification: " + str(row["id"]))
    return success and quality


def aggregate(rows, teacher, neural, dimension):
    groups = defaultdict(list)
    for row in rows:
        item = row["input"]
        key = item["stageCount"] if dimension == "stages" else len(item.get("pumparounds", []))
        groups[key].append(str(row["id"]))
    points = []
    for x, ids in sorted(groups.items()):
        point = {"x": x, "n": len(ids)}
        for label, journal in (("current", teacher), ("neural", neural)):
            observations = [journal[case_id] for case_id in ids]
            point[label] = {"qualified": sum(qualified(row) for row in observations),
                "advisories": sum(row.get("success") is True and row.get("waterQualification") == "DRY_SUPERSATURATED" for row in observations),
                "accepted": sum(row.get("success") is True for row in observations)}
        points.append(point)
    return points


def validated_data(design_path, teacher_path, evaluation_path, selection_path):
    authored, _ = load_jsonl(design_path)
    test = [row for row in authored if row.get("split") == "test"]
    if len(authored) != 2793 or len(test) != EXPECTED_TEST_COUNT:
        raise ValueError("Wrong frozen design revision or held-out test population")
    teacher, teacher_run = complete_journal(teacher_path, len(authored))
    neural, neural_run = complete_journal(evaluation_path, EXPECTED_EVALUATION_COUNT)
    selection = json.loads(selection_path.read_text(encoding="utf-8-sig"))
    if selection.get("testOrRetryOutcomesUsedForSelection") is not False:
        raise ValueError("The selection record must exclude test/retry outcomes")
    model_hash = selection["selected"]["modelSha256"]
    if neural_run.get("modelSha256") != model_hash or selection.get("bundledSha256") != model_hash:
        raise ValueError("The evaluated model does not match the frozen selected model")
    if selection.get("datasetSha256") != sha(teacher_path):
        raise ValueError("Teacher journal changed after model selection")
    if teacher_run.get("sourceSha256") != sha(design_path):
        raise ValueError("Teacher did not execute the frozen admitted matrix")
    if neural_run.get("mode") != "evaluate":
        raise ValueError("Use the full independent LNN_ONLY evaluation, not validation or a profile run")
    test_ids = {str(row["id"]) for row in test}
    if not test_ids <= neural.keys() or not test_ids <= teacher.keys():
        raise ValueError("A true held-out test ID is missing from a complete journal")
    # Match the exact original design IDs, not just split='test': some historical
    # failure retries carry an old test label and must not enter this denominator.
    for authored_row in test:
        case_id = str(authored_row["id"])
        expected_hash = canonical_input_hash(authored_row["input"])
        for journal in (teacher, neural):
            row = journal[case_id]
            if row.get("split") != "test" or canonical_input_hash(row["input"]) != expected_hash:
                raise ValueError("Held-out input/split was changed: " + case_id)
            qualified(row)
    panels = [
        {"key": "stages", "xLabel": "Equilibrium trays (count)", "sampleLabel": "tray count",
         "points": aggregate(test, teacher, neural, "stages")},
        {"key": "pumparounds", "xLabel": "Pumparounds (count)", "sampleLabel": "PA count",
         "points": aggregate(test, teacher, neural, "pumparounds")},
    ]
    if {point["x"] for point in panels[0]["points"]} != set(range(7, 64, 7)) or {point["x"] for point in panels[1]["points"]} != set(range(5)):
        raise ValueError("The expected tray-count and PA-count facets are incomplete")
    totals = {}
    for key, journal in (("current", teacher), ("neural", neural)):
        values = [journal[case_id] for case_id in test_ids]
        totals[key] = {"qualified": sum(qualified(row) for row in values), "n": len(values),
            "advisories": sum(row.get("success") is True and row.get("waterQualification") == "DRY_SUPERSATURATED" for row in values)}
    for panel in panels:
        if sum(point["n"] for point in panel["points"]) != EXPECTED_TEST_COUNT:
            raise AssertionError("Facet denominators do not sum to the full test population")
        for key in totals:
            if sum(point[key]["qualified"] for point in panel["points"]) != totals[key]["qualified"]:
                raise AssertionError("Facet qualified numerator drift")
    return {"revision": "generalized-final-test-coverage-v1", "testCases": EXPECTED_TEST_COUNT,
        "series": [{"key": "current", "label": "Current initializer", "mode": "CURRENT_ONLY", "color": "var(--viz-series-1)", "shape": "square"},
                   {"key": "neural", "label": "New LNN", "mode": "LNN_ONLY", "color": "var(--viz-series-2)", "shape": "circle"}],
        "panels": panels, "totals": totals,
        "evidence": {"teacherSha256": sha(teacher_path), "evaluationSha256": sha(evaluation_path),
            "designSha256": sha(design_path), "selectionSha256": sha(selection_path), "modelSha256": model_hash,
            "evaluationCompleted": neural_run["completed"], "denominator": "All 395 original design test inputs, including every failure, admission and advisory.",
            "numerator": "Native solve success AND equilibriumQualified=true; dry/wet qualification grades cross-checked.",
            "frozenUtc": selection["frozenUtc"]}}


def render(data, output):
    template = TEMPLATE.read_text(encoding="utf-8")
    token = "@@GENERALIZED_COVERAGE_DATA@@"
    if template.count(token) != 1:
        raise ValueError("Literal fragment template must contain exactly one data token")
    embedded = json.dumps(data, ensure_ascii=False, separators=(",", ":"), allow_nan=False).replace("</", "<\\/")
    fragment = template.replace(token, embedded)
    if any(tag in fragment.lower() for tag in ("<!doctype", "<html", "<head", "<body")):
        raise ValueError("Expected an inline fragment, not a standalone document")
    if "\\\"" in fragment or "\\n" in fragment:
        raise ValueError("Fragment contains escaped markup or literal newline escapes")
    if len(fragment.encode("utf-8")) >= 1_000_000:
        raise ValueError("Inline visual exceeds the 1 MB limit")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(fragment, encoding="utf-8", newline="\n")
    actual = output.read_text(encoding="utf-8")
    if actual != fragment or actual.count('.attr("data-chart-hover-overlay", "cross-series")') != 1:
        raise AssertionError("Written literal fragment differs from its validated source")
    return {"path": str(output.resolve()), "bytes": len(actual.encode("utf-8")), "testCases": data["testCases"],
            "totals": data["totals"], "panels": data["panels"], "modelSha256": data["evidence"]["modelSha256"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--design", type=Path, default=ROOT / "build/neural-generalized/design/matrix.jsonl")
    parser.add_argument("--teacher", type=Path, default=ROOT / "build/neural-generalized/v2/cases.jsonl")
    parser.add_argument("--evaluation", type=Path, default=ROOT / "build/neural-generalized/evaluation/evaluation.jsonl")
    parser.add_argument("--selection", type=Path, default=ROOT / "tools/neural/generalized-selection.json")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    data = validated_data(args.design, args.teacher, args.evaluation, args.selection)
    print(json.dumps(render(data, args.output), indent=2))


if __name__ == "__main__":
    main()
