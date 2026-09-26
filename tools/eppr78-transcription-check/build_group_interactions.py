"""Builds the bundled E-PPR78 group-interaction record from the Clapeyron.jl transcription (P3 WP2).

Batch 2026-09-24-coolprop-low-temperature. Reads EPPR78_unlike.csv (Clapeyron.jl commit 0778184, MIT), checks its
SHA-256 and git blob hash against MANIFEST.md section 4, maps Clapeyron's group labels onto the scheme eppr78-2022 names
of science.material.Eppr78Groups, and writes materials/group_interactions/eppr78_2022.json with every A and B value
copied verbatim (MPa, as published). Standard library only; the output is byte-for-byte reproducible.

    python build_group_interactions.py --csv <sources>/e-ppr78/clapeyron/EPPR78_unlike.csv --out <file>
    python build_group_interactions.py --csv ... --out <file> --check   # compare with an existing file, write nothing
"""
import argparse
import csv
import hashlib
import io
import re
import sys

UNLIKE_SHA256 = "f20474d2e958447d5e3dc69d7ef20274bd9e3f98abc26a8d1ce913f78fb73e18"
UNLIKE_BLOB = "2045f7ff04cdcd7b790b2c4a426088f0d86840f9"
GROUPS_SHA256 = "dc785106ac3cf04544fb623ba571f29725a51d61224f9db54f5a4af3d361ea8e"
GROUPS_BLOB = "2e2b497e6194fdd37ebcbba9d79743b8095cd0db"
COMMIT = "0778184abbe0de50791b6338cffe3444d4017508"

# (E-PPR78 number, scheme name, Clapeyron label). Numbers and names 1-21 and 28-37 follow science.material.Eppr78Groups
# (Jaubert et al. 2022, Lasala et al. 2020, PPR78 papers); the freon (22-27) and alkyne (38-40) names come from the
# transcription, their order inside each class from the transcription's column order (assumed, not sourced).
GROUPS = [
    (1, "CH3", "CH3"),
    (2, "CH2", "CH2"),
    (3, "CH", "CH"),
    (4, "C", "C"),
    (5, "CH4", "CH4"),
    (6, "C2H6", "C2H6"),
    (7, "CHaro", "CH aro~|~aCH"),
    (8, "Caro", "C aro~|~aC"),
    (9, "Cfused_aromatic", "C fused aromatic rings"),
    (10, "CH2_cyclic", "CH2 cyclic~|~cCH2"),
    (11, "CH_cyclic", "CH cyclic~|~C cyclic~|~cCH~|~cC"),
    (12, "CO2", "CO2"),
    (13, "N2", "N2"),
    (14, "H2S", "H2S"),
    (15, "SH", "SH"),
    (16, "H2O", "H2O"),
    (17, "C2H4", "C2H4"),
    (18, "CH2_alkenic", "CH2 alkenic~|~CH alkenic~|~=CH2~|~CH2=~|~=CH~|~CH="),
    (19, "C_alkenic", "C alkenic~|~=C~|~C="),
    (20, "CH_cycloalkenic", "CH cycloalkenic~|~C cycloalkenic~|~cCH=~|~cC="),
    (21, "H2", "H2"),
    (22, "C2F6", "C2F6"),
    (23, "CF3", "CF3"),
    (24, "CF2", "CF2"),
    (25, "CF_double_bond", "CF2 double bond~|~CF double bond~|~CF2=~|~CF=~|~=CF2~|~=CF"),
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
    (36, "NO2", "NO2~|~N2O4"),
    (37, "N2O", "N2O"),
    (38, "C2H2", "C2H2"),
    (39, "CH_alkynic", "HC=-C-~|~C≡CH~|~CH#C"),
    (40, "C_alkynic", "-C=-C-~|~C≡C~|~C#C"),
]

NUMBER = re.compile(r"-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?")


def git_blob(data):
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def read_pairs(path):
    data = open(path, "rb").read()
    if hashlib.sha256(data).hexdigest() != UNLIKE_SHA256 or git_blob(data) != UNLIKE_BLOB:
        sys.exit("EPPR78_unlike.csv does not match MANIFEST.md section 4 (SHA-256 / git blob)")
    rows = list(csv.reader(io.StringIO(data.decode("utf-8"))))
    if rows[0] != ["Clapeyron Database File"] or rows[1] != ["E-PPR78 unlike Parameters"] or rows[2] != ["species1", "species2", "A", "B"]:
        sys.exit("unexpected header")
    by_label = {label: name for _, name, label in GROUPS}
    pairs, seen = [], set()
    for row in rows[3:]:
        if len(row) != 4:
            sys.exit("malformed row %r" % row)
        a, b, va, vb = row
        if a not in by_label or b not in by_label:
            sys.exit("unmapped group label in %r" % row)
        if not NUMBER.fullmatch(va) or not NUMBER.fullmatch(vb):
            sys.exit("not a JSON number in %r" % row)
        key = frozenset((by_label[a], by_label[b]))
        if len(key) != 2 or key in seen:
            sys.exit("self or duplicate pair %r" % row)
        seen.add(key)
        pairs.append((by_label[a], by_label[b], va, vb))
    return pairs


