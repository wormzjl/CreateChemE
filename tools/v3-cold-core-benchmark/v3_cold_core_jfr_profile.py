#!/usr/bin/env python3
"""Run the post-timing V3 cold-call JFR profile panel.

This runner is intentionally separate from the benchmark supervisor.  Its
profile-samples.jsonl is never an input to screen/timing/slowdown metrics.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import queue
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

WORKER = "com.wormzjl.createcheme.science.column.v3.V3ColdCoreBenchmarkWorker"
WARMUPS = ("A150-quarter-off", "A100-quarter-on", "A250-wet-slowdown-off")
CASES = ("A150-quarter-off", "N30-on")
STARTUP_SECONDS = 45
CALL_SECONDS = 60
WATCHDOG_SECONDS = 75


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def hidden_flags() -> int:
    return getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0


class ProfileJournal:
    def __init__(self, output: Path, metadata: dict[str, Any]):
        output.mkdir(parents=True, exist_ok=True)
        self.samples = output / "profile-samples.jsonl"
        self.metadata = output / "profile-run.json"
        self.sequence = 0
        if self.metadata.exists() or self.samples.exists():
            raise RuntimeError(f"profile output already exists: {output}; choose a fresh --profile-run-dir")
        self.write_metadata(metadata)

    def write_metadata(self, data: dict[str, Any]) -> None:
        temporary = self.metadata.with_suffix(".json.tmp")
        with temporary.open("w", encoding="utf-8", newline="\n") as stream:
            json.dump(data, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, self.metadata)

    def append(self, record: dict[str, Any]) -> dict[str, Any]:
        full = {"profileRecordId": str(uuid.uuid4()), "recordedAt": utc_now(),
                "journalSequence": self.sequence, **record}
        self.sequence += 1
        with self.samples.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps(full, sort_keys=True, separators=(",", ":")) + "\n")
            stream.flush(); os.fsync(stream.fileno())
        return full


class ProfileWorker:
    def __init__(self, runner: "Runner", revision: str):
        self.runner, self.revision = runner, revision
        self.worker_id = f"jfr-{revision}-1"
        self.process: subprocess.Popen[str] | None = None
        self.events: queue.Queue[tuple[str, str]] = queue.Queue()
        self.cancelled = False

    def _read(self, source: str, stream: Any) -> None:
        try:
            for line in iter(stream.readline, ""):
                self.events.put((source, line.rstrip("\r\n")))
        finally:
            try: stream.close()
            except OSError: pass

    def start(self) -> None:
        classpath = self.runner.classpath(self.revision)
        command = [self.runner.java, *self.runner.jvm_flags, "-cp", classpath, WORKER,
                   str(self.runner.manifest), self.revision, self.worker_id, "timing"]
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        text=True, encoding="utf-8", bufsize=1,
                                        creationflags=hidden_flags())
        threading.Thread(target=self._read, args=("stdout", self.process.stdout), daemon=True).start()
        threading.Thread(target=self._read, args=("stderr", self.process.stderr), daemon=True).start()
        until = time.monotonic() + STARTUP_SECONDS
        while time.monotonic() < until:
            event = self.next_event(0.2)
            if event and event[0] == "stdout" and isinstance(event[1], dict) and event[1].get("type") == "READY":
                self.runner.journal.append({"kind": "workerReady", "revision": self.revision, "workerId": self.worker_id,
                                            "ready": event[1]})
                return
            if event:
                self.runner.journal.append({"kind": "workerOutput", "revision": self.revision,
                                            "workerId": self.worker_id, "stream": event[0], "line": event[1]})
            if self.process.poll() is not None: break
        self.stop(force=True)
        raise RuntimeError(f"{self.revision} profile worker did not become READY within {STARTUP_SECONDS}s")

    def next_event(self, timeout: float) -> tuple[str, Any] | None:
        try: source, line = self.events.get(timeout=timeout)
        except queue.Empty: return None
        if source != "stdout": return source, line
        try: return source, json.loads(line)
        except json.JSONDecodeError: return source, line

    def call(self, case_id: str, phase: str) -> dict[str, Any]:
        assert self.process and self.process.stdin
        request_id = f"profile-{phase}-{self.revision}-{case_id}"
        dispatched = time.monotonic()
        self.process.stdin.write(json.dumps({"command": "solve", "requestId": request_id, "caseId": case_id,
                                              "phase": phase, "repetition": 1, "deadlineSeconds": CALL_SECONDS}) + "\n")
        self.process.stdin.flush()
        started: float | None = None
        while True:
            event = self.next_event(0.2)
            observed = time.monotonic()
            if self.cancelled:
                return self.record_harness("CANCELLED", case_id, phase, request_id, dispatched, started)
            if event:
                source, value = event
                if source == "stdout" and isinstance(value, dict) and value.get("requestId") == request_id:
                    if value.get("type") == "STARTED":
                        started = observed
                        self.runner.journal.append({"kind": "callStarted", "revision": self.revision,
                                                    "workerId": self.worker_id, "caseId": case_id, "phase": phase,
                                                    "requestId": request_id})
                        continue
                    if value.get("type") == "RESULT":
                        sample = value.get("sample", {})
                        status = sample.get("status", "HARNESS_INVALID_RESULT") if isinstance(sample, dict) else "HARNESS_INVALID_RESULT"
                        if started is not None and observed - started > CALL_SECONDS and status.startswith("SUCCESS"):
                            status = "LATE_SUCCESS"
                        return self.runner.journal.append({"kind": "profileSample", "revision": self.revision,
                                                           "workerId": self.worker_id, "caseId": case_id, "phase": phase,
                                                           "requestId": request_id, "status": status,
                                                           "workerReportedStatus": sample.get("status") if isinstance(sample, dict) else None,
                                                           "queueAndStartMs": None if started is None else round((started-dispatched)*1000, 3),
                                                           "sample": sample})
                self.runner.journal.append({"kind": "workerOutput", "revision": self.revision,
                                            "workerId": self.worker_id, "stream": source, "line": value})
            if started is None and observed - dispatched >= STARTUP_SECONDS:
                self.stop(force=True)
                return self.record_harness("HARNESS_START_TIMEOUT", case_id, phase, request_id, dispatched, started)
            if started is not None and observed - started >= WATCHDOG_SECONDS:
                self.stop(force=True)
                return self.record_harness("WATCHDOG_KILLED", case_id, phase, request_id, dispatched, started)
            if self.process.poll() is not None:
                return self.record_harness("HARNESS_CRASH", case_id, phase, request_id, dispatched, started,
                                           returnCode=self.process.returncode)

    def record_harness(self, status: str, case_id: str, phase: str, request_id: str,
                       dispatched: float, started: float | None, **extra: Any) -> dict[str, Any]:
        return self.runner.journal.append({"kind": "profileHarness", "revision": self.revision, "workerId": self.worker_id,
                                           "caseId": case_id, "phase": phase, "requestId": request_id, "status": status,
                                           "queueAndStartMs": round(((started or time.monotonic())-dispatched)*1000, 3), **extra})

    def stop(self, force: bool = False) -> None:
        process = self.process
        if not process or process.poll() is not None: return
        if not force:
            try:
                assert process.stdin
                process.stdin.write('{"command":"shutdown"}\n'); process.stdin.flush()
                process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                force = True
        if force and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)

    def cancel(self) -> None:
        self.cancelled = True
        self.stop()


class Runner:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.parent = Path(args.benchmark_run_dir).resolve()
        parent_metadata = self.parent / "run.json"
        self.parent_run = json.loads(parent_metadata.read_text(encoding="utf-8"))
        self.manifest = Path(self.parent_run["manifest"]).resolve()
        self.java = args.java or self.parent_run["settings"]["java"]
        self.timing_flags = list(self.parent_run["execution"]["timingWorkerJvmArgs"])
        self.jcmd_executable = Path(args.jcmd).resolve() if args.jcmd else Path(self.java).resolve().with_name("jcmd.exe")
        if not self.jcmd_executable.is_file(): raise RuntimeError(f"jcmd not found: {self.jcmd_executable}")
        if not self.manifest.is_file(): raise RuntimeError(f"frozen manifest not found: {self.manifest}")
        self.profile_output = Path(args.profile_run_dir).resolve()
        self.jfr_repository = self.profile_output / "jfr-repository"
        self.jvm_temp = self.profile_output / "jvm-tmp"
        self.jfr_repository.mkdir(parents=True, exist_ok=True)
        self.jvm_temp.mkdir(parents=True, exist_ok=True)
        self.profile_only_flags = [f"-XX:FlightRecorderOptions=repository={self.jfr_repository.as_posix()}",
                                   f"-Djava.io.tmpdir={self.jvm_temp.as_posix()}"]
        self.jvm_flags = [*self.timing_flags, *self.profile_only_flags]
        provenance = {"parentRunDirectory": str(self.parent), "parentRunMetadataSha256": digest(parent_metadata),
                      "parentProvenance": self.parent_run.get("provenance"), "manifest": str(self.manifest),
                      "manifestSha256": digest(self.manifest), "runnerSha256": digest(Path(__file__)),
                      "java": self.java, "jcmd": str(self.jcmd_executable), "timingJvmArgs": self.timing_flags,
                      "profileOnlyJvmArgs": self.profile_only_flags, "jfrRepository": str(self.jfr_repository),
                      "jvmTempDirectory": str(self.jvm_temp),
                      "profileCases": list(CASES), "warmupCaseIds": list(WARMUPS),
                      "excludedFromBenchmarkMetrics": True}
        self.journal = ProfileJournal(self.profile_output,
                                      {"schemaVersion": 1, "createdAt": utc_now(), "provenance": provenance,
                                       "state": "created"})

    def classpath(self, revision: str) -> str:
        return self.parent_run["settings"][f"{revision}Classpath"]

    def run_jcmd(self, pid: int, arguments: list[str], timeout: float = 30) -> dict[str, Any]:
        completed = subprocess.run([str(self.jcmd_executable), str(pid), *arguments], capture_output=True, text=True,
                                   encoding="utf-8", timeout=timeout, creationflags=hidden_flags())
        return {"arguments": arguments, "returnCode": completed.returncode, "stdout": completed.stdout, "stderr": completed.stderr}

    @staticmethod
    def jcmd_failed(result: dict[str, Any]) -> bool:
        output = (result.get("stdout", "") + "\n" + result.get("stderr", "")).lower()
        return result.get("returnCode") != 0 or "can't create flight recorder" in output

    def profile_call(self, worker: ProfileWorker, case_id: str) -> None:
        assert worker.process
        destination = self.journal.metadata.parent / f"{worker.revision}-{case_id}.jfr"
        recording = f"v3-{worker.revision}-{case_id}".replace("_", "-")
        started = self.run_jcmd(worker.process.pid,
                                ["JFR.start", f"name={recording}", "settings=profile", f"filename={destination}"])
        self.journal.append({"kind": "jfrStart", "revision": worker.revision, "caseId": case_id,
                             "recording": recording, "destination": str(destination), "jcmd": started})
        if self.jcmd_failed(started): raise RuntimeError(f"JFR.start failed for {recording}: {started['stderr'] or started['stdout']}")
        try:
            worker.call(case_id, "profile")
        finally:
            stopped = self.run_jcmd(worker.process.pid, ["JFR.stop", f"name={recording}", f"filename={destination}"])
            self.journal.append({"kind": "jfrStop", "revision": worker.revision, "caseId": case_id,
                                 "recording": recording, "destination": str(destination), "jcmd": stopped,
                                 "fileExistsAfterStop": destination.is_file()})
            if self.jcmd_failed(stopped):
                raise RuntimeError(f"JFR.stop failed for {recording}: {stopped['stderr'] or stopped['stdout']}")
            if not destination.is_file() or destination.stat().st_size == 0:
                raise RuntimeError(f"JFR.stop returned successfully but did not create a non-empty {destination}")

    def run(self) -> None:
        state = json.loads(self.journal.metadata.read_text(encoding="utf-8")); state["state"] = "running"; self.journal.write_metadata(state)
        worker: ProfileWorker | None = None
        try:
            for revision in ("baseline", "candidate"):
                worker = ProfileWorker(self, revision); worker.start()
                for case_id in WARMUPS:
                    worker.call(case_id, "profile_warmup")
                for case_id in CASES:
                    self.profile_call(worker, case_id)
                worker.stop(); worker = None
            state["state"] = "complete"; state["completedAt"] = utc_now(); self.journal.write_metadata(state)
        except KeyboardInterrupt:
            if worker: worker.cancel()
            state["state"] = "interrupted"; state["interruptedAt"] = utc_now(); self.journal.write_metadata(state)
            raise
        except Exception as exc:
            if worker: worker.stop()
            state["state"] = "failed"; state["failedAt"] = utc_now(); state["failure"] = repr(exc); self.journal.write_metadata(state)
            raise


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--benchmark-run-dir", required=True, help="frozen completed benchmark run directory")
    parser.add_argument("--profile-run-dir", required=True, help="new separate JFR output directory")
    parser.add_argument("--java", help="defaults to frozen run metadata")
    parser.add_argument("--jcmd", help="defaults beside --java")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    try:
        Runner(parse_args(argv or sys.argv[1:])).run(); return 0
    except KeyboardInterrupt:
        print("JFR profiling interrupted; owned worker was stopped.", file=sys.stderr); return 130
    except Exception as exc:
        print(f"JFR profiling failed: {exc}", file=sys.stderr); return 2


if __name__ == "__main__":
    raise SystemExit(main())
