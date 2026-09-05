#!/usr/bin/env python3
"""Resumable supervisor for the V3 cold-core JVM benchmark.

This is deliberately stdlib-only.  It owns only JVMs it starts and writes a
JSONL journal; the Java worker owns calculation and result serialization.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import ctypes
import hashlib
import json
import os
import platform
import queue
import random
import statistics
import subprocess
import sys
import threading
import time
import uuid
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

WORKER_CLASS = "com.wormzjl.createcheme.science.column.v3.V3ColdCoreBenchmarkWorker"
RUN_SCHEMA = 1
STARTUP_SECONDS = 45
CALL_DEADLINE_SECONDS = 60
WATCHDOG_SECONDS = 75
SUCCESS = {"SUCCESS_EXACT", "SUCCESS_REDUCED", "SUCCESS_IDENTITY", "SUCCESS_FALLBACK"}
RESOURCE_STATUSES = {"COOPERATIVE_TIMEOUT", "DEADLINE_EXCEEDED", "LATE_SUCCESS", "WATCHDOG_KILLED", "RESOURCE_LIMIT",
                     "HARNESS_CRASH", "HARNESS_START_TIMEOUT"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def json_dump(path: Path, value: Any) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(value, f, indent=2, sort_keys=True)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(temporary, path)


def available_memory_gib() -> float | None:
    """Return currently available physical memory without third-party modules."""
    if os.name == "nt":
        class MEMORYSTATUSEX(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                        ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                        ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                        ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                        ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]
        value = MEMORYSTATUSEX()
        value.dwLength = ctypes.sizeof(value)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(value)):
            return value.ullAvailPhys / (1024 ** 3)
        return None
    try:
        pages = os.sysconf("SC_AVPHYS_PAGES")
        page_size = os.sysconf("SC_PAGE_SIZE")
        return pages * page_size / (1024 ** 3)
    except (AttributeError, OSError, ValueError):
        return None


def screen_worker_count(requested: int, execution: dict[str, Any]) -> tuple[int, float | None]:
    available = available_memory_gib()
    if available is None:
        return min(12, requested), None
    reserve = float(execution.get("availableMemoryReserveGiB", 6))
    budget = float(execution.get("estimatedMemoryBudgetGiBPerScreenWorker", 2))
    allowed = max(1, int((available - reserve) // budget))
    allowed = min(12, requested, allowed)
    if allowed >= 2:
        allowed -= allowed % 2
    return max(1, allowed), available


class Journal:
    def __init__(self, run_dir: Path, manifest: Path, args: argparse.Namespace, data: dict[str, Any]):
        self.run_dir, self.path = run_dir, run_dir / "samples.jsonl"
        self.lock = threading.Lock()
        self.records: list[dict[str, Any]] = []
        self.run_path = run_dir / "run.json"
        run_dir.mkdir(parents=True, exist_ok=True)
        digest = sha256(manifest)
        settings = {"java": args.java, "baselineClasspath": args.baseline_classpath,
                    "candidateClasspath": args.candidate_classpath, "workersRequested": args.workers,
                    "manifestSha256": digest}
        if self.run_path.exists():
            self.run = json.loads(self.run_path.read_text(encoding="utf-8"))
            expected = self.run.get("settings", {})
            for key in ("manifestSha256", "java", "baselineClasspath", "candidateClasspath"):
                if expected.get(key) != settings[key]:
                    raise ValueError(f"run metadata differs for {key}; use a new --run-dir")
        else:
            self.run = {"schemaVersion": RUN_SCHEMA, "createdAt": now(), "manifest": str(manifest.resolve()),
                        "settings": settings, "execution": data["execution"], "phases": {},
                        "host": {"platform": platform.platform(), "python": sys.version},
                        "notes": ["samples.jsonl is append-only; run.json is mutable metadata."]}
            json_dump(self.run_path, self.run)
        if self.path.exists():
            with self.path.open(encoding="utf-8") as f:
                for number, line in enumerate(f, 1):
                    if line.strip():
                        try:
                            self.records.append(json.loads(line))
                        except json.JSONDecodeError as exc:
                            raise ValueError(f"invalid JSONL at {self.path}:{number}") from exc
        self.sequence = len(self.records)

    def update_run(self, **changes: Any) -> None:
        self.run.update(changes)
        json_dump(self.run_path, self.run)

    def phase(self, name: str, state: str, **extra: Any) -> None:
        self.run.setdefault("phases", {}).setdefault(name, {}).update({"state": state, "updatedAt": now(), **extra})
        json_dump(self.run_path, self.run)

    def append(self, record: dict[str, Any]) -> dict[str, Any]:
        with self.lock:
            record = {"sampleId": str(uuid.uuid4()), "observedAt": now(), "journalSequence": self.sequence, **record}
            self.sequence += 1
            with self.path.open("a", encoding="utf-8", newline="\n") as f:
                f.write(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
                f.flush()
                os.fsync(f.fileno())
            self.records.append(record)
        return record

    def completed(self, phase: str, revision: str, case_id: str, repetition: int) -> bool:
        return any(r.get("kind") in {"sample", "harness"} and r.get("phase") == phase
                   and r.get("revision") == revision and r.get("caseId") == case_id
                   and r.get("repetition") == repetition and r.get("status") not in {"NOT_RUN", "CANCELLED"}
                   for r in self.records)

    def has_terminal(self, phase: str, revision: str, case_id: str, repetition: int) -> bool:
        return any(r.get("kind") in {"sample", "harness"} and r.get("phase") == phase
                   and r.get("revision") == revision and r.get("caseId") == case_id
                   and r.get("repetition") == repetition for r in self.records)

    def latest(self, phase: str) -> dict[tuple[str, str, int], dict[str, Any]]:
        latest: dict[tuple[str, str, int], dict[str, Any]] = {}
        for record in self.records:
            if record.get("kind") in {"sample", "harness"} and record.get("phase") == phase:
                latest[(record.get("revision"), record.get("caseId"), record.get("repetition", 1))] = record
        return latest


class Worker:
    def __init__(self, supervisor: "Supervisor", revision: str, worker_id: str, mode: str):
        self.s, self.revision, self.worker_id, self.mode = supervisor, revision, worker_id, mode
        self.process: subprocess.Popen[str] | None = None
        self.events: queue.Queue[tuple[str, str]] = queue.Queue()
        self.ready: dict[str, Any] | None = None
        self.closed = False
        self.starts = 0
        self.cancelling = False

    def _reader(self, source: str, stream: Any) -> None:
        try:
            for line in iter(stream.readline, ""):
                self.events.put((source, line.rstrip("\r\n")))
        finally:
            try:
                stream.close()
            except OSError:
                pass

    def start(self) -> None:
        if self.process and self.process.poll() is None:
            return
        if self.starts >= self.s.max_restarts + 1:
            raise RuntimeError(f"worker {self.worker_id} exhausted {self.s.max_restarts} bounded restarts")
        self.starts += 1
        self.closed = False
        self.cancelling = False
        cp = self.s.classpath(self.revision)
        flags = self.s.execution["screenWorkerJvmArgs"] if self.mode == "screen" else self.s.execution["timingWorkerJvmArgs"]
        command = [self.s.args.java, *flags, "-cp", cp, WORKER_CLASS, str(self.s.manifest),
                   self.revision, self.worker_id, self.mode]
        startupinfo = None
        creationflags = 0
        if os.name == "nt":
            creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        text=True, encoding="utf-8", bufsize=1, creationflags=creationflags,
                                        startupinfo=startupinfo)
        threading.Thread(target=self._reader, args=("stdout", self.process.stdout), daemon=True).start()
        threading.Thread(target=self._reader, args=("stderr", self.process.stderr), daemon=True).start()
        deadline = time.monotonic() + STARTUP_SECONDS
        while time.monotonic() < deadline:
            event = self._next_event(0.25)
            if event is None:
                if self.process.poll() is not None:
                    break
                continue
            source, payload = event
            if source == "stdout" and payload.get("type") == "READY":
                self.ready = payload
                self.s.record_worker_ready(self, payload)
                return
            self.s.journal.append({"kind": "workerOutput", "revision": self.revision, "workerId": self.worker_id,
                                   "workerMode": self.mode, "stream": source, "line": payload if isinstance(payload, str) else payload})
        self.stop(force=True)
        raise RuntimeError(f"worker {self.worker_id} did not become READY within {STARTUP_SECONDS}s")

    def _next_event(self, timeout: float) -> tuple[str, Any] | None:
        try:
            source, line = self.events.get(timeout=timeout)
        except queue.Empty:
            return None
        if source != "stdout":
            return source, line
        try:
            return source, json.loads(line)
        except json.JSONDecodeError:
            return source, line

    def solve(self, case_id: str, phase: str, repetition: int, confirmation_of: list[str] | None = None) -> dict[str, Any]:
        # Stable across resume.  Worker identity is already part of the process envelope.
        request_id = f"{phase}-{self.revision}-{case_id}-{repetition}"
        dispatched = time.monotonic()
        if self.cancelling:
            return self.s.record_harness(self, "CANCELLED", phase, case_id, repetition, request_id,
                                         dispatched, None, confirmation_of)
        self.start()
        assert self.process and self.process.stdin
        command = {"command": "solve", "requestId": request_id, "caseId": case_id, "phase": phase,
                   "repetition": repetition, "deadlineSeconds": CALL_DEADLINE_SECONDS}
        self.process.stdin.write(json.dumps(command, separators=(",", ":")) + "\n")
        self.process.stdin.flush()
        started: float | None = None
        while True:
            event = self._next_event(0.20)
            elapsed = time.monotonic()
            if self.cancelling:
                return self.s.record_harness(self, "CANCELLED", phase, case_id, repetition, request_id,
                                             dispatched, started, confirmation_of)
            if event:
                source, payload = event
                if source == "stdout" and isinstance(payload, dict) and payload.get("requestId") == request_id:
                    if payload.get("type") == "STARTED":
                        started = elapsed
                        self.s.journal.append({"kind": "callStarted", "revision": self.revision, "workerId": self.worker_id,
                                               "workerMode": self.mode, "phase": phase, "caseId": case_id,
                                               "repetition": repetition, "requestId": request_id})
                        continue
                    if payload.get("type") == "RESULT":
                        return self.s.record_result(self, payload.get("sample", {}), request_id, phase, case_id,
                                                    repetition, dispatched, started, confirmation_of)
                self.s.journal.append({"kind": "workerOutput", "revision": self.revision, "workerId": self.worker_id,
                                       "workerMode": self.mode, "stream": source, "line": payload})
            if started is None and elapsed - dispatched >= STARTUP_SECONDS:
                self.stop(force=True)
                return self.s.record_harness(self, "HARNESS_START_TIMEOUT", phase, case_id, repetition, request_id,
                                             dispatched, started, confirmation_of)
            if started is not None and elapsed - started >= WATCHDOG_SECONDS:
                self.stop(force=True)
                return self.s.record_harness(self, "WATCHDOG_KILLED", phase, case_id, repetition, request_id,
                                             dispatched, started, confirmation_of)
            if self.process.poll() is not None:
                return self.s.record_harness(self, "HARNESS_CRASH", phase, case_id, repetition, request_id,
                                             dispatched, started, confirmation_of, returnCode=self.process.returncode)

    def stop(self, force: bool = False) -> None:
        if self.closed:
            return
        process = self.process
        if not process:
            self.closed = True
            return
        if process.poll() is None and not force:
            try:
                assert process.stdin
                process.stdin.write('{"command":"shutdown"}\n')
                process.stdin.flush()
                process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                force = True
        if process.poll() is None and force:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        self.closed = True

    def request_cancel(self) -> None:
        """Tell the solve loop that supervisor termination is intentional."""
        self.cancelling = True


class Supervisor:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.manifest = Path(args.manifest).resolve()
        self.data = json.loads(self.manifest.read_text(encoding="utf-8"))
        self.execution: dict[str, Any] = self.data["execution"]
        self.cases = {case["id"]: case for case in self.data["cases"]}
        self.default_cases = list(self.data["suites"]["default"])
        self.journal = Journal(Path(args.run_dir).resolve(), self.manifest, args, self.data)
        self.restarts: defaultdict[tuple[str, str], int] = defaultdict(int)
        self.max_restarts = 2

    def classpath(self, revision: str) -> str:
        return self.args.baseline_classpath if revision == "baseline" else self.args.candidate_classpath

    def record_worker_ready(self, worker: Worker, payload: dict[str, Any]) -> None:
        self.journal.append({"kind": "workerReady", "revision": worker.revision, "workerId": worker.worker_id,
                             "workerMode": worker.mode, "ready": payload})

    def record_harness(self, worker: Worker, status: str, phase: str, case_id: str, repetition: int,
                       request_id: str, dispatched: float, started: float | None, confirmation_of: list[str] | None,
                       **extra: Any) -> dict[str, Any]:
        return self.journal.append({"kind": "harness", "revision": worker.revision, "workerId": worker.worker_id,
                                    "workerMode": worker.mode, "parallel": worker.mode == "screen" and phase == "screen",
                                    "phase": phase, "caseId": case_id, "panel": self.cases[case_id].get("panel"),
                                    "repetition": repetition, "requestId": request_id, "status": status,
                                    "queueAndStartMs": round((started or time.monotonic()) * 1000 - dispatched * 1000, 3),
                                    "confirmationOf": confirmation_of, "logicalKey": f"{phase}/{worker.revision}/{case_id}/{repetition}",
                                    **extra})

    def record_result(self, worker: Worker, sample: dict[str, Any], request_id: str, phase: str, case_id: str,
                      repetition: int, dispatched: float, started: float | None,
                      confirmation_of: list[str] | None) -> dict[str, Any]:
        if not isinstance(sample, dict):
            sample = {"status": "HARNESS_INVALID_RESULT", "rawSample": sample}
        status = sample.get("status", "HARNESS_INVALID_RESULT")
        if started is not None and time.monotonic() - started > CALL_DEADLINE_SECONDS and status in SUCCESS:
            status = "LATE_SUCCESS"
        canonical = {"kind": "sample", "revision": worker.revision, "workerId": worker.worker_id,
                     "workerMode": worker.mode, "parallel": worker.mode == "screen" and phase == "screen",
                     "phase": phase, "caseId": case_id, "panel": self.cases[case_id].get("panel"),
                     "repetition": repetition, "requestId": request_id, "status": status,
                     "workerReportedStatus": sample.get("status"), "queueAndStartMs": None if started is None else round((started-dispatched)*1000, 3),
                     "confirmationOf": confirmation_of,
                     "logicalKey": f"{phase}/{worker.revision}/{case_id}/{repetition}"}
        # Keep all domain fields without allowing a worker to overwrite provenance.
        for key, value in sample.items():
            if key not in canonical:
                canonical[key] = value
        return self.journal.append(canonical)

    def warm(self, worker: Worker) -> None:
        for index, case_id in enumerate(self.execution["warmupCaseIds"], 1):
            worker.solve(case_id, "warmup", index)

    def run_serial(self, tasks: Iterable[tuple[str, str, int, list[str] | None]], mode: str,
                   phase: str | None = None) -> list[dict[str, Any]]:
        workers: dict[str, Worker] = {}
        output: list[dict[str, Any]] = []
        # Pilot matches the constrained screen population.  Confirmations and
        # elapsed-time work are deliberately isolated with the 2 GiB JVMs.
        worker_mode = "timing" if mode in {"timing", "confirm"} else "screen"
        try:
            for revision, case_id, repetition, confirmation_of in tasks:
                worker = workers.get(revision)
                if worker is None or worker.closed:
                    worker = Worker(self, revision, f"{mode}-{revision}-{len(workers)+1}", worker_mode)
                    worker.start(); self.warm(worker); workers[revision] = worker
                output.append(worker.solve(case_id, phase or mode,
                                           repetition, confirmation_of))
        except KeyboardInterrupt:
            self.cancel_workers(workers.values())
            raise
        finally:
            for worker in workers.values():
                worker.stop()
        return output

    @staticmethod
    def cancel_workers(workers: Iterable[Worker]) -> None:
        """Stop only owned children, concurrently, after a bounded cooperative grace."""
        owned = list(workers)
        for worker in owned:
            worker.request_cancel()
        if not owned:
            return
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(owned)) as canceller:
            futures = [canceller.submit(worker.stop) for worker in owned]
            for future in futures:
                future.result()

    def pilot(self) -> None:
        self.journal.phase("pilot", "running")
        tasks = [(revision, case_id, 1, None) for case_id in ("N64-off", "N64-on")
                 for revision in ("baseline", "candidate")
                 if not self.journal.completed("pilot", revision, case_id, 1)]
        try:
            self.run_serial(tasks, "pilot")
        except KeyboardInterrupt:
            self.mark_not_run("pilot", tasks)
            self.close_not_run("pilot")
            raise
        pilot_latest = self.journal.latest("pilot")
        if any(record.get("status") == "RESOURCE_LIMIT" for record in pilot_latest.values()):
            self.journal.phase("pilot", "blocked", reason="N64 pilot reported RESOURCE_LIMIT; do not start high-concurrency screen")
            raise RuntimeError("N64 pilot reported RESOURCE_LIMIT; reduce memory/concurrency before screen")
        self.journal.phase("pilot", "complete", cases=["N64-off", "N64-on"])

    def _screen_tasks(self) -> list[tuple[str, str, int, None]]:
        tasks = [(revision, case_id, 1, None) for case_id in self.default_cases for revision in ("baseline", "candidate")
                 if not self.journal.completed("screen", revision, case_id, 1)]
        random.Random(int(self.execution["seed"])).shuffle(tasks)
        return tasks

    def screen(self) -> None:
        # A screen is never allowed to bypass the N64 memory pilot.
        if not self.journal.run.get("phases", {}).get("pilot", {}).get("state") == "complete":
            self.pilot()
        tasks = self._screen_tasks()
        self.journal.phase("screen", "running", pending=len(tasks))
        if not tasks:
            self.journal.phase("screen", "complete", pending=0)
            return
        planned = tasks[:]
        count, available = screen_worker_count(self.args.workers, self.execution)
        self.journal.update_run(screenSizing={"actualWorkers": count, "availableMemoryGiB": available})
        try:
            if count < 2:
                # This is intentionally a single active solve.  A worker is re-created on revision change.
                self._screen_serial(tasks)
            else:
                self._screen_parallel(tasks, count)
        except KeyboardInterrupt:
            self.mark_not_run("screen", planned)
            self.close_not_run("screen")
            raise
        self.journal.phase("screen", "complete", pending=0)

    def _screen_serial(self, tasks: list[tuple[str, str, int, None]]) -> None:
        current: Worker | None = None
        try:
            for revision, case_id, repetition, _ in tasks:
                if current is None or current.revision != revision or current.closed:
                    if current: current.stop()
                    current = Worker(self, revision, f"screen-serial-{revision}", "screen")
                    current.start(); self.warm(current)
                current.solve(case_id, "screen", repetition)
        except KeyboardInterrupt:
            if current:
                self.cancel_workers([current])
            raise
        finally:
            if current: current.stop()

    def _screen_parallel(self, tasks: list[tuple[str, str, int, None]], count: int) -> None:
        workers = [Worker(self, revision, f"screen-{revision}-{number+1}", "screen")
                   for revision in ("baseline", "candidate") for number in range(count // 2)]
        try:
            # Startup/warmup are bounded by the same worker cap.  Doing this in
            # one serial loop would unnecessarily add 3 * worker-count cold calls
            # before the measured screen begins.
            warm_pool = concurrent.futures.ThreadPoolExecutor(max_workers=len(workers))
            warm_futures = [warm_pool.submit(self._start_and_warm, worker) for worker in workers]
            try:
                for future in warm_futures:
                    future.result()
            except KeyboardInterrupt:
                # Do this before executor shutdown: __exit__/shutdown(wait=True)
                # otherwise waits for up-to-75-second solve futures.
                self.cancel_workers(workers)
                warm_pool.shutdown(wait=True, cancel_futures=True)
                raise
            else:
                warm_pool.shutdown(wait=True)
            idle = workers[:]
            pending = tasks[:]
            active: dict[concurrent.futures.Future[dict[str, Any]], tuple[Worker, tuple[str, str, int, None]]] = {}
            pool = concurrent.futures.ThreadPoolExecutor(max_workers=len(workers))
            try:
                while pending or active:
                    for worker in idle[:]:
                        position = next((i for i, task in enumerate(pending) if task[0] == worker.revision), None)
                        if position is None: continue
                        # A watchdog/crash/resource worker is never reused as-is.  Its
                        # bounded replacement is warmed before receiving another measurement.
                        if worker.closed:
                            worker.start()
                            self.warm(worker)
                        revision, case_id, repetition, _ = pending.pop(position)
                        idle.remove(worker)
                        self.journal.append({"kind": "dispatch", "revision": revision, "workerId": worker.worker_id,
                                             "workerMode": "screen", "phase": "screen", "caseId": case_id,
                                             "repetition": repetition, "dispatchOrder": len(self.journal.records)})
                        task = (revision, case_id, repetition, None)
                        active[pool.submit(worker.solve, case_id, "screen", repetition)] = (worker, task)
                    done, _ = concurrent.futures.wait(active, return_when=concurrent.futures.FIRST_COMPLETED)
                    for future in done:
                        worker, task = active.pop(future)
                        try:
                            result = future.result()
                            if result.get("status") == "RESOURCE_LIMIT":
                                worker.stop()
                        except Exception as exc:
                            self.journal.append({"kind": "harness", "revision": worker.revision, "workerId": worker.worker_id,
                                                 "workerMode": "screen", "phase": "screen", "caseId": task[1],
                                                 "panel": self.cases[task[1]].get("panel"), "repetition": task[2],
                                                 "status": "HARNESS_EXCEPTION", "error": repr(exc),
                                                 "logicalKey": f"screen/{worker.revision}/{task[1]}/{task[2]}"})
                            worker.stop(force=True)
                        idle.append(worker)
            except KeyboardInterrupt:
                # Active calls see CANCELLED rather than HARNESS_CRASH.  Queued
                # jobs are cancelled and outer screen() journals them as NOT_RUN.
                self.cancel_workers(workers)
                pool.shutdown(wait=True, cancel_futures=True)
                raise
            else:
                pool.shutdown(wait=True)
        finally:
            for worker in workers: worker.stop()

    def _start_and_warm(self, worker: Worker) -> None:
        worker.start()
        self.warm(worker)

    @staticmethod
    def _stream_map(record: dict[str, Any]) -> dict[str, dict[str, Any]] | None:
        streams = record.get("streams")
        if not isinstance(streams, list):
            return None
        mapped: dict[str, dict[str, Any]] = {}
        for index, stream in enumerate(streams):
            if not isinstance(stream, dict):
                return None
            identity = stream.get("streamId", stream.get("id", stream.get("stableId", str(index))))
            mapped[str(identity)] = stream
        return mapped

    def output_changed(self, baseline: dict[str, Any], candidate: dict[str, Any], case_id: str) -> bool:
        """Implement the predeclared product identity/flow/temperature review triggers.

        The worker supplies product component flows, so this deliberately avoids comparing
        harmless differences in formatted strings or uninstrumented diagnostics.
        """
        left, right = self._stream_map(baseline), self._stream_map(candidate)
        if left is None or right is None:
            return False
        if set(left) != set(right):
            return True
        flow_limit = float(self.cases[case_id]["input"]["feedMolarFlowMolPerSecond"]) * 1e-6
        for stream_id in left:
            a, b = left[stream_id], right[stream_id]
            if a.get("phase") != b.get("phase"):
                return True
            total_a, total_b = a.get("molarFlowMolPerSecond"), b.get("molarFlowMolPerSecond")
            if isinstance(total_a, (int, float)) and isinstance(total_b, (int, float)) and abs(total_a-total_b) > flow_limit:
                return True
            for temperature_key in ("temperatureKelvin", "temperatureK"):
                av, bv = a.get(temperature_key), b.get(temperature_key)
                if isinstance(av, (int, float)) and isinstance(bv, (int, float)) and abs(av - bv) > 0.01:
                    return True
            # Canonical worker field plus tolerant support for an object/map representation.
            for flow_key in ("componentMolarFlowsMolPerSecond", "componentFlowsMolPerSecond"):
                av, bv = a.get(flow_key), b.get(flow_key)
                if isinstance(av, dict) and isinstance(bv, dict):
                    if set(av) != set(bv): return True
                    if any(isinstance(av[k], (int, float)) and isinstance(bv[k], (int, float))
                           and abs(av[k] - bv[k]) > flow_limit for k in av): return True
                elif isinstance(av, list) and isinstance(bv, list):
                    amap = {str(x.get("componentId", x.get("id", i))): x for i, x in enumerate(av) if isinstance(x, dict)}
                    bmap = {str(x.get("componentId", x.get("id", i))): x for i, x in enumerate(bv) if isinstance(x, dict)}
                    if set(amap) != set(bmap): return True
                    for key in amap:
                        x, y = amap[key].get("molarFlowMolPerSecond"), bmap[key].get("molarFlowMolPerSecond")
                        if isinstance(x, (int, float)) and isinstance(y, (int, float)) and abs(x-y) > flow_limit:
                            return True
        return False

    def outcome_changed(self, baseline: dict[str, Any], candidate: dict[str, Any], case_id: str) -> bool:
        fields = ("status", "apiOutcome", "apiFailureCode", "terminalSupport")
        return (any(baseline.get(field) != candidate.get(field) for field in fields)
                or self.output_changed(baseline, candidate, case_id))

    def confirmation_requirement(self, case_id: str, baseline: dict[str, Any], candidate: dict[str, Any]) -> int:
        if self.outcome_changed(baseline, candidate, case_id):
            return 3
        if baseline.get("status") in RESOURCE_STATUSES or candidate.get("status") in RESOURCE_STATUSES:
            # A matching parallel resource outcome initially needs one serial
            # pair.  If that pair exposes a real A/B outcome, support, or output
            # difference, retain it and extend the same case to three pairs.
            confirmations = self.journal.latest("confirm")
            first_baseline = confirmations.get(("baseline", case_id, 0))
            first_candidate = confirmations.get(("candidate", case_id, 0))
            if (first_baseline is not None and first_candidate is not None
                    and first_baseline.get("status") != "NOT_RUN"
                    and first_candidate.get("status") != "NOT_RUN"
                    and self.outcome_changed(first_baseline, first_candidate, case_id)):
                return 3
            return 1
        return 0

    def confirmed_outcomes(self) -> dict[str, dict[str, list[dict[str, Any]]]]:
        """Return the evidence that timing may use, refusing partial confirmation."""
        screen = self.journal.latest("screen")
        confirmations = self.journal.latest("confirm")
        evidence: dict[str, dict[str, list[dict[str, Any]]]] = {}
        incomplete: list[str] = []
        for case_id in self.default_cases:
            base = screen.get(("baseline", case_id, 1)); candidate = screen.get(("candidate", case_id, 1))
            if not base or not candidate:
                incomplete.append(case_id)
                continue
            pairs = self.confirmation_requirement(case_id, base, candidate)
            if not pairs:
                evidence[case_id] = {"baseline": [base], "candidate": [candidate]}
                continue
            rows = {revision: [confirmations.get((revision, case_id, repetition)) for repetition in range(pairs)]
                    for revision in ("baseline", "candidate")}
            if any(any(row is None or row.get("status") in {"NOT_RUN", "CANCELLED"} for row in rows[revision])
                   for revision in rows):
                incomplete.append(case_id)
            else:
                evidence[case_id] = rows  # type: ignore[assignment]
        if incomplete:
            raise RuntimeError("required serial confirmation is incomplete for " + ", ".join(incomplete))
        return evidence

    def confirmations(self) -> None:
        while True:
            latest = self.journal.latest("screen")
            tasks: list[tuple[str, str, int, list[str] | None]] = []
            for case_id in self.default_cases:
                base = latest.get(("baseline", case_id, 1)); candidate = latest.get(("candidate", case_id, 1))
                if not base or not candidate: continue
                pairs = self.confirmation_requirement(case_id, base, candidate)
                if not pairs: continue
                originals = [base["sampleId"], candidate["sampleId"]]
                for repetition in range(pairs):
                    order = ("baseline", "candidate") if repetition % 2 == 0 else ("candidate", "baseline")
                    for revision in order:
                        if not self.journal.completed("confirm", revision, case_id, repetition):
                            tasks.append((revision, case_id, repetition, originals))
            self.journal.phase("confirm", "running", pending=len(tasks))
            if not tasks:
                self.journal.phase("confirm", "complete", pending=0)
                return
            try:
                self.run_serial(tasks, "confirm")
            except KeyboardInterrupt:
                self.mark_not_run("confirm", tasks)
                self.close_not_run("confirm")
                raise

    def timing_selection(self) -> list[str]:
        existing = self.journal.run.get("timingSelection")
        if existing is not None:
            # A frozen case list does not waive a confirmation that was left
            # incomplete by interruption between phases.
            self.confirmed_outcomes()
            return existing
        latest = self.journal.latest("screen")
        missing = [case_id for case_id in self.default_cases
                   if ("baseline", case_id, 1) not in latest or ("candidate", case_id, 1) not in latest]
        if missing:
            raise RuntimeError("screen is incomplete; timing refuses to silently skip " + ", ".join(missing))
        evidence = self.confirmed_outcomes()
        shared = {case_id for case_id in self.default_cases
                  if all(row.get("status") in SUCCESS for row in evidence[case_id]["baseline"])
                  and all(row.get("status") in SUCCESS for row in evidence[case_id]["candidate"])}
        preferred = self.execution["timingPriorityCaseIds"]
        selected = [case_id for case_id in preferred if case_id in shared]
        selected += [case_id for case_id in self.default_cases if case_id in shared and case_id not in selected]
        selected = selected[:int(self.execution["maxPrimaryTimingCases"])]
        self.journal.update_run(timingSelection=selected)
        return selected

    def timing(self) -> None:
        selected = self.timing_selection()
        self.journal.phase("timing", "running", selected=selected)
        tasks: list[tuple[str, str, int, list[str] | None]] = []
        seed = int(self.execution["seed"])
        for repetition in range(1, int(self.execution["timingPairsPerCase"]) + 1):
            cases = selected[:]; random.Random(seed + repetition).shuffle(cases)
            for position, case_id in enumerate(cases):
                case_index = selected.index(case_id)
                starts_baseline = (case_index % 2 == 0) == (repetition % 2 == 1)
                order = ("baseline", "candidate") if starts_baseline else ("candidate", "baseline")
                for revision in order:
                    if not self.journal.completed("timing", revision, case_id, repetition):
                        tasks.append((revision, case_id, repetition, None))
        try:
            self.run_serial(tasks, "timing")
        except KeyboardInterrupt:
            self.mark_not_run("timing", tasks)
            self.close_not_run("timing")
            raise
        self.journal.phase("timing", "complete", pending=0, selected=selected)

    def slowdown(self) -> None:
        selected = self.timing_selection()
        values: dict[tuple[str, str], list[float]] = defaultdict(list)
        for record in self.journal.records:
            if record.get("kind") == "sample" and record.get("phase") == "timing" and record.get("status") in SUCCESS:
                elapsed = record.get("elapsedMs")
                if isinstance(elapsed, (int, float)): values[(record["revision"], record["caseId"])].append(float(elapsed))
        slow = [case_id for case_id in selected
                if values[("baseline", case_id)] and values[("candidate", case_id)]
                and statistics.median(values[("baseline", case_id)]) > 0
                and statistics.median(values[("candidate", case_id)]) / statistics.median(values[("baseline", case_id)]) >= 1.10]
        self.journal.phase("slowdown", "running", selected=slow)
        tasks: list[tuple[str, str, int, list[str] | None]] = []
        for repetition in range(4, 9):
            for position, case_id in enumerate(slow):
                case_index = selected.index(case_id)
                starts_baseline = (case_index % 2 == 0) == (repetition % 2 == 1)
                order = ("baseline", "candidate") if starts_baseline else ("candidate", "baseline")
                for revision in order:
                    if not self.journal.completed("slowdown", revision, case_id, repetition):
                        tasks.append((revision, case_id, repetition, None))
        try:
            self.run_serial(tasks, "timing", "slowdown")
        except KeyboardInterrupt:
            self.mark_not_run("slowdown", tasks)
            self.close_not_run("slowdown")
            raise
        self.journal.phase("slowdown", "complete", pending=0, selected=slow)

    def close_not_run(self, phase: str) -> None:
        self.journal.phase(phase, "interrupted")

    def mark_not_run(self, phase: str, tasks: Iterable[tuple[str, str, int, list[str] | None]]) -> None:
        """Preserve cancellation truthfully; a later resume will schedule these again."""
        for revision, case_id, repetition, _ in tasks:
            if not self.journal.has_terminal(phase, revision, case_id, repetition):
                self.journal.append({"kind": "harness", "revision": revision, "phase": phase, "caseId": case_id,
                                     "panel": self.cases[case_id].get("panel"), "repetition": repetition,
                                     "parallel": phase == "screen", "status": "NOT_RUN",
                                     "logicalKey": f"{phase}/{revision}/{case_id}/{repetition}"})

    def run(self) -> None:
        phases = [self.args.phase] if self.args.phase != "all" else ["pilot", "screen", "confirm", "timing", "slowdown"]
        for phase in phases:
            if phase == "pilot": self.pilot()
            elif phase == "screen": self.screen()
            elif phase == "confirm": self.confirmations()
            elif phase == "timing": self.timing()
            elif phase == "slowdown": self.slowdown()


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("--manifest", required=True, help="authoritative v3-cold-core-v1.json")
    parser.add_argument("--run-dir", required=True, help="new or resumable output directory")
    parser.add_argument("--java", required=True, help="absolute java executable from the frozen JDK")
    parser.add_argument("--baseline-classpath", required=True, help="frozen baseline worker classpath")
    parser.add_argument("--candidate-classpath", required=True, help="frozen candidate worker classpath")
    parser.add_argument("--workers", type=int, default=12, help="combined screen JVM cap; memory may reduce it")
    parser.add_argument("--phase", choices=("pilot", "screen", "confirm", "timing", "slowdown", "all"), default="all")
    args = parser.parse_args(argv)
    if args.workers < 1 or args.workers > 12:
        parser.error("--workers must be from 1 through 12 (combined baseline/candidate cap)")
    for name in ("manifest",):
        if not Path(getattr(args, name)).is_file(): parser.error(f"--{name} is not a file")
    return args


def main(argv: list[str] | None = None) -> int:
    try:
        Supervisor(parse_args(argv or sys.argv[1:])).run()
        return 0
    except KeyboardInterrupt:
        print("Interrupted: no unstarted cells were reclassified as failures.", file=sys.stderr)
        return 130
    except Exception as exc:
        print(f"benchmark supervisor failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
