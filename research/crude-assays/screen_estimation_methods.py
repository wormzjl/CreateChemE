"""Research-only screening of published correlations. Never writes production catalog files.

Requires pdfplumber and the previously downloaded, hash-verified source PDFs.
"""
from pathlib import Path
import hashlib
import json
import math
import re
import pdfplumber

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def table_rows(path, labels):
    with pdfplumber.open(path) as pdf:
        words = pdf.pages[0].extract_words(x_tolerance=1)
    start = next(w for w in words if w["text"] == "Start" and w["x0"] < 120)
    header = sorted([w for w in words if abs(w["top"]-start["top"]) < 2 and w["x0"] > 140], key=lambda w:w["x0"])
    centers = [(w["x0"]+w["x1"])/2 for w in header]
    c5 = [w["text"] for w in header].index("C5")
    indices = list(range(c5,c5+8))+list(range(c5+9,c5+13))
    result = {}
    for label in labels:
        for first in words:
            if first["top"] <= start["top"] or first["x0"] > 120 or first["text"] != label.split()[0]:
                continue
            row = sorted([w for w in words if abs(w["top"]-first["top"]) < 2], key=lambda w:w["x0"])
            if not " ".join(w["text"] for w in row).startswith(label):
                continue
            cells = [None]*len(centers)
            for word in row:
                if word["x0"] < centers[0]-10 or not re.fullmatch(r"-?\d+(?:\.\d+)?",word["text"]):
                    continue
                x=(word["x0"]+word["x1"])/2
                i=min(range(len(centers)),key=lambda i:abs(x-centers[i]))
                if cells[i] is not None or abs(x-centers[i])>10:
                    raise ValueError((path,label,"Ambiguous numeric cell"))
                cells[i]=float(word["text"])
            result[label]={"whole":cells[0],"cuts":[cells[i] for i in indices]}
            break
        if label not in result:
            raise ValueError((path,"Missing row",label))
    return result


def phillips_hydrogen(sg60, watson_k):
    """US6275776B1 claim 4, wt% H. Caller MUST evaluate applicability separately."""
    return -20.77*(sg60-.8510)+.58*(watson_k-12.5)+14


def crude_saturates(density15, pour_point_c):
    """Processes 2023, 11, 420, equations 10/11; whole-crude estimates only."""
    s = 100-(100/(.2748+5.198*math.exp(-4.787*density15))-239)
    p = pour_point_c
    adjusted = (.30283*s-.25515*p+31.45053+.0052145*s*s+.0028855*s*p
                -.0067996*p*p+.00006159*s*s*p+.000152899*s*p*p-441.77259/s)
    return s, adjusted


def generate():
    sources=json.loads((HERE/"source-data.json").read_text(encoding="utf-8"))["sources"]
    chemistry=json.loads((HERE/"source-quality.json").read_text(encoding="utf-8"))["sources"]
    current=json.loads((HERE/"cut-quality-profiles.json").read_text(encoding="utf-8"))
    output=[]
    for source, quality in zip(sources,chemistry):
        path=ROOT/"research/crude-assay-sources"/(source["id"]+".pdf")
        if hashlib.sha256(path.read_bytes()).hexdigest()!=source["pdf_sha256"]:
            raise ValueError("Source PDF changed")
        rows=table_rows(path,["API Gravity","UOPK","Total Acid Number","Pour Point", "Paraffins", "Naphthenes", "Aromatics"])
        density=source["whole_crude"]["density_15c_kg_per_m3"]/1000
        pp=rows["Pour Point"]["whole"]
        sat10,sat11=crude_saturates(density,pp)
        phillips=phillips_hydrogen(141.5/(rows["API Gravity"]["cuts"][-1]+131.5),rows["UOPK"]["cuts"][-1])
        slug="wti_light_export" if source["id"]=="wti_light" else source["id"]
        probes=[]
        for i,h in enumerate(quality["fields"]["H"]["cuts_reported_mass_fraction"]):
            api,k=rows["API Gravity"]["cuts"][i],rows["UOPK"]["cuts"][i]
            if h is not None and api is not None and k is not None:
                sg=141.5/(api+131.5);pred=phillips_hydrogen(sg,k)
                probes.append({"source_cut_index":i,"reported_h_wt_percent":h*100,"predicted_h_wt_percent":pred,
                               "difference_wt_percent":pred-h*100,"sg_and_k_ranges_pass":.8838<=sg<=1.0736 and 10.3<=k<=12.1,
                               "full_applicability_established":False})
        tan=rows["Total Acid Number"]["whole"]
        output.append({"id":slug,"name":source["name"],"source_url":source["url"],"source_pdf_sha256":source["pdf_sha256"],
            "source_rows":rows,
            "residue_hydrogen":{"current_balance_estimate_wt_percent":current[slug]["reconciliation"]["H"]["estimated_550_plus_mass_fraction"]*100,
                "phillips_extrapolation_wt_percent":phillips,"suitable_for_automatic_replacement":False,
                "reason":"Every isolated 550C+ cut is 100% above 1000F; patent calibration mixtures ranged only to 53.3% above 1000F. SG/K alone do not establish applicability."},
            "reported_cut_hydrogen_probes":probes,
            "whole_crude_saturates":{"density15_g_per_cm3":density,"pour_point_celsius":pp,
                "equation10_wt_percent":sat10,"equation11_wt_percent":sat11,
                "reported_input_ranges_pass":.782<=density<=1.002 and -45.6<=pp<=37.8,
                "note":"Prior estimate, not measured SARA; do not apply the whole-crude equations to isolated cuts."},
            "pna_plus_asphaltenes_diagnostic_wt_percent":sum(rows[k]["whole"] for k in ("Paraffins","Naphthenes","Aromatics"))
                +100*quality["fields"]["c7_asphaltenes"]["whole_reported_mass_fraction"],
            "tan_carboxylic_oxygen_equivalent_wt_percent":tan*2*15.999/(39.0983+15.999+1.008)/10,
            "oxygen_note":"Conditional acid-oxygen equivalent only: all titrated acidity assigned to carboxyl groups. Not total oxygen or an unconditional lower bound."})
    (HERE/"estimation-screening.json").write_text(json.dumps({"research_only":True,"production_profiles_changed":False,
        "references":{"phillips":"https://patents.google.com/patent/US6275776B1/en","saturates":"https://doi.org/10.3390/pr11020420"},"crudes":output},indent=2)+"\n",encoding="utf-8")
    for r in output:
        h=r["residue_hydrogen"];s=r["whole_crude_saturates"]
        print(r["name"],"H balance/Phillips",round(h["current_balance_estimate_wt_percent"],3),round(h["phillips_extrapolation_wt_percent"],3),
              "Sat10/Sat11",round(s["equation10_wt_percent"],2),round(s["equation11_wt_percent"],2),
              "Oacid",round(r["tan_carboxylic_oxygen_equivalent_wt_percent"],5),"PNA+As",round(r["pna_plus_asphaltenes_diagnostic_wt_percent"],2))


if __name__ == "__main__":
    generate()
