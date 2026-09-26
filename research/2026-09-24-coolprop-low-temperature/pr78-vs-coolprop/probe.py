"""PR78 in the mod's formulation (Soave kappa with the 0.491 split, constant volume translation anchored at a
2 MPa liquid reference, liquid carried to the state pressure by the global exp(-k dP) response with k = 1e-9 /Pa)
against CoolProp's reference Helmholtz equations, at the states the unified-thermo plan targets.
Pure components only; the ideal-gas part is taken from CoolProp so every deviation below is the residual model's.
Read-only research probe (2026-09-24), no product data touched."""
import math
import CoolProp.CoolProp as CP
from CoolProp.CoolProp import PropsSI

R = 8.31446261815324
K_GLOBAL = 1e-9          # GlobalLiquidResponse compressibility, 1/Pa
P_REF = 2e6              # HydrocarbonModel.REFERENCE_PRESSURE
SQ2 = math.sqrt(2)


def kappa(w):
    if w <= 0.491:
        return 0.37464 + 1.54226 * w - 0.26992 * w * w
    return 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w ** 3


def cbrt(x):
    return math.copysign(abs(x) ** (1 / 3), x)


class PR78:
    def __init__(s, name, Tc, Pc, w):
        s.name, s.Tc, s.Pc, s.w = name, Tc, Pc, w
        s.ac = 0.45724 * R * R * Tc * Tc / Pc
        s.b = 0.07780 * R * Tc / Pc
        s.k = kappa(w)
        s.c = 0.0

    def a(s, T):
        sq = math.sqrt(T / s.Tc)
        f = 1 + s.k * (1 - sq)
        return s.ac * f * f, -s.ac * s.k * f / math.sqrt(T * s.Tc)

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
        return [x for x in z if x > B + 1e-12], B

    def Z(s, T, P, liquid):
        z, _ = s.roots(T, P)
        return min(z) if liquid else max(z)

    def lnphi_hres(s, T, P, liquid):
        a, da = s.a(T)
        z = s.Z(T, P, liquid)
        A = a * P / (R * R * T * T)
        B = s.b * P / (R * T)
        lg = math.log((z + (1 + SQ2) * B) / (z + (1 - SQ2) * B))
        lnphi = z - 1 - math.log(z - B) - A / (2 * SQ2 * B) * lg
        hres = R * T * (z - 1) + (T * da - a) / (2 * SQ2 * s.b) * lg
        return lnphi, hres

    def v_raw(s, T, P, liquid):
        return s.Z(T, P, liquid) * R * T / P

    def anchor(s, T, v_target):
        """Translation as HydrocarbonModel: shift = v_ref(T_a, 2 MPa) - v_PR(T_a, 2 MPa)."""
        s.c = v_target - s.v_raw(T, P_REF, True)

    def v_liquid_network(s, T, P):
        """The network's liquid path: translated PR at 2 MPa, carried to P by the global response."""
        return (s.v_raw(T, P_REF, True) + s.c) * math.exp(-K_GLOBAL * (P - P_REF))

    def v_direct(s, T, P, liquid):
        return s.v_raw(T, P, liquid) + s.c

    def psat(s, T):
        """Equal-fugacity pressure by bisection in log P. With one real root the side is decided by the root's
        branch (the kernel's own v/b > 3.95 vapour heuristic): a lone vapour root means P < Psat."""
        if T >= s.Tc:
            return float("nan")
        lo, hi = 1.0, s.Pc
        for _ in range(200):
            p = math.sqrt(lo * hi)
            z, B = s.roots(T, p)
            if len(z) < 3:
                if z[0] / B > 3.9513730355914:
                    lo = p
                else:
                    hi = p
                continue
            if s.lnphi_hres(T, p, True)[0] > s.lnphi_hres(T, p, False)[0]:
                lo = p
            else:
                hi = p
        return math.sqrt(lo * hi)

    def cp_res(s, T, P, liquid, h=0.02):
        """d(h_res)/dT at constant P; the constant translation adds P*c to h and nothing to cp."""
        return (s.lnphi_hres(T + h, P, liquid)[1] - s.lnphi_hres(T - h, P, liquid)[1]) / (2 * h)


def pct(a, b):
    return 100 * (a / b - 1)


fluids = {}
for name, anchorT in [("Nitrogen", 90.0), ("Methane", 150.0), ("Ethane", 240.0), ("CarbonDioxide", 250.0),
                      ("Hydrogen", None)]:
    m = PR78(name, PropsSI("Tcrit", name), PropsSI("pcrit", name), PropsSI("acentric", name))
    if anchorT:
        m.anchor(anchorT, 1 / PropsSI("Dmolar", "T", anchorT, "P", P_REF, name))
    fluids[name] = m
    print(f"{name}: Tc={m.Tc:.3f} K Pc={m.Pc / 1e6:.4f} MPa w={m.w:.4f} kappa={m.k:.4f} "
          f"translation c={m.c * 1e6:+.3f} cm3/mol (anchor {anchorT} K, 2 MPa)")

print("\n== Saturation: PR78 vs reference EOS. rhoL columns: raw PR at Psat, translated PR at Psat, and the "
      "network liquid path (2 MPa reference carried by the global k). cpL = CoolProp cp0 + PR residual. ==")
print(f"{'fluid':14}{'T[K]':>8}{'Psat ref[kPa]':>14}{'dPsat%':>8}{'rhoL ref':>10}{'raw%':>7}{'transl%':>8}"
      f"{'netpath%':>9}{'cpL ref':>9}{'cpL PR%':>8}{'hvap ref':>10}{'hvap PR%':>9}{'kT ref[1/Pa]':>14}")
