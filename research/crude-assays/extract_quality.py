"""Extract assay quality rows by PDF column position, preserving blanks as unknowns.

Requires pdfplumber. Run with the source PDF directory as an optional argument.
"""
from pathlib import Path
import hashlib
import json
import re
import sys
import pdfplumber

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
FIELDS = {
    "H": ("Hydrogen (% wt)", .01),
    "S": ("Total Sulfur (% wt)", .01),
    "N": ("Total Nitrogen (ppm)", 1e-6),
    "Ni": ("Nickel (ppm)", 1e-6),
    "V": ("Vanadium (ppm)", 1e-6),
    "Fe": ("Iron (ppm)", 1e-6),
    "Na": ("Sodium (ppm)", 1e-6),
    "Hg": ("Mercury (ppb)", 1e-9),
    "As": ("Arsenic (ppb)", 1e-9),
    "c7_asphaltenes": ("C7 Asphaltenes (% wt)", .01),
    "micro_carbon_residue": ("Micro Carbon Residue (% wt)", .01),
}


def extract(directory):
    spec = json.loads((HERE / "source-data.json").read_text(encoding="utf-8"))
    result = []
    for source in spec["sources"]:
        path = directory / (source["id"] + ".pdf")
        if hashlib.sha256(path.read_bytes()).hexdigest() != source["pdf_sha256"]:
            raise ValueError(f"Source hash changed: {path}")
        with pdfplumber.open(path) as pdf:
            words = pdf.pages[0].extract_words(x_tolerance=1)
        start = next(w for w in words if w["text"] == "Start" and w["x0"] < 120)
        header = sorted([w for w in words if abs(w["top"] - start["top"]) < 2 and w["x0"] > 140], key=lambda w: w["x0"])
        labels = [w["text"] for w in header]
        if labels not in [["IBP", "IBP", "C5", "65", "100", "150", "200", "250", "300", "350", "370", "370", "450", "500", "550"],
                          ["IBP", "C5", "65", "100", "150", "200", "250", "300", "350", "370", "370", "450", "500", "550"]]:
            raise ValueError((source["id"], labels))
        centers = [(w["x0"] + w["x1"]) / 2 for w in header]
        c5 = labels.index("C5")
        indices = list(range(c5, c5 + 8)) + list(range(c5 + 9, c5 + 13))
        fields = {}
        for name, (label, scale) in FIELDS.items():
            match = None
            for first in words:
                if first["top"] <= start["top"] or first["x0"] > 120 or first["text"] != label.split()[0]:
                    continue
                row = sorted([w for w in words if abs(w["top"] - first["top"]) < 2], key=lambda w: w["x0"])
                if " ".join(w["text"] for w in row).startswith(label):
                    match = row
                    break
            if match is None:
                raise ValueError((source["id"], "Missing quality row", label))
            values = [None] * len(centers)
            for word in match:
                if word["x0"] < centers[0] - 10 or not re.fullmatch(r"\d+(?:\.\d+)?", word["text"]):
                    continue
                x = (word["x0"] + word["x1"]) / 2
                index = min(range(len(centers)), key=lambda i: abs(x - centers[i]))
                if abs(x - centers[index]) > 10 or values[index] is not None:
                    raise ValueError((source["id"], label, "Ambiguous cell", word))
                values[index] = float(word["text"]) * scale
            fields[name] = {"whole_reported_mass_fraction": values[0],
                            "cuts_reported_mass_fraction": [values[i] for i in indices],
                            "residue_370_plus_reported_mass_fraction": values[c5 + 8]}
        # Prefer the more precise summary values already verified in the composition transcription.
        for name, key, scale in [("S", "sulfur_mass_percent", .01), ("N", "nitrogen_mass_ppm", 1e-6),
                ("Ni", "nickel_mass_ppm", 1e-6), ("V", "vanadium_mass_ppm", 1e-6), ("Fe", "iron_mass_ppm", 1e-6)]:
            fields[name]["whole_reported_mass_fraction"] = source["whole_crude"][key] * scale
        result.append({"id": source["id"], "reference": source["reference"], "url": source["url"],
                       "pdf_sha256": source["pdf_sha256"], "fields": fields})
        print(source["id"], "H cut wt%", [None if v is None else round(v * 100, 3) for v in fields["H"]["cuts_reported_mass_fraction"]])
    output = {"schema_version": 1, "units": "kg/kg of source cut or whole crude, as labeled", "missing": "null means not reported; printed zeros retained as reported zeros, not detection limits",
              "cut_start_celsius": spec["cut_start_celsius"], "cut_end_celsius": spec["cut_end_celsius"], "sources": result}
    (HERE / "source-quality.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    extract(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "research/crude-assay-sources")
