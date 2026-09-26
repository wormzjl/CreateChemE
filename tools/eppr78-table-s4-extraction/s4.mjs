import fs from 'node:fs';
import path from 'node:path';
const dir = process.argv[2];
const mode = process.argv[3] || 'dump';
const xml = fs.readFileSync(path.join(dir, 'word', 'document.xml'), 'utf8');
const rels = fs.readFileSync(path.join(dir, 'word', '_rels', 'document.xml.rels'), 'utf8');
const relMap = {};
for (const m of rels.matchAll(/<Relationship\s+([^>]*)\/>/g)) {
  const attrs = m[1];
  const id = /Id="([^"]+)"/.exec(attrs);
  const target = /Target="([^"]+)"/.exec(attrs);
  if (id && target) relMap[id[1]] = target[1];
}
const symbolMap = { 0x2D: '-', 0xB4: 'x', 0xB1: '+/-', 0xB0: 'deg', 0xA5: 'inf', 0xB7: '.', 0x3D: '=', 0x2B: '+', 0x2E: '.', 0x28: '(', 0x29: ')', 0x2F: '/' };
function decode(bytes, font) {
  const charset = font ? font.charset : 0;
  let out = '';
  for (const b of bytes) {
    if (charset === 2) out += (symbolMap[b] !== undefined ? symbolMap[b] : `<sym${b.toString(16)}>`);
    else if (b === 0x96 || b === 0x97) out += '-';
    else if (b === 0xAD) out += '-';
    else if (b >= 0x20 && b < 0x7F) out += String.fromCharCode(b);
    else out += `<${b.toString(16)}>`;
  }
  return out;
}
function wmfTexts(file) {
  const buf = fs.readFileSync(file);
  let off = 0;
  if (buf.readUInt32LE(0) === 0x9AC6CDD7) off = 22;
  const headerSizeWords = buf.readUInt16LE(off + 2);
  off += headerSizeWords * 2;
  const objects = [];
  let font = null;
  const texts = [];
  let orgX = 0, orgY = 0, vpX = 0, vpY = 0;
  while (off + 6 <= buf.length) {
    const sizeWords = buf.readUInt32LE(off);
    const fn = buf.readUInt16LE(off + 4);
    if (sizeWords < 3 || fn === 0) break;
    const p = off + 6;
    const end = off + sizeWords * 2;
    const place = (obj) => { const slot = objects.indexOf(null); if (slot < 0) objects.push(obj); else objects[slot] = obj; };
    switch (fn) {
      case 0x02FB: {
        const charset = buf.readUInt8(p + 12);
        let name = '';
        for (let i = p + 18; i < end && buf[i] !== 0; i++) name += String.fromCharCode(buf[i]);
        place({ kind: 'font', charset, name, height: buf.readInt16LE(p) });
        break;
      }
      case 0x02FA: case 0x02FC: case 0x00F7: case 0x0142: case 0x01F9: case 0x06FF:
        place({ kind: 'other' });
        break;
      case 0x012D: {
        const idx = buf.readUInt16LE(p);
        const o = objects[idx];
        if (o && o.kind === 'font') font = o;
        break;
      }
      case 0x01F0: {
        const idx = buf.readUInt16LE(p);
        objects[idx] = null;
        break;
      }
      case 0x020B: { orgY = buf.readInt16LE(p); orgX = buf.readInt16LE(p + 2); break; }
      case 0x020D: { vpY = buf.readInt16LE(p); vpX = buf.readInt16LE(p + 2); break; }
      case 0x020F: { orgY += buf.readInt16LE(p); orgX += buf.readInt16LE(p + 2); break; }
      case 0x0214: { vpY = buf.readInt16LE(p); vpX = buf.readInt16LE(p + 2); break; }
      case 0x0A32: {
        const y = buf.readInt16LE(p) - orgY + vpY, x = buf.readInt16LE(p + 2) - orgX + vpX, n = buf.readInt16LE(p + 4), opts = buf.readUInt16LE(p + 6);
        let s = p + 8;
        if (opts & 0x0006) s += 8;
        const str = [...buf.subarray(s, s + n)];
        const dxStart = s + n + (n % 2);
        const dx = [];
        for (let i = dxStart; i + 1 < end && dx.length < n; i += 2) dx.push(buf.readInt16LE(i));
        texts.push({ x, y, font: font ? font.name : null, charset: font ? font.charset : null, height: font ? font.height : null, bytes: str, dx, text: decode(str, font) });
        break;
      }
      case 0x0521: {
        const n = buf.readInt16LE(p);
        const str = [...buf.subarray(p + 2, p + 2 + n)];
        const pad = n % 2;
        const y = buf.readInt16LE(p + 2 + n + pad), x = buf.readInt16LE(p + 4 + n + pad);
        texts.push({ x, y, font: font ? font.name : null, charset: font ? font.charset : null, height: font ? font.height : null, bytes: str, dx: [], text: decode(str, font) });
        break;
      }
    }
    off = end;
  }
  return texts;
}
function textOf(fragment) {
  const parts = [];
  const re = /<w:t(?:\s[^>]*)?>([^<]*)<\/w:t>/g;
  let m;
  while ((m = re.exec(fragment)) !== null) parts.push(m[1]);
  return parts.join('').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').trim();
}
// top-level tables
const body = xml.slice(xml.indexOf('<w:body>'));
const tables = [];
let pos = 0;
const tagRe = /<w:(p|tbl)(\s[^>]*)?>/g;
while (true) {
  tagRe.lastIndex = pos;
  const open = tagRe.exec(body);
  if (!open) break;
  const kind = open[1];
  const start = open.index;
  const openTag = new RegExp(`<w:${kind}(\\s[^>]*)?>`, 'g');
  const closeTag = `</w:${kind}>`;
  let depth = 1, scan = start + open[0].length, end = -1;
  while (depth > 0) {
    openTag.lastIndex = scan;
    const nextOpen = openTag.exec(body);
    const nextClose = body.indexOf(closeTag, scan);
    if (nextClose < 0) { end = body.length; break; }
    if (nextOpen && nextOpen.index < nextClose) { depth++; scan = nextOpen.index + nextOpen[0].length; }
    else { depth--; scan = nextClose + closeTag.length; end = scan; }
  }
  if (kind === 'tbl') tables.push(body.slice(start, end));
  pos = end;
}
const wanted = [3, 4, 5, 6, 7, 8]; // zero-based: tables 04..09
const cells = [];
for (const ti of wanted) {
  const fragment = tables[ti];
  const rows = [];
  const rowRe = /<w:tr(?:\s[^>]*)?>([\s\S]*?)<\/w:tr>/g;
  let r;
  while ((r = rowRe.exec(fragment)) !== null) {
    const cellsOfRow = [];
    const cellRe = /<w:tc(?:\s[^>]*)?>([\s\S]*?)<\/w:tc>/g;
    let c;
    while ((c = cellRe.exec(r[1])) !== null) {
      const imgs = [...c[1].matchAll(/<v:imagedata r:id="([^"]+)"/g)].map(m => relMap[m[1]]);
      cellsOfRow.push({ text: textOf(c[1]), imgs });
    }
    rows.push(cellsOfRow);
  }
  const header = rows[0].map(c => c.text);
  for (let ri = 1; ri < rows.length; ri++) {
    const rowLabel = rows[ri][0].text;
    for (let ci = 1; ci < rows[ri].length; ci++) {
      const cell = rows[ri][ci];
      const colLabel = header[ci];
      const runs = [];
      for (const img of cell.imgs) {
        const file = path.join(dir, 'word', img);
        for (const t of wmfTexts(file)) runs.push({ img, ...t });
      }
      cells.push({ table: ti + 1, row: rowLabel, col: colLabel, text: cell.text, imgs: cell.imgs, runs });
    }
  }
}
if (mode === 'dump') {
  for (const c of cells.slice(0, Number(process.argv[4] || 12))) {
    console.log(`T${c.table} [${c.row}] x [${c.col}] text="${c.text}" imgs=${c.imgs.join(',')}`);
    for (const r of c.runs) console.log(`   ${r.img} y=${r.y} x=${r.x} font=${r.font}/${r.charset} h=${r.height} text="${r.text}" dx=${r.dx.join(',')}`);
  }
} else {
  fs.writeFileSync(path.join(dir, 'tables', 's4-cells.json'), JSON.stringify(cells, null, 1));
  console.log(`${cells.length} cells written`);
}
