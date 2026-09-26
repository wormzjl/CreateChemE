"""Builds the bundled E-PPR78 group-interaction record from the publisher's Table S4 (P3 WP2b).

Batch 2026-09-24-coolprop-low-temperature. Reads jaubert-2022-si-table-s4.tsv, the extraction of Table S4 of the Supporting
Information of Jaubert, Qian, Lasala, Privat 2022 (Fluid Phase Equilibria 560, 113456) made by
tools/eppr78-table-s4-extraction from the MathType objects of the publisher's docx. Checks the TSV's SHA-256 (and, when
they lie next to it, the docx's and the article PDF's), checks that the TSV is the complete lower triangle of the 40 groups
(820 cells, diagonal 0, A and B both numeric or both NA), maps each group number 1..40 onto the scheme eppr78-2022 name
of science.material.Eppr78Groups (the label printed in the table must be the one listed below), and writes
materials/group_interactions/eppr78_2022.json with one pair per numeric off-diagonal cell, A and B with the table's
decimal text (MPa, as published). NA cells are absent. Standard library only; the output is byte-for-byte reproducible.

    python build_group_interactions_s4.py --tsv <sources>/e-ppr78/jaubert-2022-si-table-s4.tsv --out <file>
    python build_group_interactions_s4.py --tsv ... --out <file> --check   # compare with an existing file, write nothing

Revision eppr78-2022-clapeyron-0778184-r1 (the Clapeyron.jl transcription) was built by build_group_interactions.py;
check_pilot_kij.py compares the two sources row by row.
"""
import argparse
import hashlib
import io
import os
import re
import sys

TSV_SHA256 = "bd347cdaa070c14b14e3c8c46e00bd8af77079e4ae996b5c055c247f7c417682"
DOCX_NAME = "jaubert-2022-si-mmc1.docx"
DOCX_SHA256 = "bd068feb989e5d47d02f90960f08ef75a05e82a9a1bb9fe93554c9ec9f4613c1"
DOCX_BYTES = 2837469
PDF_NAME = "jaubert-2022-fpe-560-113456-publisher.pdf"
PDF_SHA256 = "f63f0da9d5ddf3a641714fb2343a4f1bc8c866dd185c463e4ee2ac6c4b817618"
PDF_BYTES = 3921337
REVISION = "eppr78-2022-si-table-s4-r2"
R1_REVISION = "eppr78-2022-clapeyron-0778184-r1"
R1_SHA256 = "f20474d2e958447d5e3dc69d7ef20274bd9e3f98abc26a8d1ce913f78fb73e18"
HEADER = ["k", "l", "group_k", "group_l", "Akl_MPa", "Bkl_MPa"]

# (E-PPR78 number, scheme name of science.material.Eppr78Groups, the label as Table S4 prints it in the TSV).
GROUPS = [
    (1, "CH3", "CH3"),
    (2, "CH2", "CH2"),
    (3, "CH", "CH"),
    (4, "C", "C"),
    (5, "CH4", "CH4"),
    (6, "C2H6", "C2H6"),
    (7, "CHaro", "CHaro"),
    (8, "Caro", "Caro"),
    (9, "Cfused_aromatic", "Cfused aromatic rings"),
    (10, "CH2_cyclic", "CH2,cyclic"),
    (11, "CH_cyclic", "CHcyclic/Ccyclic"),
    (12, "CO2", "CO2"),
    (13, "N2", "N2"),
    (14, "H2S", "H2S"),
    (15, "SH", "SH"),
    (16, "H2O", "H2O"),
    (17, "C2H4", "C2H4"),
    (18, "CH2_alkenic", "CH2,alkenic/CHalkenic"),
    (19, "C_alkenic", "Calkenic"),
    (20, "CH_cycloalkenic", "CHcycloalkenic/Ccycloalkenic"),
    (21, "H2", "H2"),
    (22, "C2F6", "C2F6"),
    (23, "CF3", "CF3"),
    (24, "CF2", "CF2"),
    (25, "CF_double_bond", "CF2,double bond or CFdouble bond"),
    (26, "C2H4F2", "C2H4F2"),
    (27, "C2H2F4", "C2H2F4"),
    (28, "CO", "CO"),
    (29, "He", "He"),
    (30, "Ar", "Ar"),
    (31, "SO2", "SO2"),
    (32, "O2", "O2"),
    (33, "NO", "NO"),
    (34, "COS", "COS"),
    (35, "NH3", "NH3"),
    (36, "NO2", "NO2/N2O4"),
    (37, "N2O", "N2O"),
    (38, "C2H2", "C2H2"),
    (39, "CH_alkynic", "HC≡C-"),
    (40, "C_alkynic", "-C≡C-"),
]
NAME = {number: name for number, name, _ in GROUPS}

