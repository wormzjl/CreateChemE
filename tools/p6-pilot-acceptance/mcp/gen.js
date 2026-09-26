#!/usr/bin/env node
// P6 GUI helper: configure one fluid generator through the runMcpClient lane (compat mixins route click/type to the
// focused EditBox). Usage: node gen.js <x> <z> <presetClicks> <pressurePa> <temperatureK> <shotPrefix>
// Stands on the block at (x, 101, z) looking down, opens its screen, waits for the engine's view, cycles the preset
// button <presetClicks> times, types pressure and temperature (select-all first, waits after every input: the bridge's
// typing lands asynchronously), screenshots, applies, waits for the acknowledgement at the next bucket, screenshots.
const { execFileSync } = require('child_process');
const path = require('path');
const BASE = `http://127.0.0.1:${process.env.MC_MCP_PORT || '9876'}`;
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function cmd(name, params = {}) {
  const r = await fetch(`${BASE}/api/cmd`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cmd: name, ...params }), signal: AbortSignal.timeout(30000) });
  const t = await r.text(); try { return JSON.parse(t); } catch { return t; }
}
async function chat(line) { await cmd('open_chat'); await sleep(300); await cmd('type_text', { text: line }); await sleep(200); await cmd('press_key', { key: 'enter' }); await sleep(500); }
function shot(file) {
  execFileSync('node', [path.join(__dirname, 'bridge.js'), 'shot', file], { stdio: 'inherit' });
}
async function main() {
  const [x, z, clicks, pressure, temperature, prefix] = process.argv.slice(2);
  await cmd('close_screen'); await sleep(300);
  await chat(`/tp @s ${x}.5 102 ${z}.5 0 89`); await sleep(800);
  await cmd('use_item'); await sleep(7000);
  for (let i = 0; i < Number(clicks); i++) { await cmd('click_button_index', { index: 23 }); await sleep(400); }
  for (const [y, value] of [[162, pressure], [226, temperature]]) {
    await cmd('click', { x: 628, y }); await sleep(500);
    await cmd('hotkey', { keys: 'ctrl,a' }); await sleep(500);
    await cmd('type_text', { text: value }); await sleep(1200);
  }
  shot(`${prefix}-typed.png`);
  await cmd('click_button_index', { index: 21 });
  await sleep(14000);
  shot(`${prefix}-applied.png`);
}
main().catch(e => { console.error(e); process.exit(1); });