def text(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def render(pairs):
    out = io.StringIO()
    w = out.write
    w("{\n")
    w('  "schema_version": 1,\n')
    w('  "id": "createcheme:eppr78_2022",\n')
    w('  "revision": "eppr78-2022-clapeyron-0778184-r1",\n')
    w('  "scheme": "eppr78-2022",\n')
    w('  "units": "MPa",\n')
    w('  "source": ' + text(
        "E-PPR78 group-interaction parameters A_kl and B_kl (MPa, the E-PPR78 convention) of the 40 groups of Jaubert, Qian, "
        "Lasala and Privat, Fluid Phase Equilibria 560 (2022) 113456, as transcribed by the Clapeyron.jl database "
        "(database/cubic/EPPR78/EPPR78_unlike.csv). Third-party transcription: the publisher's Table S4 was not acquired and the "
        "open papers do not print the values (P3 design section 4.5), so the grade rests on the functional check against "
        "GERG-2008 bubble points (P3 WP8). Built by tools/eppr78-transcription-check/build_group_interactions.py.") + ",\n")
    w('  "provenance": {\n')
    w('    "transcription": ' + text("Clapeyron.jl (MIT, copyright 2020 Hon Wa Yew and Pierre Walker) commit " + COMMIT
                                     + ", https://raw.githubusercontent.com/ClapeyronThermo/Clapeyron.jl/" + COMMIT
                                     + "/database/cubic/EPPR78/EPPR78_unlike.csv") + ",\n")
    w('    "unlike_file": ' + text("EPPR78_unlike.csv, git blob " + UNLIKE_BLOB + ", SHA-256 " + UNLIKE_SHA256
                                   + ", 355 group pairs, every A and B copied verbatim") + ",\n")
    w('    "groups_file": ' + text("EPPR78_groups.csv, git blob " + GROUPS_BLOB + ", SHA-256 " + GROUPS_SHA256
                                   + "; its decompositions are not used (it splits ethane into 2 CH3, whereas Jaubert et al. 2022 "
                                   "treat ethane as the C2H6 group); the decomposition rule is the spine records' groups field") + ",\n")
    w('    "group_names": ' + text("Numbers and names of groups 1-21 and 28-37 as science.material.Eppr78Groups (Jaubert et al. 2022, "
                                   "Lasala et al. 2020, PPR78 papers); freon (22-27) and alkyne (38-40) names from the transcription's "
                                   "labels, their order inside each class assumed from the transcription's column order") + ",\n")
    w('    "publisher_check": ' + text("Pending: Table S4 of the supporting information of Jaubert et al. 2022 "
                                       "(doi:10.1016/j.fluid.2022.113456) is to be fetched through a browser and compared row by row; "
                                       "the MANIFEST claim that the Lasala 2020 chapter prints the pilot rows was checked and is wrong") + ",\n")
    w('    "zero_a_rows": ' + text("The transcription has pairs with A = 0 and B != 0, for which the formula A (298.15/T)^(B/A - 1) is "
                                   "undefined; the loader refuses to resolve a species pair that needs such a term") + "\n")
    w("  },\n")
    w('  "groups": [\n')
    for i, (number, name, label) in enumerate(GROUPS):
        w('    { "number": %d, "name": %s, "transcription_label": %s }%s\n'
          % (number, text(name), text(label), "," if i + 1 < len(GROUPS) else ""))
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
    parser.add_argument("--csv", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    pairs = read_pairs(args.csv)
    data = render(pairs)
    if args.check:
        existing = open(args.out, "rb").read().replace(b"\r\n", b"\n")  # a core.autocrlf checkout stores CRLF
        print("identical" if existing == data else "DIFFERENT")
        sys.exit(0 if existing == data else 1)
    open(args.out, "wb").write(data)
    print("wrote %s: %d groups, %d pairs, %d bytes, SHA-256 %s" % (args.out, len(GROUPS), len(pairs), len(data), hashlib.sha256(data).hexdigest()))


if __name__ == "__main__":
    main()
