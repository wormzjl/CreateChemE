#!/usr/bin/env python3
"""WP10 (P3, batch 2026-09-24-coolprop-low-temperature): methane ideal-gas Cp above 375 K.

Computes the ideal-gas heat capacity of 12CH4 by direct summation over the
ExoMol MM states file (Yurchenko, Owens, Kefala and Tennyson 2024), estimates
the missing high-energy tail, cross-checks against the ExoMol partition
function file, the Wenger, Champion and Boudon 2008 partition sum and the
HITRAN TIPS q32 file, and tabulates the other sources (JANAF via the NIST
Shomate fit, GERG-2008 ideal part, CoolProp Setzmann-Wagner alpha0, NASA CEA
Gurvich 1991, and a rigid-rotor harmonic-oscillator sanity bound).

It then fits a NASA 9 segment (the spine's `nasa9` record type) to the
line-list Cp over the chosen range and writes everything under --out.

Run from the worktree root (see README.md):
    python tools/methane-ideal-gas/methane_cp.py \
        --data research/2026-09-24-coolprop-low-temperature/methane-ideal-gas \
        --sources research/2026-09-24-coolprop-low-temperature/sources \
        --out research/2026-09-24-coolprop-low-temperature/methane-ideal-gas/outputs
(add --fit-max 1500 and --out .../outputs/fit-1500 for the 1500 K variant)
"""
import argparse
import bz2
import json
import math
import os
import re
import sys

import numpy as np

# CODATA 2018
R = 8.314462618          # J/(mol K)
C2 = 1.438776877         # second radiation constant hc/k, cm K
H_PLANCK = 6.62607015e-34
K_B = 1.380649e-23
N_A = 6.02214076e23

TABLE_T = [298.15, 300.0, 375.0, 500.0, 600.0, 700.0, 800.0, 900.0, 1000.0,
           1073.0, 1100.0, 1143.0, 1200.0, 1300.0, 1500.0]


# --------------------------------------------------------------------------
# ExoMol MM states file
# --------------------------------------------------------------------------
def load_states(path):
    energies = []
    degeneracies = []
    rot_j = []
    with bz2.open(path, "rt") as handle:
        for line in handle:
            parts = line.split(None, 4)
            energies.append(float(parts[1]))
            degeneracies.append(int(parts[2]))
            rot_j.append(int(parts[3]))
    return (np.asarray(energies, dtype=np.float64),
            np.asarray(degeneracies, dtype=np.float64),
            np.asarray(rot_j, dtype=np.int32))


class StateSum:
    """Moments of the Boltzmann distribution over an explicit level list."""

    def __init__(self, energy, degeneracy):
        order = np.argsort(energy, kind="stable")
        self.energy = energy[order]
        self.degeneracy = degeneracy[order]

    def moments(self, temperature, energy_cut=None):
        e = self.energy
        g = self.degeneracy
        if energy_cut is not None:
            n = np.searchsorted(e, energy_cut, side="right")
            e = e[:n]
            g = g[:n]
        beta = C2 / temperature
        w = g * np.exp(-beta * e)
        q = float(np.sum(w))
        mean = float(np.sum(w * e)) / q
        var = float(np.sum(w * (e - mean) ** 2)) / q
        return q, mean, var


def cp_from_moments(temperature, var):
    """Ideal-gas Cp in J/(mol K): 5/2 R (translation + pV) + internal part."""
    return 2.5 * R + R * (C2 / temperature) ** 2 * var


