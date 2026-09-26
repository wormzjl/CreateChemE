"""P1 item 4 (review F1): translated PR78 (Soave alpha) evaluated DIRECTLY at (T, P), no 2 MPa reference path,
against CoolProp HEOS, for two translation anchors:
  a = current calibration (liquid volume at 2 MPa and N2 90 K, CH4 150 K, C2H6 240 K, CO2 250 K; H2 none)
  b = D1 choice (saturated-liquid volume at Tr = 0.8 from CoolProp, every fluid)
Root choice from CoolProp's phase: liquid/supercritical_liquid -> liquid root, gas/supercritical_gas -> vapour root,
supercritical -> lowest-Gibbs root. h convention: h - h_ig(298.15 K, 0.1 MPa) in both models (ideal parts cancel;
the model's h = h_ig,CoolProp(T) + h_res,PR + P c). Critical band Tr 0.95-1.1 with Pr 0.8-1.5 is flagged and
excluded from means. Writes item4-points.csv and item4-output.txt next to this file."""
import csv
import math
import os
import numpy as np
import CoolProp.CoolProp as CP
from pr78 import PR78, Ref, coolprop_constants, PHASE_CLASS, PHASE_ROOT, R

HERE = os.path.dirname(os.path.abspath(__file__))
FLUIDS = {"Nitrogen": 90.0, "Methane": 150.0, "Ethane": 240.0, "CarbonDioxide": 250.0, "Hydrogen": None}
ISOBARS_MPA = [0.1, 0.5, 1, 2, 5, 10]
N_GRID = 24
SAT = {"Nitrogen": [63.151, 70, 77.355, 90, 100, 110, 120],
       "Methane": [90.7, 100, 111.67, 130, 150, 170, 185],
       "Ethane": [90.4, 120, 150, 184.55, 220, 250, 280, 295],
       "CarbonDioxide": [216.6, 230, 250, 270, 290, 300],
       "Hydrogen": [14.0, 17.0, 20.369, 24.0, 28.0, 31.0]}  # H2 not in the probe; added here
DENSE = {"Nitrogen": [(77.355, 5), (77.355, 10), (100, 10), (126.2, 3.4), (130, 4), (130, 6), (130, 10),
                      (150, 10), (300, 10)],
         "CarbonDioxide": [(280, 8), (280, 10), (300, 8), (304.5, 7.4), (310, 8), (310, 10), (320, 8), (320, 10),
                           (350, 10), (400, 10)],
         "Methane": [(110, 10), (150, 10), (190.6, 4.6), (200, 6), (200, 10), (250, 10), (300, 10)],
         "Ethane": [(200, 10), (300, 10), (305.4, 4.9), (320, 6), (320, 10), (400, 10)],
         "Hydrogen": [(300, 10), (623, 6), (650, 9), (663, 9.1), (700, 10)]}
ANCHORS = ("a", "b")
H_FLOOR = 1000.0  # J/mol floor of the h percentage denominator


def pct(x, y):
    return 100 * (x / y - 1)


def build_models(name):
    Tc, Pc, w, Ttr = coolprop_constants(name)
    ma, mb = PR78(name, Tc, Pc, w), PR78(name, Tc, Pc, w)
    if FLUIDS[name]:
        ma.anchor_current(FLUIDS[name])
    else:
        ma.anchor_none()
    mb.anchor_tr08()
    return {"a": ma, "b": mb}, Tc, Pc, Ttr


def evaluate(models, ref, T, P, cls, root):
    out = {}
    for k, m in models.items():
        s = m.state(T, P, root)
        z_all, B = m.roots(T, P)
        branch = "V" if s["Z"] / B > 3.9513730355914 else "L"
        want = {"liquid-like": "L", "vapour-like": "V"}.get(cls, branch)
        out[k] = {"drho": pct(1 / s["v"], ref["rho"]),
                  "dcp": pct(ref["cp0"] + m.cp_res(T, P, root), ref["cp"]),
                  "dh": (ref["hig"] + s["hres"]) - ref["h"],
                  "dlnphi": s["lnphi"] - ref["lnphi"],
                  "mismatch": int(len(z_all) == 1 and branch != want)}
        out[k]["dh_pct"] = 100 * out[k]["dh"] / max(abs(ref["h"]), H_FLOOR)
    return out


