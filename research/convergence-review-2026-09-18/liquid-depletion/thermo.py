"""Request-only thermodynamic helpers: Lee-Kesler vapour pressure and an ideal (Raoult) flash.

These read the shipped per-component property files, so they need nothing but the authored request:
the same information the Java design generator already has in hand before it calls the solver.
They are deliberately NOT the production Peng-Robinson package -- they are a screening surrogate,
and every number derived from them is reported as a calibrated statistic, not as physics.
"""
from __future__ import annotations

import glob
import json
import math
import os

R_GAS = 8.314462618

_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
_PROP_DIR = os.path.join(_ROOT, "src", "main", "resources", "data", "createcheme", "materials",
                         "properties")


def load_components():
    """componentId -> {tc, pc, omega, nbp} from the shipped property files."""
    out = {}
    for path in glob.glob(os.path.join(_PROP_DIR, "*.json")):
        with open(path, "r", encoding="utf-8") as handle:
            d = json.load(handle)
        pr = d.get("models", {}).get("pr78")
        if not pr:
            continue
        out[d["component"]] = {
            "tc": pr["critical_temperature_kelvin"],
            "pc": pr["critical_pressure_pascal"],
            "omega": pr["acentric_factor"],
            "nbp": d.get("normal_boiling_point_kelvin"),
            "mw": d.get("molecular_weight_kg_per_mol"),
        }
    return out


COMPONENTS = load_components()


def lee_kesler_psat(tc, pc, omega, temperature):
    """Three-parameter corresponding-states vapour pressure, clamped away from the critical point."""
    tr = temperature / tc
    if tr >= 1.0:
        return pc * 10.0  # supercritical: treat as permanently vapour
    tr = max(tr, 0.30)
    f0 = 5.92714 - 6.09648 / tr - 1.28862 * math.log(tr) + 0.169347 * tr ** 6
    f1 = 15.2518 - 15.6875 / tr - 13.4721 * math.log(tr) + 0.43577 * tr ** 6
    return pc * math.exp(f0 + omega * f1)


def k_values(component_ids, temperature, pressure):
    ks = []
    for cid in component_ids:
        c = COMPONENTS.get(cid)
        if c is None:
            ks.append(1.0)
            continue
        ks.append(lee_kesler_psat(c["tc"], c["pc"], c["omega"], temperature) / pressure)
    return ks


def rachford_rice(z, k):
    """Vapour mole fraction of an ideal flash; 0 below the bubble point, 1 above the dew point."""
    total = sum(z)
    if total <= 0:
        return 0.0
    zn = [v / total for v in z]
    if sum(zi * ki for zi, ki in zip(zn, k)) <= 1.0:
        return 0.0  # subcooled liquid
    if sum(zi / ki for zi, ki in zip(zn, k) if ki > 0) <= 1.0:
        return 1.0  # superheated vapour
    lo, hi = 0.0, 1.0
    for _ in range(80):
        mid = 0.5 * (lo + hi)
        f = sum(zi * (ki - 1.0) / (1.0 + mid * (ki - 1.0)) for zi, ki in zip(zn, k))
        if f > 0:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def vapour_fraction(component_ids, flows, temperature, pressure):
    return rachford_rice(flows, k_values(component_ids, temperature, pressure))


def light_fraction(component_ids, flows, temperature, pressure):
    """Mole fraction of the stream whose K exceeds one at the given conditions."""
    total = sum(flows)
    if total <= 0:
        return 0.0
    ks = k_values(component_ids, temperature, pressure)
    return sum(f for f, k in zip(flows, ks) if k > 1.0) / total


def clausius_latent_heat(component_ids, flows, temperature):
    """Molar-average latent heat (J/mol) from d ln Psat / dT of the Lee-Kesler correlation."""
    total = sum(flows)
    if total <= 0:
        return 40_000.0
    acc = 0.0
    for cid, f in zip(component_ids, flows):
        c = COMPONENTS.get(cid)
        if c is None or f <= 0:
            continue
        t = min(temperature, 0.98 * c["tc"])
        dt = max(0.5, 1e-4 * t)
        p1 = lee_kesler_psat(c["tc"], c["pc"], c["omega"], t - dt)
        p2 = lee_kesler_psat(c["tc"], c["pc"], c["omega"], t + dt)
        if p1 <= 0 or p2 <= 0:
            continue
        dlnp = (math.log(p2) - math.log(p1)) / (2 * dt)
        acc += f * R_GAS * t * t * dlnp
    return acc / total if acc > 0 else 40_000.0