sat = {"Nitrogen": [63.151, 70, 77.355, 90, 100, 110, 120],
       "Methane": [90.7, 100, 111.67, 130, 150, 170, 185],
       "Ethane": [90.4, 120, 150, 184.55, 220, 250, 280, 295],
       "CarbonDioxide": [216.6, 230, 250, 270, 290, 300]}
for name, Ts in sat.items():
    m = fluids[name]
    for T in Ts:
        try:
            Pr = PropsSI("P", "T", T, "Q", 0, name)
            rho = PropsSI("Dmolar", "T", T, "Q", 0, name)
            cpL = PropsSI("Cpmolar", "T", T, "Q", 0, name)
            hv = PropsSI("Hmolar", "T", T, "Q", 1, name) - PropsSI("Hmolar", "T", T, "Q", 0, name)
            kT = PropsSI("isothermal_compressibility", "T", T, "Q", 0, name)
            cp0 = PropsSI("Cp0molar", "T", T, "Q", 0, name)
            Pp = m.psat(T)
            rho_raw = 1 / m.v_raw(T, Pp, True)
            rho_tr = 1 / m.v_direct(T, Pp, True)
            rho_net = 1 / m.v_liquid_network(T, Pp)
            cpPR = cp0 + m.cp_res(T, Pp, True)
            hvPR = m.lnphi_hres(T, Pp, False)[1] - m.lnphi_hres(T, Pp, True)[1]
            print(f"{name:14}{T:8.2f}{Pr / 1e3:14.3f}{pct(Pp, Pr):8.2f}{rho:10.1f}{pct(rho_raw, rho):7.2f}"
                  f"{pct(rho_tr, rho):8.2f}{pct(rho_net, rho):9.2f}{cpL:9.2f}{pct(cpPR, cpL):8.2f}{hv:10.1f}"
                  f"{pct(hvPR, hv):9.2f}{kT:14.3e}")
        except Exception as e:
            print(f"{name:14}{T:8.2f}  error: {e}")

print("\n== Dense / supercritical single phase: density and cp, translated PR78 evaluated directly at (T,P) vs "
      "reference; netpath = the network's liquid path where the reference state is liquid-like and subcritical ==")
print(f"{'fluid':14}{'T[K]':>8}{'P[MPa]':>8}{'phase(ref)':>22}{'rho ref':>10}{'rho PR%':>9}{'netpath%':>9}"
      f"{'cp ref':>9}{'cp PR%':>8}{'Z ref':>7}")
dense = {"Nitrogen": [(77.355, 5), (77.355, 10), (100, 10), (126.2, 3.4), (130, 4), (130, 6), (130, 10),
                      (150, 10), (300, 10)],
         "CarbonDioxide": [(280, 8), (280, 10), (300, 8), (304.5, 7.4), (310, 8), (310, 10), (320, 8), (320, 10),
                           (350, 10), (400, 10)],
         "Methane": [(110, 10), (150, 10), (190.6, 4.6), (200, 6), (200, 10), (250, 10), (300, 10)],
         "Ethane": [(200, 10), (300, 10), (305.4, 4.9), (320, 6), (320, 10), (400, 10)],
         "Hydrogen": [(300, 10), (623, 6), (650, 9), (663, 9.1), (700, 10)]}
for name, states in dense.items():
    m = fluids[name]
    for T, PMPa in states:
        P = PMPa * 1e6
        try:
            rho = PropsSI("Dmolar", "T", T, "P", P, name)
            cp = PropsSI("Cpmolar", "T", T, "P", P, name)
            Z = P / (rho * R * T)
            ph = CP.PhaseSI("T", T, "P", P, name)
            cp0 = PropsSI("Cp0molar", "T", T, "P", P, name)
            liquid = ph in ("liquid", "supercritical_liquid")
            rhoPR = 1 / m.v_direct(T, P, liquid)
            cpPR = cp0 + m.cp_res(T, P, liquid)
            net = pct(1 / m.v_liquid_network(T, P), rho) if (liquid and T < m.Tc) else float("nan")
            print(f"{name:14}{T:8.2f}{PMPa:8.2f}{ph:>22}{rho:10.1f}{pct(rhoPR, rho):9.2f}{net:9.2f}{cp:9.2f}"
                  f"{pct(cpPR, cp):8.2f}{Z:7.3f}")
        except Exception as e:
            print(f"{name:14}{T:8.2f}{PMPa:8.2f}  error: {e}")

print("\n== Reference-EOS ceilings (CoolProp Tmax) versus the steam-cracking target 1073-1143 K ==")
for name in ["Methane", "Ethane", "Ethylene", "Propane", "Nitrogen", "CarbonDioxide", "Hydrogen", "Water"]:
    st = CP.AbstractState("HEOS", name)
    print(f"{name:14} Tmin={st.Tmin():8.3f} K  Tmax={st.Tmax():8.1f} K  pmax={st.pmax() / 1e6:8.1f} MPa")
for name in ["Methane", "Ethane"]:
    try:
        v = PropsSI("Cpmolar", "T", 1100, "P", 2e5, name)
        print(f"{name}: Cp at 1100 K, 0.2 MPa returned {v:.3f} J/mol/K (beyond Tmax; CoolProp did not refuse)")
    except Exception as e:
        print(f"{name}: Cp at 1100 K, 0.2 MPa refused: {str(e)[:100]}")
