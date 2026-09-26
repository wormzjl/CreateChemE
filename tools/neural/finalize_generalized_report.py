"""Package the completed experiment's model card and complete input/outcome map."""
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "build/neural-generalized"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def rows(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compact(outcome):
    if outcome is None:
        return {"evaluated": False}
    keys = ("success", "status", "failure", "failureClass", "waterQualification", "equilibriumQualified",
            "wetTrayCount", "ms", "cpuMillis", "allocatedBytes", "rawPrediction", "rawVsTeacher", "rawVsFinal")
    value = {key: outcome[key] for key in keys if key in outcome}
    value["evaluated"] = True
    if outcome.get("diagnostics"):
        value["maximumScaledResidual"] = outcome["diagnostics"]["maximumScaledResidual"]
        value["convergenceEvidence"] = outcome["diagnostics"]["convergenceEvidence"]
    return value


def main():
    report = read(BASE / "analysis/summary.json")
    if not report["teacher"]["evidence"]["complete"] or not report["benchmark"]["evidence"]["complete"]:
        raise ValueError("Only completed experiments may be packaged")
    if len(report["isolatedMemoryRuns"]) != 2 or any(not run["evidence"]["complete"] for run in report["isolatedMemoryRuns"]):
        raise ValueError("Both isolated memory profiles are required")
    if any(not run["evidence"]["complete"] for run in report["evaluations"]):
        raise ValueError("Evaluation is incomplete")
    selection = read(ROOT / "tools/neural/generalized-selection.json")
    model_directory = BASE / selection["selected"]["modelDirectory"]
    report["model"] = read(model_directory / "general-training.json")
    report["selection"] = selection
    report["deployment"] = read(ROOT / "tools/neural/generalized-deployment.json")
    if sha(ROOT / selection["bundledResource"]) != selection["bundledSha256"]:
        raise ValueError("The frozen model artifact changed")
    tests = [read(BASE / name) for name in ("final-junit-main.json", "final-junit-additional.json")]
    parallel = read(BASE / "parallel-check.json")
    report["verification"] = {"junitTests": sum(test["tests"] for test in tests),
        "junitFailures": sum(test["failures"] for test in tests), "junitErrors": sum(test["errors"] for test in tests),
        "junitRuns": tests, "pythonContractTests": 16,
        "pythonCommand": "python -m unittest discover -s tools/neural -p test_*generalized*.py",
        "parallel": {"allPassed": parallel["allPassed"], "scheduling": parallel["scheduling"],
            "failureCancellation": parallel["failureCancellation"],
            "simultaneousSolverCalls": parallel["numerical"]["maximumActiveSolverCalls"],
            "profilePairs": len(parallel["numerical"]["cases"]),
            "differingProfileValues": sum(case["differingProfileValues"] for case in parallel["numerical"]["cases"])},
        "javaPythonParity": read(model_directory / "parity-report.json"),
        "inGameUiRun": False}
    if report["verification"]["junitFailures"] or report["verification"]["junitErrors"] or not parallel["allPassed"]:
        raise ValueError("Verification failed")
    teacher = {row["id"]: row for row in rows(BASE / "v2/cases.jsonl")}
    evaluation = {row["id"]: row for row in rows(BASE / "evaluation/evaluation.jsonl")}
    exclusions = {row["id"]: row for row in rows(BASE / "design/exclusions.jsonl")}
    candidates = rows(BASE / "design/candidate-matrix.jsonl")
    archive = ROOT / "tools/neural/generalized-case-map.jsonl.gz"
    with archive.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, filename="", mode="wb", mtime=0) as compressed:
            for row in candidates:
                record = {"id": row["id"], "split": row["split"], "input": row["input"], "design": row["design"],
                    "preflightAdmitted": row["id"] not in exclusions,
                    "exclusions": exclusions.get(row["id"], {}).get("exclusions", []),
                    "current": compact(teacher.get(row["id"])), "generalNeural": compact(evaluation.get(row["id"]))}
                compressed.write((json.dumps(record, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode())
    with gzip.open(archive, "rt", encoding="utf-8") as saved:
        restored = [json.loads(line) for line in saved]
    if len(restored) != 2809 or len({row["id"] for row in restored}) != 2809:
        raise ValueError("Incomplete archived case map")
    report["archivedCaseMap"] = {"path": str(archive.relative_to(ROOT)).replace("\\", "/"),
        "sha256": sha(archive), "compressedBytes": archive.stat().st_size, "candidateRows": len(restored),
        "scope": "Every finite-design input and latent vector, preflight disposition, original outcome and fresh-model outcome where evaluated. Unattempted predictions are explicitly marked evaluated=false. Full teacher profiles and original journals remain in build/neural-generalized with hashes in this card."}
    output = ROOT / "tools/neural/generalized-model-card.json"
    output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    print(json.dumps({"modelCard": str(output), "caseMapBytes": archive.stat().st_size,
        "junitTests": report["verification"]["junitTests"], "candidateRows": len(restored)}))


if __name__ == "__main__":
    main()
