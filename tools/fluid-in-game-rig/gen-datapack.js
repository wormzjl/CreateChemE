// Generates the WP5 in-game benchmark datapack (Minecraft 1.21.1 layout, pack_format 48).
// Usage: node gen-datapack.js <output datapacks dir>
// Every device is placed with /setblock, so FluidDeviceBlock.onPlace registers it with the mod's placement defaults,
// exactly as a player-placed block: reservoirs 1 m3 nitrogen at 298.15 K and 101,325 Pa, generators water at
// 298.15 K and 101,325 Pa, voids nitrogen at 101,325 Pa, pipes 1 m of 0.05 m bore, pumps 0.01 m3/s target with a
// 500 kPa head limit. Lines run along +x at y = Y and face east (a pump's outlet is its east face).
const fs = require('fs'), path = require('path');
const out = process.argv[2];
if (!out) { console.error('usage: node gen-datapack.js <datapacks dir>'); process.exit(2); }
const Y = 64, X0 = 16, Z0 = 16;
const ID = {R: 'createcheme:fluid_reservoir', P: 'createcheme:fluid_pipe', U: 'createcheme:fluid_pump', G: 'createcheme:fluid_generator', V: 'createcheme:fluid_void'};
// Line kinds. REST: two tanks joined by three pipes at one elevation, both at the default charge, so nothing moves.
// THROUGH: generator, pump, a 1 m3 vessel and pipes into a void: the pump holds 0.01 m3/s of water through the vessel,
// which floods and is then flushed of its nitrogen for as long as the run lasts (the stress100 regime: through-flow
// with a drifting holdup). FILL: generator and pump filling a closed chain of three 1 m3 nitrogen tanks (about 250 s
// of pumping at the target flow before the head limit); with 100 lines placed at once most first slices are held
// (wall budget, Newton failures) and retried for about two minutes before the lines fill (probes 1 to 4 in the WP5
// review: six-tank chains stay held, vented chains and pump transfers between tanks are held, gravity fills end in
// under a minute). PURE: generator, pump, three pipes, void: through-flow with no vessel, which certifies STEADY.
const LINES = {
  REST: 'RPPPR',
  THROUGH: 'GUPRPPV',
  FILL: 'GUPRPRPR',
  // The six-tank closed chain the WP5 probes placed as 'FILL' (probe-r01, probe2-r01: 237 and 528 devices).
  FILL6: 'GUPRPRPRPRPRPR',
  PURE: 'GUPPPV',
  FLAT: 'GPPPV',
  F1: 'GUPR',
  F2: 'GUPRPR',
  F3: 'GUPRPRPR',
  T1: 'RUPR',
  T2: 'RUPRPR',
  T3: 'RPRUPRPR',
  VC3: 'GUPRPRPRPV',
  VC6: 'GUPRPRPRPRPRPRPV',
};
function line(kind, x, z) {
  const cmds = [];
  [...LINES[kind]].forEach((c, i) => cmds.push(`setblock ${x + i} ${Y} ${z} ${ID[c]}[facing=east]`));
  return cmds;
}
// Gravity line (probe only): a generator four blocks above a tank, three vertical pipes, then tanks ahead along +x.
function gravity(x, z, tanks) {
  const cmds = [`setblock ${x} ${Y + 4} ${z} ${ID.G}[facing=east]`];
  for (let dy = 3; dy >= 1; dy--) cmds.push(`setblock ${x} ${Y + dy} ${z} ${ID.P}[facing=east]`);
  cmds.push(`setblock ${x} ${Y} ${z} ${ID.R}[facing=east]`);
  for (let t = 1; t < tanks; t++) { cmds.push(`setblock ${x + 2 * t - 1} ${Y} ${z} ${ID.P}[facing=east]`); cmds.push(`setblock ${x + 2 * t} ${Y} ${z} ${ID.R}[facing=east]`); }
  return cmds;
}
// 100-line grid: 4 columns 16 blocks apart, 25 rows 2 blocks apart; line n at column n % 4, row n / 4.
function grid100(kindOf) {
  const cmds = [];
  for (let n = 0; n < 100; n++) cmds.push(...line(kindOf(n), X0 + 16 * (n % 4), Z0 + 2 * Math.floor(n / 4)));
  return cmds;
}
// 1,000-line grid: 16 columns 6 blocks apart, rows 2 blocks apart (63 rows, the last one partly used).
function grid1000(kind, count = 1000) {
  const cmds = [];
  for (let n = 0; n < count; n++) cmds.push(...line(kind, X0 + 6 * (n % 16), Z0 + 2 * Math.floor(n / 16)));
  return cmds;
}
const scenarios = {
  empty: [],
  rest100: grid100(() => 'REST'),
  through100: grid100(() => 'THROUGH'),
  fill100: grid100(() => 'FILL'),
  mixed100: grid100(n => n % 4 < 2 ? 'FILL' : n % 4 === 2 ? 'THROUGH' : 'REST'),
  rest1000: grid1000('REST'),
  // F2: the first 400 lines of rest1000's grid (2,000 devices).
  rest400: grid1000('REST', 400),
  pure100: grid100(() => 'PURE'),
  // Probe: 1 REST, 2 PURE, 4 THROUGH, 8 FILL, 16 FLAT in separate rows, so the kind counts decode per line type.
  probe: (() => { const c = []; let row = 0; for (const [k, m] of [['REST', 1], ['PURE', 2], ['THROUGH', 4], ['FILL6', 8], ['FLAT', 16]]) for (let i = 0; i < m; i++) c.push(...line(k, X0, Z0 + 2 * row++)); return c; })(),
};
// Probe 2: fill variants. 1 F1, 2 F2, 4 F3, 8 FILL (six tanks), 16 gravity lines with one tank, 32 with three.
scenarios.probe2 = (() => { const c = []; let row = 0; for (const [k, m] of [['F1', 1], ['F2', 2], ['F3', 4], ['FILL6', 8]]) for (let i = 0; i < m; i++) c.push(...line(k, X0, Z0 + 2 * row++));
  for (let i = 0; i < 16; i++) c.push(...gravity(X0 + 20, Z0 + 2 * i, 1)); for (let i = 0; i < 32; i++) c.push(...gravity(X0 + 40, Z0 + 2 * i, 3)); return c; })();
