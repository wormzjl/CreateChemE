"""Reproduce the independent NIST vapor compressibility checkpoints (offline developer tool)."""
import concurrent.futures
import csv
import io
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / "src/test/resources/fluid/reference"


def fetch(point):
    temperature = point["temperatureKelvin"]
    pressure = point["partialPressurePascal"]
    # Backward differences remain on the vapor side close to saturation.
    params = dict(ID="C7732185", Action="Data", Type="IsoTherm", Digits=12,
                  PLow=pressure * .98 / 1000, PHigh=pressure / 1000,
                  PInc=pressure * .01 / 1000, T=temperature, RefState="DEF",
                  TUnit="K", PUnit="kPa", DUnit="mol/l", HUnit="kJ/mol",
                  WUnit="m/s", VisUnit="uPa*s", STUnit="N/m")
    url = "https://webbook.nist.gov/cgi/fluid.cgi?" + urllib.parse.urlencode(params, safe="*")
    cache = REFERENCE / f"steam-{temperature:g}K-{pressure:g}Pa.tsv"
    if cache.exists():
        data = cache.read_text(encoding="utf-8")
    else:
        for attempt in range(3):
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "CreateChemE property-reference research"})
                with urllib.request.urlopen(request, timeout=25) as response:
                    data = response.read().decode("utf-8")
                break
            except Exception:
                if attempt == 2:
                    raise
                time.sleep(1)
        cache.write_text(data, encoding="utf-8")
    rows = list(csv.reader(io.StringIO(data), delimiter="\t"))[1:]
    rows = [r for r in rows if len(r) >= 14 and r[13] == "vapor"]
    if len(rows) != 3:
        raise ValueError(f"Expected three vapor points: {url}: {rows}")
    volumes = [float(r[3]) * .001 for r in rows]
    dp = (float(rows[2][1]) - float(rows[1][1])) * 1000
    derivative = (3 * volumes[2] - 4 * volumes[1] + volumes[0]) / (2 * dp)
    return dict(temperatureKelvin=temperature, partialPressurePascal=pressure,
                referenceCompressibility=-derivative/volumes[2],
                idealCompressibility=1/pressure, source=url, raw=cache.name)


if __name__ == "__main__":
    points = json.loads((REFERENCE / "water-vapor-checks.json").read_text(encoding="utf-8"))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        results = list(executor.map(fetch, points))
    (REFERENCE / "water-vapor-compression.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    for result in results:
        error = abs(result["idealCompressibility"] / result["referenceCompressibility"] - 1)
        print(result["temperatureKelvin"], result["partialPressurePascal"], error)
