#!/usr/bin/env node
// Drive the langyo/minecraft-mod-mcp in-game mod directly over its HTTP endpoint.
// No MCP tools need to be loaded in the session: the mod serves /api/status and /api/cmd itself,
// and the npm bridge only translates MCP calls into these same requests.
//
//   node bridge.js status
//   node bridge.js wait-ready [timeoutSeconds=300]   poll /api/status until the mod answers
//   node bridge.js wait-world [timeoutSeconds=90]    poll until no screen is open (world loaded)
//   node bridge.js <cmd> ['{"json":"params"}']
//   node bridge.js shot <file.png>           screenshot_to_file + alpha flatten (needs java on PATH)
//   node bridge.js chat "/gamemode creative"  open_chat, type the line, press Enter (the only command path that works)
//   node bridge.js cmds <file.txt>             one slash command per line, run in order through chat
//   node bridge.js batch <file.jsonl>          one {"cmd":..., ...params} object per line, run in order
//
// Set MC_MCP_PORT to talk to a client started on another port (default 9876).

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const PORT = process.env.MC_MCP_PORT || '9876';
const BASE = `http://127.0.0.1:${PORT}`;
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function status() {
  try {
    const r = await fetch(`${BASE}/api/status`, { signal: AbortSignal.timeout(2000) });
    return r.ok ? await r.json() : null;
  } catch { return null; }
}

async function cmd(name, params = {}, timeoutMs = 30000) {
  const r = await fetch(`${BASE}/api/cmd`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cmd: name, ...params }),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const text = await r.text();
  let v; try { v = JSON.parse(text); } catch { v = text; }
  if (!r.ok) throw new Error(`bridge ${name} -> HTTP ${r.status} ${text}`);
  return v;
}

function flatten(png) {
  const dir = __dirname;
  const cls = path.join(dir, 'Flatten.class');
  if (!fs.existsSync(cls)) execFileSync('javac', ['-d', dir, path.join(dir, 'Flatten.java')], { stdio: 'inherit' });
  execFileSync('java', ['-cp', dir, 'Flatten', png], { stdio: 'inherit' });
}

async function main() {
  const [op, ...rest] = process.argv.slice(2);
  if (!op || op === 'status') { const s = await status(); console.log(JSON.stringify(s)); process.exit(s ? 0 : 1); }
  if (op === 'wait-ready') {
    const limit = Number(rest[0] || 300);
    for (let i = 0; i < limit; i++) { const s = await status(); if (s) { console.log(JSON.stringify(s)); return; } await sleep(1000); }
    throw new Error(`no bridge on ${BASE} after ${limit} s`);
  }
  if (op === 'wait-world') {
    // In the world = no screen is open. Menus and loading screens answer with a screen name.
    const limit = Number(rest[0] || 90);
    for (let i = 0; i < limit; i += 3) {
      const r = await cmd('get_screen_buttons').catch(() => null);
      if (r && r.error === 'no screen') { console.log('in world'); return; }
      await sleep(3000);
    }
    throw new Error(`still on a screen after ${limit} s`);
  }
  if (op === 'shot') {
    const file = path.resolve(rest[0] || `shot-${Date.now()}.png`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    console.log(JSON.stringify(await cmd('screenshot_to_file', { path: file })));
    flatten(file);
    return;
  }
  if (op === 'chat') {
    // The path every batch used: open the chat, type the line, press Enter as its own key event.
    // (type_text's press_enter flag was reported not to submit in one session; press_key always did.)
    const line = rest.join(' ');
    console.log(JSON.stringify(await cmd('open_chat')));
    await sleep(400);
    console.log(JSON.stringify(await cmd('type_text', { text: line })));
    await sleep(400);
    console.log(JSON.stringify(await cmd('press_key', { key: 'enter' })));
    await sleep(600);
    return;
  }
  if (op === 'cmds') {
    // One slash command per line from a file; '#' lines are comments. Leading '/' is added when missing.
    const lines = fs.readFileSync(rest[0], 'utf8').split(/\r?\n/).map(l => l.trim()).filter(l => l && !l.startsWith('#'));
    for (const l of lines) {
      const line = l.startsWith('/') ? l : '/' + l;
      await cmd('open_chat'); await sleep(400);
      await cmd('type_text', { text: line }); await sleep(400);
      await cmd('press_key', { key: 'enter' }); await sleep(600);
      console.log('ran', line);
    }
    return;
  }
  if (op === 'batch') {
    const lines = fs.readFileSync(rest[0], 'utf8').split(/\r?\n/).filter(l => l.trim() && !l.startsWith('#'));
    for (const line of lines) {
      const { cmd: name, delay_ms, ...params } = JSON.parse(line);
      console.log(name, JSON.stringify(await cmd(name, params)));
      await sleep(delay_ms ?? 250);
    }
    return;
  }
  console.log(JSON.stringify(await cmd(op, rest[0] ? JSON.parse(rest[0]) : {})));
}

main().catch(e => { console.error(e.message); process.exit(1); });
