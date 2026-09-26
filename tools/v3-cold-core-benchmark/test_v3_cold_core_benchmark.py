"""Focused no-JVM tests for v3_cold_core_benchmark.py scheduling policy."""
from __future__ import annotations

import importlib.util
import sys
import threading
import time
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("v3_cold_core_benchmark.py")
SPEC = importlib.util.spec_from_file_location("v3_cold_core_benchmark", SCRIPT)
assert SPEC and SPEC.loader
SUP = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SUP
SPEC.loader.exec_module(SUP)


class MemoryJournal:
    def __init__(self, screen=None, confirm=None):
        self.by_phase = {"screen": screen or {}, "confirm": confirm or {}}
        self.records = []
        self.run = {}

    def latest(self, phase):
        return self.by_phase.get(phase, {})

    def update_run(self, **changes):
        self.run.update(changes)

    def phase(self, name, state, **extra):
        self.run.setdefault("phases", {}).setdefault(name, {}).update({"state": state, **extra})

    def completed(self, phase, revision, case_id, repetition):
        record = self.by_phase.get(phase, {}).get((revision, case_id, repetition))
        return record is not None and record.get("status") not in {"NOT_RUN", "CANCELLED"}

    def append(self, record):
        record = {"sampleId": f"fake-{len(self.records)}", **record}
        self.records.append(record)
        return record


def sample(status):
    return {"kind": "sample", "sampleId": f"sample-{status}", "status": status, "streams": None}


def supervisor(screen=None, confirm=None):
    instance = SUP.Supervisor.__new__(SUP.Supervisor)
    instance.default_cases = ["C"]
    instance.cases = {"C": {"id": "C", "panel": "test", "input": {"feedMolarFlowMolPerSecond": 100.0}}}
    instance.execution = {"timingPriorityCaseIds": ["C"], "maxPrimaryTimingCases": 12}
    instance.journal = MemoryJournal(screen, confirm)
    return instance


class ConfirmationAuthorityTests(unittest.TestCase):
    def changed_screen(self):
        return {
            ("baseline", "C", 1): sample("SUCCESS_EXACT"),
            ("candidate", "C", 1): sample("API_FAILURE"),
        }

    def test_timeout_and_late_results_require_serial_pair(self):
        s = supervisor()
        for status in ("DEADLINE_EXCEEDED", "LATE_SUCCESS", "RESOURCE_LIMIT"):
            self.assertEqual(1, s.confirmation_requirement("C", sample(status), sample(status)))

    def test_changed_outcome_requires_three_pairs_and_missing_is_rejected(self):
        screen = self.changed_screen()
        confirm = {(revision, "C", repetition): sample("SUCCESS_EXACT")
                   for revision in ("baseline", "candidate") for repetition in range(2)}
        s = supervisor(screen, confirm)
        self.assertEqual(3, s.confirmation_requirement("C", screen[("baseline", "C", 1)],
                                                         screen[("candidate", "C", 1)]))
        with self.assertRaisesRegex(RuntimeError, "required serial confirmation"):
            s.confirmed_outcomes()

    def test_unstable_confirmation_cannot_select_timing_case(self):
        screen = self.changed_screen()
        confirm = {(revision, "C", repetition): sample("SUCCESS_EXACT")
                   for revision in ("baseline", "candidate") for repetition in range(3)}
        confirm[("candidate", "C", 1)] = sample("API_FAILURE")
        s = supervisor(screen, confirm)
        self.assertEqual([], s.timing_selection())
        self.assertEqual([], s.journal.run["timingSelection"])

    def test_resource_recheck_difference_escalates_to_three_and_gates_timing(self):
        screen = {
            ("baseline", "C", 1): sample("DEADLINE_EXCEEDED"),
            ("candidate", "C", 1): sample("DEADLINE_EXCEEDED"),
        }
        confirm = {
            ("baseline", "C", 0): sample("DEADLINE_EXCEEDED"),
            ("candidate", "C", 0): sample("SUCCESS_EXACT"),
        }
        s = supervisor(screen, confirm)
        self.assertEqual(3, s.confirmation_requirement("C", screen[("baseline", "C", 1)],
                                                         screen[("candidate", "C", 1)]))
        with self.assertRaisesRegex(RuntimeError, "required serial confirmation"):
            s.timing_selection()

    def test_confirmations_recompute_and_expand_in_one_invocation(self):
        screen = {
            ("baseline", "C", 1): sample("DEADLINE_EXCEEDED"),
            ("candidate", "C", 1): sample("DEADLINE_EXCEEDED"),
        }
        s = supervisor(screen)
        batches = []

        def fake_run_serial(tasks, _mode):
            batches.append(tasks[:])
            for revision, case_id, repetition, _ in tasks:
                status = "SUCCESS_EXACT" if revision == "candidate" else "DEADLINE_EXCEEDED"
                s.journal.by_phase["confirm"][(revision, case_id, repetition)] = sample(status)
            return []

        s.run_serial = fake_run_serial
        s.confirmations()
        self.assertEqual([2, 4], [len(batch) for batch in batches])
        self.assertEqual({0, 1, 2}, {task[2] for batch in batches for task in batch})
        self.assertEqual("complete", s.journal.run["phases"]["confirm"]["state"])


class JournalResumptionTests(unittest.TestCase):
    def test_cancelled_and_not_run_requests_remain_resumable(self):
        journal = SUP.Journal.__new__(SUP.Journal)
        journal.records = [
            {"kind": "harness", "phase": "screen", "revision": "baseline", "caseId": "C", "repetition": 1,
             "status": "CANCELLED"},
            {"kind": "harness", "phase": "screen", "revision": "candidate", "caseId": "C", "repetition": 1,
             "status": "NOT_RUN"},
        ]
        self.assertTrue(journal.has_terminal("screen", "baseline", "C", 1))
        self.assertFalse(journal.completed("screen", "baseline", "C", 1))
        self.assertFalse(journal.completed("screen", "candidate", "C", 1))


class WarmupBoundTests(unittest.TestCase):
    def test_parallel_warmup_is_bounded_by_worker_count(self):
        class FakeWorker:
            def __init__(self, _supervisor, revision, worker_id, mode):
                self.revision, self.worker_id, self.mode, self.closed = revision, worker_id, mode, False

            def start(self):
                return None

            def stop(self, force=False):
                self.closed = True

            def solve(self, case_id, phase, repetition):
                return {"status": "SUCCESS_EXACT", "caseId": case_id, "phase": phase, "repetition": repetition}

        s = supervisor()
        s.journal = MemoryJournal()
        active = 0
        maximum = 0
        lock = threading.Lock()

        def warm(_worker):
            nonlocal active, maximum
            with lock:
                active += 1
                maximum = max(maximum, active)
            time.sleep(0.03)
            with lock:
                active -= 1

        s.warm = warm
        original = SUP.Worker
        SUP.Worker = FakeWorker
        try:
            s._screen_parallel([("baseline", "C", 1, None), ("candidate", "C", 1, None)], 4)
        finally:
            SUP.Worker = original
        self.assertEqual(4, maximum)


if __name__ == "__main__":
    unittest.main(verbosity=2)
