"""Analyze the complete frozen campaign with distinct historical/repeated labels."""
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "build/neural-gen3"


def main():
    selection = json.loads((BASE / "selection.json").read_text())
    command = [sys.executable, str(ROOT / "tools/neural/summarize_gen3.py"),
        "--design", str(BASE / "fresh-design"), "--selection", str(BASE / "selection.json"),
        "--cache-manifest", str(ROOT / ".neural-cache/gen2-1a4a01d/manifest.json"),
        "--output", str(BASE / "analysis/summary.json"), "--final", "--baseline-label", "gen2",
        "--baseline-label", "current-reference", "--run", "current-reference=" + str(BASE / "evaluation-inputs/current-reference/evaluation.jsonl")]
    for label, entry in selection["candidates"].items():
        command.extend(["--run", label + "=" + str(BASE / ("comparison-" + label) / "evaluation.jsonl"),
            "--run", "validation:" + label + "=" + str(ROOT / entry["validationPath"] / "evaluation.jsonl")])
    for label in selection["fullRecoveryLabels"]:
        command.extend(["--run", "full-recovery:" + label + "=" + str(BASE / ("recovery-" + label) / "evaluation.jsonl"),
            "--run", "fitted-replay:" + label + "=" + str(BASE / ("replay-" + label) / "evaluation.jsonl")])
    for label in selection["fallbackBenchmarkLabels"]:
        command.extend(["--serial", str(BASE / ("benchmark-" + label) / "evaluation.jsonl")])
    for label in selection["serialCandidateLabels"]:
        command.extend(["--isolated-profile", label + "=" + str(BASE / ("profile-" + label) / "evaluation.jsonl")])
    subprocess.run(command, cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
