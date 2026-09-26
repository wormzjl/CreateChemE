"""Rebin five source assays onto the fixed TJL20 basis. No thermodynamic properties are refitted."""
from pathlib import Path
import bisect
import copy
import json
import math

ROOT = Path(__file__).resolve().parents[2]
MATERIALS = ROOT / "src/main/resources/data/createcheme/materials"
SOURCE = Path(__file__).with_name("source-data.json")


class AssayCurve:
    """Piecewise-linear measured volume CDF with a mean-constrained exponential unmeasured tail."""
    def __init__(self, source):
        self.temperatures = source["tbp_temperatures_celsius"]
        self.volumes = source["tbp_cumulative_volume_percent"]
        if len(self.temperatures) != len(self.volumes) or self.temperatures[-1] != 590:
            raise ValueError("Expected matched TBP data ending at 590 C")
        if any(b <= a for a, b in zip(self.temperatures, self.temperatures[1:])):
            raise ValueError("TBP temperatures must increase")
        if any(not math.isfinite(v) or v < 0 or v >= 100 for v in self.volumes):
            raise ValueError("Invalid TBP volume fraction")
        if any(b < a for a, b in zip(self.volumes, self.volumes[1:])):
            raise ValueError("TBP cumulative volumes must not decrease")
        self.remaining_550 = 100 - self.measured(550)
        self.remaining_590 = 100 - self.measured(590)
        self.residue_mean = source["residue_550_plus_volume_average_boiling_point_celsius"]
        points = [550, 560, 570, 580, 590]
        known_integral = math.fsum((b - a) * (200 - self.measured(a) - self.measured(b)) / 2
                                  for a, b in zip(points, points[1:]))
        self.tail_scale = ((self.residue_mean - 550) * self.remaining_550 - known_integral) / self.remaining_590
        if not math.isfinite(self.tail_scale) or self.tail_scale <= 0:
            raise ValueError("Reported residue mean cannot support this tail model")

    def measured(self, temperature):
        if not self.temperatures[0] <= temperature <= self.temperatures[-1]:
            raise ValueError("Requested point outside measured TBP interval")
        index = bisect.bisect_left(self.temperatures, temperature)
        if self.temperatures[index] == temperature:
            return self.volumes[index]
        lo, hi = self.temperatures[index - 1:index + 1]
        fraction = (temperature - lo) / (hi - lo)
        return self.volumes[index - 1] + fraction * (self.volumes[index] - self.volumes[index - 1])

    def remaining(self, temperature):
        if math.isinf(temperature) and temperature > 0:
            return 0.0
        if temperature <= 590:
            return 100 - self.measured(temperature)
        return self.remaining_590 * math.exp(-(temperature - 590) / self.tail_scale)


def basis():
    package = json.loads((MATERIALS / "packages/tjl20.json").read_text(encoding="utf-8"))
    properties = {p["id"]: p for path in (MATERIALS / "properties").glob("*.json")
                  for p in [json.loads(path.read_text(encoding="utf-8"))]}
    components = {c["id"]: c for path in (MATERIALS / "components").glob("*.json")
                  for c in [json.loads(path.read_text(encoding="utf-8"))]}
    ids = package["components"]
    if len(ids) != 20 or ids[:7] != ["Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane"]:
        raise ValueError("Converter requires the declared TJL20 component order")
    rows = [properties[id] for id in package["properties"]]
    windows = []
    for id in ids[7:]:
        cut = components[id]["cut"]
        windows.append((cut.get("lower_kelvin", -math.inf) - 273.15,
                        cut.get("upper_kelvin", math.inf) - 273.15))
    if windows[0][0] != -math.inf or windows[-1][1] != math.inf:
        raise ValueError("Basis must cover both open tails")
    for a, b in zip(windows, windows[1:]):
        if abs(a[1] - b[0]) > 1e-9:
            raise ValueError("Pseudocomponent intervals have a gap or overlap")
    return package, rows, windows


