#!/usr/bin/env python3
"""Compare the per-runtime captures of sweep.sh and print Markdown tables.

Usage: compare.py <capture-dir> [--reference system] [--manifest <jdks>/manifest.tsv]

Groups the runtimes by identical output (junction lines, chain-100 exact regression, each BitwiseProbe file, MathSweep
Math hashes) and counts, per differing group, the lines that differ from the reference runtime's output (for the
probe files every double is printed in hex, so a differing line is a differing double).
"""
import argparse
import csv
import hashlib
import os
import re
import sys
from collections import OrderedDict

PROBE_FILES = ["chain-100.json", "scenarios.txt", "gas-ports.txt", "liquid-ports.txt"]
FUNCS = ["log", "exp", "pow", "cbrt", "log1p", "expm1", "cos", "log10", "acos", "sqrt", "fma"]


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except FileNotFoundError:
        return None


def digest(text):
    return None if text is None else hashlib.sha256(text.encode()).hexdigest()[:10]


def meta(d):
    m = {}
    for line in (read(os.path.join(d, "meta.txt")) or "").splitlines():
        for part in line.split(" "):
            if "=" in part and not part.startswith("java=") and not part.startswith("build="):
                k, v = part.split("=", 1)
                m.setdefault(k, v)
        if line.startswith(("java.runtime.version=", "java.vm.name=", "java.vendor.version=", "java.vendor=")):
            k, v = line.split("=", 1)
            m[k] = v
    return m


def diff_lines(a, b):
    """Differing lines between two texts (same line count assumed; extra lines count as differing)."""
    if a is None or b is None:
        return None
    la, lb = a.splitlines(), b.splitlines()
    n = sum(1 for x, y in zip(la, lb) if x != y) + abs(len(la) - len(lb))
    return n


def chain_numbers(text):
    return None if text is None else re.findall(r"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?", text)


def chain_values(text):
    """Hex and decimal doubles in the chain-100 JSON / probe text, in order."""
    if text is None:
        return None
    return re.findall(r"0x[0-9a-fA-F.p+-]+|-?\d+\.\d+(?:[eE][-+]?\d+)?", text)


def mathsweep(path):
    text = read(path)
    rows = {}
    if text is None or text.startswith("skipped"):
        return None
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 6 and parts[0] in FUNCS:
            rows[parts[0]] = {"differ": int(parts[1]), "ulp": int(parts[2]), "math": parts[3], "strict": parts[4],
                              "input": parts[5], "stable": parts[6] if len(parts) > 6 else "?"}
    return rows


