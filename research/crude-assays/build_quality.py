"""Generate research-only H/S/N/Ni/V/Fe profiles. Never writes production catalog files."""
from pathlib import Path
import json
import math

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ELEMENTS = ("H", "S", "N", "Ni", "V", "Fe")
INDICATORS = ("c7_asphaltenes", "micro_carbon_residue")
CARBON_NUMBERS = (1, 2, 3, 4, 4, 5, 5)
HYDROGEN = [(2*n+2)*1.008/(12.011*n+1.008*(2*n+2)) for n in CARBON_NUMBERS]


def fraction(value, method, estimated=True):
    if not math.isfinite(value) or not 0 <= value <= 1:
        raise ValueError((value, method))
    return {"mass_fraction": value, "estimated": estimated, "method": method}


def profile(source, quality, row, component_ids):
    masses = row["corrected_residual_source_cut_mass_percent"]
    scale = row["residual_mass_rounding_scale"]
    allocations = row["source_cut_to_pseudocomponent_mass_percent"]
    feed_mass = row["mass_fractions"]
    result = []
    for i, component in enumerate(component_ids):
        elements = {}
        if i < 7:
            elements = {"C": fraction(1-HYDROGEN[i], "chemical_formula", False),
                        "H": fraction(HYDROGEN[i], "chemical_formula", False)}
            for element in ("O", "S", "N", "Ni", "V", "Fe", "Na", "Hg", "As"):
                elements[element] = fraction(0, "chemical_formula", False)
        result.append({"component": component, "elements": elements, "indicators": {}})
    reconciliation = {}
    for element in ELEMENTS:
        field = quality["fields"][element]
        source_values = list(field["cuts_reported_mass_fraction"])
        missing = [i for i, v in enumerate(source_values) if v is None]
        values = [0 if v is None else v for v in source_values]
        # Remove pure pentane elemental mass from the inclusive C5-65 source cut.
        pure_c5_element = (source["light_ends_mass_percent"]["isopentane"] * HYDROGEN[5]
                          + source["light_ends_mass_percent"]["n-pentane"] * HYDROGEN[6]) if element == "H" else 0
        values[0] = (source["cut_mass_percent"][0] * values[0] - pure_c5_element) * scale / masses[0]
        target = field["whole_reported_mass_fraction"]
        exact = math.fsum(w * HYDROGEN[i] for i, w in enumerate(feed_mass[:7])) if element == "H" else 0
        if element == "H":
            if missing != [11]:
                raise ValueError("Hydrogen closure requires only the 550+ cut to be missing")
            known = exact + math.fsum(w*v/100 for w, v in zip(masses[:-1], values[:-1]))
            values[-1] = (target-known)/(masses[-1]/100)
            # Reporting precision only; not a statistical confidence interval or model uncertainty.
            rounding = (.0005 + scale * math.fsum(source["cut_mass_percent"][:-1])/100 * .0005)/(masses[-1]/100)
            reconciliation[element] = {"reported_whole_mass_fraction": target, "factor": 1,
                    "estimated_550_plus_mass_fraction": values[-1], "rounding_only_half_width_mass_fraction": rounding,
                    "method": "whole_hydrogen_balance_after_pure_light_end_subtraction"}
        else:
            modeled = math.fsum(w*v/100 for w, v in zip(masses, values))
            if modeled <= 0 or target <= 0:
                raise ValueError("Cannot reconcile element without positive reported inventory")
            correction = target/modeled
            if not .5 <= correction <= 1.5:
                raise ValueError((element, "Reconciliation exceeds screening limit", correction))
            values = [v * correction for v in values]
            reconciliation[element] = {"reported_whole_mass_fraction": target, "unreconciled_whole_mass_fraction": modeled,
                    "factor": correction, "assumed_zero_source_cut_indices": missing,
                    "method": "reported_cut_pattern_scaled_to_whole_total; unreported_cut_concentrations_assumed_zero"}
        for j in range(13):
            value = math.fsum(allocations[i][j] * values[i] for i in range(12))/(100*feed_mass[j+7])
            method = "source_cut_mass_rebin; uniform_composition_within_source_cut"
            if element == "H":
                method += "; 550_plus_hydrogen_from_whole_balance"
            else:
                method += "; whole_total_reconciliation; missing_source_cuts_assumed_zero"
            result[j+7]["elements"][element] = fraction(value, method)
        total = math.fsum(w * r["elements"][element]["mass_fraction"] for w,r in zip(feed_mass,result))
        if not math.isclose(total, target, rel_tol=1e-12, abs_tol=1e-15):
            raise ValueError((element, "Inventory does not close", total, target))
    for indicator in INDICATORS:
        values = quality["fields"][indicator]["cuts_reported_mass_fraction"]
        for j in range(13):
            contributors = [i for i in range(12) if allocations[i][j] > 0]
            if any(values[i] is None for i in contributors):
                continue  # Do not turn missing analytical indicators into zero.
            value = math.fsum(allocations[i][j] * values[i] for i in contributors)/(100*feed_mass[j+7])
            result[j+7]["indicators"][indicator] = fraction(value, "source_cut_mass_rebin; uniform_within_source_cut; not_a_conserved_element")
    for record in result:
        if math.fsum(v["mass_fraction"] for v in record["elements"].values()) > 1 + 1e-12:
            raise ValueError("Elemental mass exceeds total cut mass")
    return {"schema_version": 1, "revision": "assay-cut-quality-r1", "source": quality["url"] + " (" + quality["reference"] + ")",
            "source_pdf_sha256": quality["pdf_sha256"], "components": result,
            "reconciliation": reconciliation,
            "limitations": ["Reported source numbers may themselves be estimated; no measurement method or analytical uncertainty is supplied.",
                "Pseudocomponent carbon, oxygen, sodium, mercury and arsenic partitioning are unresolved; absent fields are not zero.",
                "Missing nitrogen/metals in source cuts are assumed zero for this estimated profile; reported source zeros are not detection limits.",
                "550+ elemental concentrations are uniform across the estimated heavy tail; no invented NBP enrichment law.",
                "Asphaltenes and micro carbon residue are analytical indicators, not extra mass, elemental carbon or a complete SARA model.",
                "These profiles describe unreacted assay cuts. Reaction, blending and separation must carry updated elemental inventories."]}


