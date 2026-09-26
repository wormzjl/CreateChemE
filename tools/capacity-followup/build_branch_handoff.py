"""Build the requested branch handoff only after capacity results are sealed."""
from capacity_common import *
from datetime import datetime, timezone
import hashlib
import subprocess

BASE = 'ef052603d2f840e7f624ab034f234ddf3953d146'
MANIFESTS = [
    'tools/neural/transformer-cache-manifest.json',
    'tools/neural/unified-evaluation-cache-manifest.json',
    'tools/neural/transformer-accuracy-cache-manifest.json',
    'tools/neural/checkpoint-selection-cache-manifest.json',
    'tools/neural/generation-comparison-cache-manifest.json',
    'tools/neural/hybrid-initializer-feasibility-cache-manifest.json',
    'tools/neural/salvage_dataset_manifest.json',
    'tools/hybrid-learning/training-cache-manifest.json',
    'tools/hybrid-learning/results-cache-manifest.json',
    'tools/hybrid-diagnosis/cache-manifest.json',
    'tools/training-curation/cache-manifest.json',
    'tools/trace-followup/registration-manifest.json',
    'tools/trace-followup/cache-manifest.json',
    'tools/capacity-followup/registration-manifest.json',
    'tools/capacity-followup/cache-manifest.json',
]


def git(*args):
    return subprocess.check_output(['git', '-c', 'safe.directory='+ROOT.as_posix(), *args],
        cwd=ROOT, text=True, encoding='utf-8').strip()


def link(relative, label=None):
    path = ROOT/relative
    assert path.is_file(), path
    return f'[{label or relative}](<{path.as_posix()}>)'


def inventory():
    entries = []
    for relative in MANIFESTS:
        doc = read(ROOT/relative)
        archives = []
        if 'archivePath' in doc:
            archives.append(dict(path=doc['archivePath'], sha256=doc['archiveSha256']))
        elif isinstance(doc.get('archive'), dict):
            archives.append(doc['archive'])
        elif isinstance(doc.get('archive'), str):
            archives.append(dict(path=doc['archive'], sha256=doc['archiveSha256']))
        for key in ('studyArchive', 'dependencyArchive'):
            if key in doc: archives.append(doc[key])
        assert archives, relative
        for archive in archives:
            path = ROOT/archive['path']
            assert digest(path) == archive['sha256'], path
            entries.append(dict(manifest=info(ROOT/relative), archive=info(path), bytes=path.stat().st_size))
    assert len({e['archive']['path'] for e in entries}) == len(entries)
    return entries


