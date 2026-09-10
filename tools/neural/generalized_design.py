"""Reproducible mixed structural / constrained-mixture column experiment.

Only Python's standard library is required.  The 2,809-point latent design is a
prime-field strength-two orthogonal-array Latin hypercube.  Its finite structural
part enumerates all 2,520 requested cells, including explicitly excluded cells.
No numerical solver outcome is used to select, split, or remove a design point.
"""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import hashlib
import itertools
import json
import math
from pathlib import Path
import random


REVISION = "generalized-oa53-mixture-v1"
DEFAULT_SEED = 20260910
PRIME = 53
REPOSITORY = Path(__file__).resolve().parents[2]
DEFAULT_BASELINE = REPOSITORY / "tools/neural/methane-qualification.json"
SCALAR_FACTORS = [
    "feed_flow", "feed_temperature", "condenser_temperature", "top_pressure",
    "pressure_drop", "reflux_ratio", "steam_rate", "steam_temperature",
    "reboiler_duty", "feed_location",
]
EQUIPMENT_FACTORS = [
    *(f"side_{i}_{part}" for i in range(3) for part in ("location", "rate")),
    *(f"pa_{i}_{part}" for i in range(4) for part in ("return", "span", "duty")),
]


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def orthogonal_latin_hypercube(prime, dimensions, seed):
    """Construct OA(p^2,d,p,2), then Latinize within each symbol.

    Columns a, b, a+b, a+2b,... are pairwise independent over F_p. Independent
    symbol and within-symbol permutations preserve that property. Exact marginal
    endpoints replace only the smallest/largest point in the same strata.
    """
    if prime < 2 or any(prime % d == 0 for d in range(2, math.isqrt(prime) + 1)):
        raise ValueError("OA order must be prime")
    if not 1 <= dimensions <= prime + 1:
        raise ValueError("Strength-two OA requires dimensions <= prime+1")
    rng = random.Random(seed)
    count = prime * prime
    points = [[0.0] * dimensions for _ in range(count)]
    for column in range(dimensions):
        symbols = list(range(prime))
        rng.shuffle(symbols)
        members = [[] for _ in range(prime)]
        for row, (a, b) in enumerate(itertools.product(range(prime), repeat=2)):
            raw = a if column == 0 else b if column == 1 else (a + (column - 1) * b) % prime
            members[symbols[raw]].append(row)
        for symbol, rows in enumerate(members):
            rng.shuffle(rows)
            for within, row in enumerate(rows):
                points[row][column] = (symbol * prime + within + 0.5) / count
        points[min(range(count), key=lambda r: points[r][column])][column] = 0.0
        points[max(range(count), key=lambda r: points[r][column])][column] = 1.0
    rng.shuffle(points)
    return points


def verify_oa(points, prime):
    """Verify every marginal stratum and every two-factor coarse stratum."""
    count, dimensions = len(points), len(points[0])
    if count != prime * prime:
        raise AssertionError("Wrong number of OA rows")
    fine = [[min(count - 1, int(row[d] * count)) for row in points] for d in range(dimensions)]
    coarse = [[min(prime - 1, int(row[d] * prime)) for row in points] for d in range(dimensions)]
    for column in fine:
        if len(set(column)) != count:
            raise AssertionError("Latin marginal is incomplete")
    for a, b in itertools.combinations(range(dimensions), 2):
        if len(set(zip(coarse[a], coarse[b]))) != count:
            raise AssertionError("Strength-two pair is incomplete")
    return {"rows": count, "dimensions": dimensions, "prime": prime,
            "latinMarginalsVerified": dimensions,
            "orthogonalPairsVerified": dimensions * (dimensions - 1) // 2,
            "strataConvention": "half-open bins, with the exact endpoint 1 in the final bin"}


