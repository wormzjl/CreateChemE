"""Package completed Gen3 comparisons while retaining the immutable Gen2 cache."""
import copy
import gzip
import json
from pathlib import Path

from finalize_generalized_report import compact
from prepare_generalized_evaluation import canonical_input_hash, load_jsonl, sha
from prepare_gen3_data import cache_generation_two

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "build/neural-gen3"


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def rows(path):
    return load_jsonl(path)[0]


def resolved(path):
    path = Path(path)
    return path if path.is_absolute() else ROOT / path


def available_source(path):
    """An immutable cached gen2 run can retain its original pre-clean source path."""
    source = resolved(path)
    if source.exists():
        return source
    try:
        relative = source.relative_to(ROOT / "build/neural-generalized")
    except ValueError:
        return source
    cached = ROOT / ".neural-cache/gen2-1a4a01d/study" / relative
    return cached if cached.exists() else source


def verify_run_evidence(evidence, *, count, mode, expected_model_sha=None, budget=None,
                        expected_source=None, legacy_iterations=False, input_only=False):
    """Bind a packaged observation to the exact analyzed journal and source."""
    journal = resolved(evidence["journal"])
    if not evidence.get("complete") or sha(journal) != evidence.get("journalSha256AtSnapshot"):
        raise ValueError("Journal changed or is incomplete since analysis: " + str(journal))
    metadata = read(journal.parent / "run.json")
    if metadata != evidence.get("run"):
        raise ValueError("Run metadata changed since analysis: " + str(journal))
    if metadata.get("completed") != count or metadata.get("caseCount") != count or metadata.get("mode") != mode:
        raise ValueError("Unexpected completed population/mode: " + str(journal))
    source = available_source(metadata["source"])
    if sha(source) != metadata.get("sourceSha256"):
        raise ValueError("Run source changed after execution: " + str(source))
    source_rows = rows(source)
    source_by_id = {str(row["id"]): row for row in source_rows}
    if len(source_rows) != count or len(source_by_id) != count:
        raise ValueError("Run source has missing or duplicate IDs")
    if expected_source is not None:
        expected = rows(expected_source)
        expected_by_id = {str(row["id"]): row for row in expected}
        if source_by_id.keys() != expected_by_id.keys():
            raise ValueError("Run source differs from its frozen cohort")
        for case_id, value in source_by_id.items():
            authored = expected_by_id[case_id]
            if value.get("split") != authored.get("split") or canonical_input_hash(value["input"]) != canonical_input_hash(authored["input"]):
                raise ValueError("Run source changed a frozen input or fold: " + case_id)
    if input_only and any("seed" in row or "targets" in row or "gen2ReferenceSeed" in row for row in source_rows):
        raise ValueError("Isolated profile loaded teacher states instead of input-only source")
    if expected_model_sha is not None and metadata.get("modelSha256") != expected_model_sha:
        raise ValueError("Run used another candidate model: " + str(journal))
    if mode == "profile-current" and metadata.get("modelSha256") is not None:
        raise ValueError("Classical isolated profile unexpectedly loaded a model")
    if budget is not None:
        actual_iterations = metadata.get("neuralMaximumIterations")
        if actual_iterations is None and legacy_iterations:
            actual_iterations = 16
        expected_budget = (budget["workers"], budget["parentDeadlineMillis"], budget["candidateMillis"], budget["candidateIterations"])
        actual_budget = (metadata.get("workers"), metadata.get("deadlineMillis"), metadata.get("neuralBudgetMillis"), actual_iterations)
        if actual_budget != expected_budget:
            raise ValueError("Run budget differs from frozen protocol: " + str(journal))
    return count


