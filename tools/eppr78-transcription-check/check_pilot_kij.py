"""Record check against Table S4, the row-by-row comparison with the Clapeyron transcription, and the appendix A
reproduction for the E-PPR78 pilot pairs (P3 WP2, extended in WP2b).

Batch 2026-09-24-coolprop-low-temperature. Standard library only.

1. Verifies jaubert-2022-si-table-s4.tsv (SHA-256, complete lower triangle; build_group_interactions_s4.read_table) and
   checks that the bundled record materials/group_interactions/eppr78_2022.json carries every numeric off-diagonal cell
   of Table S4, and nothing else, with the identical decimal text of A and B and the group numbers and names of the
   builder.
2. Compares Table S4 row by row with the Clapeyron.jl transcription EPPR78_unlike.csv (commit 0778184, revision
   eppr78-2022-clapeyron-0778184-r1 of the record; build_group_interactions.read_pairs checks its SHA-256 and git blob):
   every one of the 780 off-diagonal cells is classed as identical (same numbers), a discrepancy (A = 0 with the printed
   B, A = 0 with another B, another B, another A, missing from the transcription, NA in Table S4) or absent from both.
   A transcription value that Table S4 prints in another cell is named (a shifted row).
3. Recomputes kij(T) of the six pilot pairs (N2, CH4, C2H6, CO2, one group each) at 100..900 K from the record's values
   with the kernel's PR78 (Omega_a 0.45724, Omega_b 0.07780, R = 8.31446261815324, Soave m(omega) of PR78), twice:
   with the ethane constants the design's appendix A used (CoolProp: 305.322 K, 4.8722 MPa, 0.0990) and with the pilot
   property record's (305.32 K, 4.872 MPa, 0.099, identical to the bundled tjl19_ethane record); compares each cell
   with appendix A at four decimals.

    python check_pilot_kij.py --tsv <sources>/e-ppr78/jaubert-2022-si-table-s4.tsv \
        --csv <sources>/e-ppr78/clapeyron/EPPR78_unlike.csv --record <eppr78_2022.json> > pilot-kij-output.txt
"""
import argparse
import json
import math
import sys
from collections import Counter
from decimal import Decimal

from build_group_interactions import read_pairs as read_transcription
from build_group_interactions_s4 import GROUPS, read_table

R = 8.31446261815324
TEMPERATURES = [100, 150, 200, 250, 300, 400, 600, 900]
# Pilot property records (PR78 constants identical to the bundled N2, CH4, C2H6 records; CO2 from P2).
PILOT = {"N2": (126.192, 3395800.0, 0.0372), "CH4": (190.564, 4599200.0, 0.01142),
         "C2H6": (305.32, 4872000.0, 0.099), "CO2": (304.1282, 7377300.0, 0.22394)}
APPENDIX_CONSTANTS = dict(PILOT, C2H6=(305.322, 4872200.0, 0.0990))
PAIRS = [("CH4", "C2H6"), ("CH4", "CO2"), ("CH4", "N2"), ("C2H6", "CO2"), ("C2H6", "N2"), ("CO2", "N2")]
APPENDIX_A = {
    ("CH4", "C2H6"): [0.0072, 0.0063, 0.0059, 0.0057, 0.0057, 0.0060, 0.0075, 0.0121],
    ("CH4", "CO2"): [0.1213, 0.1074, 0.1040, 0.1057, 0.1104, 0.1259, 0.1747, 0.3010],
    ("CH4", "N2"): [0.0337, 0.0315, 0.0295, 0.0276, 0.0256, 0.0205, 0.0030, -0.0729],
    ("C2H6", "CO2"): [0.1734, 0.1453, 0.1331, 0.1279, 0.1267, 0.1308, 0.1543, 0.2184],
    ("C2H6", "N2"): [0.0610, 0.0492, 0.0405, 0.0332, 0.0262, 0.0122, -0.0244, -0.1472],
    ("CO2", "N2"): [0.0955, 0.0371, 0.0050, -0.0158, -0.0306, -0.0505, -0.0712, -0.0675],
}
NUMBER_OF = {name: number for number, name, _ in GROUPS}
LABEL_OF = {name: printed for _, name, printed in GROUPS}


def cell_name(k, l):
    """'l-k (name/name)' with l < k, the lower-numbered group first."""
    lo, hi = min(k, l), max(k, l)
    return "%d-%d (%s/%s)" % (lo, hi, GROUPS[lo - 1][1], GROUPS[hi - 1][1])


