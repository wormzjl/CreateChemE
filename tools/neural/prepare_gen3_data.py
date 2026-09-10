"""Preserve generation two outside Gradle clean and augment labels without moving folds."""
import argparse
from collections import Counter
import copy
import hashlib
import json
from pathlib import Path
import shutil

from prepare_generalized_evaluation import canonical_input_hash

ROOT = Path(__file__).resolve().parents[2]
GEN2 = ROOT / "build/neural-generalized"
CACHE = ROOT / ".neural-cache/gen2-1a4a01d"
GEN3 = ROOT / "build/neural-gen3/data"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def jsonl(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8-sig").splitlines() if line]


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")


def cache_generation_two():
    manifest_path = CACHE / "manifest.json"
    if manifest_path.exists():
        manifest = read(manifest_path)
        for entry in manifest["files"]:
            if sha(CACHE / entry["cachedPath"]) != entry["sha256"]:
                raise ValueError("Cached gen2 data changed: " + entry["cachedPath"])
        return manifest
    if CACHE.exists() and any(CACHE.iterdir()):
        raise FileExistsError("Incomplete cache exists; inspect it rather than overwriting")
    sources = []
    for folder in ("design", "evaluation-inputs", "v2", "model-v1", "model-v2", "validation-v1",
                   "validation-v2", "validation-v1-32", "evaluation", "benchmark", "profile-current", "profile-neural", "analysis"):
        for path in sorted((GEN2 / folder).rglob("*")):
            if path.is_file() and not (folder.startswith("model-") and path.name == "cases.jsonl"):
                sources.append((path, Path("study") / path.relative_to(GEN2)))
    for name in ("parallel-check.json", "final-junit-main.json", "final-junit-additional.json"):
        sources.append((GEN2 / name, Path("study") / name))
    for name in ("generalized-selection.json", "generalized-deployment.json", "generalized-model-card.json",
                 "generalized-case-map.jsonl.gz", "train_generalized.py", "generalized_design.py"):
        sources.append((ROOT / "tools/neural" / name, Path("provenance") / name))
    sources.append((ROOT / "src/main/resources/data/createcheme/neural/v3-general-stage.json", Path("model.json")))
    entries = []
    for source, relative in sources:
        if not source.is_file():
            raise FileNotFoundError(source)
        target = CACHE / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        digest = sha(source)
        if sha(target) != digest:
            raise ValueError("Cache copy verification failed")
        entries.append({"sourcePath": str(source.relative_to(ROOT)).replace("\\", "/"),
            "cachedPath": str(relative).replace("\\", "/"), "bytes": target.stat().st_size, "sha256": digest})
    manifest = {"revision": "gen2-cache-v1", "sourceCommit": "1a4a01d", "cacheRoot": ".neural-cache/gen2-1a4a01d",
        "scope": "Canonical teacher/design, both trained candidates, validation/evaluation/benchmark results, provenance and model. Duplicate model-directory copies of cases.jsonl are represented by study/v2/cases.jsonl.",
        "retention": "Outside build/ so gradle clean does not delete this cache. Never used as an output directory.",
        "files": entries, "totalBytes": sum(entry["bytes"] for entry in entries)}
    write_json(manifest_path, manifest)
    write_json(ROOT / "tools/neural/gen2-cache-manifest.json", manifest)
    return manifest


def qualified(row):
    return row.get("success") is True and row.get("equilibriumQualified") is True and row.get("waterQualification") in ("DRY_EQUILIBRIUM", "WET_EQUILIBRIUM") and "seed" in row


def prepare():
    manifest = cache_generation_two()
    destination = GEN3 / "cases.jsonl"
    if destination.exists():
        raise FileExistsError("Gen3 data already exists; choose a new revision")
    original_path = CACHE / "study/v2/cases.jsonl"
    recovery_path = CACHE / "study/evaluation/evaluation.jsonl"
    original = jsonl(original_path)
    recovery = {row["id"]: row for row in jsonl(recovery_path)}
    original_sha, recovery_sha = sha(original_path), sha(recovery_path)
    promoted, retained, rejected_advisories = Counter(), Counter(), []
    records = []
    for old in sorted(original, key=lambda row: str(row["id"])):
        row = copy.deepcopy(old)
        recovered = recovery.get(old["id"])
        if recovered and canonical_input_hash(old["input"]) != canonical_input_hash(recovered["input"]):
            raise ValueError("Recovery input mismatch")
        if old.get("success") is False and recovered and qualified(recovered):
            seed = recovered["seed"]
            if canonical_input_hash(seed["input"]) != canonical_input_hash(old["input"]):
                raise ValueError("Recovered state belongs to a different problem")
            checks = recovered["diagnostics"]["acceptanceAudit"]["checks"]
            certificate = recovered["diagnostics"]["convergenceEvidence"]
            if not checks or not all(check["passed"] is True for check in checks):
                raise ValueError("Recovered label lacks its native acceptance audit")
            if not (certificate["hasFinalNewtonStep"] is True and certificate["closureTolerance"] == 1e-8
                    and certificate["finalLinearBackwardError"] <= 1e-12
                    and certificate["maximumLogFlowChange"] <= 1e-8 and certificate["maximumTemperatureStepRatio"] <= 1):
                raise ValueError("Recovered label lacks the strict final Newton certificate")
            for key in ("success", "status", "diagnostics", "seed", "streams", "waterQualification", "waterEvidence",
                        "equilibriumQualified", "wetTrayCount", "formulationRevision"):
                if key in recovered:
                    row[key] = copy.deepcopy(recovered[key])
            row.pop("failure", None)
            row.pop("failureClass", None)
            row["labelProvenance"] = {"kind": "qualified_gen2_recovery", "sourceCaseId": old["id"],
                "sourceModelId": "tjl20-general-stage-v1", "recoveryJournalSha256": recovery_sha,
                "originalOutcome": {"success": old["success"], "status": old["status"], "failure": old.get("failure")},
                "splitUnchanged": old["split"], "eligibleForFitting": old["split"] == "train"}
            promoted[old["split"]] += 1
        else:
            row["labelProvenance"] = {"kind": "original_current_initializer", "sourceJournalSha256": original_sha,
                "sourceCaseId": old["id"], "splitUnchanged": old["split"], "eligibleForFitting": old["split"] == "train" and qualified(old)}
            if qualified(old):
                retained[old["split"]] += 1
            if old.get("success") is False and recovered and recovered.get("success") is True:
                rejected_advisories.append(old["id"])
        if row["split"] != old["split"]:
            raise ValueError("A column changed folds")
        records.append(row)
    if promoted != Counter({"train": 96, "validation": 26, "test": 19}):
        raise ValueError("Unexpected recovery counts; inspect provenance before fitting")
    signatures = {}
    for row in records:
        digest = canonical_input_hash(row["input"])
        if digest in signatures:
            raise ValueError("Duplicate or cross-fold input")
        signatures[digest] = row["split"]
    GEN3.mkdir(parents=True, exist_ok=True)
    destination.write_text("".join(json.dumps(row, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n" for row in records), encoding="utf-8")
    info = {"revision": "gen3-augmented-data-v1", "gen2CacheManifestSha256": sha(CACHE / "manifest.json"),
        "originalTeacherJournalSha256": sha(original_path), "recoveryJournalSha256": sha(recovery_path),
        "rows": len(records), "originalQualified": dict(retained), "qualifiedRescues": dict(promoted),
        "qualifiedBySplit": dict(Counter(row["split"] for row in records if qualified(row))),
        "excludedAdvisoryRescues": rejected_advisories, "datasetSha256": sha(destination),
        "fittingPolicy": "Only original train-fold qualified labels, including 96 recovered train cases. The 26 validation and 19 test recoveries remain in their original folds and never enter fitting.",
        "newGenerationCacheUntouched": True, "allInputsAndSplitsUnchanged": True}
    write_json(GEN3 / "data.json", info)
    print(json.dumps({"cacheFiles": len(manifest["files"]), "cacheBytes": manifest["totalBytes"], **info}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-cache-only", action="store_true")
    args = parser.parse_args()
    if args.verify_cache_only:
        print(json.dumps({"verifiedFiles": len(cache_generation_two()["files"])}))
    else:
        prepare()