def verify_campaign(card, selection):
    selection_path, design_path = BASE / "selection.json", BASE / "fresh-design/design.json"
    selection_evidence = card.get("selectionFreeze") or {}
    if selection_evidence.get("sha256") != sha(selection_path) or selection_evidence.get("document") != selection:
        raise ValueError("Selection differs from the analyzed frozen selection")
    design = read(design_path)
    if sha(design_path) != selection["freshDesignSha256"] or (card.get("prospectiveDesign") or {}).get("sha256") != sha(design_path):
        raise ValueError("Fresh design differs from selection or analysis")
    if (card.get("prospectiveDesign") or {}).get("document") != design:
        raise ValueError("Analyzed design metadata changed")
    for name, digest in design["fileSha256"].items():
        if sha(BASE / "fresh-design" / name) != digest:
            raise ValueError("Frozen source changed: " + name)
    data_sha = sha(BASE / "data/cases.jsonl")
    if data_sha != selection["trainingDataSha256"] or read(BASE / "data/data.json")["datasetSha256"] != data_sha:
        raise ValueError("Training data changed after selection")
    attachment = read(BASE / "evaluation-inputs/attachment.json")
    if attachment["designSha256"] != sha(design_path):
        raise ValueError("Reference attachment belongs to another design")
    for name, digest in attachment["fileSha256"].items():
        if sha(BASE / "evaluation-inputs" / name) != digest:
            raise ValueError("Attached comparison/reference source changed: " + name)
    expected_labels = {"current-reference", *selection["comparisonCandidateLabels"],
        *("validation:" + label for label in selection["candidates"]),
        *("full-recovery:" + label for label in selection["fullRecoveryLabels"]),
        *("fitted-replay:" + label for label in selection["fullRecoveryLabels"])}
    labels = [run["label"] for run in card["runs"]]
    if len(labels) != len(set(labels)) or set(labels) != expected_labels:
        raise ValueError("Missing, duplicate or unexpected comparison/validation/recovery/replay labels")
    expected_observations = 0
    for run in card["runs"]:
        label = run["label"]
        if label == "current-reference":
            expected_observations += verify_run_evidence(run["evidence"], count=877,
                mode="cached_CURRENT_and_historical_prior_reference", expected_source=BASE / "evaluation-inputs/comparison-source.jsonl")
            continue  # Assembled historical outcomes have no new timing/model budget.
        candidate = label.split(":", 1)[-1]
        entry = selection["candidates"][candidate]
        if label.startswith("validation:"):
            count, source = 405, BASE / "fresh-design/validation-replay.jsonl"
            if run["evidence"]["journalSha256AtSnapshot"] != entry["validationSha256"]:
                raise ValueError("Validation journal differs from candidate selection: " + label)
        elif label.startswith("full-recovery:"):
            count, source = 1934, BASE / "fresh-design/remaining-gen2-failures.jsonl"
        elif label.startswith("fitted-replay:"):
            count, source = 96, BASE / "fresh-design/train-rescue-replay.jsonl"
        else:
            count, source = 877, BASE / "evaluation-inputs/comparison-source.jsonl"
        expected_observations += verify_run_evidence(run["evidence"], count=count, mode="evaluate",
            expected_model_sha=entry["modelSha256"], budget=selection["parallelBudget"], expected_source=source,
            legacy_iterations=label == "validation:gen2")
    profile_labels = [run["label"] for run in card["isolatedProfiles"]]
    if len(profile_labels) != len(set(profile_labels)) or set(profile_labels) != set(selection["serialCandidateLabels"]):
        raise ValueError("Missing, duplicate or unexpected isolated profile labels")
    for run in card["isolatedProfiles"]:
        label = run["label"]
        memory = run["evidence"].get("processMemory") or {}
        memory_path = resolved(run["evidence"]["journal"]).parent / "memory.json"
        if (memory != read(memory_path) or memory.get("sampleCount", 0) < 1
                or not memory.get("monitorFinishedUtc")
                or memory.get("requiredCommandPattern") != "com.wormzjl.createcheme.science.column.v3.V3GeneralTrainingProbe"):
            raise ValueError("Memory evidence lacks a completed solver-JVM match: " + label)
        expected_observations += verify_run_evidence(run["evidence"], count=64,
            mode="profile-current" if label == "current" else "profile-neural",
            expected_model_sha=None if label == "current" else selection["candidates"][label]["modelSha256"],
            budget=selection["serialBudget"], expected_source=BASE / "fresh-design/fresh-benchmark.jsonl", input_only=True)
    benchmark_labels = []
    for run in card["serialBenchmarks"]:
        metadata = run["evidence"]["run"]
        candidates = [label for label in selection["fallbackBenchmarkLabels"]
            if selection["candidates"][label]["modelSha256"] == metadata.get("modelSha256")]
        if len(candidates) != 1:
            raise ValueError("Fallback benchmark has an unselected or ambiguous model")
        benchmark_labels.append(candidates[0])
        expected_observations += 3 * verify_run_evidence(run["evidence"], count=64, mode="benchmark",
            expected_model_sha=selection["candidates"][candidates[0]]["modelSha256"], budget=selection["serialBudget"],
            expected_source=BASE / "evaluation-inputs/fresh-benchmark-source.jsonl")
    if len(benchmark_labels) != len(set(benchmark_labels)) or set(benchmark_labels) != set(selection["fallbackBenchmarkLabels"]):
        raise ValueError("Missing or duplicate selected fallback benchmarks")
    return expected_observations


