"""Pure-component PR78 in the mod's formulation, extended for the P1 study (2026-09-24).

Same model as ../pr78-vs-coolprop/probe.py (Soave kappa with the 0.491 split, a_c and b of PR, constant volume
translation v = v_PR + c, h gets +P c and ln phi gets +P c/(RT) as in TranslatedPengRobinson), with:
- a pluggable alpha function: Soave (PR78) or Twu 1991, alpha = Tr^(N(M-1)) exp[L(1 - Tr^(NM))];
- two translation anchors: 'current' (liquid volume at the calibration temperature and 2 MPa, as
  HydrocarbonModel/liquid_calibration.json) and 'tr08' (saturated-liquid volume at Tr = 0.8 from CoolProp,
  matched by the translated PR evaluated directly at (0.8 Tc, Psat_ref));
- a Newton saturation solver with the probe's bisection as fallback.
The ideal-gas part is CoolProp's, so every property deviation is the residual model's (plus the translation).
Research only; no product code or data touched."""
import math
import numpy as np
import CoolProp.CoolProp as CP

R = 8.31446261815324
P_REF = 2e6
SQ2 = math.sqrt(2)
T_H_REF = 298.15  # enthalpy convention: h - h_ig(298.15 K, 0.1 MPa)


def kappa(w):
    if w <= 0.491:
        return 0.37464 + 1.54226 * w - 0.26992 * w * w
    return 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w ** 3


def cbrt(x):
    return math.copysign(abs(x) ** (1 / 3), x)


class SoaveAlpha:
    name = "Soave"

    def __init__(s, w):
        s.m = kappa(w)

    def f(s, tr):
        """alpha, d alpha/d Tr"""
        sq = math.sqrt(tr)
        g = 1 + s.m * (1 - sq)
        return g * g, -s.m * g / sq

    def derivs(s, tr):
        """alpha and its first three Tr-derivatives (for the consistency check); tr may be a numpy array."""
        sq = np.sqrt(tr)
        g = 1 + s.m * (1 - sq)
        a1 = -s.m * g / sq
        a2 = s.m * (1 + s.m) / (2 * tr ** 1.5)
        a3 = -3 * s.m * (1 + s.m) / (4 * tr ** 2.5)
        return g * g, a1, a2, a3


class TwuAlpha:
    name = "Twu"

    def __init__(s, L, M, N):
        s.L, s.M, s.N = L, M, N
        s.A = N * (M - 1)
        s.B = N * M

    def f(s, tr):
        lnA = s.A * math.log(tr) + s.L * (1 - tr ** s.B)
        a = math.exp(lnA)
        d1 = s.A / tr - s.L * s.B * tr ** (s.B - 1)
        return a, a * d1

    def derivs(s, tr):
        A, B, L = s.A, s.B, s.L
        a = np.exp(A * np.log(tr) + L * (1 - tr ** B))
        f1 = A / tr - L * B * tr ** (B - 1)
        f2 = -A / tr ** 2 - L * B * (B - 1) * tr ** (B - 2)
        f3 = 2 * A / tr ** 3 - L * B * (B - 1) * (B - 2) * tr ** (B - 3)
        return a, a * f1, a * (f1 * f1 + f2), a * (f1 ** 3 + 3 * f1 * f2 + f3)


def consistency(alpha, tr_lo=0.2, tr_hi=20.0, n=20000):
    """Le Guennec et al. 2016 conditions on a log grid of n+1 points: alpha > 0, alpha' < 0, alpha'' > 0,
    alpha''' < 0 (Tr-derivatives; same signs as T-derivatives). Returns (ok, first violated condition, Tr)."""
    tr = np.geomspace(tr_lo, tr_hi, n + 1)
    with np.errstate(all="ignore"):
        a, a1, a2, a3 = alpha.derivs(tr)
    first = None
    for cond, bad in (("alpha>0", ~(a > 0)), ("dalpha<0", ~(a1 < 0)), ("d2alpha>0", ~(a2 > 0)),
                      ("d3alpha<0", ~(a3 < 0))):
        idx = np.flatnonzero(bad)
        if idx.size and (first is None or tr[idx[0]] < first[1]):
            first = (cond, float(tr[idx[0]]))
    return (True, "", None) if first is None else (False, first[0], first[1])


