"""Renders the campaign's tables from the analysis JSON, and seals the evidence that gets committed.

Nothing is computed here that the analysis did not already compute; this only chooses what a reader sees
first. The seal copies the per-case evidence and the arm manifests under the study root and binds every
copied byte by SHA-256, so the committed evidence can be checked against the run it came from.
"""
from gap_common import *
import argparse


def row(cells):
    return '| ' + ' | '.join('' if c is None else str(c) for c in cells) + ' |'


def number(value, digits=1):
    return '' if value is None else f'{value:.{digits}f}'


def counts_table(result):
    lines = ['| arm | block | classical | LNN_ONLY | LNN_FIRST | ONLY mean ms | FIRST mean ms |',
             '|---|--:|--:|--:|--:|--:|--:|']
    for name in result['arms']:
        for block in result['blocks']:
            entry = result['summaries'][f'{block}/{name}']
            lines.append(row([name, block, entry['strict']['current'], entry['strict']['neural'],
                              entry['strict']['neuralFirst'],
                              number(entry['milliseconds']['neural']['mean']),
                              number(entry['milliseconds']['neuralFirst']['mean'])]))
    return lines


def paired_table(result, mode):
    lines = [f'| arm | b1 gained / lost | b2 gained / lost | net (min) | reproduced | paired common-success ms |',
             '|---|---|---|--:|---|--:|']
    for name, entry in result['contrasts'].items():
        value = entry[mode]
        lines.append(row([
            name,
            f"{value['blocks']['1']['gained']} / {value['blocks']['1']['lost']}",
            f"{value['blocks']['2']['gained']} / {value['blocks']['2']['lost']}",
            value['netGainBothBlocks'],
            'yes' if value['gainsReproduceAcrossBlocks'] and value['lossesReproduceAcrossBlocks'] else 'no',
            number((value['commonSuccess']['pairedMillis'] or {}).get('mean'))]))
    return lines


def group_tables(result):
    lines = []
    for group, entry in result['groups'].items():
        lines += ['', f"### {group} ({entry['present']} of {entry['registered']} present)", '',
                  '| arm | ONLY strict b1/b2 | FIRST strict b1/b2 | ONLY recovered b1 | ONLY lost b1 |',
                  '|---|--:|--:|--:|--:|']
        for name, value in entry['arms'].items():
            lines.append(row([name, f"{value['onlyStrict'][0]}/{value['onlyStrict'][1]}",
                              f"{value['firstStrict'][0]}/{value['firstStrict'][1]}",
                              len(value['onlyRecoveredIds']['1']), len(value['onlyLostIds']['1'])]))
    return lines


def gate_table(result):
    lines = ['| arm | FIRST b1/b2 | gain both blocks | classical union kept | latency delta ms | passed |',
             '|---|--:|---|---|--:|---|']
    for name, value in result['qualification'].items():
        lines.append(row([name, f"{value['strictFirstCounts'][0]}/{value['strictFirstCounts'][1]}",
                          'yes' if value['improvesStrictFirstBothBlocks'] else 'no',
                          'yes' if value['preservesClassicalUnionBothBlocks'] else 'no',
                          number(value['latencyDeltaMillis']),
                          'yes' if value['passed'] else 'no']))
    return lines


def handoff_section(result):
    if not result['handoff']:
        return []
    lines = ['', '## Ramp handoff cost', '',
             '| arm | mode | armed requests | accepted | mean ms armed | mean ms accepted | mean ms refused | total s |',
             '|---|---|--:|--:|--:|--:|--:|--:|']
    for name, modes in result['handoff'].items():
        for mode, value in modes.items():
            lines.append(row([name, mode, value['armedRequests'], value['acceptedRequests'],
                              number((value['millisecondsWhenArmed'] or {}).get('mean')),
                              number((value['millisecondsWhenAccepted'] or {}).get('mean')),
                              number((value['millisecondsWhenRefused'] or {}).get('mean')),
                              number(value['totalSecondsSpent'])]))
    return lines