def tail_model(energy, degeneracy, fit_lo, fit_hi, bin_width, e_top):
    """Power-law density of states ln(sum g per bin) = a + n ln(E + Ez), fitted
    over [fit_lo, fit_hi) where the file is complete (Ez scanned on a 250 cm-1
    grid). Returns the missing weight: the deficit of the file against the model
    from fit_hi to the file's top plus the model from the top to e_top."""
    top = float(np.max(energy))
    edges = np.arange(0.0, top + bin_width, bin_width)
    sums, _ = np.histogram(energy, bins=edges, weights=degeneracy)
    centres = 0.5 * (edges[1:] + edges[:-1])
    sel = (centres >= fit_lo) & (centres < fit_hi)
    best = None
    for ez in np.arange(1000.0, 16001.0, 250.0):
        x = np.log(centres[sel] + ez)
        y = np.log(sums[sel])
        basis = np.vstack([np.ones_like(x), x]).T
        coef, *_ = np.linalg.lstsq(basis, y, rcond=None)
        rss = float(np.sum((basis @ coef - y) ** 2))
        if best is None or rss < best[0]:
            best = (rss, ez, coef)
    rss, ez, coef = best

    def model(e):
        return np.exp(coef[0] + coef[1] * np.log(e + ez))

    above = centres >= fit_hi
    deficit = np.clip(model(centres[above]) - sums[above], 0.0, None)
    tail_centres = np.arange(top + bin_width / 2, e_top, bin_width)
    tail_e = np.concatenate([centres[above], tail_centres])
    tail_g = np.concatenate([deficit, model(tail_centres)])
    ratios = {c: float(sums[np.argmin(np.abs(centres - c))] / model(c)) for c in (15550.0, 16550.0, 17550.0)}
    info = {"ez_cm": float(ez), "exponent": float(coef[1]), "ln_prefactor": float(coef[0]),
            "rms_ln_residual": math.sqrt(rss / int(np.sum(sel))), "file_over_model": ratios,
            "fit_window_cm": [fit_lo, fit_hi], "top_of_file_cm": top, "extrapolated_to_cm": e_top}
    return tail_e, tail_g, info


def moments_with_tail(state_sum, temperature, tail_e, tail_g):
    q0, mean0, var0 = state_sum.moments(temperature)
    beta = C2 / temperature
    w = tail_g * np.exp(-beta * tail_e)
    qt = float(np.sum(w))
    m1t = float(np.sum(w * tail_e))
    m2t = float(np.sum(w * tail_e ** 2))
    q = q0 + qt
    m1 = q0 * mean0 + m1t
    m2 = q0 * (var0 + mean0 ** 2) + m2t
    mean = m1 / q
    var = m2 / q - mean ** 2
    return q, mean, var, qt / q


# --------------------------------------------------------------------------
# Partition-function tables: Cp by local polynomial fits of ln Q
# --------------------------------------------------------------------------
def load_two_column(path):
    t_values = []
    q_values = []
    with open(path, "r") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) < 2:
                continue
            t_values.append(float(parts[0]))
            q_values.append(float(parts[1]))
    return np.asarray(t_values), np.asarray(q_values)


def cp_from_lnq_table(t_table, q_table, temperature, half_window, degree):
    """Cp = 5/2 R + R d/dT (T^2 dlnQ/dT) from a least-squares polynomial of
    ln Q in (T - T0) over T0 +- half_window."""
    sel = np.abs(t_table - temperature) <= half_window + 1e-9
    x = t_table[sel] - temperature
    y = np.log(q_table[sel])
    coef = np.polyfit(x, y, degree)
    d1 = np.polyval(np.polyder(coef, 1), 0.0)
    d2 = np.polyval(np.polyder(coef, 2), 0.0)
    return 2.5 * R + R * (2.0 * temperature * d1 + temperature ** 2 * d2)


# --------------------------------------------------------------------------
# Other sources
# --------------------------------------------------------------------------
def janaf_shomate(temperature):
    """NIST WebBook methane gas Shomate fit to Chase 1998 (JANAF 4th ed.)."""
    t = temperature / 1000.0
    if temperature <= 1300.0:
        a, b, c, d, e = -0.703029, 108.4773, -42.52157, 5.862788, 0.678565
    else:
        a, b, c, d, e = 85.81217, 11.26467, -2.114146, 0.138190, -26.42221
    return a + b * t + c * t * t + d * t ** 3 + e / (t * t)


def load_janaf(path):
    """JANAF C-067 (Chase 1998) as served by janaf.nist.gov: T -> Cp."""
    table = {}
    with open(path, "r") as handle:
        for line in handle:
            parts = line.rstrip().split("\t")
            try:
                table[float(parts[0])] = float(parts[1])
            except (ValueError, IndexError):
                continue
    return table