class PR78:
    def __init__(s, name, Tc, Pc, w, alpha=None):
        s.name, s.Tc, s.Pc, s.w = name, Tc, Pc, w
        s.ac = 0.45724 * R * R * Tc * Tc / Pc
        s.b = 0.07780 * R * Tc / Pc
        s.alpha = alpha if alpha is not None else SoaveAlpha(w)
        s.c = 0.0

    def a(s, T):
        al, dal = s.alpha.f(T / s.Tc)
        return s.ac * al, s.ac * dal / s.Tc

    def roots(s, T, P):
        a, _ = s.a(T)
        A = a * P / (R * R * T * T)
        B = s.b * P / (R * T)
        c2, c1, c0 = -(1 - B), A - 3 * B * B - 2 * B, -(A * B - B * B - B ** 3)
        p = c1 - c2 * c2 / 3
        q = 2 * c2 ** 3 / 27 - c2 * c1 / 3 + c0
        disc = q * q / 4 + p ** 3 / 27
        off = c2 / 3
        if disc > 1e-16:
            r = math.sqrt(disc)
            z = [cbrt(-q / 2 + r) + cbrt(-q / 2 - r) - off]
        else:
            rad = 2 * math.sqrt(-p / 3)
            ang = math.acos(max(-1.0, min(1.0, (3 * q / (2 * p)) * math.sqrt(-3 / p)))) / 3
            z = sorted([rad * math.cos(ang) - off, rad * math.cos(ang - 2 * math.pi / 3) - off,
                        rad * math.cos(ang - 4 * math.pi / 3) - off])
        # Newton polish: at very low pressure the trigonometric roots lose ~1e-3 relative accuracy on the liquid
        # root (acos near +-1), which makes finite-difference cp noisy (the probe's ethane 90.4 K cp was affected).
        pol = []
        for x in z:
            for _ in range(3):
                fz = ((x + c2) * x + c1) * x + c0
                d = (3 * x + 2 * c2) * x + c1
                if d == 0:
                    break
                x -= fz / d
            pol.append(x)
        return [x for x in pol if x > B * (1 + 1e-9)], B

    def _res(s, T, P, z):
        a, da = s.a(T)
        A = a * P / (R * R * T * T)
        B = s.b * P / (R * T)
        lg = math.log((z + (1 + SQ2) * B) / (z + (1 - SQ2) * B))
        lnphi = z - 1 - math.log(z - B) - A / (2 * SQ2 * B) * lg
        hres = R * T * (z - 1) + (T * da - a) / (2 * SQ2 * s.b) * lg
        return lnphi, hres

    def Z(s, T, P, root):
        """root: 'L' smallest, 'V' largest, 'G' lowest residual Gibbs energy."""
        z, _ = s.roots(T, P)
        if root == "L":
            return min(z)
        if root == "V":
            return max(z)
        return min(z, key=lambda x: s._res(T, P, x)[0])

    def state(s, T, P, root):
        """Untranslated Z, ln phi, h_res; translated volume, ln phi and h (h_res + P c)."""
        z = s.Z(T, P, root)
        lnphi, hres = s._res(T, P, z)
        v = z * R * T / P + s.c
        return {"Z": z, "v": v, "lnphi": lnphi + P * s.c / (R * T), "hres": hres + P * s.c}

    def v_raw(s, T, P, root):
        return s.Z(T, P, root) * R * T / P

    def cp_res(s, T, P, root, h=0.02):
        return (s.state(T + h, P, root)["hres"] - s.state(T - h, P, root)["hres"]) / (2 * h)

    # --- translation anchors -------------------------------------------------------------------------------
    def anchor_current(s, T_a):
        """HydrocarbonModel: c = v_ref(T_a, 2 MPa) - v_PR(T_a, 2 MPa, liquid)."""
        st = CP.AbstractState("HEOS", s.name)
        st.update(CP.PT_INPUTS, P_REF, T_a)
        s.c = 1 / st.rhomolar() - s.v_raw(T_a, P_REF, "L")
        return s.c

    def anchor_tr08(s):
        """D1: c = v_satL,ref(0.8 Tc) - v_PR(0.8 Tc, Psat_ref, liquid root)."""
        T = 0.8 * s.Tc
        st = CP.AbstractState("HEOS", s.name)
        st.update(CP.QT_INPUTS, 0, T)
        s.c = 1 / st.rhomolar() - s.v_raw(T, st.p(), "L")
        return s.c

    def anchor_none(s):
        s.c = 0.0
        return s.c

    # --- saturation --------------------------------------------------------------------------------------
    def psat(s, T, guess=None):
        """Equal fugacity. Newton in ln P (d(lnphiL - lnphiV)/dlnP = ZL - ZV) from a guess, else the probe's
        bisection in log P."""
        if T >= s.Tc:
            return float("nan")
        if guess:
            lp = math.log(guess)
            for _ in range(60):
                p = math.exp(lp)
                z, _ = s.roots(T, p)
                if len(z) < 3:
                    break
                zl, zv = min(z), max(z)
                d = s._res(T, p, zl)[0] - s._res(T, p, zv)[0]
                if abs(d) < 1e-13:
                    return p
                step = -d / (zl - zv)
                lp += max(-0.5, min(0.5, step))
            # fall through to bisection
        lo, hi = 1e-6, s.Pc
        for _ in range(200):
            p = math.sqrt(lo * hi)
            z, B = s.roots(T, p)
            if len(z) < 3:
                if z[0] / B > 3.9513730355914:
                    lo = p
                else:
                    hi = p
                continue
            if s._res(T, p, min(z))[0] > s._res(T, p, max(z))[0]:
                lo = p
            else:
                hi = p
            if hi / lo < 1 + 1e-14:
                break
        return math.sqrt(lo * hi)

    def sat_props(s, T, guess=None):
        """PR at its own saturation pressure: Psat, rhoL (translated), residual cpL, hvap."""
        p = s.psat(T, guess)
        L = s.state(T, p, "L")
        V = s.state(T, p, "V")
        return {"P": p, "rhoL": 1 / L["v"], "cpresL": s.cp_res(T, p, "L"), "hvap": V["hres"] - L["hres"]}


