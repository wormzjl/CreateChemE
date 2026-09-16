"""Offline decimal reference table; never imports or executes production Java.

EPA EPANET 2.2, section 12, specifies the Swamee-Jain turbulent relation:
https://usepa.github.io/EPANET2.2/12_analysis_algorithms.html
Colebrook values are a separate correlation comparison, not the REF tolerance
for the explicitly selected Swamee-Jain model. Transition is a project-specific
C1 blend, so no EPANET transition equivalence is claimed.
"""
from decimal import Decimal as D, localcontext
import json
from pathlib import Path


def references():
    rows = []
    with localcontext() as context:
        context.prec = 60
        for reynolds in (1000, 2000, 3000, 4000, 5000, 10000, 100000):
            re = D(reynolds)
            for relative_roughness in ("0", "0.0001", "0.001", "0.01"):
                roughness = D(relative_roughness)
                argument = roughness / D("3.7") + D("5.74") / (D("0.9") * re.ln()).exp()
                turbulent = D("0.25") / argument.log10() ** 2
                laminar = 64 / re
                blend = min(D(1), max(D(0), (re - 2000) / 2000))
                weight = blend * blend * (3 - 2 * blend)
                friction = laminar * (1 - weight) + turbulent * weight
                # Bisect x=1/sqrt(f) in the implicit Colebrook equation.
                low, high = D(1), D(100)
                for _ in range(220):
                    x = (low + high) / 2
                    residual = x + 2 * (roughness / D("3.7") + D("2.51") * x / re).log10()
                    if residual > 0:
                        high = x
                    else:
                        low = x
                colebrook = 1 / ((low + high) / 2) ** 2
                rows.append({"reynolds": reynolds, "relativeRoughness": relative_roughness,
                             "specifiedDarcyFactor": str(friction),
                             "regime": "laminar" if reynolds <= 2000 else "transition" if reynolds < 4000 else "turbulent",
                             "colebrookDarcyFactor": str(colebrook) if reynolds >= 4000 else None})
    return {"source": "https://usepa.github.io/EPANET2.2/12_analysis_algorithms.html",
            "generator": "examples/Generate-Hydraulic-References.py; Decimal precision 60",
            "rows": rows}


if __name__ == "__main__":
    output = Path(__file__).resolve().parents[1] / "src/test/resources/fluid/reference/hydraulic-friction.json"
    output.write_text(json.dumps(references(), indent=2) + "\n", encoding="utf-8")