def check_record(record_path, cells):
    """The record against Table S4: every numeric off-diagonal cell with the identical decimal text, nothing else."""
    raw = open(record_path, "rb").read().decode("utf-8")
    record = json.loads(raw, parse_float=str, parse_int=str)
    problems = 0
    names = [(int(g["number"]), g["name"]) for g in record["groups"]]
    if names != [(number, name) for number, name, _ in GROUPS]:
        problems += 1
        print("MISMATCH group list: %r" % names)
    carried = {}
    for p in record["pairs"]:
        k, l = NUMBER_OF[p["first"]], NUMBER_OF[p["second"]]
        carried[(max(k, l), min(k, l))] = (p["a_mpa"], p["b_mpa"], k < l)
    expected = {key: value for key, value in cells.items() if key[0] != key[1] and value is not None}
    for key, (a, b) in sorted(expected.items(), key=lambda item: (item[0][1], item[0][0])):
        got = carried.get(key)
        if got is None or got[0] != a or got[1] != b or not got[2]:
            problems += 1
            print("MISMATCH %s: Table S4 %s %s, record %s" % (cell_name(*key), a, b, got))
    extra = sorted(set(carried) - set(expected))
    for key in extra:
        problems += 1
        print("EXTRA %s: record %s, Table S4 %s" % (cell_name(*key), carried[key][:2], cells.get(key, "no cell")))
    na = sum(1 for key, value in cells.items() if key[0] != key[1] and value is None)
    print("record %s revision %s: %d groups, %d pairs; Table S4 numeric off-diagonal cells %d, NA %d; "
          "mismatched or missing %d; pairs not in Table S4 %d"
          % (record["id"], record["revision"], len(record["groups"]), len(record["pairs"]), len(expected), na,
             problems - len(extra), len(extra)))
    return problems == 0, {key: (Decimal(a), Decimal(b)) for key, (a, b, _) in carried.items()}


def compare_with_transcription(csv_path, cells):
    """Row-by-row comparison of Table S4 with the Clapeyron transcription (revision r1)."""
    transcription = {}
    for first, second, va, vb in read_transcription(csv_path):
        k, l = NUMBER_OF[first], NUMBER_OF[second]
        transcription[(max(k, l), min(k, l))] = (va, vb)
    printed_b = Counter()
    where_b = {}
    for key, value in cells.items():
        if key[0] != key[1] and value is not None:
            printed_b[Decimal(value[1])] += 1
            where_b.setdefault(Decimal(value[1]), []).append(key)
    classes = Counter()
    lines = []
    groups_with_zero_a = Counter()
    text_only = 0
    for key in sorted((key for key in cells if key[0] != key[1]), key=lambda key: (key[1], key[0])):
        s4, tr = cells[key], transcription.get(key)
        if s4 is None and tr is None:
            classes["absent from both"] += 1
            continue
        if tr is None:
            cls = "missing from the transcription"
            detail = "Table S4 A=%s B=%s" % s4
        elif s4 is None:
            cls = "in the transcription, NA in Table S4"
            detail = "transcription A=%s B=%s" % tr
        else:
            sa, sb, ta, tb = Decimal(s4[0]), Decimal(s4[1]), Decimal(tr[0]), Decimal(tr[1])
            detail = "Table S4 A=%s B=%s; transcription A=%s B=%s" % (s4 + tr)
            if sa == ta and sb == tb:
                classes["identical"] += 1
                if s4 != tr:
                    text_only += 1
                continue
            if ta == 0 and sa != 0:
                cls = "A = 0 with the printed B" if sb == tb else "A = 0 with another B"
                if sb == tb:
                    groups_with_zero_a[key[0]] += 1
                    groups_with_zero_a[key[1]] += 1
            elif sa == ta:
                cls = "another B"
            elif sb == tb:
                cls = "another A"
            else:
                cls = "another A and B"
        classes[cls] += 1
        if tr is not None and (s4 is None or Decimal(tr[1]) != Decimal(s4[1])):
            elsewhere = [other for other in where_b.get(Decimal(tr[1]), []) if other != key]
            if elsewhere:
                detail += "; the transcription's B is Table S4's B of " + ", ".join(cell_name(*other) for other in elsewhere)
        lines.append("%s: %s: %s" % (cls, cell_name(*key), detail))
    discrepancies = sum(count for cls, count in classes.items() if cls not in ("identical", "absent from both"))
    print("Table S4 %d numeric off-diagonal pairs, transcription %d pairs; identical %d (%d of them differ in decimal "
          "text only, e.g. trailing zeros); discrepancies %d; absent from both %d"
          % (sum(1 for key, v in cells.items() if key[0] != key[1] and v is not None), len(transcription),
             classes["identical"], text_only, discrepancies, classes["absent from both"]))
    for cls in ("A = 0 with the printed B", "A = 0 with another B", "another B", "another A", "another A and B",
                "missing from the transcription", "in the transcription, NA in Table S4"):
        print("  %-40s %d" % (cls, classes[cls]))
    print("  groups in the 'A = 0 with the printed B' class (number: pairs): "
          + ", ".join("%d %s: %d" % (g, GROUPS[g - 1][1], n) for g, n in sorted(groups_with_zero_a.items(), key=lambda item: (-item[1], item[0]))))
    print()
    print("Pilot pairs (groups 5 CH4, 6 C2H6, 12 CO2, 13 N2):")
    for i, j in PAIRS:
        k, l = NUMBER_OF[i], NUMBER_OF[j]
        key = (max(k, l), min(k, l))
        s4, tr = cells[key], transcription.get(key)
        same = tr is not None and s4 is not None and Decimal(s4[0]) == Decimal(tr[0]) and Decimal(s4[1]) == Decimal(tr[1])
        print("  %s: Table S4 A=%s B=%s; transcription A=%s B=%s %s" % (cell_name(*key), s4[0], s4[1], tr[0], tr[1],
                                                                        "IDENTICAL" if same else "DIFFERENT"))
    print()
    print("Discrepancies, row by row:")
    for line in lines:
        print("  " + line)
    return classes