def bounded_mixture(reference, unit_coordinates):
    """Project all-component relative perturbations onto a bounded simplex.

    Every component follows the same formula.  The common scalar multiplier
    enforces sum(z)=1; there is no named light component or residual/slack species.
    """
    if len(reference) != len(unit_coordinates) or any(x <= 0.0 for x in reference):
        raise ValueError("This relative-mixture design requires a positive reference for every component")
    total = math.fsum(reference)
    fractions = [x / total for x in reference]
    raw = [0.8 + 0.4 * u for u in unit_coordinates]
    low, high = -0.4, 0.4
    for _ in range(70):
        shift = (low + high) / 2.0
        norm = math.fsum(z * min(1.2, max(0.8, r - shift)) for z, r in zip(fractions, raw))
        if norm > 1.0:
            low = shift
        else:
            high = shift
    shift = (low + high) / 2.0
    result = [z * min(1.2, max(0.8, r - shift)) for z, r in zip(fractions, raw)]
    if abs(math.fsum(result) - 1.0) > 2e-14:
        raise AssertionError("Mixture normalization failed")
    return result


def split_for(stage_count):
    # Hold out whole stage counts, including every equipment cell and replicate.
    return "test" if stage_count % 7 == 0 else "validation" if stage_count % 7 == 3 else "train"


def water_saturation_pressure(temperature):
    """The same pinned IAPWS Wagner-Pruss relation as V3WaterProperties."""
    if not 273.16 <= temperature <= 647.096:
        raise ValueError("Water saturation temperature outside the correlation domain")
    theta = 1.0 - temperature / 647.096
    polynomial = (-7.85951783 * theta + 1.84408259 * theta ** 1.5
                  - 11.7866497 * theta ** 3 + 22.6807411 * theta ** 3.5
                  - 15.9618719 * theta ** 4 + 1.80122502 * theta ** 7.5)
    return 22.064e6 * math.exp(647.096 / temperature * polynomial)


def water_saturation_temperature(pressure):
    if not water_saturation_pressure(273.16) <= pressure <= 22.064e6:
        raise ValueError("Water saturation pressure outside the correlation domain")
    low, high = 273.16, 647.096
    for _ in range(80):
        middle = 0.5 * (low + high)
        if water_saturation_pressure(middle) < pressure:
            low = middle
        else:
            high = middle
    return 0.5 * (low + high)


