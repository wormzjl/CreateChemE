"""Prepend DWSIM 20-25 C samples, preserving every existing viscosity sample above 25 C."""
from pathlib import Path
import argparse
import json
import math

parser=argparse.ArgumentParser()
parser.add_argument("report",type=Path)
parser.add_argument("--dissolved-report",type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
report=json.loads(args.report.read_text(encoding="utf-8-sig"))
paths={json.loads(p.read_text(encoding="utf-8"))["id"]:p
       for p in (root/"src/main/resources/data/createcheme/materials/properties").glob("*.json")}
pending=[]
for row in report["records"]:
    if row.get("errors"):
        raise ValueError(row["errors"])
    if not row.get("viscosity"):
        continue
    path=paths[row["property_id"]]
    data=json.loads(path.read_text(encoding="utf-8"))
    for phase,extension in row["viscosity"].items():
        curve=data["viscosity"][phase]
        assert extension["temperature_min_kelvin"]==293.15 and extension["temperature_max_kelvin"]==298.15
        assert curve["type"]=="log_table" and curve["temperature_min_kelvin"] in (293.15,298.15)
        old_start=curve["temperatures_kelvin"].index(298.15)
        assert math.isclose(curve["coefficients"][old_start],extension["coefficients"][-1],rel_tol=1e-12)
        curve["temperatures_kelvin"]=extension["temperatures_kelvin"][:-1]+curve["temperatures_kelvin"][old_start:]
        curve["coefficients"]=extension["coefficients"][:-1]+curve["coefficients"][old_start:]
        curve["temperature_min_kelvin"]=293.15
        curve["revision"]="dwsim-viscosity-api-ambient-r2"
        note=" Ambient extension to 293.15 K sampled with the same API; original nodes at and above 298.15 K retained."
        if note not in curve["source"]:curve["source"]+=note
    pending.append((path,data))
for path,data in pending:path.write_text(json.dumps(data,indent=2)+"\n",encoding="utf-8")
(root/"src/test/resources/materials/dwsim-ambient-viscosity-api.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
print(f"Extended {len(pending)} datasets; original samples retained.")
if args.dissolved_report:
    extension=json.loads(args.dissolved_report.read_text(encoding="utf-8-sig"))
    path=root/"src/main/resources/data/createcheme/fluid/dissolved_viscosity.json"
    data=json.loads(path.read_text(encoding="utf-8"))
    curves={c["component"]:c for c in data["curves"]}
    for extra in extension["curves"]:
        curve=curves[extra["component"]]
        start=curve["temperatures_kelvin"].index(298.15)
        assert math.isclose(curve["viscosities_pascal_seconds"][start],extra["viscosities_pascal_seconds"][-1],rel_tol=1e-12)
        curve["temperatures_kelvin"]=extra["temperatures_kelvin"][:-1]+curve["temperatures_kelvin"][start:]
        curve["viscosities_pascal_seconds"]=extra["viscosities_pascal_seconds"][:-1]+curve["viscosities_pascal_seconds"][start:]
        curve["checks"]=extra["checks"]+[c for c in curve["checks"] if c["temperature_kelvin"]>=298.15]
    data["revision"]="dwsim-conditional-solute-ambient-v2"
    path.write_text(json.dumps(data,separators=(',',':'))+"\n",encoding="utf-8")
    print("Extended dissolved-solute reference factors without changing the existing samples.")
