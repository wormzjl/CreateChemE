"""Build versioned nitrogen data for the fluid-only package extension from primary references."""
import csv
import io
import json
from pathlib import Path
import urllib.parse
import urllib.request
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/data/createcheme/fluid"
REF = ROOT / "src/test/resources/fluid/reference"


def fetch(name, **state):
    params = dict(ID="C7727379", Action="Data", Digits=12, RefState="DEF", TUnit="K",
                  PUnit="kPa", DUnit="mol/l", HUnit="kJ/mol", WUnit="m/s", VisUnit="uPa*s", STUnit="N/m", **state)
    url = "https://webbook.nist.gov/cgi/fluid.cgi?" + urllib.parse.urlencode(params, safe="*")
    path = REF / name
    if path.exists():
        text = path.read_text(encoding="utf-8")
    else:
        request = urllib.request.Request(url, headers={"User-Agent": "CreateChemE property-reference research"})
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode("utf-8")
        path.write_text(text, encoding="utf-8")
    rows = [r for r in list(csv.reader(io.StringIO(text), delimiter="\t"))[1:] if len(r) >= 14]
    if not rows:
        raise ValueError(f"Missing NIST rows: {url}")
    return rows, url


def shomate(temperature):
    t = np.asarray(temperature) / 1000
    low = 28.98641 + 1.853978*t - 9.647459*t*t + 16.63537*t**3 + .000117/t**2
    high = 19.50583 + 19.88705*t - 8.598535*t*t + 1.369784*t**3 + .527601/t**2
    return np.where(t < .5, low, high)


def curve(rows, pressure):
    return dict(type="log_table", revision="nitrogen-nist-viscosity-r1", source="NIST WebBook nitrogen isobar; source URL in property provenance; reference-pressure approximation",estimated=False,
                temperature_min_kelvin=float(rows[0][0]), temperature_max_kelvin=float(rows[-1][0]),
                pressure_min_pascal=pressure, pressure_max_pascal=pressure, reference_temperature_kelvin=298.15,
                temperatures_kelvin=[float(r[0]) for r in rows], coefficients=[float(r[11])*1e-6 for r in rows])


if __name__ == "__main__":
    gas, gas_source = fetch("nitrogen-gas-isobar.tsv", Type="IsoBar", P=100, TLow=275, THigh=900, TInc=25)
    low, low_source = fetch("nitrogen-gas-low.tsv", Type="IsoTherm", T=273.16, PLow=100, PHigh=100, PInc=1)
    liquid, liquid_source = fetch("nitrogen-liquid-calibration.tsv", Type="IsoBar", P=2000, TLow=90, THigh=100, TInc=10)
    assert all(r[13] == "vapor" for r in gas+low)
    assert all(r[13] == "liquid" for r in liquid)
    temperatures = np.linspace(273.16, 900, 2001)
    fit = np.polynomial.Polynomial.fit(temperatures-298.15, shomate(temperatures), 5).convert()
    checks_t = np.linspace(273.16, 900, 3002)
    maximum_error = float(np.max(np.abs(fit(checks_t-298.15)/shomate(checks_t)-1)))
    assert maximum_error < .001
    prop = dict(schema_version=1, id="createcheme:fluid_nitrogen", component="Nitrogen", revision="fluid-nitrogen-nist-r1",
                source="NIST WebBook nitrogen Shomate Cp fit and viscosity tables; PR constants from CoolProp nitrogen reference page. Nitrogen cross-interactions initially estimated zero.",
                molecular_weight_kg_per_mol=.0280134, normal_boiling_point_kelvin=77.355,
                standard_liquid_density_kg_per_m3=.0280134/(float(liquid[0][3])*.001),
                standard_temperature_kelvin=90, standard_pressure_pascal=2000000,
                temperature_min_kelvin=273.16, temperature_max_kelvin=900, estimated_heavy_residue=False,
                ideal_gas_cp=dict(type="shifted_polynomial_5",reference_kelvin=298.15,coefficients=fit.coef.tolist()),
                models=dict(pr78=dict(critical_temperature_kelvin=126.192,critical_pressure_pascal=3395800,acentric_factor=.0372)),
                viscosity=dict(liquid=curve(liquid,2000000),vapor=curve(low+gas,100000)),
                provenance=dict(cp="https://webbook.nist.gov/cgi/cbook.cgi?ID=C7727379&Mask=1#Thermo-Gas",
                                critical="https://coolprop.org/fluid_properties/fluids/Nitrogen.html",gas=gas_source,low=low_source,liquid=liquid_source))
    (OUT / "nitrogen_property.json").write_text(json.dumps(prop,indent=2),encoding="utf-8")
    calibration=json.loads((OUT/"liquid_calibration.json").read_text(encoding="utf-8-sig"))
    calibration["points"]=[r for r in calibration["points"] if r["component"]!="Nitrogen"]
    calibration["points"].append(dict(component="Nitrogen",temperatureKelvin=90,pressurePascal=2000000,
                                      molarVolumeCubicMetres=float(liquid[0][3])*.001,source=liquid_source))
    (OUT/"liquid_calibration.json").write_text(json.dumps(calibration,indent=2),encoding="utf-8")
    checks=dict(maximumCpFitRelativeError=maximum_error,points=[dict(temperatureKelvin=float(t),referenceCp=float(shomate(t))) for t in checks_t[::100]])
    (REF/"nitrogen-cp-checks.json").write_text(json.dumps(checks,indent=2),encoding="utf-8")
    print("Nitrogen Cp maximum relative fit error:",maximum_error)