def convert(source, specification, properties, windows):
    curve = AssayCurve(source)
    light = source["light_ends_mass_percent"]
    exact = [0.0, light["methane + ethane"], light["propane"], light["isobutane"],
             light["n-butane"], light["isopentane"], light["n-pentane"]]
    cut_mass = list(source["cut_mass_percent"])
    if len(cut_mass) != 12:
        raise ValueError("Expected 12 disjoint C5+ source cuts, excluding the aggregate 370+ row")
    # Explicit C5 chemicals already occur in C5–65 C. Remove them before rebinning the residual.
    cut_mass[0] -= exact[5] + exact[6]
    if any(not math.isfinite(v) or v < 0 for v in cut_mass) or math.fsum(exact) >= 100:
        raise ValueError("Source has inconsistent light ends or cut masses")
    scale = (100 - math.fsum(exact)) / math.fsum(cut_mass)
    if abs(scale - 1) > .005:
        raise ValueError("Rounding correction exceeds 0.5%; inspect source data rather than silently renormalizing")
    corrected = [v * scale for v in cut_mass]
    allocations = []
    for index, (start, end) in enumerate(zip(specification["cut_start_celsius"], specification["cut_end_celsius"])):
        fractions = []
        for low, high in windows:
            if index == 0:
                # Entire residual C5–65 C lies within our first open-ended pseudocomponent.
                if not windows[0][1] >= end:
                    raise ValueError("First basis cut no longer contains the residual C5–65 C interval")
                fraction = 1.0 if low == -math.inf else 0.0
            else:
                a, b = max(start, low), min(math.inf if end is None else end, high)
                if b <= a:
                    fraction = 0.0
                elif end is None:
                    fraction = (curve.remaining(a) - curve.remaining(b)) / curve.remaining_550
                else:
                    fraction = (curve.measured(b) - curve.measured(a)) / (curve.measured(end) - curve.measured(start))
            if fraction < -1e-12:
                raise ValueError("Negative cut allocation")
            fractions.append(max(0, fraction))
        if abs(math.fsum(fractions) - 1) > 1e-12:
            raise ValueError("Source cut allocation does not conserve mass")
        allocations.append([corrected[index] * f for f in fractions])
    pseudo = [math.fsum(row[i] for row in allocations) for i in range(13)]
    mass = [v / 100 for v in exact + pseudo]
    mw = [p["molecular_weight_kg_per_mol"] for p in properties]
    molar_amounts = [w / m for w, m in zip(mass, mw)]
    moles = [n / math.fsum(molar_amounts) for n in molar_amounts]
    volumes = [w / p["standard_liquid_density_kg_per_m3"] for w, p in zip(mass, properties)]
    volume_fractions = [v / math.fsum(volumes) for v in volumes]
    for vector in [mass, moles, volume_fractions]:
        if any(v < 0 or not math.isfinite(v) for v in vector) or abs(math.fsum(vector) - 1) > 1e-12:
            raise ValueError("Invalid converted composition")
    slug = "wti_light_export" if source["id"] == "wti_light" else source["id"]
    return {
        "id": slug, "name": source["name"], "package_id": "createcheme:" + slug + "_tjl20",
        "assay_id": "createcheme:" + slug, "source_reference": source["reference"], "source_url": source["url"],
        "source_pdf_sha256": source["pdf_sha256"], "mass_fractions": mass, "mole_fractions": moles,
        "proxy_standard_liquid_volume_fractions": volume_fractions,
        "proxy_molecular_weight_kg_per_mol": 1 / math.fsum(molar_amounts),
        "proxy_standard_liquid_density_kg_per_m3": 1 / math.fsum(volumes),
        "source_whole_crude": source["whole_crude"], "residual_mass_rounding_scale": scale,
        "corrected_residual_source_cut_mass_percent": corrected,
        "source_cut_to_pseudocomponent_mass_percent": allocations,
        "tail": {"model": "exponential_survival_constrained_by_residue_vabp", "estimated_above_celsius": 590,
                 "scale_celsius": curve.tail_scale, "remaining_at_590_volume_percent": curve.remaining_590,
                 "residue_550_plus_mean_celsius": curve.residue_mean,
                 "estimated_mass_percent_above_590": corrected[-1] * curve.remaining_590 / curve.remaining_550}}