def coolprop_constants(name):
    st = CP.AbstractState("HEOS", name)
    return st.T_critical(), st.p_critical(), st.acentric_factor(), st.Ttriple()


class Ref:
    """CoolProp HEOS reference at (T, P) or on saturation; h is reported as h - h_ig(298.15 K)."""

    def __init__(s, name):
        s.name = name
        s.st = CP.AbstractState("HEOS", name)
        s.st.update(CP.PT_INPUTS, 1e5, T_H_REF)
        s.hig298 = s.st.keyed_output(CP.iHmolar_idealgas)

    def _pack(s):
        st = s.st
        return {"rho": st.rhomolar(), "cp": st.cpmolar(), "h": st.hmolar() - s.hig298,
                "hig": st.keyed_output(CP.iHmolar_idealgas) - s.hig298, "cp0": st.cp0molar(),
                "lnphi": math.log(st.fugacity_coefficient(0)), "P": st.p(), "T": st.T()}

    def tp(s, T, P):
        s.st.update(CP.PT_INPUTS, P, T)
        d = s._pack()
        d["phase"] = s.st.phase()
        return d

    def sat(s, T, Q):
        """Saturated liquid (Q=0) or vapour (Q=1), re-evaluated as a single phase at its own density so the
        fugacity coefficient is defined."""
        s.st.update(CP.QT_INPUTS, Q, T)
        rho, p = s.st.rhomolar(), s.st.p()
        ph = CP.iphase_liquid if Q == 0 else CP.iphase_gas
        s.st.specify_phase(ph)
        try:
            s.st.update(CP.DmolarT_INPUTS, rho, T)
            d = s._pack()
        finally:
            s.st.unspecify_phase()
        d["P"] = p
        d["phase"] = ph
        return d


PHASE_CLASS = {CP.iphase_liquid: "liquid-like", CP.iphase_supercritical_liquid: "liquid-like",
               CP.iphase_gas: "vapour-like", CP.iphase_supercritical_gas: "vapour-like",
               CP.iphase_supercritical: "supercritical"}
PHASE_ROOT = {"liquid-like": "L", "vapour-like": "V", "supercritical": "G"}
