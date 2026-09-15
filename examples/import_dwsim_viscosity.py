"""Import an audited Export-DwsimViscosity.cs report; no DWSIM runtime is used by the mod."""
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("report", type=Path)
parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
args = parser.parse_args()
report = json.loads(args.report.read_text(encoding="utf-8-sig"))
properties = args.root / "src/main/resources/data/createcheme/materials/properties"
by_id = {json.loads(p.read_text(encoding="utf-8"))["id"]: p for p in properties.glob("*.json")}
updated = 0
for row in report["records"]:
    if not row.get("viscosity"):
        continue
    if row.get("errors"):
        raise ValueError(f"API errors for {row['property_id']}: {row['errors']}")
    path = by_id[row["property_id"]]
    data = json.loads(path.read_text(encoding="utf-8"))
    data["viscosity"] = row["viscosity"]
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    updated += 1
fixture = args.root / "src/test/resources/materials/dwsim-viscosity-api.json"
fixture.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(f"Imported viscosity for {updated} property datasets; saved independent API check points.")
