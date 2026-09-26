"""Record regression, parity, cache and packaged-model checks after native runs."""
import hashlib
import json
from pathlib import Path
import sys
import unittest
import zipfile

from prepare_gen3_data import cache_generation_two
from prepare_generalized_evaluation import sha

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "build/neural-gen3"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    suite = unittest.defaultTestLoader.discover(str(ROOT / "tools/neural"), pattern="test_*.py")
    with (BASE / "python-tests.log").open("w", encoding="utf-8") as stream:
        result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    junit = read(BASE / "junit.json")
    parity_paths = ["mlp-v1/parity-report.json", "factorized-v1/parity-report.json",
        "nearest-parity/verified-k1.json", "nearest-parity/verified-k3.json"]
    parity = [{"path": path, "sha256": sha(BASE / path), "report": read(BASE / path)} for path in parity_paths]
    cache = cache_generation_two()
    artifact = ROOT / "build/libs/createcheme-0.1.0.jar"
    resources = {}
    with zipfile.ZipFile(artifact) as packaged:
        for name in ("v3-general-stage.json", "v3-general-gen3-factorized.json", "v3-mvp.json", "v3-tjl20-dry.json", "v3-tjl20-wet.json"):
            entry = "data/createcheme/neural/" + name
            expected = ROOT / "src/main/resources" / entry
            digest = hashlib.sha256(packaged.read(entry)).hexdigest()
            if digest != sha(expected):
                raise ValueError("Packaged model differs from source: " + name)
            resources[name] = digest
    passed = result.wasSuccessful() and junit["tests"] >= 143 and not junit["failures"] and not junit["errors"] and not junit["skipped"] and all(entry["report"]["passed"] for entry in parity)
    report = {"passed": passed, "junit": junit, "pythonTests": result.testsRun,
        "pythonFailures": len(result.failures), "pythonErrors": len(result.errors), "pythonSkipped": len(result.skipped),
        "pythonCommand": "python tools/neural/verify_gen3_campaign.py (unittest discover tools/neural/test_*.py)",
        "pythonTestModules": {str(path.relative_to(ROOT)): sha(path) for path in sorted((ROOT / "tools/neural").glob("test_*.py"))},
        "pythonVersion": sys.version, "javaPythonParity": parity,
        "gen2CacheVerifiedFiles": len(cache["files"]), "gen2CacheVerifiedBytes": cache["totalBytes"],
        "jar": {"path": str(artifact.relative_to(ROOT)), "sha256": sha(artifact), "bytes": artifact.stat().st_size,
            "bundledModelSha256": resources}, "inGameUiRun": False,
        "nativeQualification": "All experiment corrections used existing physical audits and final Newton certificates. No thermodynamic equation, convergence tolerance, or native solver implementation was changed."}
    (BASE / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"passed": passed, "junitTests": junit["tests"], "pythonTests": result.testsRun,
        "parityChecks": len(parity), "gen2CacheVerifiedFiles": len(cache["files"]), "jarSha256": sha(artifact)}, indent=2))
    if not passed:
        raise SystemExit("Verification failed; inspect build/neural-gen3/python-tests.log and junit.json")


if __name__ == "__main__":
    main()
