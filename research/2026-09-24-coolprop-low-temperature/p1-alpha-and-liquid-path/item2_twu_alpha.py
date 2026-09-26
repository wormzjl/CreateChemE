"""P1 item 2 (decision D3): Soave (PR78) versus Twu 1991 alpha in the same PR78 (unchanged a_c, b; constant
translation re-anchored per alpha on the CoolProp saturated liquid at Tr = 0.8), against CoolProp 8.0.0 HEOS.

Parameter routes, in the order of preference of the P1 brief:
  (1) published tc-PR L, M, N (Pina-Martinez et al. 2018 / 2022 tables). The ACS supporting information refused
      (HTTP 403); the values below are the secondary copy in Clapeyron.jl (MIT), database/cubic/tcPR/tcPR_single.csv
      at master 0778184abbe0de50791b6338cffe3444d4017508 (2026-09-23), whose tcPR model cites Le Guennec et al. 2016,
      Pina-Martinez et al. 2018 (JCED 63, 3980) and Pina-Martinez et al. 2022 (AIChE J 68, e17518). Hydrogen has no
      entry there.
  (2) generalized tc-PR Twu parameters as functions of omega (N = 2), as documented in the same Clapeyron.jl model
      (2018 and 2022 versions). Note: FPE 485, 264 (Pina-Martinez, Privat, Jaubert, Peng 2019) is the updated
      generalized SOAVE m(omega), not a generalized Twu alpha; its HAL copy refused (Anubis access denial).
  (3) own fit of L, M, N to CoolProp Psat, hvap and saturated-liquid cp over Tr max(0.3, Tr_triple) to 0.95, equal
      weights on relative errors, rejecting any set that violates the Le Guennec et al. 2016 consistency conditions
      on a Tr grid 0.2 to 20 (to 25 for hydrogen). FITTED VALUES ARE NOT LITERATURE VALUES.
Writes item2-output.txt next to this file."""
import math
import os
import numpy as np
from scipy.optimize import minimize
import CoolProp.CoolProp as CP
from pr78 import PR78, SoaveAlpha, TwuAlpha, consistency, coolprop_constants, Ref

HERE = os.path.dirname(os.path.abspath(__file__))
FLUIDS = ["Nitrogen", "Methane", "Ethane", "CarbonDioxide", "Hydrogen"]
LIT = {  # route (1), secondary copy (see module doc): L, M, N
    "Nitrogen": (0.124272851100283, 0.889814700276183, 2.0128512866036),
    "Methane": (0.147384886868325, 0.907477449053516, 1.82410890216284),
    "Ethane": (0.305327515842468, 0.869266115354361, 1.32966169703278),
    "CarbonDioxide": (0.178351313291442, 0.859029468643349, 2.41073648335997),
}
GEN = {  # route (2): L(omega), M(omega) polynomials (c0, c1, c2), N = 2
    "2018": ((0.0728, 0.6693, 0.0925), (0.8788, -0.2258, 0.1695)),
    "2022": ((0.0544, 0.7536, 0.0297), (0.8678, -0.1785, 0.1401)),
}
SAT = {"Nitrogen": [63.151, 70, 77.355, 90, 100, 110, 120],
       "Methane": [90.7, 100, 111.67, 130, 150, 170, 185],
       "Ethane": [90.4, 120, 150, 184.55, 220, 250, 280, 295],
       "CarbonDioxide": [216.6, 230, 250, 270, 290, 300],
       "Hydrogen": [14.0, 17.0, 20.369, 24.0, 28.0, 31.0]}
DENSE = {"Nitrogen": [(77.355, 5), (77.355, 10), (100, 10), (130, 6), (130, 10), (150, 10), (300, 10)],
         "CarbonDioxide": [(280, 8), (280, 10), (350, 10), (400, 10)],
         "Methane": [(110, 10), (150, 10), (200, 10), (250, 10), (300, 10)],
         "Ethane": [(200, 10), (300, 10), (320, 10), (400, 10)],
         "Hydrogen": [(300, 10), (623, 6), (650, 9), (663, 9.1), (700, 10)]}  # probe states outside the band