def archive_record(records, row):
    """Merge membership separately from first-seen design provenance."""
    key = canonical_input_hash(row["input"])
    if key not in records:
        records[key] = {"inputSha256": key, "id": row["id"], "split": row.get("split"),
            "input": copy.deepcopy(row["input"]), "design": copy.deepcopy(row.get("design") or {}),
            "cohorts": [], "origins": [], "observations": {}}
    entry = records[key]
    if str(entry["id"]) != str(row["id"]) or entry["split"] != row.get("split"):
        raise ValueError("Multiple case IDs or folds for one archived input")
    incoming = row.get("design") or {}
    merged = set(entry["cohorts"]) | set(incoming.get("gen3Cohorts", []))
    if "fresh_test" in merged:
        merged.discard("unused_fresh_candidate_pool")
    entry["cohorts"] = sorted(merged)
    origin = incoming.get("gen3Origin")
    if origin and origin not in entry["origins"]:
        entry["origins"].append(origin)
        entry["origins"].sort()
    old_cohorts = set(entry["design"].get("gen3Cohorts", []))
    new_cohorts = set(incoming.get("gen3Cohorts", []))
    if new_cohorts - {"unused_fresh_candidate_pool", "fresh_pool_preflight_excluded"} and (
            not old_cohorts or old_cohorts == {"unused_fresh_candidate_pool"}):
        entry.setdefault("firstDesignProvenance", copy.deepcopy(entry["design"]))
        entry["design"] = copy.deepcopy(incoming)
    entry["design"]["gen3Cohorts"] = list(entry["cohorts"])
    return entry


def add_observation(records, row, name, outcome=None):
    entry = archive_record(records, row)
    if name in entry["observations"]:
        raise ValueError("Duplicate archived observation: " + name)
    value = compact(row if outcome is None else outcome)
    design = row.get("design") or {}
    value["cohorts"] = list(design.get("gen3Cohorts", []))
    for key in ("gen3Origin", "gen3Reference", "evaluationOrigin", "labelProvenance"):
        source = row.get(key) if key == "labelProvenance" else design.get(key)
        if source is not None:
            value[key] = copy.deepcopy(source)
    for key in ("sourceSuccess", "sourceStatus", "referenceOnly"):
        if key in row:
            value[key] = row[key]
    entry["observations"][name] = value
    return entry


def verified_rows(evidence):
    path = resolved(evidence["journal"])
    if sha(path) != evidence["journalSha256AtSnapshot"]:
        raise ValueError("Journal changed before archival: " + str(path))
    values = rows(path)
    if len(values) != evidence["run"]["caseCount"] or len({str(row["id"]) for row in values}) != len(values):
        raise ValueError("Journal lost or duplicated observations before archival")
    if sha(path) != evidence["journalSha256AtSnapshot"]:
        raise ValueError("Journal changed during archival read: " + str(path))
    return values