def gerg_ideal_cp(temperature, gerg_cpp):
    """GERG-2008 methane ideal part exactly as GERG2008.cpp forms Cp0:
    Cp0 = Rs * [(n3 - 1) + sum sinh/cosh terms] + RGERG,
    with Rs = 8.31451, RGERG = 8.314472 (SetupGERG and PropertiesGERG)."""
    n = {}
    th = {}
    for match in re.finditer(r"n0i\[1\]\[(\d)\]\s*=\s*([-0-9.Ee+]+);", gerg_cpp):
        n[int(match.group(1))] = float(match.group(2))
    for match in re.finditer(r"th0i\[1\]\[(\d)\]\s*=\s*([-0-9.Ee+]+);", gerg_cpp):
        th[int(match.group(1))] = float(match.group(2))
    rs = 8.31451
    rgerg = 8.314472
    total = n[3] - 1.0
    for j in (4, 5, 6, 7):
        x = th[j] / temperature
        if j in (4, 6):
            total += n[j] * (x / math.sinh(x)) ** 2
        else:
            total += n[j] * (x / math.cosh(x)) ** 2
    return rs * total + rgerg


def setzmann_wagner_cp(temperature, spine):
    """CoolProp Methane.json alpha0 as carried by the P2 spine record
    (log_tau a, planck_einstein_function_t n, v), R = 8.31451."""
    seg = spine["ideal_gas"]["segments"][0]
    rr = seg["gas_constant_j_per_mol_kelvin"]
    a = None
    total = 1.0
    for term in seg["terms"]:
        if term["type"] == "log_tau":
            a = term["a"]
        elif term["type"] == "planck_einstein_function_t":
            for nn, vv in zip(term["n"], term["v"]):
                x = vv / temperature
                total += nn * x * x * math.exp(-x) / (1.0 - math.exp(-x)) ** 2
    return rr * (total + a)


def nasa9_cp(coefficients, temperature, gas_constant):
    a = coefficients
    t = temperature
    return gas_constant * (a[0] / t ** 2 + a[1] / t + a[2] + a[3] * t
                           + a[4] * t ** 2 + a[5] * t ** 3 + a[6] * t ** 4)


def nasa9_h_over_r(a, t):
    return (-a[0] / t + a[1] * math.log(t) + a[2] * t + a[3] * t ** 2 / 2
            + a[4] * t ** 3 / 3 + a[5] * t ** 4 / 4 + a[6] * t ** 5 / 5)


def nasa9_s_over_r(a, t):
    return (-a[0] / (2 * t ** 2) - a[1] / t + a[2] * math.log(t) + a[3] * t
            + a[4] * t ** 2 / 2 + a[5] * t ** 3 / 3 + a[6] * t ** 4 / 4)


def cea_cp(temperature, spine):
    for seg in spine["ideal_gas"]["segments"][1:]:
        if seg["temperature_min_kelvin"] <= temperature <= seg["temperature_max_kelvin"]:
            return nasa9_cp(seg["coefficients"], temperature, 8.314510)
    # below the spine's CEA segment start (375 K) use the 200..1000 K interval
    return nasa9_cp(spine["ideal_gas"]["segments"][1]["coefficients"], temperature, 8.314510)


# Observed fundamentals of 12CH4, cm-1 (band origins; nu1 and nu3 also appear
# as J = 0 levels in the MM states file): (wavenumber, degeneracy)
FUNDAMENTALS = [(2916.48, 1), (1533.33, 2), (3019.49, 3), (1310.76, 3)]


def rrho_cp(temperature):
    """Rigid rotor (classical spherical top) + harmonic oscillators at the
    observed fundamentals: a sanity bound, not a source."""
    total = 4.0  # 3/2 translation + 1 (pV) + 3/2 classical rotation
    for nu, deg in FUNDAMENTALS:
        x = C2 * nu / temperature
        total += deg * x * x * math.exp(x) / (math.exp(x) - 1.0) ** 2
    return R * total