def pct(x, y):
    return 100 * (x / y - 1)


def ref_sat(name, T):
    st = CP.AbstractState("HEOS", name)
    st.update(CP.QT_INPUTS, 0, T)
    P, rhoL, cpL, cp0, hL = st.p(), st.rhomolar(), st.cpmolar(), st.cp0molar(), st.hmolar()
    st.update(CP.QT_INPUTS, 1, T)
    return {"P": P, "rhoL": rhoL, "cpL": cpL, "cp0": cp0, "hvap": st.hmolar() - hL}


def sat_dev(m, name, T, r=None):
    r = r or ref_sat(name, T)
    s = m.sat_props(T, guess=r["P"])
    return {"Psat": pct(s["P"], r["P"]), "rhoL": pct(s["rhoL"], r["rhoL"]),
            "cpL": pct(r["cp0"] + s["cpresL"], r["cpL"]), "hvap": pct(s["hvap"], r["hvap"])}


def model(name, alpha):
    Tc, Pc, w, Ttr = coolprop_constants(name)
    m = PR78(name, Tc, Pc, w, alpha)
    m.anchor_tr08()
    return m


def fit_setup(name):
    Tc, Pc, w, Ttr = coolprop_constants(name)
    lo = max(0.3, Ttr / Tc * 1.0005)
    Ts = [x * Tc for x in np.linspace(lo, 0.95, 14)]
    refs = [ref_sat(name, T) for T in Ts]
    m = PR78(name, Tc, Pc, w)

    def sumsq(alpha):
        m.alpha = alpha
        s = 0.0
        for T, r in zip(Ts, refs):
            d = sat_dev(m, name, T, r)
            s += (d["Psat"] / 100) ** 2 + (d["hvap"] / 100) ** 2 + (d["cpL"] / 100) ** 2
        return s

    def rms(alpha):
        return math.sqrt(sumsq(alpha) / (3 * len(Ts))) * 100
    return sumsq, rms, (lo, 0.95, len(Ts))


def fit_twu(name, starts, tr_hi_check, fix_n=None):
    sumsq, rms, rng = fit_setup(name)

    def unpack(x):
        return (x[0], x[1], fix_n) if fix_n is not None else tuple(x)

    def obj(x):
        L, M, N = unpack(x)
        if not (N > 0 and 0 < M and L > -5):
            return 1e6
        a = TwuAlpha(L, M, N)
        try:
            ok, _, _ = consistency(a, 0.2, tr_hi_check, 20000)
        except (OverflowError, ValueError):
            return 1e6
        if not ok:
            return 1e6
        try:
            return sumsq(a)
        except (ValueError, ZeroDivisionError, OverflowError):
            return 1e6

    best = None
    for x0 in starts:
        x0 = x0[:2] if fix_n is not None else x0
        if obj(x0) >= 1e6:
            continue
        res = minimize(obj, x0, method="Nelder-Mead",
                       options={"xatol": 1e-7, "fatol": 1e-12, "maxiter": 4000, "maxfev": 8000})
        if best is None or res.fun < best.fun:
            best = res
    if best is None:
        return None, float("nan"), False, rng
    L, M, N = unpack(best.x)
    ok, cond, trf = consistency(TwuAlpha(L, M, N), 0.2, tr_hi_check, 20000)
    return (L, M, N), best.fun, ok, rng


