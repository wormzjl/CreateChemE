#!/usr/bin/env python3
"""Summarise Gradle JUnit XML: xmlcases.py <test-results-dir> <out-prefix>
Writes <out-prefix>-cases.tsv (class, test, status, seconds) and <out-prefix>-stdout/<class>.txt (system-out with wall
times, byte counts and allocation masked) and prints totals."""
import os, re, sys, xml.etree.ElementTree as ET
src, out = sys.argv[1], sys.argv[2]
os.makedirs(out + "-stdout", exist_ok=True)
tot = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
rows = []
for f in sorted(os.listdir(src)):
    if not f.endswith(".xml"):
        continue
    s = ET.parse(os.path.join(src, f)).getroot()
    for k in tot:
        tot[k] += int(s.get(k, 0))
    for c in s.findall("testcase"):
        st = "failed" if c.find("failure") is not None or c.find("error") is not None else "skipped" if c.find("skipped") is not None else "passed"
        rows.append((c.get("classname"), c.get("name"), st, c.get("time")))
    so = s.findtext("system-out") or ""
    so = re.sub(r"\b(ms|wall ms|bytes|allocatedMB|nanos|Nanos)=[0-9.]+", r"\1=*", so)
    so = re.sub(r"\b\d+(\.\d+)? ?(ms|s)\b(?![a-zA-Z])", "*time*", so)
    with open(os.path.join(out + "-stdout", s.get("name") + ".txt"), "w") as w:
        w.write(so)
with open(out + "-cases.tsv", "w") as w:
    for r in sorted(rows):
        w.write("\t".join(map(str, r)) + "\n")
print(f"classes={len([f for f in os.listdir(src) if f.endswith('.xml')])} tests={tot['tests']} failures={tot['failures']} errors={tot['errors']} skipped={tot['skipped']}")
for r in sorted(rows):
    if r[2] == "failed":
        print("FAILED", r[0], r[1], r[3], "s")
