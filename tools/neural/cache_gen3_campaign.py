"""Preserve the completed Gen3 study in a deterministic, verified ZIP outside Gradle clean.

Run only after the final comparisons, memory profiles, benchmarks, analysis, and
verification have completed. Existing cache bytes are verified, never replaced.
Archive entry paths are relative to build/neural-gen3, so extracting study.zip
there restores the study directly (the omitted duplicate MLP journal can be
copied from data/cases.jsonl when needed).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
STUDY = ROOT / "build/neural-gen3"
CACHE = ROOT / ".neural-cache/gen3-19ed5060"
PUBLIC_MANIFEST = ROOT / "tools/neural/gen3-cache-manifest.json"
REVISION = "gen3-compressed-cache-v1"
CHUNK_BYTES = 1024 * 1024
ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
COMPRESSION_LEVEL = 6

# Explicit completed-campaign whitelist. No recursive search of the study root.
DATA_DIRECTORIES = (
    "data", "mlp-v1", "factorized-v1", "nearest-k1", "nearest-k3", "nearest-parity",
    "fresh-design", "fresh-current", "evaluation-inputs",
)
VALIDATION_DIRECTORIES = (
    "validation-mlp", "validation-factorized", "validation-nearest-k1", "validation-nearest-k3",
)
COMPARISON_DIRECTORIES = (
    "comparison-gen2", "comparison-gen3-mlp", "comparison-gen3-factorized", "comparison-nearest-k1", "comparison-nearest-k3",
)
RECOVERY_DIRECTORIES = ("recovery-gen3-factorized", "recovery-nearest-k1")
REPLAY_DIRECTORIES = ("replay-gen3-factorized", "replay-nearest-k1")
PROFILE_DIRECTORIES = (
    "profile-current", "profile-gen2", "profile-gen3-mlp", "profile-gen3-factorized", "profile-nearest-k1", "profile-nearest-k3",
)
BENCHMARK_DIRECTORIES = ("benchmark-gen3-factorized", "benchmark-nearest-k1")
DIRECTORIES = (*DATA_DIRECTORIES, *VALIDATION_DIRECTORIES, *COMPARISON_DIRECTORIES,
               *RECOVERY_DIRECTORIES, *REPLAY_DIRECTORIES, *PROFILE_DIRECTORIES, *BENCHMARK_DIRECTORIES)
ROOT_FILES = ("selection.json", "junit.json", "verification.json", "python-tests.log")
SUMMARY_FILES = ("analysis/summary.json", "analysis/summary.md", "analysis/summary-cases.csv", "analysis/summary-zones.csv")
OMITTED_DUPLICATES = {"mlp-v1/cases.jsonl": "data/cases.jsonl"}
EXCLUDED = (
    "profile-current-invalid-launcher-monitor/", "analysis-core/", "analysis-current-reference/", "analysis-protocol/",
    "analysis/* other than the four final summary files", "tools/neural/gen3-model-card.json",
    "tools/neural/gen3-case-map.jsonl.gz", "tools/neural/gen3-cache-manifest.json",
)


def canonical_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha_stream(stream):
    digest, size = hashlib.sha256(), 0
    while chunk := stream.read(CHUNK_BYTES):
        digest.update(chunk); size += len(chunk)
    return digest.hexdigest(), size


def sha_file(path):
    with path.open("rb") as stream:
        return sha_stream(stream)


def linked(path):
    return path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction())


def require_source(path):
    """Reject links or paths escaping the explicitly named study, including Windows junctions."""
    absolute = path.resolve(strict=True)
    absolute.relative_to(STUDY.resolve(strict=True))
    current = path
    while current != STUDY:
        if linked(current): raise ValueError("Linked campaign source is not archived: " + str(current))
        current = current.parent
    if not path.is_file(): raise ValueError("Campaign source is not a regular file: " + str(path))
    return path


def collect_sources():
    if linked(STUDY) or not STUDY.is_dir(): raise ValueError("Expected a real completed Gen3 study directory")
    collected = {}
    for name in DIRECTORIES:
        directory = STUDY / name
        if linked(directory) or not directory.is_dir(): raise FileNotFoundError("Required campaign directory: " + str(directory))
        before = len(collected)
        for current, directories, files in os.walk(directory, followlinks=False):
            current = Path(current)
            for child in directories:
                if linked(current / child): raise ValueError("Linked campaign directory is not archived: " + str(current / child))
            for child in files:
                path = require_source(current / child)
                relative = path.relative_to(STUDY).as_posix()
                if relative not in OMITTED_DUPLICATES:
                    collected[relative] = path
        if len(collected) == before: raise ValueError("Required campaign directory is empty: " + str(directory))
    for relative in (*ROOT_FILES, *SUMMARY_FILES): collected[relative] = require_source(STUDY / relative)
    if len(collected) > 4096: raise ValueError("Unexpected campaign source count; inspect the whitelist")
    if len({name.casefold() for name in collected}) != len(collected): raise ValueError("Archive names collide on a case-insensitive filesystem")
    return dict(sorted(collected.items()))


def completed_campaign():
    """Cheap completion gates before streaming; the final analysis retains its full audit evidence."""
    selection = read_json(STUDY / "selection.json")
    selected = selection["candidates"][selection["selectedNeural"]]["modelSha256"]
    if not selected.startswith("19ed5060"): raise ValueError("Different selected model requires a new cache revision")
    verification = read_json(STUDY / "verification.json")
    junit = read_json(STUDY / "junit.json")
    if verification.get("passed") is not True or verification.get("junit") != junit:
        raise ValueError("Completed matching verification/JUnit evidence is required")
    if junit.get("failures") != 0 or junit.get("errors") != 0 or junit.get("tests", 0) <= 0:
        raise ValueError("Final JUnit verification did not pass")
    summary = read_json(STUDY / "analysis/summary.json")
    if summary.get("finalRequested") is not True: raise ValueError("Only final analysis can enter the durable cache")
    for field, required_count in (("runs", None), ("serialBenchmarks", 2), ("isolatedProfiles", 6)):
        values = summary.get(field)
        if not isinstance(values, list) or not values or (required_count is not None and len(values) != required_count):
            raise ValueError("Missing final analysis evidence: " + field)
        if any(value.get("evidence", {}).get("complete") is not True for value in values):
            raise ValueError("Incomplete final analysis evidence: " + field)
    modes = {"fresh-current": "generate"}
    modes.update({name: "evaluate" for name in (*VALIDATION_DIRECTORIES, *COMPARISON_DIRECTORIES, *RECOVERY_DIRECTORIES, *REPLAY_DIRECTORIES)})
    modes.update({name: "profile-current" if name == "profile-current" else "profile-neural" for name in PROFILE_DIRECTORIES})
    modes.update({name: "benchmark" for name in BENCHMARK_DIRECTORIES})
    expected_rows = {}
    for directory, mode in modes.items():
        metadata = read_json(STUDY / directory / "run.json")
        count = metadata.get("caseCount")
        if not isinstance(count, int) or isinstance(count, bool) or count <= 0 or metadata.get("completed") != count or metadata.get("mode") != mode:
            raise ValueError("Incomplete or unexpected run metadata: " + directory)
        journal = "cases.jsonl" if mode == "generate" else "evaluation.jsonl"
        expected_rows[directory + "/" + journal] = count
    for directory in PROFILE_DIRECTORIES:
        memory = read_json(STUDY / directory / "memory.json")
        if not memory.get("monitorFinishedUtc"): raise ValueError("Memory monitor has not finished: " + directory)
    return selection, summary, expected_rows


def source_state(path):
    value = path.stat()
    return value.st_size, value.st_mtime_ns, value.st_ino


def stream_entry(archive, entry_name, path):
    initial = source_state(path)
    info = zipfile.ZipInfo(entry_name, date_time=ZIP_TIMESTAMP)
    info.create_system = 3
    info.external_attr = (stat.S_IFREG | 0o644) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    # Python 3.12's streaming ZipInfo API exposes this only as _compresslevel.
    info._compresslevel = COMPRESSION_LEVEL
    info.file_size = initial[0]
    digest, size, newlines = hashlib.sha256(), 0, 0
    last_byte = b""
    with path.open("rb") as source, archive.open(info, "w", force_zip64=True) as target:
        while chunk := source.read(CHUNK_BYTES):
            target.write(chunk); digest.update(chunk); size += len(chunk)
            newlines += chunk.count(b"\n"); last_byte = chunk[-1:]
    if source_state(path) != initial or size != initial[0]: raise ValueError("Campaign source changed while archiving: " + entry_name)
    line_count = newlines + int(bool(last_byte) and last_byte != b"\n")
    return {"archiveEntry": entry_name, "sourcePath": path.relative_to(ROOT).as_posix(), "bytes": size,
            "sha256": digest.hexdigest()}, line_count


def archive_manifest_files(files):
    if not isinstance(files, list) or not files or len(files) > 4096: raise ValueError("Invalid archive manifest file list")
    by_name = {}
    for entry in files:
        name = entry.get("archiveEntry")
        if not isinstance(name, str) or name in ("", ".") or ":" in name or "\\" in name or PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts:
            raise ValueError("Unsafe archive entry name")
        if PurePosixPath(name).as_posix() != name or name in by_name:
            raise ValueError("Noncanonical or repeated archive entry")
        if entry.get("sourcePath") != "build/neural-gen3/" + name:
            raise ValueError("Archive source does not match its direct restoration path")
        if not isinstance(entry.get("bytes"), int) or isinstance(entry["bytes"], bool) or entry["bytes"] < 0:
            raise ValueError("Invalid source byte count")
        if not isinstance(entry.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
            raise ValueError("Invalid source SHA-256")
        by_name[name] = entry
    if len({name.casefold() for name in by_name}) != len(by_name): raise ValueError("Case-insensitive archive name collision")
    return by_name


def verify_archive(path, files, expected_sha=None, expected_bytes=None):
    expected = archive_manifest_files(files)
    digest, size = sha_file(path)
    if expected_sha is not None and digest != expected_sha: raise ValueError("Cached compressed archive hash changed")
    if expected_bytes is not None and size != expected_bytes: raise ValueError("Cached compressed archive size changed")
    with zipfile.ZipFile(path, "r") as archive:
        infos = archive.infolist()
        if [item.filename for item in infos] != sorted(expected): raise ValueError("Archive entries are missing, repeated, reordered, or unexpected")
        for info in infos:
            if info.is_dir() or info.date_time != ZIP_TIMESTAMP or info.compress_type != zipfile.ZIP_DEFLATED:
                raise ValueError("Unexpected deterministic ZIP metadata: " + info.filename)
            entry = expected[info.filename]
            with archive.open(info, "r") as source: entry_sha, entry_size = sha_stream(source)
            if entry_sha != entry["sha256"] or entry_size != entry["bytes"] or info.file_size != entry_size:
                raise ValueError("Archive round-trip failed: " + info.filename)
    return digest, size


def verify_current_sources(files, duplicates):
    expected = archive_manifest_files(files)
    current = collect_sources()
    if list(current) != sorted(expected): raise ValueError("Completed campaign source set changed; use a new cache revision")
    for name, path in current.items():
        digest, size = sha_file(path)
        if digest != expected[name]["sha256"] or size != expected[name]["bytes"]:
            raise ValueError("Completed campaign source changed; cache was not overwritten: " + name)
    for entry in duplicates:
        path = ROOT / entry["sourcePath"]
        require_source(path)
        digest, size = sha_file(path)
        if digest != entry["sha256"] or size != entry["bytes"]: raise ValueError("Omitted duplicate changed: " + str(path))


def verify_analysis_bindings(summary, selection, files):
    sources = {entry["sourcePath"]: entry for entry in files}
    for field in ("runs", "serialBenchmarks", "isolatedProfiles"):
        for item in summary[field]:
            evidence = item["evidence"]
            journal = Path(evidence["journal"].replace("\\", "/"))
            journal = journal if journal.is_absolute() else ROOT / journal
            try: relative = journal.resolve().relative_to(ROOT).as_posix()
            except ValueError: continue
            if relative.startswith("build/neural-gen3/"):
                if relative not in sources or sources[relative]["sha256"] != evidence.get("journalSha256AtSnapshot"):
                    raise ValueError("Final analysis journal changed before caching: " + relative)
    for candidate in selection["candidates"].values():
        relative = candidate["modelPath"].replace("\\", "/")
        if relative.startswith("build/neural-gen3/"):
            if relative not in sources or sources[relative]["sha256"] != candidate["modelSha256"]:
                raise ValueError("Validation-selected candidate changed before caching: " + relative)


def write_exclusive(path, payload):
    with path.open("xb") as stream:
        stream.write(payload); stream.flush(); os.fsync(stream.fileno())


def publish_identical_manifest(payload):
    if PUBLIC_MANIFEST.exists():
        if PUBLIC_MANIFEST.read_bytes() != payload:
            raise ValueError("Existing public Gen3 cache manifest differs; it was not overwritten")
    else:
        write_exclusive(PUBLIC_MANIFEST, payload)


def verify_existing(verify_sources=False, publish_manifest=False):
    manifest_path, archive_path = CACHE / "manifest.json", CACHE / "study.zip"
    if not manifest_path.is_file() or not archive_path.is_file():
        raise FileExistsError("Incomplete Gen3 cache is retained for inspection; do not overwrite it: " + str(CACHE))
    if linked(CACHE) or linked(manifest_path) or linked(archive_path): raise ValueError("Linked cache outputs are not accepted")
    payload = manifest_path.read_bytes()
    manifest = json.loads(payload)
    if (manifest.get("revision") != REVISION or manifest.get("cacheRoot") != ".neural-cache/gen3-19ed5060"
            or manifest.get("restoreRoot") != "build/neural-gen3" or manifest.get("archiveName") != "study.zip"):
        raise ValueError("Unexpected Gen3 cache manifest; existing bytes were not changed")
    digest, size = verify_archive(archive_path, manifest["files"], manifest["archiveSha256"], manifest["archiveBytes"])
    if verify_sources: verify_current_sources(manifest["files"], manifest.get("omittedDuplicateSources", []))
    if publish_manifest: publish_identical_manifest(payload)
    elif PUBLIC_MANIFEST.exists() and PUBLIC_MANIFEST.read_bytes() != payload:
        raise ValueError("Public cache manifest differs from the verified durable manifest")
    return {"verified": True, "created": False, "archive": str(archive_path.relative_to(ROOT)), "archiveSha256": digest,
            "archiveBytes": size, "entries": len(manifest["files"]), "currentSourcesVerified": verify_sources}


def create_cache():
    if linked(CACHE.parent): raise ValueError("Linked cache parent is not accepted")
    if CACHE.exists(): return verify_existing(verify_sources=STUDY.exists(), publish_manifest=True)
    if PUBLIC_MANIFEST.exists(): raise FileExistsError("A public Gen3 manifest already exists without its cache; restore that cache rather than replacing the manifest")
    selection, summary, expected_rows = completed_campaign()
    sources = collect_sources()
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir()  # Exclusive ownership of this new cache revision; another invocation cannot enter it.
    temporary = CACHE / "study.zip.incomplete"
    files = []
    # Failures retain the incomplete new cache for diagnosis, never delete or replace an existing cache.
    with temporary.open("xb") as raw:
        with zipfile.ZipFile(raw, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=COMPRESSION_LEVEL, allowZip64=True) as archive:
            for name, path in sources.items():
                entry, lines = stream_entry(archive, name, path)
                if name in expected_rows and lines != expected_rows[name]:
                    raise ValueError("Completed journal row count differs from run metadata: " + name)
                files.append(entry)
        raw.flush(); os.fsync(raw.fileno())
    by_name = {entry["archiveEntry"]: entry for entry in files}
    duplicates = []
    for omitted, retained in OMITTED_DUPLICATES.items():
        source = STUDY / omitted
        if not source.exists(): continue
        require_source(source)
        digest, size = sha_file(source)
        if digest != by_name[retained]["sha256"] or size != by_name[retained]["bytes"]:
            raise ValueError("Cannot omit a nonidentical MLP training journal")
        duplicates.append({"sourcePath": source.relative_to(ROOT).as_posix(), "restorePath": omitted,
                           "copyFromArchiveEntry": retained, "bytes": size, "sha256": digest})
    verify_analysis_bindings(summary, selection, files)
    archive_sha, archive_size = verify_archive(temporary, files)
    # Catch writes or new files that happened after an earlier entry was streamed.
    verify_current_sources(files, duplicates)
    generator_sha, _ = sha_file(Path(__file__).resolve())
    manifest = {
        "revision": REVISION, "cacheRoot": ".neural-cache/gen3-19ed5060", "archiveName": "study.zip",
        "archivePath": ".neural-cache/gen3-19ed5060/study.zip", "archiveSha256": archive_sha, "archiveBytes": archive_size,
        "restoreRoot": "build/neural-gen3", "entryPathConvention": "POSIX paths relative to build/neural-gen3; extract study.zip directly into that directory",
        "restoreOmittedDuplicates": "Optionally copy data/cases.jsonl to mlp-v1/cases.jsonl after extraction; those duplicate bytes are stored only once",
        "sourceTotalBytes": sum(entry["bytes"] for entry in files), "entryCount": len(files), "files": files,
        "omittedDuplicateSources": duplicates, "whitelistDirectories": list(DIRECTORIES),
        "whitelistRootFiles": list(ROOT_FILES), "whitelistFinalSummaryFiles": list(SUMMARY_FILES), "excluded": list(EXCLUDED),
        "selectedNeuralModelSha256": selection["candidates"][selection["selectedNeural"]]["modelSha256"],
        "trainingDataSha256": selection["trainingDataSha256"], "gen2CacheManifestSha256": selection["gen2CacheManifestSha256"],
        "zipMetadata": {"compression": "ZIP_DEFLATED", "compressionLevel": COMPRESSION_LEVEL,
                        "entryOrder": "lexicographic POSIX path", "timestamp": "1980-01-01T00:00:00",
                        "unixFileMode": "0100644", "zip64": True},
        "verification": "SHA-256 and byte count for every streamed source, complete journal line counts, every decompressed entry round-tripped through SHA-256/CRC, compressed archive SHA-256, then every live source rechecked",
        "retention": "Outside build/ so Gradle clean does not delete the archive. Existing changed or incomplete cache revisions are never overwritten",
        "cycleAvoidance": "Final model card, final case map, and this manifest are outside study.zip; the final card may reference the resulting manifest hash",
        "generator": {"sourcePath": "tools/neural/cache_gen3_campaign.py", "sha256": generator_sha,
                      "pythonVersion": sys.version, "zlibVersion": zlib.ZLIB_VERSION},
    }
    payload = canonical_bytes(manifest)
    archive_path = CACHE / "study.zip"
    if archive_path.exists(): raise FileExistsError("Unexpected existing archive; no cache file was replaced")
    temporary.rename(archive_path)
    write_exclusive(CACHE / "manifest.json", payload)
    publish_identical_manifest(payload)
    return {"verified": True, "created": True, "archive": str(archive_path.relative_to(ROOT)), "archiveSha256": archive_sha,
            "archiveBytes": archive_size, "sourceTotalBytes": manifest["sourceTotalBytes"], "entries": len(files),
            "manifestSha256": hashlib.sha256(payload).hexdigest(), "currentSourcesVerified": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true", help="Read-only verification of the durable archive; works after Gradle clean")
    parser.add_argument("--verify-sources", action="store_true", help="Also require every current study source to match the retained manifest")
    args = parser.parse_args()
    if args.verify_sources and not args.verify_only: parser.error("--verify-sources requires --verify-only")
    result = verify_existing(verify_sources=args.verify_sources) if args.verify_only else create_cache()
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == "__main__": main()