def generate():
    sources = json.loads((HERE/"source-data.json").read_text(encoding="utf-8"))["sources"]
    quality = json.loads((HERE/"source-quality.json").read_text(encoding="utf-8"))["sources"]
    converted = json.loads((HERE/"converted-compositions.json").read_text(encoding="utf-8"))
    profiles = {}
    for s,q,row in zip(sources,quality,converted["crudes"]):
        if s["id"] != q["id"] or s["reference"] != row["source_reference"]:
            raise ValueError("Source order mismatch")
        data=profile(s,q,row,converted["components"])
        profiles[row["id"]]=data
        print(row["id"],"estimated residue H wt%",round(data["reconciliation"]["H"]["estimated_550_plus_mass_fraction"]*100,3))
    (HERE/"cut-quality-profiles.json").write_text(json.dumps(profiles,indent=2)+"\n",encoding="utf-8")
    report = ["# Crude-specific elemental profiles on the unchanged 20-component basis", "",
        "Research only: these are candidate cut-chemistry profiles in research/crude-assays, not data loaded by the mod. No Java integration or production assay changes are included. Component IDs, feed amounts, MW, density, PR parameters, heat-capacity coefficients and viscosity curves remain unchanged.", "",
        "## Evidence and estimation", "",
        "The source PDFs report hydrogen, sulfur and nitrogen for several cuts, and selected residue metal and analytical-quality values. The PDFs do not identify every value as experimentally measured, so the transcription calls them reported values. Blanks remain null and printed zeros retain their original meaning; neither is a detection limit.", "",
        "The raw transcription is [source-quality.json](../../crude-assays/source-quality.json), with source PDF hashes and the original twelve disjoint cut positions. Whole-crude summary values take precedence where the cut table rounds them more coarsely. The overlapping 370°C+ aggregate is retained for reference, never added as another cut.", "",
        "Each modeled pseudocomponent value is a mass-weighted rebin of the source cuts using the existing conversion allocation matrix. Exact light chemicals retain formula-based C/H and zero heteroatoms/metals. Pure pentane contributions are subtracted from C5–65°C before assigning the residual cut chemistry.", "",
        "For S, N, Ni, V and Fe, missing source-cut concentrations are explicitly assumed zero, then the reported cut pattern is scaled to match the whole-crude elemental inventory. This is a modeling assumption, not a new measurement. Source values are preserved separately. At very low metal concentrations, rounding produces substantial relative reconciliation factors; WTI nickel requires about 1.328×. These profiles should not be treated as high-precision trace-metal partition measurements.", "",
        "Every 550°C+ source hydrogen entry is missing. It is estimated from H_residue = (H_whole − sum of lighter-cut hydrogen masses) / residue mass, including pure light ends. The same 550°C+ elemental concentration applies throughout the extrapolated tail; no unsupported enrichment curve is introduced.", "",
        "## Hydrogen estimate sensitivity", "",
        "The half-width below propagates only ±0.05 wt% rounding in each reported hydrogen concentration with fixed cut masses. It excludes cut-yield uncertainty, analytical uncertainty and model error; it is not a confidence interval.", "",
        "| Crude | Reported whole H, wt% | Estimated 550°C+ H, wt% | Rounding-only half-width, wt% |", "|---|---:|---:|---:|"]
    for row in converted["crudes"]:
        h=profiles[row["id"]]["reconciliation"]["H"]
        report.append(f"| {row['name']} | {100*h['reported_whole_mass_fraction']:.1f} | {100*h['estimated_550_plus_mass_fraction']:.3f} | ±{100*h['rounding_only_half_width_mass_fraction']:.3f} |")
    report.extend(["", "WTI's small residue fraction makes its inferred hydrogen concentration particularly weakly constrained. The resulting value is not forced to decrease with boiling point.", "",
        "## Unknowns and interpretation", "",
        "Pseudocomponent carbon and oxygen are unresolved. The unassigned mass is largely carbon plus oxygen and untracked material; it must not be silently labeled carbon. Carbon by difference would require an explicit oxygen/other-element assumption. Sodium, mercury and arsenic have reported whole-crude totals but insufficient cut allocation here, so their pseudocomponent fields are absent, not zero.", "",
        "C7 asphaltenes and micro carbon residue are carried as separate analytical indicators only when all contributing source cuts report them. They are not extra feed mass, elemental carbon, a full SARA composition, or guaranteed reactor coke yields. In particular, WTI's rounded whole-crude asphaltene zero is not used to erase its positive reported residue value.", "",
        "Adding these profiles can distinguish future reactor feeds chemically; it does not make the unchanged TJL thermodynamic properties reproduce each real crude's density, VLE, viscosity or heat behavior. Full elemental reaction closure still requires the missing C/O information; kinetics and reaction heats require process-specific datasets.", "",
        "## Modeled cut compositions", "",
        "Values are fractions of each component's mass, not of whole crude. All petroleum pseudocomponent values are estimated mappings; exact chemical values follow their formulas. Full precision, methods, indicators and reconciliation data are in [cut-quality-profiles.json](../../crude-assays/cut-quality-profiles.json)."])
    for element in ELEMENTS:
        factor, unit = (100,"wt%") if element in ("H","S") else (1e6,"ppmw")
        report.extend(["", f"### {element} ({unit})", "", "| Component | " + " | ".join(r["name"] for r in converted["crudes"]) + " |", "|---|" + "---:|"*5])
        for i,component in enumerate(converted["components"]):
            report.append("| "+component+" | "+" | ".join(f"{factor*profiles[r['id']]['components'][i]['elements'][element]['mass_fraction']:.4f}" for r in converted["crudes"])+" |")
    report.extend(["", "## Proposed database use and research reproducibility", "",
        "A future implementation could attach profiles to assay/component identities with explicit unknown elements and a separate quality fingerprint. This is a proposal only: MaterialCatalog has no cut-quality API and the research files are not consumed by the simulation.", "",
        "Future stream handling must transport component-resolved elemental mass, not repeatedly look up the original assay after blending or reaction. For two unreacted lots of the same component, q_mix = (m_A q_A + m_B q_B)/(m_A+m_B); unknown contributions must remain unknown. Downstream reaction caches must include both thermodynamic and quality/model fingerprints.", "",
        "Re-extract with `python research/crude-assays/extract_quality.py <source-PDF-directory>` (pdfplumber required). Regenerate research profiles and this report with `python research/crude-assays/build_quality.py`. Run `python research/crude-assays/test_quality.py`. These tools do not write to src/ and are not called by the production composition converter.", "",
        "## Producer sources", ""])
    for q,row in zip(quality,converted["crudes"]):
        report.append(f"- [{row['name']} — {q['reference']}]({q['url']})")
    (ROOT/"research/crude-regrouping/notes/CUT_ELEMENTAL_PROFILES.md").write_text("\n".join(report)+"\n",encoding="utf-8")


if __name__ == "__main__":
    generate()