def build():
    proof = read(OUT/'verification.json')
    cache = read(ROOT/'tools/capacity-followup/cache-manifest.json')
    assert proof['passed'] and digest(ROOT/cache['archive']['path']) == cache['archive']['sha256']
    result = read(OUT/'report-summary.json')
    val = read(OUT/'validation-analysis.json')
    head = git('rev-parse', 'HEAD')
    assert git('branch', '--show-current') == 'codex/v4-transformer-investigation'
    assert git('merge-base', 'codex/v3-neural-gen3-comparison', 'HEAD') == BASE
    assert not git('diff', BASE+'..HEAD', '--', 'src')
    archives = inventory()
    history = git('log', '--reverse', '--format=%h %s', BASE+'..HEAD').splitlines()
    created = datetime.now(timezone.utc).isoformat()
    artifact_doc = dict(revision='v4-branch-handoff-artifacts-v1', createdUtc=created, branch=git('branch', '--show-current'),
        scientificSnapshotCommit=head, baseCommit=BASE, verifiedArchiveCount=len(archives), archives=archives,
        capacityVerification=info(OUT/'verification.json'), capacityResult=info(OUT/'report-summary.json'),
        history=history, productionSourceDiffEmpty=True)
    artifact_path = ROOT/'BRANCH_HANDOFF_ARTIFACTS.json'
    assert not artifact_path.exists(), 'Do not overwrite an existing handoff snapshot.'
    artifact_path.write_text(json.dumps(artifact_doc, indent=2, sort_keys=True)+'\n', encoding='utf-8')
    lines = ['# Branch handoff: transformer and hybrid solver investigation', '',
        f'Branch: `codex/v4-transformer-investigation` · Prepared: {created}', '',
        f'Scientific snapshot: `{head}`. This document summarizes the work after branch base `{BASE}` (`codex/v3-neural-gen3-comparison`), including the completed capacity experiment. Its own documentation commit follows that snapshot.', '',
        f'Workspace: `{ROOT.as_posix()}`. The branch diff changes offline research tools, three Gradle verification tasks and ignore rules; it contains no changes under `src/`. No experiment automatically promoted a production model.', '',
        '## Where the work ended', '',
        'The strongest completed historical full-validation candidate is **F0**, the two-layer full-anchor, absolute-output Transformer from the controlled continuation study: 168/405 strict FIRST successes and 134/405 ONLY successes in both of that study’s blocks, versus the retained original Transformer I at 161/405 and 116/405. Its gain over matched continuation C is only two FIRST cases; the full seven-case difference from I also includes continued training.', '',
        ('The later four-layer experiment met its registered replicated depth rule.' if result['strongReplicatedDepthBenefit'] else
         'The later four-layer experiment did not meet its registered replicated depth rule.'), '',
        'The table below is from the latest completed capacity campaign. FIRST includes classical fallback; ONLY is a separately executed neural-only request. Counts require the unchanged native strict audit and final Newton certificate.', '',
        '| Latest pipeline | ONLY, blocks 1 / 2 | FIRST, blocks 1 / 2 | Pooled all-case FIRST mean ms |',
        '| --- | --- | --- | --- |']
    for name in val['pipelines']:
        values = [val['summaries'][f'{block}/{name}']['all']['modes'] for block in (1, 2)]
        counts = lambda mode: ' / '.join(str(v[mode]['outcomes'].get('strict', 0)) for v in values)
        mean = sum(v['neuralFirst']['milliseconds']['mean'] for v in values)/2
        lines.append(f'| {name} | {counts("neural")} | {counts("neuralFirst")} | {mean:.2f} |')
    qualified = result['qualifiedAgainstControls']['F0']
    lines += ['', f'Latest candidates passing every further-qualification gate against F0: {", ".join(qualified) or "none"}. '
        'Those gates require strict improvement in both blocks, preservation of the contemporaneous classical-success union, and no pooled mean FIRST latency regression. Even passing supports further qualification, not deployment.', '',
        'Read '+link('tools/capacity-followup/results.md', 'the capacity report')+' and '+link('tools/trace-followup/results.md', 'the preceding controlled-study report')+' first.', '',
        '## Work and results by campaign', '',
        '### 1. Expanded certified data and CUDA architecture pilot', '',
        'Qualified training coverage grew from 579 to 805 columns: 133 original TRAIN rescues and 93 fresh Gen3 labels. One additional branch-ambiguous fresh success was quarantined. The pilot treated each column, rather than each correlated tray/node, as the sampling and scoring unit. It preserved 168 qualified validation references and historical fold identities, and froze replacement holdouts before use.', '',
        'Six matched fits compared a two-layer width-64/four-head Transformer (83,800 parameters) with a residual MLP (83,288), using three seeds. Mean validation objective was 1.298 versus 1.541; temperature MAE was 10.94 versus 12.57 K. Mean GPU fit time was 21.75 versus 12.07 seconds. This supported the Transformer for profile prediction, not yet native convergence.', '',
        'Evidence: '+link('tools/neural/transformer-investigation.md')+'.', '',
        '### 2. Native exports and unified convergence/runtime evaluation', '',
        'Added an offline Java Transformer initializer, model export/parity checks, malformed-artifact rejection, cancellation/concurrency checks, and a common three-strategy evaluator. The earlier ten-second neural validation/test and separate 64-case timing scheme were superseded by a serial two-second neural / thirty-second request evaluation on every one of a frozen 252-input population. The interrupted historical nearest-profile timing journal was retained as incomplete.', '',
        'The unified campaign ran 3,024 requests. FIRST strict counts were Transformer 98/252, matched MLP 89, nearest-profile 81, and factorized Gen3 79; paired classical controls were 66. Transformer FIRST gained 32 classical failures without losing a classical success, but its all-case mean was 3.753 seconds versus paired classical 3.437 seconds. The result was improved coverage, not a general runtime speedup.', '',
        'Evidence: '+link('tools/neural/unified-evaluation-results.md')+' and '+link('tools/neural/unified-evaluation-findings.md')+'.', '',
        '### 3. Accuracy/loss ablations and planning review', '',
        'Six predeclared accuracy arms across three seeds compared baseline training, decoded-flow loss, and two regularization strengths with/without decoded-flow loss. The baseline had the best mean profile-selection score (1.111 versus 1.123–1.136). The selected baseline seed was 20260911; no improvement was attributed to the added loss/regularization.', '',
        'On that campaign’s separate prospectively frozen 252-case test, the selected candidate reached 86 FIRST successes versus 83 for its incumbent, with 10 gains and seven losses. Both preserved the 58 paired classical successes. The subsequent ChatGPT review prioritized complete native validation and case-level diagnosis over profile score alone.', '',
        'Evidence: '+link('tools/neural/transformer-accuracy-results.md')+' and '+link('tools/neural/transformer-accuracy-review.md')+'.', '',
        '### 4. Native checkpoint selection with ten workers', '',
        'Three archived seeds were evaluated without retraining on all 405 validation cases. FIRST counts were 154, 161 and 154 for seeds 20260910, 20260911 and 20260912. No alternative met all replacement gates, so seed 20260911 remained the reference. Its frozen test execution reached 80/252 FIRST and 54/252 ONLY, with 51 classical successes on that campaign’s test population.', '',
        'Revision 1 stopped before scientific evaluation because a scheduler fixture was neural-rescued but not cold-classical qualified. Revision 2 changed only the numerical-check fixtures and their registration; model bytes, inputs, selection rule, solver and budgets were preserved. A ten-worker lifecycle/failure/cancellation harness was added. Do not execute the superseded v1 campaign entry points.', '',
        'Evidence: '+link('tools/neural/checkpoint-selection-results.md')+' and '+link('tools/neural/checkpoint-selection-amendment-v2.md')+'.', '',
        '### 5. Matched generation comparison and balance-formulation correction', '',
        'Frozen Gen2, Gen3 and Transformer models were compared on the same exposed populations, reusing verified Transformer journals. FIRST validation counts were 136, 134 and 161 out of 405; the corresponding test counts were 60, 66 and 80 out of 252. This compared whole generations with different training/selection history, not architecture alone. No default changed.', '',
        'An additive erratum corrected a proposed material-loss formulation: current V3 pumparounds are prescribed stage-heat terms, not circulation edges in component material balances. They belong in a separately proposed energy loss. The comparison’s frozen numerical results and archive were not edited.', '',
        'Evidence: '+link('tools/neural/generation-comparison-results.md')+' and '+link('tools/neural/generation-comparison-recommendation-erratum-v1.md')+'.', '',
        '### 6. Material-completion feasibility and original-matrix salvage', '',
        'On twenty dry/no-side-draw TRAIN inputs, frozen-model material completion increased FIRST strict outcomes from 17 to 19, with two gains and no losses; preparation applied to eight cases and declined twelve. This was a small TRAIN feasibility result, not held-out qualification. Material closure did not imply energy/equilibrium closure.', '',
        'A one-pass, ten-worker sweep of 2,793 admitted original conditions recovered 101 novel strict TRAIN labels. Of 1,993 TRAIN requests, 752 were strict; 651 duplicated existing N inputs and 101 were appended. Validation 161/405 and historical test 164/395 outcomes were recorded but not added to training. The original 805-row N prefix and all nontraining records remained unchanged, yielding N+1=906. Advisory outcomes were excluded, every failed attempt was retained, and no retry campaign was used.', '',
        'A separate organization audit moved 71 misplaced untracked cache copies from the main worktree to this worktree after confirming every file matched its canonical copy. This was cache housekeeping, not a production source change.', '',
        'Evidence: '+link('tools/neural/hybrid-initializer-feasibility-results.md')+', '+link('tools/neural/salvage_results.md')+' and '+link('tools/neural/worktree-artifact-organization.json')+'.', '',
        '### 7. Learned native-baseline residual hybrids, N versus N+1', '',
        'Six paired hybrid fits used three seeds, the same N-derived normalization and 4,160 updates. The branch classifier chose a native MATERIAL_CLOSED anchor in training and inference. Continuous coordinates were learned residuals, and the pipeline included the exact material-completion wrapper.', '',
        'Across two full-validation blocks, the selected N hybrid reached 159/405 FIRST and selected N+1 reached 155, versus incumbent I and I+wrapper at 161. On this study’s new frozen 252-case test, I reached 92, I+wrapper 93, N 91, and N+1 89 FIRST successes in both blocks. N gained/lost 12/13 I cases; N+1 gained/lost 10/13. No pipeline passed the replacement gate. Better raw profile errors did not imply better convergence; the wrapper applied to relatively few inputs.', '',
        'A supported test entry point was added to recompute selection and validation-journal bindings before releasing test requests. Final sealing also replays selection, summaries, case maps and report text. These changes repaired verification boundaries without changing frozen scientific treatments.', '',
        'Evidence: '+link('tools/hybrid-learning/results.md')+', '+link('tools/hybrid-learning/native-results.md')+', '+link('tools/hybrid-learning/profile-results.md')+', '+link('tools/hybrid-learning/test-gate-note.md')+' and '+link('tools/hybrid-learning/sealing-note.md')+'.', '',
        '### 8. Hybrid diagnosis and controlled support-coordinate ablation', '',
        'Case analysis linked many lost successes to continuous trace flows falling below the existing cutoff even when the presence head kept the component. Completion was not the direct source of those losses because it had not prepared their seeds. The MATERIAL_CLOSED composition anchor was essentially the feed prior and supplied little separation information. The added 101 cases were learned; uniform gradient conflict was not supported. Equal update budgets reduced old-example exposure in N+1.', '',
        'A paired, function-preserving canonical support-output reparameterization did not help: on the 65-case diagnostic panel, empirical control FIRST was 46 and canonical FIRST 43 in each block. Canonical gained/lost 4/7 FIRST cases, and total above-floor omissions increased from 1,150 to 1,338. The experiment used 1,170 measured requests plus 36 warmups. Its empirical control reproduced the earlier selected checkpoint exactly. The proposed scaling was rejected for this recipe.', '',
        'Evidence: '+link('tools/hybrid-diagnosis/results.md')+'.', '',
        '### 9. Certified-data curation', '',
        'All 906 certified records passed strict source/shape checks, but gd-s08-w0-p1-d0-r00 had a recorded historical profile disagreement and was held unchanged in quarantine. The remaining 905 records had no admissible nonself representative pairs under the registered coverage rules, even at half/double continuous resolution: 905 selected, zero reserve, one quarantined. No arbitrary smaller target size was imposed, and no fit or solver campaign was run for curation.', '',
        'All eleven LIQUID_ONLY cases remained. Stage counts 38 and 52 still had no fitted labels; no wet-qualified or VAPOR_ONLY profiles were created. The quarantine records a target-consistency problem, not proof of multiple physical roots.', '',
        'Evidence: '+link('tools/training-curation/results.md')+'.', '',
        '### 10. Controlled trace, anchor, checkpoint and exposure follow-ups', '',
        'Ten continuation fits (five arms, two order seeds) produced thirty fixed checkpoints. I was preserved at initialization; new anchor embeddings started with zero coupling. C used curated N804, T added one TRAIN-calibrated trace-margin term, F added full native anchors with absolute outputs, K added compact temperature/traffic anchors, and D added the 101 curated profiles. No wrapper was added to these new arms.', '',
        'The primary full-validation seed was fixed in advance. Twelve pipelines were tested twice on 405 historical inputs, with 35,400 measured requests and 336 TRAIN warmups including screening. At +4,160, FIRST counts were I 161, C 166, T 162, F 168, K 165 and D 167, identical across both blocks. F became the F0 starting reference for the capacity study.', '',
        'The trace treatment lost four net FIRST cases against matched C and increased above-floor omissions from 778 to 842. Compact K lost one net FIRST case against C and three against full F. D at equal +4,160 updates gained one net case against C, but D at +4,640 matched old-example exposure fell to 160 versus C@4,160 at 166; extra C training alone fell to 162. The native and profile rules chose the same primary T checkpoint, so no checkpoint-choice gain was observed for T. Seven endpoints passed the further-qualification rule against I; none was deployed and no fresh test was used.', '',
        'Evidence: '+link('tools/trace-followup/results.md')+' and '+link('tools/trace-followup/protocol.md')+'.', '',
        '### 11. Robustness research and the four-layer capacity experiment', '',
        'Research covered robustness to poor initial guesses and numerical acceptance tolerance. The user supplied prior negative evidence for loosening criteria; the suggestion to repeat a tolerance sweep, including loose initialization followed by strict polishing, was withdrawn. No new tolerance experiment was run here. The offline follow-up probe uses default strict closure directly, so changing the game config would not change this benchmark.', '',
        'The authorized capacity experiment grew F0 from two to four width-64 layers (89,496 to 156,440 parameters). Two appended residual blocks initially contributed exactly zero through their output projections. Both arms started with identical predictions and fresh AdamW states, used matched N804 minibatches for order seeds 20260913 and 20260914, and received +3,120 updates with checkpoints at +1,040/+2,080/+3,120. The primary comparison remained fixed at +2,080; independent native-selected endpoints were secondary.', '',
        f'Four fits and twelve exports completed. Screening covered fourteen pipelines; full validation retained seven pipelines in two reversed blocks. Accounting is {result["measuredNativeRequests"]:,} measured native requests plus {result["warmupRequests"]} TRAIN warmups. No new data or test set, loss, decoder, support threshold, solver acceptance gate or production default changed.', '',
        'The deeper model reduced profile error, but the final native results and paired losses above determine its practical value. The native core was rebuilt from 113 archive-matched Java sources with only Gson as a compiler dependency. All 804 optimization inputs had identical complete Java F0/L2/L4 initial predictions; eight malformed manifests were rejected. Eleven preflight tests, four decision tests, all twelve export parity checks, cancellation/ten-worker checks, tensor and optimizer verification, independent per-ID exposure reconstruction, exact analysis/report replay, and archive checks passed.', '',
        'The preserved capacity preflight failures involved sandbox access to the Java dependency cache and an initial source closure that included an unused Minecraft-dependent probe; error printing also encountered an encoding failure. They were corrected before registration and fitting. They were not discarded fit trajectories or discarded native campaign outcomes.', '',
        'Evidence: '+link('tools/capacity-followup/results.md')+', '+link('tools/capacity-followup/protocol.md')+', '+link('tools/capacity-followup/research-context.md')+' and '+link('tools/capacity-followup/robustness-research.md')+'. The robustness note is a preserved pre-capacity research snapshot; its unexecuted proposals are not additional measured results.', '',
        '## Dataset and model naming', '',
        '| Name | Meaning |', '| --- | --- |',
        '| N / H805 training | 805 original certified TRAIN records; H805 is the old residual hybrid with completion wrapper. |',
        '| N+1 | The same 805-record prefix plus 101 certified additions = 906. |',
        '| N804 | Original N excluding the one disputed historical target. |',
        '| N905 / curated selected | N804 plus the same 101 additions; all eleven LIQUID_ONLY records retained. |',
        '| I | Retained seed-20260911 absolute-output Transformer from native checkpoint selection, 83,800 parameters; distinct from the earlier pilot’s seed-20260912 export. |',
        '| C / T / D | Plain continuation / trace treatment / added-data continuation in the controlled study. |',
        '| F / F0 | Full-anchor absolute-output model, 185 inputs and 89,496 parameters; F0 is F-20260911-s4160. |',
        '| K | Compact-anchor absolute-output model, 103 inputs and 84,248 parameters. |',
        '| L2 / L4 | Matched continuations from F0 in the latest capacity study; update numbers are additional to F0. |', '',
        'Excluding the disputed target from new minibatches does not undo its historical influence through pretrained weights. Steam-fed inputs are not evidence of wet-equilibrium training. The 168 certified validation references are a subset of the 405 evaluation inputs, not a second independent evaluation population.', '',
        '## Rules for interpreting and extending the results', '',
        '- Different campaigns used different 252-input populations. Compare within the named campaign and its input hashes; do not pool their counts.',
        '- Serial timings, concurrent timings and ten-second historical neural runs are different conditions. All-case costs include failed requests and fallback. Stable common-success costs are the appropriate additional check for speed claims.',
        '- Repeated blocks reuse identical inputs. Standard deviations usually describe case variability, not a confidence interval from independent campaigns.',
        '- Strict outcomes require native audit plus final Newton evidence; advisory success is excluded. No failed input leaves the denominator.',
        '- FIRST terminal evidence can belong to classical fallback. A separately timed ONLY request is not FIRST’s internal neural trajectory.',
        '- Initial projected residuals and audit ratios depend on support and scaling. Neither material closure, lower profile RMSE, nor fewer support omissions alone proves a better warm start.',
        '- Preserve the ten-worker budget, solver/property/support/decoder rules and the distinction between fixed endpoints and selected endpoints. Do not rerun exposed tests to tune a choice.', '',
        'The next research proposal is a frozen-weight, paired trajectory diagnosis under strict criteria: compare accepted step sizes, residual families, support/phase changes and rejected property-domain trials on gained/lost cases. Only then choose one bounded intervention, such as solver-facing training, a budgeted seed selector, or a recovery change. These are proposals, not completed improvements. Current code already contains line search and recovery mechanisms; adding generic damping is not automatically a new method.', '',
        '## Artifacts, environment and safe continuation', '',
        'Git contains the protocols, source, readable reports and manifests. Large datasets, checkpoints, journals and ZIPs live under the ignored .neural-cache directory; working copies under build can be removed by Gradle clean. Preserve the caches together with this branch and the linked Gen2/Gen3 prerequisite caches.', '',
        f'{len(archives)} authoritative branch-study archives were rehashed for this handoff. Full SHA-256 values, sizes, manifest bindings and the captured commit history are in '+link('BRANCH_HANDOFF_ARTIFACTS.json')+'.', '',
        '| Study manifest | Archive | Size MiB |', '| --- | --- | --- |']
    for entry in archives:
        lines.append(f'| {link(entry["manifest"]["path"])} | {link(entry["archive"]["path"])} | {entry["bytes"]/1024**2:.2f} |')
    lines += ['',
        'Follow each manifest’s restoration rules: legacy archives can use different restore roots. The trace/capacity registration and results ZIP entries are repository-root relative. Their source/runtime checks bind the recorded paths; relocation requires an explicit provenance-preserving restoration or amendment, not silent edits to a sealed plan.', '',
        'The latest environment used Python from `.neural-venv/Scripts/python.exe`, PyTorch 2.10.0+cu128, an RTX 4070 Ti, deterministic float32 CUDA with TF32 disabled, Java 21.0.11, and Gson 2.10.1. The latest native runtime is under `build/neural-capacity-followup/native-core-v2` and `classes-v1`; exact external/runtime paths and hashes are in its registration.', '',
        'Read-only verification of the completed capacity archive:', '',
        '```powershell', f'Set-Location -LiteralPath \'{ROOT.as_posix()}\'',
        r'.\.neural-venv\Scripts\python.exe tools/capacity-followup/capacity_seal.py verify', '```', '',
        'The `fit`, `export`, benchmark, registration and sealing entry points create experimental artifacts. They are not status commands; do not invoke them to inspect an already completed campaign. Existing directories are intentionally guarded against overwrite or silent retries. Protected Java-cache and Git writes may require the app’s permission flow.', '',
        'Planning/review integration was separately updated with explicit user approval to codex-with-chatgpt 0.1.3 (checkout commit 9663b887), and the workspace connection was restored. This was machine/workspace tooling work, not a model or solver change. The final independent review uses the same '+
        '[ChatGPT conversation](https://chatgpt.com/g/g-p-6aa026927b5c819187b10da97168efd4-createcheme/c/6aa13fb1-1d04-83ec-8fb0-8f0a7c432664).', '',
        '## Commit trail', '',
        'The base already contained earlier Gen2/Gen3 training. The following are the branch-specific commits through the scientific snapshot; the handoff’s own commit and any later review correction can be found with git log.', '',
        '```text', *history, '```', '',
        'The experiment and its result preservation are complete. No production rollout or additional research campaign is pending under this task. Future experiments require their own defined scope and frozen evaluation policy.']
    path = ROOT/'BRANCH_HANDOFF.md'
    assert not path.exists(), 'Do not overwrite an existing handoff snapshot.'
    path.write_text('\n'.join(lines)+'\n', encoding='utf-8')
    print(json.dumps(dict(handoff=info(path), artifactInventory=info(artifact_path), verifiedArchives=len(archives),
        scientificSnapshotCommit=head)), flush=True)


if __name__ == '__main__': build()