// Probe 3: pump transfer between tanks. 8 T1, 8 T2, 8 T3 in rows (T1 first, so they get the lowest island ids).
scenarios.probe3 = (() => { const c = []; let row = 0; for (const [k, m] of [['T1', 8], ['T2', 8], ['T3', 8]]) for (let i = 0; i < m; i++) c.push(...line(k, X0, Z0 + 2 * row++)); return c; })();
// Probe 4: vented tank chains (pumped water into three or six tanks in series, the last one venting to a void) and
// the through line. 8 VC3, 8 VC6, 8 THROUGH, in that order of island ids.
scenarios.probe4 = (() => { const c = []; let row = 0; for (const [k, m] of [['VC3', 8], ['VC6', 8], ['THROUGH', 8]]) for (let i = 0; i < m; i++) c.push(...line(k, X0, Z0 + 2 * row++)); return c; })();
const pack = path.join(out, 'createcheme_bench');
fs.rmSync(pack, {recursive: true, force: true});
const fn = path.join(pack, 'data', 'createcheme_bench', 'function');
fs.mkdirSync(fn, {recursive: true});
fs.writeFileSync(path.join(pack, 'pack.mcmeta'), JSON.stringify({pack: {pack_format: 48, description: 'CreateChemE fluid scheduling WP5 in-game benchmark scenarios'}}, null, 2) + '\n');
// The same 48 chunks (x 16..111, z 16..143) are force-loaded in every scenario, empty included.
fs.writeFileSync(path.join(fn, 'forceload.mcfunction'), 'forceload add 16 16 111 143\n');
fs.writeFileSync(path.join(fn, 'view.mcfunction'), 'gamemode spectator @s\ntp @s 64 150 80 0 90\n');
// F2 marginal functions, placed in every grid's free space inside the force-loaded chunks: a lone tank above the grid
// (a new island), a pipe on top of the first line's first pipe (a dead-end branch of that line's island), the first
// line's middle pipe turned north and back (a facing edit), twenty lone tanks, and a no-op for the RCON round trip.
const marginal = {
  noop: ['time query gametime'],
  plus_tank: [`setblock 16 70 16 ${ID.R}[facing=east]`], minus_tank: ['setblock 16 70 16 minecraft:air'],
  plus_pipe: [`setblock 17 65 16 ${ID.P}[facing=east]`], minus_pipe: ['setblock 17 65 16 minecraft:air'],
  edit_pipe: [`setblock 18 64 16 ${ID.P}[facing=north]`], edit_back: [`setblock 18 64 16 ${ID.P}[facing=east]`],
  plus20: Array.from({length: 20}, (_, k) => `setblock ${16 + 2 * k} 70 20 ${ID.R}[facing=east]`), minus20: Array.from({length: 20}, (_, k) => `setblock ${16 + 2 * k} 70 20 minecraft:air`),
};
for (const [name, cmds] of Object.entries(marginal)) fs.writeFileSync(path.join(fn, name + '.mcfunction'), cmds.join('\n') + '\n');
const summary = {};
for (const [name, cmds] of Object.entries(scenarios)) {
  fs.writeFileSync(path.join(fn, name + '.mcfunction'), cmds.join('\n') + (cmds.length ? '\n' : '') + `say createcheme_bench ${name} placed ${cmds.length} devices\n`);
  summary[name] = cmds.length;
}
console.log(JSON.stringify(summary));