def main():
    out = []
    p = out.append
    p("P1 item 2: Soave (PR78) vs Twu 1991 alpha in the same PR78, CoolProp 8.0.0 HEOS reference")
    p("translation re-anchored per alpha at the CoolProp saturated liquid at Tr = 0.8 (D1); saturation properties "
      "at the model's own Psat; cpL = CoolProp cp0 + PR residual cp")
    p("")
    params = {}
    route = {}
    for n in FLUIDS:
        if n in LIT:
            params[n] = LIT[n]
            route[n] = "1 (tc-PR table, Clapeyron.jl copy)"
    # route 2 check for hydrogen
    Tc, Pc, w, Ttr = coolprop_constants("Hydrogen")
    p("== Route 2 for hydrogen: generalized tc-PR Twu (N = 2) at CoolProp omega = %.4f ==" % w)
    gen_ok = None
    for ver, (lc, mc) in GEN.items():
        L = lc[0] + lc[1] * w + lc[2] * w * w
        M = mc[0] + mc[1] * w + mc[2] * w * w
        ok, cond, trf = consistency(TwuAlpha(L, M, 2.0), 0.2, 25.0, 20000)
        p(f"  {ver}: L={L:.5f} M={M:.5f} N=2  consistent on Tr 0.2-25: {ok}" +
          ("" if ok else f" (first violation {cond} at Tr={trf:.3f})"))
        if ok and gen_ok is None:
            gen_ok = (ver, (L, M, 2.0))
    if gen_ok:
        params["Hydrogen"] = gen_ok[1]
        route["Hydrogen"] = f"2 (generalized {gen_ok[0]})"
    p("")
    # route 3 fits: hydrogen (needed) and every fluid as a labelled sensitivity (not literature)
    fitted = {}
    p("== Route 3 fits (NOT literature values): L, M, N fitted to CoolProp Psat, hvap, cpL, equal relative weights ==")
    for n in FLUIDS:
        hi = 25.0 if n == "Hydrogen" else 20.0
        starts = [(0.15, 0.9, 2.0), (0.3, 0.87, 1.3), (0.5, 0.85, 1.0), (0.1, 0.95, 3.0), (1.0, 0.5, 0.8)]
        if n in LIT:
            starts.insert(0, LIT[n])
        (L, M, N), f, ok, rng = fit_twu(n, starts, hi)
        fitted[n] = (L, M, N)
        rms = math.sqrt(f / (3 * rng[2])) * 100
        p(f"  {n:14} L={L:.10g} M={M:.10g} N={N:.10g}  rms rel. error {rms:.2f} %  fit Tr {rng[0]:.3f}-{rng[1]:.2f} "
          f"({rng[2]} T)  consistent Tr 0.2-{hi:g}: {ok}; small-N power-law exponent N(1-M+LM) = {N * (1 - M + L * M):.4g}")
    if "Hydrogen" not in params:
        params["Hydrogen"] = fitted["Hydrogen"]
        route["Hydrogen"] = "3 (own fit, not literature)"
    p("")
    p("== Objective of the fit (rms of relative Psat, hvap, cpL errors on the fit grid, %): Soave / Twu used / fitted ==")
    for n in FLUIDS:
        Tc, Pc, w, Ttr = coolprop_constants(n)
        _, rms, rng = fit_setup(n)
        p(f"  {n:14}{rms(SoaveAlpha(w)):8.2f}{rms(TwuAlpha(*params[n])):8.2f}{rms(TwuAlpha(*fitted[n])):8.2f}")
    p("")
    p("== Hydrogen sensitivity: consistent Twu with N fixed, and an unattributed Clapeyron.jl Twu_like.csv set ==")
    h2sets = {"alpha=1 (fit limit)": (0.0, 1.0, 1.0)}
    for nfix in (1.0, 2.0):
        prm, fv, ok, rng = fit_twu("Hydrogen", [(0.05, 0.95, nfix), (0.2, 0.9, nfix), (0.5, 0.8, nfix),
                                               (0.01, 0.99, nfix)], 25.0, fix_n=nfix)
        if prm:
            h2sets[f"fit N={nfix:g} (not lit.)"] = prm
    h2sets["Twu_like.csv (no source)"] = (0.022437, 0.99999983, 0.999673209)
    Tc, Pc, w, Ttr = coolprop_constants("Hydrogen")
    _, rms, rng = fit_setup("Hydrogen")
    rf = Ref("Hydrogen")
    hdr = "".join(f"{f'rho {T:g}K/{PM:g}':>15}" for T, PM in DENSE["Hydrogen"])
    p(f"  {'set':26}{'L':>10}{'M':>12}{'N':>10}{'consistent':>11}{'rms fit':>8}{hdr}")
    for lab, (L, M, N) in [("Soave", (float('nan'),) * 3)] + list(h2sets.items()):
        a = SoaveAlpha(w) if lab == "Soave" else TwuAlpha(L, M, N)
        ok, cond, trf = consistency(a, 0.2, 25.0, 20000)
        mm = model("Hydrogen", a)
        devs = []
        for T, PM in DENSE["Hydrogen"]:
            r = rf.tp(T, PM * 1e6)
            devs.append(pct(1 / mm.state(T, PM * 1e6, "G")["v"], r["rho"]))
        p(f"  {lab:26}{L:10.5f}{M:12.8f}{N:10.5f}{str(ok):>11}{rms(a):8.2f}" + "".join(f"{d:15.2f}" for d in devs))
    p("")
    p("== Parameters used as 'Twu' below, route per fluid, consistency (alpha>0, alpha'<0, alpha''>0, alpha'''<0) ==")
    for n in FLUIDS:
        L, M, N = params[n]
        hi = 25.0 if n == "Hydrogen" else 20.0
        okT, cT, tT = consistency(TwuAlpha(L, M, N), 0.2, hi, 20000)
        Tc, Pc, w, Ttr = coolprop_constants(n)
        okS, cS, tS = consistency(SoaveAlpha(w), 0.2, hi, 20000)
        mS = SoaveAlpha(w).m
        p(f"  {n:14} route {route[n]:36} L={L:.8g} M={M:.8g} N={N:.8g}  Twu consistent 0.2-{hi:g}: {okT}"
          + ("" if okT else f" ({cT} at Tr {tT:.3f})")
          + f"  | Soave m={mS:.4f} consistent: {okS}" + ("" if okS else f" ({cS} at Tr {tS:.3f})")
          + f", Soave minimum at Tr={(1 + 1 / mS) ** 2:.1f}")
    p("")

    # saturation comparison at the probe states
    p("== Saturation states (probe list; H2 added): deviation % from CoolProp; S = Soave, T = Twu (route above), "
      "F = Twu fitted (not literature) ==")
    p(f"{'fluid':14}{'T[K]':>8}{'Tr':>6}{'Psat S':>8}{'Psat T':>8}{'Psat F':>8}{'rhoL S':>8}{'rhoL T':>8}"
      f"{'rhoL F':>8}{'cpL S':>8}{'cpL T':>8}{'cpL F':>8}{'hvap S':>8}{'hvap T':>8}{'hvap F':>8}")
    for n in FLUIDS:
        Tc, Pc, w, Ttr = coolprop_constants(n)
        ms = model(n, SoaveAlpha(w))
        mt = model(n, TwuAlpha(*params[n]))
        mf = model(n, TwuAlpha(*fitted[n]))
        for T in SAT[n]:
            r = ref_sat(n, T)
            ds, dt, df = sat_dev(ms, n, T, r), sat_dev(mt, n, T, r), sat_dev(mf, n, T, r)
            p(f"{n:14}{T:8.2f}{T / Tc:6.3f}" + "".join(f"{d[k]:8.2f}" for k in ("Psat", "rhoL", "cpL", "hvap")
                                                      for d in (ds, dt, df)))
    p("")
    # sweep statistics by Tr band
    bands = [("Ttr-0.5", 0.0, 0.5), ("0.5-0.7", 0.5, 0.7), ("0.7-0.85", 0.7, 0.85), ("0.85-0.95", 0.85, 0.951)]
    p("== Saturation sweep (30 T from the triple point to Tr 0.95): mean |dev| % per Tr band, S / T / F ==")
    p(f"{'fluid':14}{'Tr band':>10}{'n':>4}{'Psat S':>8}{'Psat T':>8}{'Psat F':>8}{'rhoL S':>8}{'rhoL T':>8}"
      f"{'rhoL F':>8}{'cpL S':>8}{'cpL T':>8}{'cpL F':>8}{'hvap S':>8}{'hvap T':>8}{'hvap F':>8}")
    summary = {}
    for n in FLUIDS:
        Tc, Pc, w, Ttr = coolprop_constants(n)
        ms = model(n, SoaveAlpha(w))
        mt = model(n, TwuAlpha(*params[n]))
        mf = model(n, TwuAlpha(*fitted[n]))
        Ts = np.linspace(Ttr * 1.0005, 0.95 * Tc, 30)
        recs = []
        for T in Ts:
            r = ref_sat(n, T)
            recs.append((T / Tc, sat_dev(ms, n, T, r), sat_dev(mt, n, T, r), sat_dev(mf, n, T, r)))
        for lab, lo, hi in bands + [("all<=0.85", 0.0, 0.8501)]:
            sel = [x for x in recs if lo <= x[0] < hi]
            if not sel:
                continue
            vals = []
            for k in ("Psat", "rhoL", "cpL", "hvap"):
                for j in (1, 2, 3):
                    vals.append(float(np.mean([abs(x[j][k]) for x in sel])))
            summary[(n, lab)] = vals
            p(f"{n:14}{lab:>10}{len(sel):4d}" + "".join(f"{v:8.2f}" for v in vals))
    p("")
    # dense / supercritical states outside the critical band
    p("== Dense and supercritical probe states outside the critical band: rho and cp deviation %, S / T / F ==")
    p(f"{'fluid':14}{'T[K]':>8}{'P[MPa]':>8}{'Tr':>7}{'rho S':>8}{'rho T':>8}{'rho F':>8}{'cp S':>8}{'cp T':>8}"
      f"{'cp F':>8}")
    ref_cache = {}
    for n in FLUIDS:
        Tc, Pc, w, Ttr = coolprop_constants(n)
        ms = model(n, SoaveAlpha(w))
        mt = model(n, TwuAlpha(*params[n]))
        mf = model(n, TwuAlpha(*fitted[n]))
        rf = Ref(n)
        for T, PM in DENSE[n]:
            P = PM * 1e6
            r = rf.tp(T, P)
            root = "L" if r["phase"] in (CP.iphase_liquid, CP.iphase_supercritical_liquid) else "G"
            vals = []
            for m in (ms, mt, mf):
                vals.append(pct(1 / m.state(T, P, root)["v"], r["rho"]))
            for m in (ms, mt, mf):
                vals.append(pct(r["cp0"] + m.cp_res(T, P, root), r["cp"]))
            p(f"{n:14}{T:8.2f}{PM:8.2f}{T / Tc:7.2f}" + "".join(f"{v:8.2f}" for v in vals))
    p("")
    p("== Translation c [cm3/mol] re-anchored at Tr = 0.8 per alpha: Soave / Twu / fitted ==")
    for n in FLUIDS:
        Tc, Pc, w, Ttr = coolprop_constants(n)
        cs = model(n, SoaveAlpha(w)).c * 1e6
        ct = model(n, TwuAlpha(*params[n])).c * 1e6
        cf = model(n, TwuAlpha(*fitted[n])).c * 1e6
        p(f"  {n:14}{cs:8.3f}{ct:8.3f}{cf:8.3f}")
    txt = "\n".join(out)
    with open(os.path.join(HERE, "item2-output.txt"), "w") as f:
        f.write(txt + "\n")
    print(txt)


if __name__ == "__main__":
    main()