NUMBER = re.compile(r"-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?")


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def check_companions(tsv_path):
    """The docx and the article PDF, when they lie next to the TSV: size and SHA-256 as in MANIFEST.md section 5."""
    folder = os.path.dirname(os.path.abspath(tsv_path))
    found = []
    for name, digest, size in ((DOCX_NAME, DOCX_SHA256, DOCX_BYTES), (PDF_NAME, PDF_SHA256, PDF_BYTES)):
        path = os.path.join(folder, name)
        if not os.path.exists(path):
            continue
        if os.path.getsize(path) != size or sha256(path) != digest:
            sys.exit("%s does not match MANIFEST.md section 5 (size / SHA-256)" % name)
        found.append(name)
    return found


def read_table(path):
    """The TSV's cells {(k, l): (A text, B text), or None for NA}, k >= l, checked complete; and its numeric pairs.

    Pairs are (first name, second name, A text, B text), first the lower-numbered group, ordered by
    (first number, second number).
    """
    data = open(path, "rb").read()
    if hashlib.sha256(data).hexdigest() != TSV_SHA256:
        sys.exit("jaubert-2022-si-table-s4.tsv does not match MANIFEST.md section 5 (SHA-256)")
    lines = data.decode("utf-8").split("\n")
    if lines[-1] != "":
        sys.exit("the TSV does not end with a newline")
    rows = [line.split("\t") for line in lines[:-1] if not line.startswith("#")]
    if rows[0] != HEADER:
        sys.exit("unexpected header %r" % rows[0])
    label = {number: printed for number, _, printed in GROUPS}
    cells = {}
    for row in rows[1:]:
        if len(row) != 6:
            sys.exit("malformed row %r" % row)
        k, l, group_k, group_l, a, b = row
        if not (k.isdigit() and l.isdigit()):
            sys.exit("group numbers not integers in %r" % row)
        k, l = int(k), int(l)
        if not (1 <= l <= k <= len(GROUPS)):
            sys.exit("cell outside the lower triangle in %r" % (row,))
        if group_k != label[k] or group_l != label[l]:
            sys.exit("group label mismatch in %r: expected %r / %r" % (row, label[k], label[l]))
        if (k, l) in cells:
            sys.exit("duplicate cell %d-%d" % (k, l))
        if (a == "NA") != (b == "NA"):
            sys.exit("half-NA cell in %r" % (row,))
        if a == "NA":
            if k == l:
                sys.exit("NA on the diagonal in %r" % (row,))
            cells[(k, l)] = None
            continue
        if not NUMBER.fullmatch(a) or not NUMBER.fullmatch(b):
            sys.exit("not a JSON number in %r" % (row,))
        if k == l and (a != "0" or b != "0"):
            sys.exit("nonzero diagonal in %r" % (row,))
        cells[(k, l)] = (a, b)
    expected = len(GROUPS) * (len(GROUPS) + 1) // 2
    if len(cells) != expected:
        sys.exit("expected %d lower-triangle cells, got %d" % (expected, len(cells)))
    pairs = []
    for (k, l), value in sorted(cells.items(), key=lambda item: (item[0][1], item[0][0])):
        if k == l or value is None:
            continue
        pairs.append((NAME[l], NAME[k], value[0], value[1]))
    return cells, pairs