def render():
    result = read(OUT / 'validation-analysis.json')
    plan_value = plan()
    lines = [
        '# LNN-gap campaign results', '',
        f"Population {result['cases']} inputs, SHA-256 `{result['population'][:16]}...`; "
        f"{len(result['arms'])} arms x {len(result['blocks'])} blocks x 3 modes = "
        f"{result['measuredRequests']} measured requests on ten workers.", '',
        f"Baseline parity against the promotion run: "
        f"**{'reproduced' if result['parity']['allIdentitySetsReproduced'] else 'NOT reproduced'}**. "
        f"Latency noise band (the baseline's own between-block spread in the pooled FIRST mean): "
        f"{number(result['latencyNoiseBandMillis'])} ms.", '',
        '## Strict counts', ''] + counts_table(result) + [
        '', '## Paired against the baseline: LNN_ONLY', ''] + paired_table(result, 'neural') + [
        '', '## Paired against the baseline: LNN_FIRST', ''] + paired_table(result, 'neuralFirst') + [
        '', '## Study gates', ''] + gate_table(result) + [
        '', '## Target groups'] + group_tables(result) + handoff_section(result) + [
        '', '## Crawling recall (E2 criterion)', '',
        '| arm | continued b1 | continued b2 | minimum fraction |', '|---|--:|--:|--:|']
    for name, value in result['crawlingRecall']['arms'].items():
        lines.append(row([name, value['byBlock'][0]['continued'], value['byBlock'][1]['continued'],
                          number(value['minimumFraction'], 3)]))
    lines += ['', '## Registered decisions', '']
    for experiment, value in result['decisions'].items():
        if experiment == 'combination':
            lines.append(f"- **E5 combination**: {value['selected'] or 'nothing selected'}")
            continue
        lines.append(f"- **{experiment}**: selected `{value['selected']}` from {value['arms']}; "
                     f"net LNN_ONLY gain (min over blocks) {value.get('netOnlyGainBothBlocks')}")
    lines += ['', '## Provenance', '',
              f"- registration `{plan_value['protocol']['sha256'][:16]}...` over protocol.md",
              f"- production delta: {', '.join(Path(p).name for p in plan_value['sourceDelta']['changed'])}",
              f"- bundled weights `{plan_value['frozen']['bundledArtifact']['sha256'][:16]}...`", '']
    (STUDY / 'results.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f"Wrote {STUDY / 'results.md'} ({len(lines)} lines)", flush=True)


def seal():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    copied = []
    for source, name in [(OUT / 'validation-case-evidence.jsonl.gz', 'validation-case-evidence.jsonl.gz'),
                         (OUT / 'validation-analysis.json', 'validation-analysis.json'),
                         (OUT / 'preflight-parity.json', 'preflight-parity.json'),
                         (OUT / 'study-plan.json', 'study-plan.json')]:
        target = EVIDENCE / name
        if not target.exists():
            target.write_bytes(Path(source).read_bytes())
            assert digest(source) == digest(target), 'Artifact copy mismatch'
        copied.append(info(target))
    total = sum(Path(ROOT / entry['path']).stat().st_size for entry in copied)
    assert total < 25 * 1024 ** 2, f'Committed evidence is {total} bytes; keep it under 25 MB'
    freeze(EVIDENCE / 'cache-manifest.json', dict(
        revision='lnn-gap-seal-v1', committedEvidence=copied, totalBytes=total,
        nativeCore=info(OUT / 'native-core-build.json'),
        runs=[info(run_directory(name, block) / 'run.json') for block in BLOCKS for name in plan()['arms']],
        note='Raw evaluation journals are bound by hash here and are not committed.'))
    print(json.dumps(dict(sealed=[entry['path'] for entry in copied], totalBytes=total), indent=1), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['render', 'seal'])
    mode = parser.parse_args().mode
    render() if mode == 'render' else seal()