def main():
    card = read(BASE / "analysis/summary.json")
    selection = read(BASE / "selection.json")
    cache = cache_generation_two()
    if sha(ROOT / ".neural-cache/gen2-1a4a01d/manifest.json") != selection["gen2CacheManifestSha256"]:
        raise ValueError("Gen2 cache manifest changed after selection")
    for entry in cache["files"]:
        original = ROOT / entry["sourcePath"]
        if original.exists() and sha(original) != entry["sha256"]:
            raise ValueError("Original Gen2 artifact changed: " + entry["sourcePath"])
    expected_observations = verify_campaign(card, selection)
    if not card["finalRequested"] or any(not run["evidence"]["complete"] for run in card["runs"]):
        raise ValueError("Only completed comparisons can be packaged")
    if len(card["serialBenchmarks"]) != 2 or len(card["isolatedProfiles"]) != 6:
        raise ValueError("Missing selected-mode benchmarks or same-input candidate profiles")
    if any(not run["evidence"]["complete"] for run in card["serialBenchmarks"] + card["isolatedProfiles"]):
        raise ValueError("Incomplete serial benchmark")
    for label, entry in selection["candidates"].items():
        if sha(ROOT / entry["modelPath"]) != entry["modelSha256"]:
            raise ValueError("Frozen model changed: " + label)
        cohort = card["candidateCohorts"].get(label, {}).get("fresh_test", {})
        if not cohort.get("completePopulation") or cohort.get("observedUniqueInputs") != 252:
            raise ValueError("Missing fresh comparison: " + label)
    for label in selection["fullRecoveryLabels"]:
        cohort = card["candidateCohorts"].get("full-recovery:" + label, {}).get("remaining_gen2_failure", {})
        if not cohort.get("completePopulation") or cohort.get("observedUniqueInputs") != 1934:
            raise ValueError("Missing full recovery: " + label)
    verification = read(BASE / "verification.json")
    if not verification.get("passed"):
        raise ValueError("Final verification has not passed")
    bundled = ROOT / "src/main/resources/data/createcheme/neural/v3-general-gen3-factorized.json"
    if sha(bundled) != selection["candidates"][selection["selectedNeural"]]["modelSha256"]:
        raise ValueError("Bundled Gen3 differs from the validation-selected artifact")
    card["verification"] = verification
    interrupted = BASE / "profile-current-invalid-launcher-monitor/invalid-measurement.json"
    if interrupted.exists():
        card["excludedMeasurementTrials"] = [{"path": str(interrupted.relative_to(ROOT)),
            "sha256": sha(interrupted), "reason": read(interrupted)["reason"], "includedInResults": False}]
    card["training"] = {
        "data": read(BASE / "data/data.json"),
        "gen3-mlp": read(BASE / "mlp-v1/general-training.json"),
        "gen3-factorized": read(BASE / "factorized-v1/factorized-training.json"),
        "nearest-k1": read(BASE / "nearest-k1/model-export.json"),
        "nearest-k3": read(BASE / "nearest-k3/model-export.json"),
    }
    card["gen2CacheVerification"] = {"verifiedFiles": len(cache["files"]), "bytes": cache["totalBytes"],
        "cacheRoot": cache["cacheRoot"], "manifestSha256": sha(ROOT / ".neural-cache/gen2-1a4a01d/manifest.json"),
        "scope": "Original study data, weights, folds, validation/evaluation/serial/profile results and provenance; outside build so Gradle clean preserves this cache."}
    gen3_manifest_path = ROOT / "tools/neural/gen3-cache-manifest.json"
    gen3_cache = read(gen3_manifest_path)
    cache_manifest = ROOT / gen3_cache["cacheRoot"] / "manifest.json"
    archive = ROOT / gen3_cache["archivePath"]
    if (gen3_manifest_path.read_bytes() != cache_manifest.read_bytes()
            or sha(archive) != gen3_cache["archiveSha256"]
            or archive.stat().st_size != gen3_cache["archiveBytes"]
            or gen3_cache["trainingDataSha256"] != selection["trainingDataSha256"]
            or gen3_cache["selectedNeuralModelSha256"] != selection["candidates"][selection["selectedNeural"]]["modelSha256"]):
        raise ValueError("Durable Gen3 cache differs from the frozen campaign")
    card["gen3Cache"] = {"manifestPath": str(gen3_manifest_path.relative_to(ROOT)),
        "manifestSha256": sha(gen3_manifest_path), "archiveSha256Verified": True, "document": gen3_cache}
    card["deployment"] = {"family": "GENERALIZED_GEN3_EXPERIMENTAL", "bundledResource": str(bundled.relative_to(ROOT)),
        "modelSha256": sha(bundled), "defaultFamily": "LOCAL_EXPERTS", "gen2Family": "GENERALIZED_EXPERIMENTAL",
        "transferCandidateBundled": False, "correctorAndAcceptanceChanged": False,
        "trainingHasWetEquilibriumLabels": False,
        "scope": "Optional TJL20 initialization guess for the existing property package and declared geometry/operating bounds. Raw predictions are never published as converged solutions."}

    records = {}

    for row in rows(BASE / "fresh-design/candidate-pool.jsonl"):
        entry = archive_record(records, row)
        entry["freshCandidatePool"] = True
        entry["selectedForFreshTest"] = row["design"].get("selectedForFreshTest", False)
    for row in rows(BASE / "fresh-design/exclusions.jsonl"):
        entry = archive_record(records, row)
        entry["preflightAdmitted"] = False
        entry["exclusions"] = row.get("exclusions")
    for row in rows(BASE / "data/cases.jsonl"):
        entry = archive_record(records, row)
        entry["labelProvenance"] = row["labelProvenance"]
        entry["eligibleTrainingLabel"] = bool(row["labelProvenance"].get("eligibleForFitting"))
    # Preserve frozen membership even when a case was not in a particular run.
    for filename in ("matrix.jsonl", "validation-replay.jsonl", "old-test-replay.jsonl",
                     "recovery-comparison.jsonl", "remaining-gen2-failures.jsonl", "train-rescue-replay.jsonl", "fresh-benchmark.jsonl"):
        for row in rows(BASE / "fresh-design" / filename):
            archive_record(records, row)
    for run in card["runs"]:
        for row in verified_rows(run["evidence"]):
            add_observation(records, row, run["label"])
    for run in card["isolatedProfiles"]:
        for row in verified_rows(run["evidence"]):
            add_observation(records, row, "serial-profile:" + run["label"])
    for run in card["serialBenchmarks"]:
        metadata = run["evidence"]["run"]
        label = next(label for label, item in selection["candidates"].items() if item["modelSha256"] == metadata["modelSha256"])
        for row in verified_rows(run["evidence"]):
            for mode in ("current", "neural", "neuralFirst"):
                if not isinstance(row.get(mode), dict):
                    raise ValueError("Fallback benchmark omitted a required mode: " + mode)
                add_observation(records, row, "serial-fallback:" + label + ":" + mode, row[mode])
    if sum(len(item["observations"]) for item in records.values()) != expected_observations:
        raise ValueError("Archive observation count differs from the complete frozen run plan")
    archive = ROOT / "tools/neural/gen3-case-map.jsonl.gz"
    with archive.open("wb") as destination:
        with gzip.GzipFile(fileobj=destination, filename="", mode="wb", mtime=0) as packed:
            for key in sorted(records):
                packed.write((json.dumps(records[key], sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode())
    with gzip.open(archive, "rt", encoding="utf-8") as packed:
        restored = [json.loads(line) for line in packed]
    if len(restored) != len(records) or {item["inputSha256"] for item in restored} != set(records):
        raise ValueError("Case archive lost inputs")
    if any(canonical_input_hash(item["input"]) != item["inputSha256"] for item in restored):
        raise ValueError("Case archive altered inputs")
    if sum(len(item["observations"]) for item in restored) != expected_observations:
        raise ValueError("Case archive lost observations")
    card["archivedCaseMap"] = {"path": str(archive.relative_to(ROOT)), "sha256": sha(archive),
        "compressedBytes": archive.stat().st_size, "uniqueInputs": len(restored),
        "observations": sum(len(item["observations"]) for item in restored),
        "expectedObservationsFromRunPlan": expected_observations,
        "scope": "Every new candidate input, every original training-design input and all historical retry inputs; frozen cohort memberships and exact listed Gen3 observations with per-run reference provenance. An absent observation means no execution under that listed Gen3 run label, not that the older teacher never ran the input. Full profiles and original teacher outcomes remain in journals/the immutable Gen2 cache, identified by hashes in this card."}
    output = ROOT / "tools/neural/gen3-model-card.json"
    output.write_text(json.dumps(card, indent=2, allow_nan=False) + "\n")
    frozen = ROOT / "tools/neural/gen3-selection.json"
    frozen.write_bytes((BASE / "selection.json").read_bytes())
    print(json.dumps({"modelCard": str(output), "caseArchive": card["archivedCaseMap"], "gen2CacheVerified": len(cache["files"])}))


if __name__ == "__main__":
    main()