# Wenger, Champion and Boudon 2008, JQSRT 109, 2697, Table 4 "Present work"
# (HAL hal-00277904v2): recommended partition sums with their printed digits.
WENGER_TABLE4 = [(100, 116.4), (200, 326.6), (300, 602.8), (400, 954.7),
                 (500, 1417.7), (600, 2045.7), (700, 2910.7), (800, 4109.1),
                 (900, 5770.1), (1000, 8067.4), (1100, 1.1233e4),
                 (1200, 1.5576e4), (1300, 2.151e4), (1400, 2.960e4),
                 (1500, 4.058e4), (1600, 5.550e4), (1700, 7.569e4),
                 (1800, 1.031e5), (1900, 1.404e5), (2000, 1.91e5),
                 (2100, 2.60e5), (2200, 3.54e5), (2300, 4.82e5), (2400, 6.58e5),
                 (2500, 8.97e5), (2600, 1.22e6), (2700, 1.67e6), (2800, 2.27e6),
                 (2900, 3.09e6), (3000, 4.19e6)]
# Their stated uncertainty of Q: < 0.1 % to 1300 K, 0.1 % at 1400-1500 K,
# 0.2 % at 1600 K, 0.3 % at 1700 K, 0.9 % at 2000 K, 8.3 % at 3000 K.
WENGER_FIT_WINDOWS = [(1300, 1700), (1400, 1700), (1300, 1600), (1300, 1500), (1300, 2000)]


# --------------------------------------------------------------------------
# NASA 9 fit
# --------------------------------------------------------------------------
def fit_nasa9(temps, cps, gas_constant):
    t = np.asarray(temps)
    y = np.asarray(cps) / gas_constant
    basis = np.vstack([t ** -2, t ** -1, np.ones_like(t), t, t ** 2, t ** 3, t ** 4]).T
    # scale columns for conditioning, relative weighting of Cp
    scale = np.max(np.abs(basis), axis=0)
    weights = 1.0 / y
    a_scaled, *_ = np.linalg.lstsq(basis / scale * weights[:, None], y * weights, rcond=None)
    return list(a_scaled / scale)


def wenger_activation_fits(state_sum):
    """ln f = ln(Q_W/Q_MM) fitted as ln f = ln A - theta/T over each window:
    the partition-sum excess of Wenger 2008 over the MM levels, read as missing
    high-lying states with one effective activation temperature theta. Returns
    (window, A, theta) per window."""
    fits = []
    for lo, hi in WENGER_FIT_WINDOWS:
        ts = np.array([t for t, _ in WENGER_TABLE4 if lo <= t <= hi], dtype=float)
        qs = np.array([q for t, q in WENGER_TABLE4 if lo <= t <= hi], dtype=float)
        f = np.log(qs / np.array([state_sum.moments(t)[0] for t in ts]))
        coef = np.polyfit(1.0 / ts, np.log(f), 1)
        fits.append(((lo, hi), math.exp(coef[1]), -coef[0]))
    return fits


def wenger_delta_cp(fits, temperature):
    """Cp excess implied by each activation fit: ln Q gains f = A exp(-theta/T),
    so Cp gains R d/dT (T^2 df/dT) = R f (theta/T)^2."""
    values = []
    for _, a, theta in fits:
        f = a * math.exp(-theta / temperature)
        values.append(R * f * (theta / temperature) ** 2)
    return min(values), max(values)


