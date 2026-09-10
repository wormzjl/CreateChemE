"""Reproduce the methane Cp fit; requires NumPy and does not modify runtime properties."""

import json
import numpy as np


def heat_capacity(temperature):
    """NIST WebBook methane Shomate, 298..1300 K, J/(mol K)."""
    t = temperature / 1000
    return -0.703029 + 108.4773 * t - 42.52157 * t**2 + 5.862788 * t**3 + 0.678565 / t**2


def enthalpy_primitive(temperature):
    t = temperature / 1000
    return 1000 * (-0.703029 * t + 108.4773 * t**2 / 2 - 42.52157 * t**3 / 3
                   + 5.862788 * t**4 / 4 - 0.678565 / t)


if __name__ == "__main__":
    temperatures = np.linspace(298.15, 900, 1201)
    fit = np.polynomial.Polynomial.fit(temperatures - 298.15, heat_capacity(temperatures), 5).convert()
    check = np.linspace(298.15, 900, 10001)
    print(json.dumps({
        "source": "https://webbook.nist.gov/cgi/cbook.cgi?ID=C74828&Mask=1&Type=JANAFG&Table=on",
        "polynomialVariable": "T_kelvin - 298.15",
        "coefficientsAscending": fit.coef.tolist(),
        "maximumCpErrorJoulesPerMolKelvin": float(np.max(np.abs(fit(check - 298.15) - heat_capacity(check)))),
        "maximumEnthalpyErrorJoulesPerMol": float(np.max(np.abs(
            fit.integ()(check - 298.15) - (enthalpy_primitive(check) - enthalpy_primitive(298.15))))),
    }, indent=2))