def text(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def render(cells, pairs):
    na = sum(1 for (k, l), v in cells.items() if k != l and v is None)
    zero_zero = [(a, b) for a, b, va, vb in pairs if float(va) == 0.0 and float(vb) == 0.0]
    zero_a = [(a, b) for a, b, va, vb in pairs if float(va) == 0.0 and float(vb) != 0.0]
    if zero_a:
        sys.exit("Table S4 pairs with A = 0 and B != 0: %r (the provenance texts below assume none)" % zero_a)
    if zero_zero != [("CF3", "CF2")] or len(pairs) != 356 or na != 424:
        sys.exit("unexpected table shape (pairs %d, NA %d, A = B = 0 pairs %r); revise the provenance texts" % (len(pairs), na, zero_zero))
    out = io.StringIO()
    w = out.write
    w("{\n")
    w('  "schema_version": 1,\n')
    w('  "id": "createcheme:eppr78_2022",\n')
    w('  "revision": "' + REVISION + '",\n')
    w('  "scheme": "eppr78-2022",\n')
    w('  "units": "MPa",\n')
    w('  "source": ' + text(
        "E-PPR78 group-interaction parameters A_kl = A_lk and B_kl = B_lk (MPa, the E-PPR78 convention) of the 40 groups of "
        "Jaubert, Qian, Lasala and Privat, Fluid Phase Equilibria 560 (2022) 113456, doi:10.1016/j.fluid.2022.113456: Table S4 "
        "of the publisher's Supporting Information, every numeric cell with its printed decimal text. Built by "
        "tools/eppr78-transcription-check/build_group_interactions_s4.py (P3 WP2b).") + ",\n")
    w('  "provenance": {\n')
    w('    "supporting_information": ' + text(
        "Elsevier file 1-s2.0-S0378381222000814-mmc1.docx, " + format(DOCX_BYTES, ",") + " bytes, SHA-256 " + DOCX_SHA256
        + ", kept as research/2026-09-24-coolprop-low-temperature/sources/e-ppr78/" + DOCX_NAME + " of the main checkout; the "
        "article PDF " + PDF_NAME + ", " + format(PDF_BYTES, ",") + " bytes, SHA-256 " + PDF_SHA256 + ". Both provided by "
        "the owner; the article states open access under CC BY 4.0, the Supporting Information file states no licence; the "
        "files are kept for non-commercial research and not redistributed, the record carries the values with this citation") + ",\n")
    w('    "extraction": ' + text(
        "MathType WMF text records of the docx, tools/eppr78-table-s4-extraction: all 820 lower-triangle cells of Table S4 "
        "parsed into jaubert-2022-si-table-s4.tsv (SHA-256 " + TSV_SHA256 + "), 40 diagonal zeros, "
        + str(len(pairs)) + " numeric off-diagonal cells, " + str(na) + " NA, decimals verbatim") + ",\n")
    w('    "pairs": ' + text(
        str(len(pairs)) + " group pairs, one per numeric off-diagonal cell, first = the lower-numbered group, A and B with the "
        "table's decimal text; an NA cell is absent from the matrix, so a species pair that needs it is refused at load") + ",\n")
    w('    "group_names": ' + text(
        "Table S4 prints the 40 group labels in E-PPR78 number order; group k of the table is group k of "
        "science.material.Eppr78Groups (names unchanged), which confirms the numbers of the freon groups 22-27 and the "
        "alkyne groups 38-40 that revision " + R1_REVISION + " had assumed from the transcription's column order") + ",\n")
    w('    "zero_terms": ' + text(
        "CF3/CF2 (groups 23/24) is printed A = B = 0.000, a zero term the loader drops. No pair has A = 0 with B != 0, for "
        "which A (298.15/T)^(B/A - 1) is undefined; the loader keeps refusing such a term as a guard") + ",\n")
    w('    "history": ' + text(
        "Revision " + R1_REVISION + " (P3 WP2, 2026-09-24) was the Clapeyron.jl transcription EPPR78_unlike.csv (commit "
        "0778184, SHA-256 " + R1_SHA256 + ", 355 pairs), used while Table S4 was unavailable. Against Table S4, 274 of its "
        "pairs were identical and 83 differed: 75 carried A = 0 with the printed B, 3 carried A = 0 with a different B, 2 a "
        "different B, 2 pairs were missing and 1 is NA in the table. The six pilot pairs (CH4, C2H6, CO2, N2) were "
        "identical, so no value a bundled package uses changed. Row-by-row comparison: "
        "tools/eppr78-transcription-check/check_pilot_kij.py") + "\n")
    w("  },\n")
    w('  "groups": [\n')
    for i, (number, name, _) in enumerate(GROUPS):
        w('    { "number": %d, "name": %s }%s\n' % (number, text(name), "," if i + 1 < len(GROUPS) else ""))
    w("  ],\n")
    w('  "pairs": [\n')
    for i, (a, b, va, vb) in enumerate(pairs):
        w('    { "first": %s, "second": %s, "a_mpa": %s, "b_mpa": %s }%s\n'
          % (text(a), text(b), va, vb, "," if i + 1 < len(pairs) else ""))
    w("  ]\n")
    w("}\n")
    return out.getvalue().encode("utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tsv", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    companions = check_companions(args.tsv)
    cells, pairs = read_table(args.tsv)
    data = render(cells, pairs)
    if args.check:
        existing = open(args.out, "rb").read().replace(b"\r\n", b"\n")  # a core.autocrlf checkout stores CRLF
        print("identical" if existing == data else "DIFFERENT")
        sys.exit(0 if existing == data else 1)
    open(args.out, "wb").write(data)
    print("verified %s; wrote %s: %d groups, %d pairs, %d bytes, SHA-256 %s"
          % (", ".join(["TSV"] + companions), args.out, len(GROUPS), len(pairs), len(data), hashlib.sha256(data).hexdigest()))


if __name__ == "__main__":
    main()