def main():
    rows = []
    anchor_info = []
    skipped = []
    for name in FLUIDS:
        models, Tc, Pc, Ttr = build_models(name)
        ref = Ref(name)
        anchor_info.append((name, Tc, Pc, models["a"].w, Ttr, models["a"].c * 1e6, models["b"].c * 1e6))
        pts = []
        for PM in ISOBARS_MPA:
            P = PM * 1e6
            Ts = list(np.linspace(Ttr, 1.2 * Tc, N_GRID))
            if P < Pc:
                Tsat = CP.PropsSI("T", "P", P, "Q", 0, name) if P > CP.PropsSI("ptriple", name) else None
                if Tsat and Tsat > Ttr + 0.6:
                    Ts = [t for t in Ts if abs(t - Tsat) > 0.5] + [Tsat - 0.5, Tsat + 0.5]
            for T in sorted(Ts):
                pts.append(("isobar", T, P, None))
        for T in SAT[name]:
            try:
                Pq = CP.PropsSI("P", "T", T, "Q", 0, name)
            except ValueError:
                continue
            pts.append(("sat-liquid", T, Pq, 0))
            pts.append(("sat-vapour", T, Pq, 1))
        for T, PM in DENSE[name]:
            pts.append(("probe-dense", T, PM * 1e6, None))
        for kind, T, P, Q in pts:
            try:
                r = ref.sat(T, Q) if Q is not None else ref.tp(T, P)
            except ValueError as e:
                skipped.append((name, kind, round(T, 3), P / 1e6, str(e)[:60]))
                continue  # below the melting line or outside CoolProp's range
            if r["phase"] not in PHASE_CLASS:
                continue
            cls = PHASE_CLASS[r["phase"]]
            Tr, Pr = T / Tc, P / Pc
            band = int(0.95 <= Tr <= 1.1 and 0.8 <= Pr <= 1.5)
            try:
                ev = evaluate(models, r, T, P, cls, PHASE_ROOT[cls])
            except (ValueError, ZeroDivisionError) as e:
                print("model failure", name, kind, T, P, e)
                continue
            row = {"fluid": name, "set": kind, "T": T, "P_MPa": P / 1e6, "Tr": Tr, "Pr": Pr, "phase": cls,
                   "band": band, "rho_ref": r["rho"], "cp_ref": r["cp"], "h_ref": r["h"], "lnphi_ref": r["lnphi"]}
            for k in ANCHORS:
                for q, v in ev[k].items():
                    row[f"{q}_{k}"] = v
            rows.append(row)
    with open(os.path.join(HERE, "item4-points.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        for r in rows:
            w.writerow({k: (f"{v:.6g}" if isinstance(v, float) else v) for k, v in r.items()})
    scan = anchor_scan(rows)
    report(rows, anchor_info, skipped, scan)


SCAN_TR = [0.6, 0.65, 0.7, 0.75, 0.8, 0.85]


def anchor_scan(rows):
    """Density only (the translation moves nothing else but P c): mean |drho| of the non-band points for a constant
    translation anchored on the saturated liquid at each Tr of SCAN_TR, and for the current calibration."""
    out = []
    for name in FLUIDS:
        models, Tc, Pc, Ttr = build_models(name)
        m = models["a"]
        sel = [r for r in rows if r["fluid"] == name and not r["band"] and r["set"] != "probe-dense"]
        vraw = [m.v_raw(r["T"], r["P_MPa"] * 1e6, PHASE_ROOT[r["phase"]]) for r in sel]
        cands = [("current", models["a"].c)]
        for x in SCAN_TR:
            if x * Tc > Ttr:
                m2 = PR78(name, Tc, Pc, m.w)
                T = x * Tc
                st = CP.AbstractState("HEOS", name)
                st.update(CP.QT_INPUTS, 0, T)
                cands.append((f"Tr={x:g}", 1 / st.rhomolar() - m2.v_raw(T, st.p(), "L")))
        for label, c in cands:
            d = [(r, pct(1 / (v + c), r["rho_ref"])) for r, v in zip(sel, vraw)]
            def mean(f):
                v = [abs(x) for r, x in d if f(r)]
                return float(np.mean(v)) if v else float("nan")
            def worst(f):
                v = [x for r, x in d if f(r)]
                return max(v, key=abs) if v else float("nan")
            liq = lambda r: r["phase"] == "liquid-like"
            out.append((name, label, c * 1e6, mean(lambda r: liq(r) and r["Tr"] < 0.8),
                        mean(lambda r: liq(r) and r["Tr"] >= 0.8), mean(liq), worst(liq),
                        mean(lambda r: not liq(r)), mean(lambda r: True)))
    return out


def stats(sel, key):
    v = [r[key] for r in sel]
    if not v:
        return float("nan"), float("nan")
    worst = max(v, key=abs)
    return float(np.mean(np.abs(v))), worst


def report(rows, anchor_info, skipped, scan):
    L = []
    p = L.append
    p("P1 item 4: translated PR78 (Soave) evaluated directly at (T, P) vs CoolProp 8.0.0 HEOS")
    p("anchors: a = current calibration (2 MPa liquid at N2 90 K, CH4 150 K, C2H6 240 K, CO2 250 K; H2 none); "
      "b = saturated liquid at Tr = 0.8 (D1)")
    p("h convention: h - h_ig(298.15 K, 0.1 MPa), deviations in J/mol (cp and translation-independent parts equal for a "
      "and b; 'worst' of dh and cp are anchor b / anchor-independent); dlnphi = absolute ln phi "
      "difference (x100 ~ % on phi). Means exclude the critical band (Tr 0.95-1.1 with Pr 0.8-1.5) and root "
      "mismatches are counted.")
    p("")
    p(f"{'fluid':14}{'Tc[K]':>9}{'Pc[MPa]':>9}{'omega':>8}{'Ttr[K]':>9}{'c_a[cm3/mol]':>14}{'c_b[cm3/mol]':>14}")
    for n, Tc, Pc, w, Ttr, ca, cb in anchor_info:
        p(f"{n:14}{Tc:9.3f}{Pc / 1e6:9.4f}{w:8.4f}{Ttr:9.3f}{ca:14.3f}{cb:14.3f}")
    fl = list(FLUIDS)

    def block(title, groups):
        p("")
        p(title)
        p(f"{'group':34}{'n':>4}{'nB':>4}{'mm':>4}{'|drho|a':>9}{'worst a':>9}{'|drho|b':>9}{'worst b':>9}"
          f"{'|dcp|':>8}{'worst':>9}{'|dh|a J':>9}{'|dh|b J':>9}{'worst dh':>9}"
          f"{'|dlnphi|a':>10}{'|dlnphi|b':>10}")
        for label, sel_all in groups:
            sel = [r for r in sel_all if not r["band"]]
            nb = len(sel_all) - len(sel)
            mm = sum(r["mismatch_a"] for r in sel)
            if not sel:
                p(f"{label:34}{0:4d}{nb:4d}")
                continue
            ma, wa = stats(sel, "drho_a")
            mb, wb = stats(sel, "drho_b")
            mc, wc = stats(sel, "dcp_a")
            ha, _ = stats(sel, "dh_a")
            hb, whb = stats(sel, "dh_b")
            fa, _ = stats(sel, "dlnphi_a")
            fb, _ = stats(sel, "dlnphi_b")
            p(f"{label:34}{len(sel):4d}{nb:4d}{mm:4d}{ma:9.2f}{wa:9.2f}{mb:9.2f}{wb:9.2f}{mc:8.2f}{wc:9.2f}"
              f"{ha:9.0f}{hb:9.0f}{whb:9.0f}{fa:10.4f}{fb:10.4f}")

    main_rows = [r for r in rows if r["set"] != "probe-dense"]
    groups = []
    for n in fl:
        for ph in ("liquid-like", "vapour-like", "supercritical"):
            groups.append((f"{n} {ph}", [r for r in main_rows if r["fluid"] == n and r["phase"] == ph]))
    block("== Per fluid and phase (isobars + saturation states; % unless stated) ==", groups)
    groups = []
    for n in fl:
        for PM in ISOBARS_MPA:
            groups.append((f"{n} {PM:g} MPa", [r for r in rows if r["fluid"] == n and r["set"] == "isobar"
                                              and abs(r["P_MPa"] - PM) < 1e-9]))
    block("== Per fluid and isobar (isobar grid only) ==", groups)
    groups = []
    for n in fl:
        groups.append((f"{n} liquid Tr<0.8", [r for r in main_rows if r["fluid"] == n and r["phase"] == "liquid-like"
                                             and r["Tr"] < 0.8]))
        groups.append((f"{n} liquid Tr>=0.8", [r for r in main_rows if r["fluid"] == n
                                              and r["phase"] == "liquid-like" and r["Tr"] >= 0.8]))
    block("== Liquid-like split at Tr = 0.8 (anchor comparison) ==", groups)
    groups = [(f"{n} all", [r for r in main_rows if r["fluid"] == n]) for n in fl]
    block("== Per fluid, all phases ==", groups)

    p("")
    p("== Saturated liquid (probe states), translated PR evaluated at (T, Psat_ref), density deviation % ==")
    p(f"{'fluid':14}{'T[K]':>8}{'Tr':>6}{'Pr':>6}{'band':>5}{'rho ref':>10}{'drho a':>8}{'drho b':>8}{'dcp':>8}"
      f"{'dh a J':>8}{'dh b J':>8}{'dlnphi a':>9}{'dlnphi b':>9}")
    for r in rows:
        if r["set"] == "sat-liquid":
            p(f"{r['fluid']:14}{r['T']:8.2f}{r['Tr']:6.3f}{r['Pr']:6.3f}{r['band']:5d}{r['rho_ref']:10.1f}"
              f"{r['drho_a']:8.2f}{r['drho_b']:8.2f}{r['dcp_a']:8.2f}{r['dh_a']:8.0f}{r['dh_b']:8.0f}"
              f"{r['dlnphi_a']:9.4f}{r['dlnphi_b']:9.4f}")
    p("")
    p("== Probe dense / supercritical states (direct evaluation; no network-path column) ==")
    p(f"{'fluid':14}{'T[K]':>8}{'P[MPa]':>8}{'phase':>14}{'band':>5}{'rho ref':>10}{'drho a':>8}{'drho b':>8}"
      f"{'dcp':>8}{'dh a J':>8}{'dh b J':>8}{'dlnphi a':>9}{'dlnphi b':>9}")
    for r in rows:
        if r["set"] == "probe-dense":
            p(f"{r['fluid']:14}{r['T']:8.2f}{r['P_MPa']:8.2f}{r['phase']:>14}{r['band']:5d}{r['rho_ref']:10.1f}"
              f"{r['drho_a']:8.2f}{r['drho_b']:8.2f}{r['dcp_a']:8.2f}{r['dh_a']:8.0f}{r['dh_b']:8.0f}"
              f"{r['dlnphi_a']:9.4f}{r['dlnphi_b']:9.4f}")
    p("")
    p("== Critical-band points (excluded from the means above) ==")
    for n in fl:
        sel = [r for r in rows if r["fluid"] == n and r["band"]]
        if sel:
            ma, wa = stats(sel, "drho_a")
            mb, wb = stats(sel, "drho_b")
            mc, wc = stats(sel, "dcp_a")
            p(f"{n:14} n={len(sel):3d}  |drho| a {ma:6.2f} (worst {wa:7.2f})  b {mb:6.2f} (worst {wb:7.2f})  "
              f"|dcp| {mc:7.2f} (worst {wc:8.2f})")
    p("")
    p("== Worst non-band points outside the band but Tr > 0.9 (near-critical saturation/liquid) ==")
    for n in fl:
        sel = [r for r in main_rows if r["fluid"] == n and not r["band"] and r["Tr"] > 0.9
               and r["phase"] == "liquid-like"]
        if sel:
            r = max(sel, key=lambda x: abs(x["drho_b"]))
            p(f"{n:14} T={r['T']:.2f} K P={r['P_MPa']:.3f} MPa Tr={r['Tr']:.3f} Pr={r['Pr']:.3f} "
              f"drho a {r['drho_a']:.2f} b {r['drho_b']:.2f} dcp {r['dcp_a']:.2f}")
    p("")
    p("== Root mismatches (PR has one root on the other branch than CoolProp's phase) ==")
    for r in rows:
        if r["mismatch_a"] or r["mismatch_b"]:
            p(f"{r['fluid']:14}{r['set']:>12} T={r['T']:.2f} P={r['P_MPa']:.3f} {r['phase']} band={r['band']} "
              f"drho a {r['drho_a']:.1f} b {r['drho_b']:.1f}")
    p("")
    p("== Anchor scan: constant translation anchored on the saturated liquid at Tr_anchor; mean |drho| % over the "
      "non-band isobar + saturation points ==")
    p(f"{'fluid':14}{'anchor':>9}{'c[cm3/mol]':>11}{'liq Tr<0.8':>11}{'liq>=0.8':>9}{'liq all':>8}{'liq worst':>10}"
      f"{'vap+sc':>8}{'all':>7}")
    for n, lab, c, a1, a2, a3, wl, a4, a5 in scan:
        p(f"{n:14}{lab:>9}{c:11.3f}{a1:11.2f}{a2:9.2f}{a3:8.2f}{wl:10.2f}{a4:8.2f}{a5:7.2f}")
    p("")
    p(f"== Reference refused {len(skipped)} points (below the melting line at that pressure) ==")
    for sk in skipped:
        p(f"  {sk}")
    txt = "\n".join(L)
    with open(os.path.join(HERE, "item4-output.txt"), "w") as f:
        f.write(txt + "\n")
    print(txt)


if __name__ == "__main__":
    main()