def integrate(fn, t0, t1, step=0.5):
    """Trapezoid rule on a uniform grid."""
    n = max(1, int(round(abs(t1 - t0) / step)))
    ts = np.linspace(t0, t1, n + 1)
    ys = np.array([fn(t) for t in ts])
    return float(np.sum(0.5 * (ys[1:] + ys[:-1]) * np.diff(ts)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True)
    parser.add_argument("--sources", required=True)
    parser.add_argument("--spine", default=None,
                        help="spine r1 record (default: <data>/inputs/spine-methane-r1-at-5100233.json)")
    parser.add_argument("--out", required=True)
    parser.add_argument("--fit-max", type=float, default=1300.0,
                        help="upper end of the NASA 9 segment (proposal 1300 K; 1500 K variant)")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    spine_path = args.spine or os.path.join(args.data, "inputs", "spine-methane-r1-at-5100233.json")
    with open(spine_path, "r") as handle:
        spine = json.load(handle)
    with open(os.path.join(args.sources, "nist-aga8", "GERG2008.cpp"), "r") as handle:
        gerg_cpp = handle.read()
    janaf = load_janaf(os.path.join(args.data, "janaf", "C-067.txt"))

    log = []

    def out(line=""):
        print(line)
        log.append(line)

    energy, degeneracy, rot_j = load_states(os.path.join(args.data, "exomol", "12C-1H4__MM.states.bz2"))
    out(f"MM states: {energy.size} levels, E max {energy.max():.3f} cm-1, J max {int(rot_j.max())}, "
        f"sum g {degeneracy.sum():.6e}")
    ss = StateSum(energy, degeneracy)

    # --- the ExoMol .pf file, TIPS and Wenger against the direct sum ------------
    t_pf, q_pf = load_two_column(os.path.join(args.data, "exomol", "12C-1H4__MM.pf"))
    t_tips, q_tips = load_two_column(os.path.join(args.data, "hitran", "q32.txt"))
    out("\nPartition sums against the MM direct sum (ratio - 1)")
    out("      T     Q direct    MM .pf     TIPS q32   Wenger 2008")
    for temperature in (300.0, 600.0, 1000.0, 1200.0, 1500.0, 1700.0, 2000.0):
        q, _, _ = ss.moments(temperature)
        qf = q_pf[np.argmin(np.abs(t_pf - temperature))]
        qt = q_tips[np.argmin(np.abs(t_tips - temperature))]
        qw = dict(WENGER_TABLE4)[int(temperature)]
        out(f"  {temperature:6.0f} {q:12.4f}  {qf / q - 1:+.2e}  {qt / q - 1:+.2e}  {qw / q - 1:+.2e}")

    # --- missing states --------------------------------------------------------
    tail_e, tail_g, tail_info = tail_model(energy, degeneracy, 10000.0, 15000.0, 100.0, 36100.0)
    out(f"\nPower-law tail: sum g per 100 cm-1 = exp({tail_info['ln_prefactor']:.4f}) (E + {tail_info['ez_cm']:.0f})"
        f"^{tail_info['exponent']:.4f}, fit 10000..15000 cm-1, rms ln residual {tail_info['rms_ln_residual']:.4f}; "
        "file/model at 15550, 16550, 17550 cm-1: "
        + ", ".join(f"{v:.3f}" for v in tail_info["file_over_model"].values())
        + "; deficit from 15000 cm-1 plus model to 36100 cm-1 (D0) added")
    w_fits = wenger_activation_fits(ss)
    for (lo, hi), a_w, theta in w_fits:
        out(f"Wenger excess fit {lo}..{hi} K: ln(Q_W/Q_MM) = {math.log(a_w):.3f} - {theta:.0f}/T "
            f"(effective energy {theta / C2:.0f} cm-1 above the mean)")
    # level-structure floor: smooth cubic of ln(Q_W/Q_MM) on 300..1200 K
    lt = np.array([t for t, _ in WENGER_TABLE4 if 300 <= t <= 1200], dtype=float)
    ld = np.array([math.log(q / ss.moments(t)[0]) for t, q in WENGER_TABLE4 if 300 <= t <= 1200])
    lc = np.polyfit(lt / 1000.0, ld, 3)
    floor = []
    for temperature in np.arange(300.0, 1201.0, 25.0):
        u = temperature / 1000.0
        d1 = np.polyval(np.polyder(lc, 1), u) / 1000.0
        d2 = np.polyval(np.polyder(lc, 2), u) / 1.0e6
        cp0 = cp_from_moments(temperature, ss.moments(temperature)[2])
        floor.append(abs(R * (2 * temperature * d1 + temperature ** 2 * d2)) / cp0)
    floor_max = max(floor)
    out(f"Level-structure difference Wenger vs MM, 300..1200 K (cubic in T of ln Q ratio): "
        f"max |dCp/Cp| {floor_max:.2e}")

    def cp_reference(temperature):
        _, _, var_t, _ = moments_with_tail(ss, temperature, tail_e, tail_g)
        return cp_from_moments(temperature, var_t)

    def h_reference(temperature):
        """H(T) - H(0 K) = 5/2 RT + R c2 <E>, J/mol."""
        _, mean_t, _, _ = moments_with_tail(ss, temperature, tail_e, tail_g)
        return 2.5 * R * temperature + R * C2 * mean_t

    # --- main table ------------------------------------------------------------
    rows = []
    for temperature in TABLE_T:
        q, mean, var = ss.moments(temperature)
        cp_mm = cp_from_moments(temperature, var)
        _, _, var_t, tail_fraction = moments_with_tail(ss, temperature, tail_e, tail_g)
        cp_ref = cp_from_moments(temperature, var_t)
        dw_lo, dw_hi = wenger_delta_cp(w_fits, temperature)
        upper = cp_mm + max(cp_ref - cp_mm, dw_hi)
        cut = {}
        for e_cut in (14000.0, 16000.0):
            cut[e_cut] = cp_from_moments(temperature, ss.moments(temperature, energy_cut=e_cut)[2])
        rows.append({
            "T": temperature, "Q_mm": q, "cp_mm": cp_mm, "cp_reference": cp_ref,
            "tail_q_fraction": tail_fraction, "delta_tail": cp_ref - cp_mm,
            "delta_wenger_min": dw_lo, "delta_wenger_max": dw_hi,
            "bound_lower": cp_mm, "bound_upper": upper,
            "declared_minus_pct": 100 * (cp_ref - cp_mm) / cp_ref + 100 * floor_max,
            "declared_plus_pct": 100 * (upper - cp_ref) / cp_ref + 100 * floor_max,
            "cp_cut14k": cut[14000.0], "cp_cut16k": cut[16000.0],
            "janaf_table": janaf.get(temperature), "janaf_shomate": janaf_shomate(temperature),
            "gerg": gerg_ideal_cp(temperature, gerg_cpp),
            "setzmann_wagner": setzmann_wagner_cp(temperature, spine),
            "cea": cea_cp(temperature, spine), "rrho": rrho_cp(temperature),
            "cp_from_pf": cp_from_lnq_table(t_pf, q_pf, temperature, 50, 6),
            "cp_from_pf_w25_d4": cp_from_lnq_table(t_pf, q_pf, temperature, 25, 4),
            "cp_from_tips": cp_from_lnq_table(t_tips, q_tips, temperature, 50, 6),
        })
    for name in ("YT10to10", "YT34to10"):
        t_cp, v_cp = load_two_column(os.path.join(args.data, "exomol", f"12C-1H4__{name}.cp"))
        for row in rows:
            k = np.argmin(np.abs(t_cp - row["T"]))
            row[f"exomol_cp_{name}"] = float(v_cp[k]) if abs(t_cp[k] - row["T"]) < 0.51 else None

    # --- join with the CoolProp (Setzmann-Wagner) segment -----------------------
    out("\nCp step, reference over Setzmann-Wagner (spine segment 1), on the 25 K grid")
    candidates = []
    for temperature in np.arange(300.0, 626.0, 25.0):
        sw = setzmann_wagner_cp(temperature, spine)
        step = cp_reference(temperature) / sw - 1.0
        candidates.append((abs(step), float(temperature), step))
        out(f"  {temperature:6.1f} K  step {step:+.2e}")
    join = min(candidates)[1]
    out(f"  smallest step at {join:.0f} K")

    # --- NASA 9 fit to the reference ---------------------------------------------
    fit_t = np.arange(join, args.fit_max + 0.5, 5.0)
    fit_cp = np.array([cp_reference(t) for t in fit_t])
    a = fit_nasa9(fit_t, fit_cp, R)
    resid = np.array([nasa9_cp(a, t, R) for t in fit_t]) / fit_cp - 1.0
    out(f"\nNASA 9 fit {join:.0f}..{args.fit_max:.0f} K to the reference: max |rel dev| "
        f"{np.max(np.abs(resid)):.2e}, rms {np.sqrt(np.mean(resid ** 2)):.2e}")

    seg0 = spine["ideal_gas"]["segments"][0]

    def sw_h_over_rt(t):
        total = 1.0
        for term in seg0["terms"]:
            if term["type"] == "log_tau":
                total += term["a"]
            else:
                for nn, vv in zip(term["n"], term["v"]):
                    x = vv / t
                    total += nn * x * math.exp(-x) / (1.0 - math.exp(-x))
        return total

    def sw_s_over_r(t):
        total = 0.0
        for term in seg0["terms"]:
            if term["type"] == "log_tau":
                total += (1.0 + term["a"]) * math.log(t)
            else:
                for nn, vv in zip(term["n"], term["v"]):
                    x = vv / t
                    total += nn * (x * math.exp(-x) / (1.0 - math.exp(-x)) - math.log(1.0 - math.exp(-x)))
        return total

    rsw = seg0["gas_constant_j_per_mol_kelvin"]
    dfh = spine["formation_enthalpy"]["value_j_per_mol"]
    s298 = spine["standard_entropy"]["value_j_per_mol_kelvin"]
    h_join = dfh + rsw * (sw_h_over_rt(join) * join - sw_h_over_rt(298.15) * 298.15)
    s_join = s298 + rsw * (sw_s_over_r(join) - sw_s_over_r(298.15))
    b1 = h_join / R - nasa9_h_over_r(a, join)
    b2 = s_join / R - nasa9_s_over_r(a, join)
    coefficients = [float(c) for c in a] + [float(b1), float(b2)]
    join_step = nasa9_cp(a, join, R) / setzmann_wagner_cp(join, spine) - 1.0
    cea_hi = spine["ideal_gas"]["segments"][2]["coefficients"]
    step_hi = nasa9_cp(cea_hi, args.fit_max, 8.314510) / nasa9_cp(a, args.fit_max, R) - 1.0
    out(f"Cp step at the join {join:.0f} K (fit over Setzmann-Wagner): {join_step:+.2e}; "
        f"at {args.fit_max:.0f} K (CEA 1000..6000 K over the fit): {step_hi:+.3e}")

    def spine_r1_cp(t):
        return setzmann_wagner_cp(t, spine) if t <= 375.0 else cea_cp(t, spine)

    def spine_r2_cp(t):
        return setzmann_wagner_cp(t, spine) if t <= join else nasa9_cp(a, t, R)

    for row in rows:
        t = row["T"]
        row["nasa9_fit"] = nasa9_cp(a, t, R) if join <= t <= args.fit_max else None
        row["spine_r1"] = spine_r1_cp(t)
        row["spine_r2"] = spine_r2_cp(t) if t <= args.fit_max else None
        row["dh_r1"] = integrate(spine_r1_cp, 298.15, t)
        row["dh_r2"] = integrate(spine_r2_cp, 298.15, t) if t <= args.fit_max else None
        row["dh_reference"] = h_reference(t) - h_reference(298.15)

    # --- standard-state checks at 298.15 K -------------------------------------
    q298, mean298, _ = ss.moments(298.15)
    for label, molar_mass in (("12CH4 mass", 16.03130013e-3), ("spine molar mass 0.0160428", 0.0160428)):
        m = molar_mass / N_A
        t = 298.15
        s_trans = R * (math.log((2 * math.pi * m * K_B * t / H_PLANCK ** 2) ** 1.5 * K_B * t / 1.0e5) + 2.5)
        s_int = R * (math.log(q298 / 16.0) + C2 * mean298 / t)
        out(f"S(298.15 K, 1 bar) from MM ({label}, nuclear spin 16 removed): {s_trans + s_int:.4f} J/(mol K) "
            "(spine/CEA 186.3702, JANAF 186.251)")
    out(f"H(298.15) - H(0) from MM: {2.5 * R * 298.15 + R * C2 * mean298:.1f} J/mol (JANAF C-067 10024)")

    # --- outputs ---------------------------------------------------------------
    segment = {
        "type": "nasa9",
        "temperature_min_kelvin": join,
        "temperature_max_kelvin": args.fit_max,
        "source": ("NASA 9 fit to the ideal-gas Cp of 12CH4 from the ExoMol MM line list (Yurchenko, Owens, Kefala "
                   "and Tennyson 2024, MNRAS 528, 3719; states file version 20240113, CC BY-SA 4.0) by direct "
                   "summation plus a power-law density-of-states tail; tools/methane-ideal-gas/methane_cp.py "
                   "(batch 2026-09-24-coolprop-low-temperature, WP10); b1 and b2 continue h and s of the "
                   "Setzmann-Wagner segment at the join and are not used by the loader"),
        "revision": "exomol-mm-20240113-methane-nasa9-r1",
        "gas_constant_j_per_mol_kelvin": R,
        "coefficients": coefficients,
    }
    result = {
        "rows": rows, "join_temperature_kelvin": join, "tail_model": tail_info,
        "wenger_activation_fits": [{"window_kelvin": list(w), "A": aa, "theta_kelvin": th} for w, aa, th in w_fits],
        "level_structure_floor_relative": floor_max,
        "nasa9_fit": {"max_relative_deviation": float(np.max(np.abs(resid))),
                      "rms_relative_deviation": float(np.sqrt(np.mean(resid ** 2))),
                      "join_cp_step": join_step, "cea_step_at_fit_max": step_hi},
        "proposed_segment": segment,
    }
    with open(os.path.join(args.out, "methane_cp_table.json"), "w", newline="\n") as handle:
        json.dump(result, handle, indent=1)
        handle.write("\n")
    segment_name = f"spine-methane-r2-segment-{join:.0f}-{args.fit_max:.0f}.json"
    with open(os.path.join(args.out, segment_name), "w", newline="\n") as handle:
        json.dump(segment, handle, indent=2)
        handle.write("\n")

    def f(v, width=8, digits=3):
        return " " * (width - 1) + "-" if v is None else f"{v:{width}.{digits}f}"

    out("\nCp, J/(mol K)")
    out("      T       MM  MM+tail    lower    upper  JANAF_t  JANAF_S     GERG       SW      CEA     RRHO"
        "    pf_d2  TIPS_d2 YT10to10 YT34to10    NASA9 spine_r2")
    for row in rows:
        out(f"{row['T']:7.2f} {f(row['cp_mm'])} {f(row['cp_reference'])} {f(row['bound_lower'])} "
            f"{f(row['bound_upper'])} {f(row['janaf_table'])} {f(row['janaf_shomate'])} {f(row['gerg'])} "
            f"{f(row['setzmann_wagner'])} {f(row['cea'])} {f(row['rrho'])} {f(row['cp_from_pf'])} "
            f"{f(row['cp_from_tips'])} {f(row.get('exomol_cp_YT10to10'))} {f(row.get('exomol_cp_YT34to10'))} "
            f"{f(row['nasa9_fit'])} {f(row['spine_r2'])}")
    out("\nDeviation from the reference (MM + tail), percent; declared reference error (-/+), percent")
    out("      T    JANAF     GERG       SW      CEA     RRHO spine_r1 spine_r2   -decl   +decl")
    for row in rows:
        ref = row["cp_reference"]

        def d(v):
            return " " * 7 + "-" if v is None else f"{100 * (v / ref - 1):+8.3f}"
        jan = row["janaf_table"] if row["janaf_table"] is not None else row["janaf_shomate"]
        out(f"{row['T']:7.2f} {d(jan)} {d(row['gerg'])} {d(row['setzmann_wagner'])} {d(row['cea'])} "
            f"{d(row['rrho'])} {d(row['spine_r1'])} {d(row['spine_r2'])} {row['declared_minus_pct']:7.3f} "
            f"{row['declared_plus_pct']:7.3f}")
    out("\nh(T) - h(298.15 K), J/mol: reference, spine r1, spine r2")
    for row in rows:
        if row["T"] > 298.15:
            out(f"{row['T']:7.2f} {row['dh_reference']:10.1f} {row['dh_r1']:10.1f} {f(row['dh_r2'], 10, 1)}")
    out("\nProposed segment (spine loader `nasa9`):")
    out(json.dumps(segment))
    with open(os.path.join(args.out, "methane_cp_log.txt"), "w", newline="\n") as handle:
        handle.write("\n".join(log) + "\n")


if __name__ == "__main__":
    sys.exit(main())