def attraction(constants, t):
    tc, pc, w = constants
    m = 0.37464 + 1.54226 * w - 0.26992 * w * w
    alpha = (1 + m * (1 - math.sqrt(t / tc))) ** 2
    return 0.45724 * R * R * tc * tc / pc * alpha, 0.07780 * R * tc / pc


def parameters(values, i, j):
    k, l = NUMBER_OF[i], NUMBER_OF[j]
    return values[(max(k, l), min(k, l))]


def kij(values, constants, i, j, t):
    va, vb = parameters(values, i, j)
    a, b = float(va), float(vb)
    energy = a * 1e6 * (298.15 / t) ** (b / a - 1)
    ai, bi = attraction(constants[i], t)
    aj, bj = attraction(constants[j], t)
    return (energy - (math.sqrt(ai) / bi - math.sqrt(aj) / bj) ** 2) / (2 * math.sqrt(ai * aj) / (bi * bj))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tsv", required=True)
    parser.add_argument("--csv", required=True)
    parser.add_argument("--record", required=True)
    args = parser.parse_args()
    cells, _ = read_table(args.tsv)
    print("1. Record against Table S4")
    ok, values = check_record(args.record, cells)
    print()
    print("2. Table S4 against the Clapeyron.jl transcription (revision eppr78-2022-clapeyron-0778184-r1), row by row")
    compare_with_transcription(args.csv, cells)
    print()
    print("3. Appendix A from the record's values")
    header = "| Pair | A, B (MPa) | " + " | ".join("%d K" % t for t in TEMPERATURES) + " |"
    for name, constants in (("appendix constants (CoolProp ethane 305.322 K, 4.8722 MPa, 0.0990)", APPENDIX_CONSTANTS),
                            ("pilot record constants (ethane 305.32 K, 4.872 MPa, 0.099)", PILOT)):
        print()
        print("kij(T), " + name)
        print(header)
        print("|---|---|" + "---|" * len(TEMPERATURES))
        exact, worst = 0, 0.0
        for i, j in PAIRS:
            va, vb = parameters(values, i, j)
            cells_out = []
            for k, t in enumerate(TEMPERATURES):
                value = kij(values, constants, i, j, t)
                expected = APPENDIX_A[(i, j)][k]
                same = round(value, 4) == expected or abs(value - expected) <= 5e-5
                exact += same
                worst = max(worst, abs(value - expected))
                cells_out.append("%.4f%s" % (value, "" if same else " (%.5f)" % value))
            print("| %s/%s | %s / %s | %s |" % (i, j, va, vb, " | ".join(cells_out)))
        print("cells equal to appendix A at four decimals: %d of %d; largest |kij - appendix| %.2e" % (exact, 6 * len(TEMPERATURES), worst))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
