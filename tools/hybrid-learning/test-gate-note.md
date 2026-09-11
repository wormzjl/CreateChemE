# Prospective test entry-point hardening

The original frozen benchmark driver checks plan/test identity and requires a completed selection record. Its direct `test` entry point does not independently recompute that selection or rehash all validation journals recorded in it. This is an orchestration weakness; no fresh-test request has run.

The supported test entry point is now `run_test.py`. Before releasing any test request, it uses `verify_study.check_selection()` to validate every complete validation journal against the native policy, recompute strict sets and latencies, reproduce both representatives and every replacement gate, verify all recorded validation hashes, and bind selected pipeline hashes to the frozen candidate registry. It creates an immutable test execution lock before calling the unchanged benchmark driver.

`test-gate-registration.json` binds these additional source files and the existing benchmark plan before any test outcomes. Existing benchmark, solver, trainer, weights, validation requests, selection rules, test inputs, budgets and repetition policy remain byte-for-byte unchanged. This adds verification to an execution boundary; it introduces no scientific treatment, tuning, replacement sample or new campaign.

The frozen `benchmark.py test` command remains the low-level implementation for the preserved revision. Use `run_test.py` for study execution. Full results verification checks the prospective lock and unchanged selection hash.
