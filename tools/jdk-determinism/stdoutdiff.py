#!/usr/bin/env python3
"""Classify stdout differences between two xmlcases.py stdout folders: stdoutdiff.py <before-stdout> <after-stdout> [noise-stdout]
With a third folder (a second run of the before tree), line positions that already differ between before and noise
(run-to-run noise: wall times, identity hashes, HashMap order of printed maps) are ignored.
Per class: differing lines, max relative difference of floating-point tokens (with the line), integer tokens that
changed (iteration/solve counts), and lines whose non-numeric text changed (e.g. SUCCESS -> FAILURE)."""
import os, re, sys
NUM = re.compile(r"[-+]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?|NaN|-?Infinity")
a, b = sys.argv[1], sys.argv[2]
nz = sys.argv[3] if len(sys.argv) > 3 else None
print("| class | lines differing | max relative float difference | integer tokens changed | text changed |")
print("|---|---|---|---|---|")
for f in sorted(os.listdir(b)):
    pa, pb = os.path.join(a, f), os.path.join(b, f)
    if not os.path.exists(pa):
        continue
    la, lb = open(pa).read().splitlines(), open(pb).read().splitlines()
    if nz and os.path.exists(os.path.join(nz, f)):
        ln = open(os.path.join(nz, f)).read().splitlines()
        noisy = {i for i, (x, y) in enumerate(zip(la, ln)) if x != y}
        la = [x for i, x in enumerate(la) if i not in noisy]
        lb = [y for i, y in enumerate(lb) if i not in noisy]
    if la == lb:
        continue
    diff = sum(1 for x, y in zip(la, lb) if x != y) + abs(len(la) - len(lb))
    maxrel, where, ints, text = 0.0, "", 0, []
    for x, y in zip(la, lb):
        if x == y:
            continue
        sx, sy = NUM.sub("#", x), NUM.sub("#", y)
        if sx != sy:
            text.append((x[:160], y[:160]))
            continue
        for p, q in zip(NUM.findall(x), NUM.findall(y)):
            if p == q:
                continue
            isint = re.fullmatch(r"[-+]?\d+", p) and re.fullmatch(r"[-+]?\d+", q)
            if isint:
                ints += 1
                continue
            try:
                fp, fq = float(p), float(q)
            except ValueError:
                continue
            if fp != fp or fq != fq or abs(fp) == float("inf") or abs(fq) == float("inf"):
                rel = float("inf")
            else:
                rel = abs(fp - fq) / max(abs(fp), abs(fq), 1e-300)
            if rel > maxrel:
                maxrel, where = rel, f"{p} -> {q}"
    cls = f.replace("com.wormzjl.createcheme.", "").replace(".txt", "")
    print(f"| {cls} | {diff} | {maxrel:.3g} ({where}) | {ints} | {len(text)}{': ' + text[0][0] + ' => ' + text[0][1] if text else ''} |")
