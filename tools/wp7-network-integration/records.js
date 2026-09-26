// P3 WP7: the fluid_domain envelope and evidence edits of the bundled records (numbers only where the plan says so).
const fs = require('fs');
const root = 'src/main/resources/data/createcheme/materials/';
const editFile = require("./records-edit.js");
function edit(path, pairs) { editFile(root + path, pairs); }

const OLD_COMPONENT = '100 Pa..2 MPa is the span the network evaluates this record over (PR78 at the 2 MPa liquid reference with the global compressibility response).';
const NEW_COMPONENT = '100 Pa..2 MPa is the span the network evaluates this record over (PR78 with every phase at its own pressure since P3 WP4; above 2 MPa this record is not qualified).';
const crude = fs.readdirSync(root + 'properties').filter(f => /^(crude_pc\d\d|tjl19_[a-z_]+)\.json$/.test(f));
if (crude.length !== 18) throw new Error('expected 18 crude and light-end records, got ' + crude.length);
for (const f of crude) edit('properties/' + f, [[OLD_COMPONENT, NEW_COMPONENT]]);

// Methane: a supercritical gas over its whole network range (floor 293.15 K, Tc 190.56 K), to 10 MPa.
edit('properties/tjl20_methane.json', [
    ['"pressure_max_pascal": 2000000,\n    "evidence": "Fluid network only', '"pressure_max_pascal": 10000000,\n    "evidence": "Fluid network only'],
    [OLD_COMPONENT, '100 Pa..10 MPa: every phase at its own pressure (P3 WP4 direct path), and above its 293.15 K floor methane (Tc 190.56 K) is a supercritical gas outside every declared critical-band box (P3 WP7); PR78 density +0.95 % at 300 K and 10 MPa against the Helmholtz reference (P1 study section 2.5). Viscosity above 2 MPa is a reference-pressure value.'],
]);

// Nitrogen: the triple point to 900 K, to 10 MPa, the critical band research-only.
edit('properties/nitrogen.json', [
    ['"pressure_min_pascal": 100,\n    "pressure_max_pascal": 2000000,\n    "evidence": "Nitrogen triple point', '"pressure_min_pascal": 100,\n    "pressure_max_pascal": 10000000,\n    "evidence": "Nitrogen triple point'],
    ['Validated in F4 (FluidNitrogenCryogenicTest): ideal-gas Cp below 273.16 K within 0.02 % of NIST zero-pressure Cp; PR78 saturation pressure at 77.355 K +1.23 %, saturated liquid density +0.34 %; vapour Cp at 100 K and 200 K, 1 atm, within 1 %; viscosity tables cover the range."',
     'Validated in F4 (FluidNitrogenCryogenicTest): ideal-gas Cp below 273.16 K within 0.02 % of NIST zero-pressure Cp; vapour Cp at 100 K and 200 K, 1 atm, within 1 %; viscosity tables cover the temperature range. Every phase at its own pressure (P3 WP4): PR78 saturation pressure at 77.355 K +1.21 %, saturated liquid density -0.07 %; compressed liquid to 10 MPa within 0.19, 0.34, 1.15 % of the Helmholtz reference at Tr 0.6, 0.7, 0.8 (3.55 % at Tr 0.9) and -0.17 % at 77.36 K, 10 MPa (DirectLiquidContinuityTest; P1 study). 10 MPa per P3 design section 8.1: the declared critical band (Tr 0.95-1.1 x Pr 0.8-1.5, 119.9-138.8 K x 2.72-5.09 MPa, and the plan\'s two smaller boxes) is research-only, typed by the equilibrium engine (P3 WP7). Viscosity above 2 MPa for dense states is a reference-pressure value."'],
]);

// The network envelope to 10 MPa.
edit('packages/tjl20_nitrogen.json', [
    ['"pressure_min_pascal": 100,\n    "pressure_max_pascal": 2000000,\n    "evidence": "Fluid network envelope', '"pressure_min_pascal": 100,\n    "pressure_max_pascal": 10000000,\n    "evidence": "Fluid network envelope'],
    ['100 Pa..2 MPa is the span the network evaluates (liquid reference 2 MPa). The crude components keep 293.15 K and water its 273.16 K triple point inside it."',
     '100 Pa..10 MPa since P3 WP7 (design section 8.1): nitrogen and methane are declared to 10 MPa, the crude components, ethane and the light ends keep their own 2 MPa, and a state is valid only inside the range of every component it carries. Every phase at its own pressure (P3 WP4). The crude components keep 293.15 K and water its 273.16 K triple point inside it."'],
    ['"NITROGEN_CRYOGENIC: valid from its 63.151 K triple point (Cp segment below 273.16 K, NIST viscosity tables); PR78 Psat at 77.355 K +1.23 %, liquid density +0.34 % against NIST (F4)",',
     '"NITROGEN_CRYOGENIC: valid from its 63.151 K triple point (Cp segment below 273.16 K, NIST viscosity tables); PR78 Psat at 77.355 K +1.21 %, liquid density -0.07 % against NIST (F4, P3 WP4 direct liquid path)",'],
]);

// The crude_19 packages carry the methane record, so their envelopes must hold its 10 MPa range (the loader's rule: every
// component's range inside the envelope). Their cuts keep 2 MPa; no crude state above 2 MPa becomes valid.
const OLD_ENVELOPE = '100 Pa..2 MPa is the span the fluid network evaluates (liquid reference 2 MPa). Every component declares its own range inside it."';
for (const f of ['bonga_tjl20', 'cold_lake_blend_tjl20', 'dalia_tjl20', 'tjl20', 'upper_zakum_tjl20', 'wti_light_export_tjl20']) {
    edit('packages/' + f + '.json', [
        ['"pressure_min_pascal": 100,\n    "pressure_max_pascal": 2000000,\n    "evidence": "Fluid network envelope', '"pressure_min_pascal": 100,\n    "pressure_max_pascal": 10000000,\n    "evidence": "Fluid network envelope'],
        [OLD_ENVELOPE, '100 Pa..10 MPa holds the methane record\'s declared range (P3 WP7); every other component keeps its own 2 MPa, and a state is valid only inside the range of every component it carries. Every phase at its own pressure (P3 WP4). Every component declares its own range inside it."'],
    ]);
}
edit('packages/tjl19.json', [[OLD_ENVELOPE, '100 Pa..2 MPa is the span the fluid network evaluates, every phase at its own pressure (P3 WP4). Every component declares its own range inside it."']]);
console.log('edited', crude.length + 10, 'records');