def group(ids, key):
    groups = OrderedDict()
    for i in ids:
        groups.setdefault(key(i), []).append(i)
    return groups


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cap")
    ap.add_argument("--reference", default="system")
    ap.add_argument("--manifest", default=None)
    a = ap.parse_args()
    ids = sorted(d for d in os.listdir(a.cap) if os.path.isfile(os.path.join(a.cap, d, "meta.txt")))
    ref = a.reference if a.reference in ids else ids[0]
    ids.remove(ref)
    ids.insert(0, ref)
    M = {i: meta(os.path.join(a.cap, i)) for i in ids}
    man = {}
    if a.manifest and os.path.exists(a.manifest):
        with open(a.manifest) as f:
            for row in csv.DictReader(f, delimiter="\t"):
                man[row["id"]] = row
    P = lambda i, *p: os.path.join(a.cap, i, *p)

    print(f"# Sweep comparison: {a.cap}\n\nReference runtime: `{ref}` ({M[ref].get('java.runtime.version')}).\n")
    print("## Runtimes and summary\n")
    print("| id | java.runtime.version | VM | runtime tests | junction lines | chain-100 exact | probe (4 files) | probe -UseLibmIntrinsic |")
    print("|---|---|---|---|---|---|---|---|")
    jl = {i: read(P(i, "junction-lines.txt")) for i in ids}
    pr = {i: {f: read(P(i, "probe", f)) for f in PROBE_FILES} for i in ids}
    pn = {i: {f: read(P(i, "probe-nolibm", f)) for f in PROBE_FILES} for i in ids}
    jgroups = group(ids, lambda i: digest(jl[i]))
    pgroups = group(ids, lambda i: tuple(digest(pr[i][f]) for f in PROBE_FILES))
    jlabel = {k: chr(65 + n) for n, k in enumerate(jgroups)}
    plabel = {k: chr(65 + n) for n, k in enumerate(pgroups)}
    pall = OrderedDict((k, plabel[k]) for k in pgroups)
    for i in ids:
        k = tuple(digest(pn[i][f]) for f in PROBE_FILES)
        if k not in pall and all(k):
            pall[k] = chr(65 + len(pall))
    for i in ids:
        tests = re.findall(r"(\d+) tests (successful|failed)", read(P(i, "runtime-summary.txt")) or "")
        t = "/".join(n for n, _ in tests[:2]) if tests else "?"
        reg = re.search(r"state/moles (\S+)", read(P(i, "regression.txt")) or "")
        pk = tuple(digest(pr[i][f]) for f in PROBE_FILES)
        nk = tuple(digest(pn[i][f]) for f in PROBE_FILES)
        vm = M[i].get("java.vm.name", "?").replace("OpenJDK 64-Bit Server VM", "HotSpot").replace(
            "Java HotSpot(TM) 64-Bit Server VM", "HotSpot (Oracle)").replace("Eclipse OpenJ9 VM", "OpenJ9")
        if "jvmci" in M[i].get("java.runtime.version", ""):
            vm += " + Graal JIT"
        nol = pall.get(nk, "-") if all(nk) else "n/a"
        print(f"| {i} | {M[i].get('java.runtime.version')} | {vm} | {t} ok/failed | {jlabel[digest(jl[i])]} | "
              f"{reg.group(1) if reg else '?'} | {plabel[pk]} | {nol} |")
    print("\nLetters name groups of byte-identical output (A = the reference's group). chain-100 exact = the maximum "
          "relative moles deviation of `harness.sh regression` against the checked-in `chain-100.json`.\n")

    print("## Junction lines by group\n")
    for k, members in jgroups.items():
        n = diff_lines(jl[ref], jl[members[0]])
        print(f"- group {jlabel[k]} ({len(members)}): {', '.join(members)}; {n} of {len((jl[members[0]] or '').splitlines())} lines differ from the reference")
    print()
    for k, members in list(jgroups.items())[1:]:
        print(f"### group {jlabel[k]} vs reference (first member `{members[0]}`)\n\n```")
        for x, y in zip((jl[ref] or "").splitlines(), (jl[members[0]] or "").splitlines()):
            if x != y:
                print("- " + x + "\n+ " + y)
        print("```\n")

    print("## BitwiseProbe groups: differing values against the reference (text files print one hex double per line; chain-100.json counts numbers)\n")
    print("| group | runtimes | " + " | ".join(PROBE_FILES) + " |")
    print("|---|---|" + "---|" * len(PROBE_FILES))
    for k, lab in pall.items():
        members = [i for i in ids if tuple(digest(pr[i][f]) for f in PROBE_FILES) == k]
        nmembers = [i + " (-UseLibmIntrinsic)" for i in ids if tuple(digest(pn[i][f]) for f in PROBE_FILES) == k]
        src = (pr[members[0]] if members else pn[[i for i in ids if tuple(digest(pn[i][f]) for f in PROBE_FILES) == k][0]])
        cells = []
        for f in PROBE_FILES:
            if f.endswith(".json"):  # one line of JSON: count differing numbers instead
                na, nb = chain_numbers(pr[ref][f]), chain_numbers(src[f])
                d = None if na is None or nb is None else sum(1 for x, y in zip(na, nb) if x != y) + abs(len(na) - len(nb))
                cells.append(f"{d} / {len(nb or [])} numbers")
            else:
                total = len((src[f] or "").splitlines())
                cells.append(f"{diff_lines(pr[ref][f], src[f])} / {total} lines")
        print(f"| {lab} | {', '.join(members + nmembers)} | " + " | ".join(cells) + " |")
    print()

    print("## MathSweep: Math vs StrictMath per function (default JIT, last of 3 repetitions)\n")
    ms = {i: {"jit": mathsweep(P(i, "mathsweep-jit.txt")), "xint": mathsweep(P(i, "mathsweep-xint.txt")),
              "nolibm": mathsweep(P(i, "mathsweep-nolibm.txt"))} for i in ids}
    strict_hashes = {(f, (ms[i][m] or {}).get(f, {}).get("strict")) for i in ids for m in ms[i] if ms[i][m] for f in FUNCS}
    input_hashes = {(f, (ms[i][m] or {}).get(f, {}).get("input")) for i in ids for m in ms[i] if ms[i][m] for f in FUNCS}
    print(f"StrictMath hashes: {len(strict_hashes)} distinct (function, hash) pairs over all runtimes and modes "
          f"(= {len(FUNCS)} means StrictMath is identical everywhere); input hashes: {len(input_hashes)} distinct pairs.\n")
    # Math hash groups per function
    mlabels = {f: OrderedDict() for f in FUNCS}
    for f in FUNCS:
        strict = (ms[ref]["jit"] or {}).get(f, {}).get("strict")
        mlabels[f][strict] = "S"
        for i in ids:
            for m in ("jit", "xint", "nolibm"):
                h = (ms[i][m] or {}).get(f, {}).get("math")
                if h and h not in mlabels[f]:
                    mlabels[f][h] = str(len(mlabels[f]))
    print("Cell = differing inputs out of 1,048,576 / max ulp / Math-hash class (S = identical to StrictMath; digits "
          "name other distinct Math output sets). Columns: default JIT; `-Xint`; `-XX:-UseLibmIntrinsic`.\n")
    for mode in ("jit", "xint", "nolibm"):
        print(f"### mode {mode}\n")
        print("| runtime | " + " | ".join(FUNCS) + " |")
        print("|---|" + "---|" * len(FUNCS))
        for i in ids:
            rows = ms[i][mode]
            if rows is None:
                print(f"| {i} | " + " | ".join("n/a" for _ in FUNCS) + " |")
                continue
            cells = []
            for f in FUNCS:
                r = rows.get(f)
                if not r:
                    cells.append("?")
                    continue
                cls = mlabels[f].get(r["math"], "?")
                st = "" if r["stable"] in ("yes", "?") else " UNSTABLE"
                cells.append("0" if cls == "S" else f"{r['differ']}/{r['ulp']}/{cls}{st}")
            print(f"| {i} | " + " | ".join(cells) + " |")
        print()
    # tier consistency within a runtime
    print("### Tier consistency (same runtime, Math hash default JIT vs -Xint)\n")
    for i in ids:
        j, x = ms[i]["jit"], ms[i]["xint"]
        if not j or not x:
            continue
        bad = [f for f in FUNCS if j.get(f, {}).get("math") != x.get(f, {}).get("math")]
        unstable = [f for f in FUNCS if j.get(f, {}).get("stable") == "NO"]
        if bad or unstable:
            print(f"- {i}: JIT != -Xint for {', '.join(bad) or '-'}; repetitions unstable for {', '.join(unstable) or '-'}")
    print()


if __name__ == "__main__":
    sys.exit(main())
