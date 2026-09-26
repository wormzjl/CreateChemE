# Vacuum-residue hydroprocessing: cut selection

Research date: 2026-09-17. Research only; no production changes. This directory is Git-ignored.

ChatGPT literature request: https://chatgpt.com/c/6aab5be3-bdc4-83ec-8a91-246901c46b82

## Primary papers checked

1. **Manek and Haydary (2014), Modelling of catalytic hydrocracking and fractionation of refinery vacuum residue.** https://doi.org/10.2478/s11696-014-0620-0
   - Commercial ebullated-bed residue hydrocracker. Six kinetic fractions; 30 Aspen pseudocomponents for fractionation.
   - Experimental section, p.1718: gas; naphtha 40–185°C; kerosene 140–290°C; gas oil 265–360°C; vacuum distillates 360–565°C; unconverted residue >565°C. The overlapping product ranges are reported stream ranges, not a disjoint assay partition.
   - Simulation description, pp.1721–1722: pseudocomponents generated from feed/product distillation curves; reaction yields distributed back into them; atmospheric/vacuum PetroFrac columns.
   - Full primary article inspected via author-uploaded PDF: https://www.researchgate.net/publication/270048445_Modelling_of_catalytic_hydrocracking_and_fractionation_of_refinery_vacuum_residue/fulltext/563dc8d908aec6f17dd94586/Modelling-of-catalytic-hydrocracking-and-fractionation-of-refinery-vacuum-residue.pdf

2. **Wang et al. (2020 issue; online December 2019), Modeling and Simulation of Reaction and Fractionation Systems for the Industrial Residue Hydrotreating Process.** https://doi.org/10.3390/pr8010032
   - Section 3.2: resin/asphaltene group plus liquid product groups <350, 350–540, >540°C, gas and coke. Parallel to a 97-lump hydrocracker model.
   - Fractionation uses separate boiling-point pseudocomponents: approximately 10°C spacing below460°C, 20°C to600°C, 25°C to850°C, then850°C+.
   - Open primary text: https://www.mdpi.com/2227-9717/8/1/32

3. **Browning et al. (2019), Distributed lump kinetic modeling for slurry phase vacuum residue hydroconversion.** https://doi.org/10.1016/j.cej.2018.08.197
   - Publisher abstract verified: 525°C+ feed, 21 hydrocarbon groups, simulated distillation plus GPC; different behavior above750°C.
   - Full original cut grid not independently verified. Do not copy conflicting secondary descriptions of its interval counts.

4. **Browning et al. (2020), Kinetic modeling of deep vacuum residue hydroconversion in a pilot scale continuous slurry reactor with recycle.** https://doi.org/10.1016/j.ceja.2020.100063
   - Kinetic-model section: gas <36, naphtha36–160, distillate160–350, VGO350–525, residue525°C+. VGO/residue subdivided at35°C intervals; light/heavy residue split735°C. Corresponding low-sulfur groups are also defined.
   - Methods: ASTM D7169 HTSD; heavy tail extrapolated beyond approximately749°C. Authors explicitly acknowledge that the735°C boundary nearly coincides with the analytical limit. Hydrogen consumption remains feed-dependent.
   - Full primary text inspected via public paper reproduction: https://www.researchgate.net/publication/347624032_Kinetic_modeling_of_deep_vacuum_residue_hydroconversion_in_a_pilot_scale_continuous_slurry_reactor_with_recycle

5. **Martínez and Ancheyta (2012), Kinetic model for hydrocracking of heavy oil in a CSTR involving short term catalyst deactivation.** https://doi.org/10.1016/j.fuel.2012.05.032
   - Atmospheric-residue feed (312°C+), stirred basket reactor. Five groups: gas, naphtha IBP–204, distillates204–343, VGO343–538, unconverted residue538°C+.
   - Exact boundaries verified in publisher abstract; analytical distillation method not verified here.

6. **Calderón and Ancheyta (2024), A Commercial Reactor Simulation for the Slurry-Phase Hydrocracking of Heavy Oil with a Mineral Catalyst Using Distillation Lumps and SARA-Based Kinetic Models.** https://doi.org/10.1021/acs.iecr.4c02337
   - Publisher abstract: VR/VGO/middle-distillate/naphtha model combined with SARA, gas and coke; industrial-scale simulation using dispersed molybdenite.
   - Exact numerical boiling boundaries not verified; do not import those from another paper merely because the group names match.

7. **Verstraete, Le Lannic and Guibard (2007), Modeling fixed-bed residue hydrotreating processes.** https://doi.org/10.1016/j.ces.2007.03.020
   - ChatGPT identified this additional paper; its publisher abstract was independently checked.
   - Eight chemical groups: gas, saturates, aromatics-minus/plus, resins-minus/plus, asphaltenes, metal deposits. Elemental sub-species track C/H/S/N/O/Ni/V through84 reactions, including catalyst-pellet diffusion.
   - Exact minus/plus boundaries and the full thermodynamic mapping remain unverified in the2007 paper. A related author conference abstract explicitly uses520°C followed by SARA separation, but this must not silently be attributed to the earlier paper: https://proceedings.aiche.org/conferences/aiche-spring-meeting-and-global-congress-on-process-safety/2010/proceeding/paper/139c-fixed-bed-residue-hydroprocessing-modeling-hydrotreating-performances-and-catalyst-deactivation-0

ChatGPT's completed review agrees that boiling, chemical and thermodynamic subdivisions serve different purposes. Its additional references include molecular reconstruction and continuous-mixture methods; those are not needed to infer a universal cut count. The recommendations below remain a research judgment, not a result of reduced-basis simulation.

## Implication for the present research basis

Inference, not an implemented or validated reduction: split PC10 at the assay-supported550°C boundary; retain PC11 and test combining PC12/13. Existing estimated PC11/PC12 boundary730.5°C is near Browning's735°C light/heavy-residue boundary, but that numerical proximity does not validate identical kinetics or a universal threshold. Combining PC11–13 would erase this distinction.

A coarse reaction model can aggregate several retained thermodynamic components. Chemical quality (SARA, H/C, sulfur, nitrogen, metals) must evolve with conversion; it is not identified by boiling range alone. Kinetic simplicity does not require irreversible deletion of the column basis. Conversely, using detailed fractions requires measured or explicitly estimated feed-specific information and qualified kinetic parameters.

550°C is a practical choice for the existing assay data, not a universal literature definition of VR. Rate parameters calibrated with525,538,540 or565°C boundaries cannot be transferred unchanged to550°C.