def screen_input(input_value, design=None):
    """Return proof-bearing exclusions; passing means undetermined, not feasible.

    Physical necessity, representational restrictions and property-domain limits
    are separate categories. Energy heuristics and numerical failures never enter
    this filter. This function is also usable on an independently authored matrix.
    """
    issues = []

    def reject(category, code, proof, **evidence):
        issues.append({"category": category, "code": code, "proof": proof, "evidence": evidence})

    try:
        n = input_value["stageCount"]
        feed_stage = input_value["feedStageNumber"]
        flows = input_value["feedComponentMolarFlowsMolPerSecond"]
        component_ids = input_value["componentBasis"]["componentIds"]
        feed_t = input_value["feedTemperatureKelvin"]
        top_p = input_value["topPressurePascal"]
        dp = input_value["stagePressureDropPascal"]
        specs = input_value["specifications"]
        tc = next(s["kelvin"] for s in specs if "kelvin" in s)
        reflux = next(s["ratio"] for s in specs if "ratio" in s)
        reboiler = next(s["watts"] for s in specs if "watts" in s)
        draws, steam, pas = (input_value.get(k, []) for k in ("sideDraws", "steamFeeds", "pumparounds"))
        scalars = [*flows, feed_t, top_p, dp, tc, reflux, reboiler,
                   *(d["molarFlowMolPerSecond"] for d in draws),
                   *(s["molarFlowMolPerSecond"] for s in steam),
                   *(s["temperatureKelvin"] for s in steam), *(p["dutyWatts"] for p in pas)]
        if any(not isinstance(v, (int, float)) or not math.isfinite(v) for v in scalars):
            reject("invalid_input", "NONFINITE_VALUE", "A thermodynamic input must be a finite real number.")
            return issues
        if not isinstance(n, int) or not 2 <= n <= 64 or not 1 <= feed_stage <= n:
            reject("model_contract", "STAGE_TOPOLOGY", "V3 permits 2..64 trays and one feed on trays 1..N.",
                   stages=n, feedStage=feed_stage)
            return issues
        if len(component_ids) != len(flows) or len(set(component_ids)) != len(component_ids):
            reject("invalid_input", "COMPONENT_AXIS", "Each component ID must map to exactly one feed flow.")
        total = math.fsum(flows)
        if any(f < 0 for f in flows) or total <= 0.0:
            reject("physical_necessity", "FEED_MATERIAL_SIGN", "A component source cannot supply negative moles; total feed must be positive.", totalFeed=total)
        if min(feed_t, tc, top_p, top_p + (n - 1) * dp) <= 0.0:
            reject("physical_necessity", "ABSOLUTE_STATE_SIGN", "Positive-density equilibrium states require positive absolute temperature and pressure.")
        if dp < 0.0 or reflux < 0.0 or reboiler < 0.0:
            reject("model_contract", "SPECIFICATION_SIGN", "V3 defines nonnegative downward pressure drop, organic reflux and reboiler heating.")
        if not 298.15 <= feed_t <= 900.0 or not 298.15 <= tc <= 900.0:
            reject("property_domain", "HYDROCARBON_TEMPERATURE", "The pinned hydrocarbon thermal fit is qualified over 298.15..900 K.", feedKelvin=feed_t, condenserKelvin=tc)
        total_draw = math.fsum(d["molarFlowMolPerSecond"] for d in draws)
        if total_draw > total + 1e-10 * max(1.0, total):
            reject("physical_necessity", "WITHDRAWAL_EXCEEDS_FEED", "Overall hydrocarbon balance is F = sum(side draws) + overhead + bottoms; nonnegative products require sum(side draws) <= F.", feedMolPerSecond=total, sideDrawMolPerSecond=total_draw)
        elif total_draw >= total:
            reject("model_contract", "NO_TERMINAL_PRODUCT_FLOW", "V3 requires total side draws strictly below hydrocarbon feed.")
        if any(d["molarFlowMolPerSecond"] <= 0 for d in draws):
            reject("model_contract", "SIDE_DRAW_RATE", "Each authored V3 side draw must have positive flow.")
        trays = [d["trayNumber"] for d in draws]
        if len(draws) > 3 or len(set(trays)) != len(trays) or any(not 1 <= t <= n for t in trays):
            reject("model_contract", "SIDE_DRAW_TOPOLOGY", "V3 supports at most three side draws, at most one per equilibrium tray.", stages=n, trayNumbers=trays)
        pairs = [(p["returnTray"], p["drawTray"]) for p in pas]
        if len(pas) > 4 or len(set(pairs)) != len(pairs) or any(not 1 <= a <= b <= n for a, b in pairs):
            reject("model_contract", "PA_TOPOLOGY", "V3 supports at most four distinct pairs with 1 <= return <= draw <= N; N trays have N(N+1)/2 such pairs.", stages=n, pairs=pairs, maximumDistinctPairs=n * (n + 1) // 2)
        if any(p["dutyWatts"] == 0 or p.get("split") not in ("UNIFORM", "RETURN_TRAY") for p in pas):
            reject("model_contract", "PA_DUTY_OR_SPLIT", "An authored PA needs nonzero duty and a supported heat-distribution rule.")
        steam_stages = [s["stageNumber"] for s in steam]
        if len(steam) > 2 or len(set(steam_stages)) != len(steam_stages):
            reject("model_contract", "STEAM_TOPOLOGY", "V3 supports at most two distinct steam-injection stages.")
        if math.fsum(s["molarFlowMolPerSecond"] for s in steam) > total:
            reject("model_contract", "STEAM_RATE_CAP", "The V3 steam-source contract caps total steam at total hydrocarbon feed; this is not a general physical impossibility.")
        if steam and reboiler == 0.0 and n + 1 not in steam_stages:
            reject("model_contract", "ZERO_DUTY_STEAM_LOCATION", "V3 requires sump steam when steam is authored with zero reboiler duty.")
        for s in steam:
            stage, t, rate = s["stageNumber"], s["temperatureKelvin"], s["molarFlowMolPerSecond"]
            if not 1 <= stage <= n + 1 or rate <= 0:
                reject("model_contract", "STEAM_SOURCE", "V3 steam stages are 1..N+1 and each authored source rate is positive.")
                continue
            p = top_p + max(0, min(stage, n) - 1) * dp
            if not 273.16 <= t <= 900.0:
                reject("property_domain", "WATER_TEMPERATURE", "V3 water enthalpy is defined over 273.16..900 K.", temperatureKelvin=t)
                continue
            try:
                saturation = water_saturation_temperature(p)
            except ValueError:
                reject("property_domain", "WATER_PRESSURE", "Injection pressure is outside the pinned pure-water saturation correlation.", pressurePascal=p)
                continue
            if t < saturation - 1e-8:
                reject("physical_necessity", "STEAM_NOT_VAPOR", "A stable pure-water vapor source at this subcritical pressure requires T >= Tsat(P); the declared source is subcooled water.", temperatureKelvin=t, pressurePascal=p, saturationKelvin=saturation)
            elif t < saturation + 5.0:
                reject("model_contract", "STEAM_SUPERHEAT_MARGIN", "V3 requires at least 5 K pure-water superheat; the margin is a solver contract.", temperatureKelvin=t, saturationKelvin=saturation)
        if design:
            requested = design.get("structuralCell", {})
            if requested.get("sideDrawCount", len(draws)) != len(draws) or requested.get("paCount", len(pas)) != len(pas):
                reject("invalid_input", "STRUCTURAL_COUNT_MISMATCH", "The authored input must retain its requested structural cell, even when excluded.")
    except (KeyError, StopIteration, TypeError, ValueError) as exc:
        reject("invalid_input", "MALFORMED_INPUT", "Required V3 input fields must exist and have the declared types.", detail=str(exc))
    return issues


def choose_available(options, unit, used):
    """Sample a location without replacement; retain an invalid duplicate if empty."""
    available = [x for x in options if x not in used]
    pool = available or options
    selected = pool[min(len(pool) - 1, int(unit * len(pool)))]
    used.add(selected)
    return selected


def make_input(baseline, cell, factors, mixture):
    n, enabled, pa_count, side_count = cell
    result = copy.deepcopy(baseline)
    base_feed = math.fsum(baseline["feedComponentMolarFlowsMolPerSecond"])
    total = base_feed * (0.8 + 0.4 * factors["feed_flow"])
    result["feedComponentMolarFlowsMolPerSecond"] = [total * x for x in mixture]
    result["stageCount"] = n
    # Relative feed height also varies by <=20%; clipping is solely to tray bounds.
    base_feed_position = (baseline["feedStageNumber"] - 1) / (baseline["stageCount"] - 1)
    position = min(1.0, base_feed_position * (0.8 + 0.4 * factors["feed_location"]))
    result["feedStageNumber"] = 1 + min(n - 1, int(position * (n - 1) + 0.5))
    result["feedTemperatureKelvin"] = baseline["feedTemperatureKelvin"] * (0.8 + 0.4 * factors["feed_temperature"])
    result["topPressurePascal"] = 100_000.0 + 200_000.0 * factors["top_pressure"]
    max_dp = min(1000.0, (300_000.0 - result["topPressurePascal"]) / (n - 1))
    result["stagePressureDropPascal"] = max_dp * factors["pressure_drop"]
    base_tc = next(s["kelvin"] for s in baseline["specifications"] if "kelvin" in s)
    tc_min, tc_max = max(298.15, 0.8 * base_tc), min(900.0, 1.2 * base_tc)
    result["specifications"] = [
        {"kelvin": tc_min + (tc_max - tc_min) * factors["condenser_temperature"]},
        {"ratio": 10.0 * factors["reflux_ratio"]},
        {"watts": 0.2 * base_feed * 100_000.0 * factors["reboiler_duty"]},
    ]
    used = set()
    result["sideDraws"] = []
    for i in range(side_count):
        tray = choose_available(list(range(1, n + 1)), factors[f"side_{i}_location"], used)
        result["sideDraws"].append({"trayNumber": tray, "molarFlowMolPerSecond":
            baseline["sideDraws"][i]["molarFlowMolPerSecond"] * (0.8 + 0.4 * factors[f"side_{i}_rate"])})
    result["sideDraws"].sort(key=lambda d: d["trayNumber"])
    base_steam = baseline["steamFeeds"][0]
    result["steamFeeds"] = [{"stageNumber": n + 1,
        "molarFlowMolPerSecond": base_steam["molarFlowMolPerSecond"] * (0.8 + 0.4 * factors["steam_rate"]),
        "temperatureKelvin": base_steam["temperatureKelvin"] * (0.8 + 0.4 * factors["steam_temperature"])}] if enabled else []
    reference_duties = [p["dutyWatts"] for p in baseline["pumparounds"]]
    reference_duties += [math.fsum(reference_duties) / len(reference_duties)]
    used = set()
    result["pumparounds"] = []
    for i in range(pa_count):
        return_tray = 1 + min(n - 1, int(factors[f"pa_{i}_return"] * n))
        draw_tray = return_tray + min(n - return_tray, int(factors[f"pa_{i}_span"] * (n - return_tray + 1)))
        pair = (return_tray, draw_tray)
        if pair in used:
            options = [(a, b) for a in range(1, n + 1) for b in range(a, n + 1)]
            pair = choose_available(options, factors[f"pa_{i}_span"], used)
        else:
            used.add(pair)
        result["pumparounds"].append({"returnTray": pair[0], "drawTray": pair[1],
            "dutyWatts": reference_duties[i] * (0.8 + 0.4 * factors[f"pa_{i}_duty"]), "split": "UNIFORM"})
    result["pumparounds"].sort(key=lambda p: (p["returnTray"], p["drawTray"]))
    return result


def generate(baseline, seed=DEFAULT_SEED):
    components = baseline["componentBasis"]["componentIds"]
    if len(components) != 20:
        raise ValueError("This design revision requires the registered 20-component basis")
    factor_names = SCALAR_FACTORS + EQUIPMENT_FACTORS + [f"mixture_{name}" for name in components]
    points = orthogonal_latin_hypercube(PRIME, len(factor_names), seed)
    oa_evidence = verify_oa(points, PRIME)
    structural = list(itertools.product(range(2, 65), (False, True), range(5), range(4)))
    rng = random.Random(seed ^ 0xC01D00)
    rng.shuffle(structural)
    cells = structural + rng.sample(structural, len(points) - len(structural))
    rng.shuffle(cells)
    repeats = Counter()
    rows = []
    for oa_row, (cell, point) in enumerate(zip(cells, points)):
        n, steam, pas, draws = cell
        replicate = repeats[cell]
        repeats[cell] += 1
        factors = dict(zip(factor_names, point))
        mixture = bounded_mixture(baseline["feedComponentMolarFlowsMolPerSecond"], point[-len(components):])
        input_value = make_input(baseline, cell, factors, mixture)
        design = {"revision": REVISION, "mode": "multivariable", "oaRow": oa_row,
            "structuralCell": {"stageCount": n, "steamEnabled": steam, "paCount": pas, "sideDrawCount": draws},
            "replicate": replicate, "latentFactors": factors,
            "inputSha256": digest(input_value), "mixtureFractions": mixture}
        rows.append({"id": f"gd-s{n:02d}-w{int(steam)}-p{pas}-d{draws}-r{replicate:02d}",
                     "split": split_for(n), "design": design, "input": input_value})
    excluded = []
    admitted = []
    for row in rows:
        reasons = screen_input(row["input"], row["design"])
        if reasons:
            excluded.append({**row, "status": "preflight_excluded", "exclusions": reasons})
        else:
            admitted.append(row)
    report = {"revision": REVISION, "seed": seed, "baselineSha256": digest(baseline),
        "baselineInput": baseline, "orthogonalArray": oa_evidence,
        "candidateRows": len(rows), "admittedRows": len(admitted), "excludedRows": len(excluded),
        "structuralCellCount": len(structural), "coveredStructuralCellCount": len(repeats),
        "replicatedCells": sum(count > 1 for count in repeats.values()),
        "candidateSplitCounts": dict(Counter(r["split"] for r in rows)),
        "admittedSplitCounts": dict(Counter(r["split"] for r in admitted)),
        "exclusionCounts": dict(Counter(e["code"] for r in excluded for e in r["exclusions"])),
        "splitStageCounts": {split: [n for n in range(2, 65) if split_for(n) == split] for split in ("train", "validation", "test")},
        "factorNames": factor_names, "variationBounds": {
            "componentMoleFractions": "every z_i in [0.8*z_i0,1.2*z_i0], sum(z)=1; all 20 treated equally",
            "feedMolarFlow": [0.8 * math.fsum(baseline["feedComponentMolarFlowsMolPerSecond"]), 1.2 * math.fsum(baseline["feedComponentMolarFlowsMolPerSecond"])],
            "feedTemperatureKelvin": [510.52, 765.78], "condenserTemperatureKelvin": [298.15, 398.58],
            "topAndAllStagePressuresPascal": [100_000, 300_000],
            "stagePressureDropPascal": "0..min(1000,(300000-Ptop)/(N-1)); 1000 is 20% of explicit 5000 Pa/tray reference",
            "organicRefluxRatio": [0, 10], "steamEnabled": [False, True],
            "steamRateWhenEnabled": "80..120% of baseline; sump injection",
            "steamTemperatureKelvin": [426.52, 639.78],
            "reboilerDutyWatts": "0..20% of Fbaseline*100000 J/mol; a nonzero reference is explicit because baseline duty is zero",
            "paCount": [0, 1, 2, 3, 4], "paLocation": "random distinct return/draw pairs, 1<=return<=draw<=N; all spans supported",
            "paDuties": "80..120% of original three duties; fourth reference is their arithmetic mean",
            "sideDrawCount": [0, 1, 2, 3], "sideDrawLocations": "random distinct trays 1..N",
            "sideDrawRates": "80..120% of respective baseline withdrawals",
            "feedLocation": "baseline normalized feed height times 0.8..1.2, clipped to 0..1 then rounded to an available tray"},
        "scope": "Complete finite structural matrix and strength-2 latent continuous design; not exhaustive continuous-space, location-combination, mixture-simplex, or feasibility coverage.",
        "admissionMeaning": "No listed necessary condition failed; existence of a column solution remains undetermined.",
        "qualification": "Teacher nonconvergence is mapped separately and has no training target; numerical outcome never changes this frozen matrix or split."}
    return rows, admitted, excluded, report


def write_jsonl(path, rows):
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(canonical(row) + "\n")


def generate_files(output, baseline_path, seed):
    baseline_document = json.loads(baseline_path.read_text(encoding="utf-8-sig"))
    baseline = baseline_document.get("input", baseline_document)
    rows, admitted, excluded, report = generate(baseline, seed)
    paths = [output / name for name in ("candidate-matrix.jsonl", "matrix.jsonl", "exclusions.jsonl", "design.json")]
    if any(path.exists() for path in paths):
        raise FileExistsError("Refusing to overwrite a frozen matrix; use a fresh output directory")
    output.mkdir(parents=True, exist_ok=True)
    for path, values in zip(paths[:3], (rows, admitted, excluded)):
        write_jsonl(path, values)
    report["fileSha256"] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in paths[:3]}
    with paths[3].open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(report, handle, indent=2, ensure_ascii=False, allow_nan=False)
        handle.write("\n")
    print(canonical({k: report[k] for k in ("revision", "candidateRows", "admittedRows", "excludedRows", "admittedSplitCounts", "exclusionCounts")}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("generate", help="Create and freeze the complete candidate matrix and preflight filter")
    create.add_argument("output", type=Path)
    create.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    create.add_argument("--seed", type=int, default=DEFAULT_SEED)
    screen = commands.add_parser("screen", help="Apply proof-bearing exclusions to an existing matrix")
    screen.add_argument("matrix", type=Path)
    screen.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "generate":
        generate_files(args.output, args.baseline, args.seed)
    else:
        args.output.mkdir(parents=True, exist_ok=True)
        admitted, excluded = [], []
        for line in args.matrix.read_text(encoding="utf-8-sig").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            reasons = screen_input(row["input"], row.get("design"))
            (excluded if reasons else admitted).append({**row, "status": "preflight_excluded", "exclusions": reasons} if reasons else row)
        write_jsonl(args.output / "matrix.jsonl", admitted)
        write_jsonl(args.output / "exclusions.jsonl", excluded)
        print(canonical({"admittedRows": len(admitted), "excludedRows": len(excluded)}))


if __name__ == "__main__":
    main()
