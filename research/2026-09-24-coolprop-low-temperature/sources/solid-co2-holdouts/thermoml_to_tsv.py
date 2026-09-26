"""Flatten a NIST TRC ThermoML file into a TSV of numeric values, digits verbatim.

Batch 2026-09-24-coolprop-low-temperature, P4/P5 holdout survey (2026-09-25).
Usage (from this folder):  python thermoml_to_tsv.py thermoml/<file>.xml > <file>.tsv
Standard library only. One output row per property value of each <NumValues> element.
Columns:
  set        nPureOrMixtureDataNumber of the data set
  system     components of the set, ThermoML common names joined with ' + '
  phases     phases present (ePhase), crystal phases tagged with their component
  property   ePropName (TRC's name; TRC files CO2 frost points as a
             'Solid-liquid equilibrium temperature' between a fluid and a crystal)
  method     sMethodName of the property
  value      nPropValue as written in the XML
  U95        nCombExpandUncertValue (expanded uncertainty, 95 % level) when present;
             in TRC files this is usually the compiler's assessment, not the authors'
  then one column per variable and constraint, named '<quantity>[<component>]@<phase>'
  with the value string exactly as stored in the XML. Labels (never values) are abbreviated:
  fluid, liquid, gas, crystal for the ThermoML phase names; CO2, CH4, N2, C2H6 for the compounds.
"""
import sys
import xml.etree.ElementTree as ET

NS = {"t": "http://www.iupac.org/namespaces/ThermoML"}


def text(el, path):
    x = el.find(path, NS)
    return x.text.strip() if x is not None and x.text else ""


def first_leaf_text(el):
    for sub in el.iter():
        if len(sub) == 0 and sub.text and sub.text.strip():
            return sub.text.strip()
    return ""


def main(path):
    root = ET.parse(path).getroot()
    names = {}
    for c in root.findall("t:Compound", NS):
        org = text(c, "t:RegNum/t:nOrgNum")
        names[org] = text(c, "t:sCommonName") or text(c, "t:sFormulaMolec")
    rows, columns = [], []
    for ds in root.findall("t:PureOrMixtureData", NS):
        setno = text(ds, "t:nPureOrMixtureDataNumber")
        comps = [names.get(text(c, "t:RegNum/t:nOrgNum"), "?") for c in ds.findall("t:Component", NS)]
        phases = []
        for p in ds.findall("t:PhaseID", NS):
            ph = text(p, "t:ePhase")
            org = text(p, "t:RegNum/t:nOrgNum")
            phases.append(ph + (f"[{names.get(org, org)}]" if org else ""))
        props = {}
        for p in ds.findall("t:Property", NS):
            num = text(p, "t:nPropNumber")
            pname = method = ""
            pm = p.find("t:Property-MethodID/t:PropertyGroup", NS)
            if pm is not None:
                for sub in pm.iter():
                    tag = sub.tag.split("}")[1]
                    if tag == "ePropName":
                        pname = sub.text.strip()
                    if tag == "sMethodName":
                        method = sub.text.strip()
            org = text(p, "t:Property-MethodID/t:RegNum/t:nOrgNum")
            if org:
                pname += f" [{names.get(org, org)}]"
            ph = text(p, "t:PropPhaseID/t:ePropPhase")
            props[num] = (pname + (f" @{ph}" if ph else ""), method)
        variables = {}
        for v in ds.findall("t:Variable", NS):
            num = text(v, "t:nVarNumber")
            vt = v.find("t:VariableID/t:VariableType", NS)
            q = first_leaf_text(vt) if vt is not None else "?"
            org = text(v, "t:VariableID/t:RegNum/t:nOrgNum")
            ph = text(v, "t:VarPhaseID/t:eVarPhase")
            variables[num] = q + (f"[{names.get(org, org)}]" if org else "") + (f"@{ph}" if ph else "")
        constraints = []
        for c in ds.findall("t:Constraint", NS):
            ct = c.find("t:ConstraintID/t:ConstraintType", NS)
            q = first_leaf_text(ct) if ct is not None else "?"
            org = text(c, "t:ConstraintID/t:RegNum/t:nOrgNum")
            ph = text(c, "t:ConstraintPhaseID/t:eConstraintPhase")
            key = "constraint:" + q + (f"[{names.get(org, org)}]" if org else "") + (f"@{ph}" if ph else "")
            constraints.append((key, text(c, "t:nConstraintValue")))
        for nv in ds.findall("t:NumValues", NS):
            row = {"set": setno, "system": " + ".join(comps), "phases": " | ".join(phases)}
            for k, val in constraints:
                row[k] = val
                if k not in columns:
                    columns.append(k)
            for vv in nv.findall("t:VariableValue", NS):
                key = variables.get(text(vv, "t:nVarNumber"), "var?")
                row[key] = text(vv, "t:nVarValue")
                if key not in columns:
                    columns.append(key)
            for pv in nv.findall("t:PropertyValue", NS):
                pname, method = props.get(text(pv, "t:nPropNumber"), ("?", ""))
                r = dict(row)
                r["property"], r["method"] = pname, method
                r["value"] = text(pv, "t:nPropValue")
                r["U95"] = text(pv, "t:CombinedUncertainty/t:nCombExpandUncertValue") or text(
                    pv, "t:PropUncertainty/t:nExpandUncertValue")
                rows.append(r)
    head = ["set", "system", "phases", "property", "method", "value", "U95"] + columns
    out = sys.stdout
    out.write("\t".join(short(h) for h in head) + "\n")
    for r in rows:
        out.write("\t".join(short(r.get(h, "")) if h in ("system", "phases", "property") else r.get(h, "")
                            for h in head) + "\n")


SHORT = [("Fluid (supercritical or subcritical phases)", "fluid"), ("Liquid", "liquid"), ("Gas", "gas"),
         ("Crystal of unknown type", "crystal(unknown type)"), ("Crystal", "crystal"),
         ("carbon dioxide", "CO2"), ("methane", "CH4"), ("nitrogen", "N2"), ("ethane", "C2H6")]


def short(s):
    """Abbreviate phase and compound names in labels only; numeric values are never touched."""
    for a, b in SHORT:
        s = s.replace(a, b)
    return s


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    main(sys.argv[1])