def generate():
    specification = json.loads(SOURCE.read_text(encoding="utf-8"))
    base, properties, windows = basis()
    rows = [convert(source, specification, properties, windows) for source in specification["sources"]]
    assumptions = [
        "Shared TJL20 component properties are retained; these are approximate composition mappings, not new crude-specific characterizations.",
        "Measured individual light-end wt% are retained. Combined methane+ethane is assigned to ethane; methane is zero because the source split is unknown.",
        "Isopentane and N-pentane are subtracted from C5–65C before allocating the remaining cut mass. Aggregate 370C+ yields are never added to their constituent vacuum cuts.",
        "Within each measured mass cut, relative cumulative TBP volume increments allocate mass (constant density within that source cut).",
        "The unmeasured tail above 590°C is exponential, continuous at 590°C, and constrained to reproduce the published 550°C+ volume-average boiling point before rebinning.",
        "Only residual pseudocomponent mass is adjusted for source rounding; measured light ends are not renormalized.",
        "Nitrogen, sulfur and metal totals are metadata embedded in the real bulk cuts, not extra free species. The 20-component model cannot predict contaminant speciation or removal.",
        "The returned standard-liquid volumes/density use the shared surrogate densities, not measured crude-specific densities."]
    output = {"schema_version": 1, "components": base["components"],
              "component_molecular_weights_kg_per_mol": [p["molecular_weight_kg_per_mol"] for p in properties],
              "assumptions": assumptions, "crudes": rows}
    Path(__file__).with_name("converted-compositions.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    index_path = ROOT / "src/main/resources/materials-index.json"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    for row in rows:
        package = copy.deepcopy(base)
        package.update(id=row["package_id"], revision=row["id"].replace("_", "-") + "-tjl20-r1")
        package["advisory_evidence"] = [
            "SHARED_COMPONENT_SURROGATE: source assay rebinned onto TJL20; properties are not characterized for this crude",
            "ASSAY_SOURCE: ExxonMobil " + row["source_reference"] + "; source light-end and cut mass yields retained with rounding adjustment",
            "C1_C2_ALLOCATION: combined methane+ethane assigned to ethane; methane zero; original split is unknown",
            "ESTIMATED_HEAVY_TAIL: above 590C exponential tail constrained by reported 550C+ volume-average boiling point",
            "CONTAMINANTS: whole-crude elemental totals retained as metadata; no chemical speciation or removal model",
            "TRANSPORT_SURROGATE: reused TJL20 component viscosity curves are not crude-specific measurements"]
        assay = {"schema_version": 1, "id": row["assay_id"], "package": row["package_id"],
                 "components": base["components"], "basis": "mass", "amounts": row["mass_fractions"],
                 "provenance": {key: row[key] for key in ["source_reference", "source_url", "source_pdf_sha256",
                     "source_whole_crude", "residual_mass_rounding_scale", "tail"]}}
        # Artistic appearance is maintained independently of the scientific source transcription.
        appearance_path = Path(__file__).with_name("appearances.json")
        if appearance_path.exists():
            appearances = json.loads(appearance_path.read_text(encoding="utf-8"))
            if row["id"] in appearances:
                assay["appearance"] = appearances[row["id"]]
        for kind, data in [("packages", package), ("assays", assay)]:
            relative = f"data/createcheme/materials/{kind}/{row['id']}_tjl20.json"
            (ROOT / "src/main/resources" / relative).write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
            if relative not in index:
                index.append(relative)
    index_path.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")
    report = ["# Five crude assays on the existing 20-component basis", "",
              "These are approximate feed compositions, using the existing seven exact chemicals and thirteen TJL pseudocomponents.",
              "Each new package has its own identity and warnings; the original TJL feed, properties, and neural qualification are unchanged.", "",
              "## Mole composition", "", "Values are mol%, not mass%. Full-precision fractions are in [converted-compositions.json](../../crude-assays/converted-compositions.json).", ""]
    header = "| Component | " + " | ".join(row["name"] for row in rows) + " |"
    divider = "|---|" + "---:|" * len(rows)
    for key, title in [("mole_fractions", None), ("mass_fractions", "Mass composition")]:
        if title:
            report.extend(["", "## " + title, "", "Values are wt% of whole crude.", ""])
        report.extend([header, divider])
        for i, id in enumerate(base["components"]):
            report.append("| " + id + " | " + " | ".join(f"{100 * row[key][i]:.5f}" for row in rows) + " |")
        report.append("| Total (full precision) | " + " | ".join("100.00000" for row in rows) + " |")
    report.extend(["", "## Mapping assumptions", ""] + ["- " + text for text in assumptions])
    report.extend(["", "## Heavy-tail estimation", "",
        "Let R(T)=100−V(T), where V is cumulative TBP vol%. Above 590°C, use R(T)=R(590)exp(−(T−590)/λ).",
        "For the reported 550°C+ volume-average boiling point M, λ=((M−550)R(550)−∫550..590 R(T)dT)/R(590).",
        "The measured part is integrated piecewise linearly. This matches the reported residue mean but does not establish the actual tail shape.", "",
        "| Crude | λ (°C interval) | Estimated whole-crude mass above 590°C | Source density at 15°C, kg/m³ | Shared-model reference-liquid density, kg/m³ |",
        "|---|---:|---:|---:|---:|"])
    for row in rows:
        report.append(f"| {row['name']} | {row['tail']['scale_celsius']:.3f} | {row['tail']['estimated_mass_percent_above_590']:.3f}% | {row['source_whole_crude']['density_15c_kg_per_m3']:.1f} | {row['proxy_standard_liquid_density_kg_per_m3']:.1f} |")
    report.extend(["", "Density differences are a limitation of the fixed property basis (whose reference is 60°F), not evidence that the source assay is wrong.",
                   "", "## Pseudocomponent intervals", "", "These are the catalog's existing estimated midpoint boundaries, not original assay cut definitions.",
                   "", "| ID | Assigned normal-boiling interval (°C) |", "|---|---|"])
    for id, (low, high) in zip(base["components"][7:], windows):
        label = f"below {high:.3f}" if math.isinf(low) else f"above {low:.3f}" if math.isinf(high) else f"{low:.3f}–{high:.3f}"
        report.append(f"| {id} | {label} |")
    report.extend(["", "## Source assays and loading", ""])
    for row in rows:
        report.append(f"- [{row['name']} — {row['source_reference']}]({row['source_url']}): package `{row['package_id']}`, assay `{row['assay_id']}`.")
    report.extend(["", "Source transcriptions and PDF SHA-256 hashes: [source-data.json](../../crude-assays/source-data.json).",
                   "Reproduce with `python research/crude-assays/convert.py`. Validate the converter with `python research/crude-assays/test_convert.py`.",
                   "Select these feeds in the column calculator under Inputs → Input presets. Loading replaces the draft and clears its previous result; no active world input is automatically replaced.",
                   "The full-precision mole fractions are obtained as z_i=(w_i/M_i)/Σ(w_j/M_j); mass-basis assay records use the same runtime conversion.", ""])
    (ROOT / "research/crude-regrouping/notes/CRUDE_ASSAY_CONVERSION.md").write_text("\n".join(report), encoding="utf-8")
    for row in rows:
        print(row["id"], "rounding", round(row["residual_mass_rounding_scale"], 8), "tail scale", round(row["tail"]["scale_celsius"], 3))


if __name__ == "__main__":
    generate()
