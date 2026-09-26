import fs from 'node:fs';
import path from 'node:path';
const dir = process.argv[2];
const xml = fs.readFileSync(path.join(dir, 'word', 'document.xml'), 'utf8');
const bodyStart = xml.indexOf('<w:body>');
const body = xml.slice(bodyStart);
const outDir = path.join(dir, 'tables');
fs.mkdirSync(outDir, { recursive: true });
function textOf(fragment) {
  const parts = [];
  const re = /<w:t(?:\s[^>]*)?>([^<]*)<\/w:t>|<w:tab\/>|<w:br\/>/g;
  let m;
  while ((m = re.exec(fragment)) !== null) {
    if (m[0] === '<w:tab/>') parts.push('\t');
    else if (m[0] === '<w:br/>') parts.push(' ');
    else parts.push(m[1]);
  }
  return parts.join('').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'");
}
// Walk top-level elements: paragraphs and tables (tables may nest; we take top-level tables only).
let pos = 0;
let lastParas = [];
let tableIndex = 0;
const summary = [];
const tagRe = /<w:(p|tbl)(\s[^>]*)?>/g;
while (true) {
  tagRe.lastIndex = pos;
  const open = tagRe.exec(body);
  if (!open) break;
  const kind = open[1];
  const start = open.index;
  // find matching close with depth counting
  const openTag = new RegExp(`<w:${kind}(\\s[^>]*)?>`, 'g');
  const closeTag = `</w:${kind}>`;
  let depth = 1;
  let scan = start + open[0].length;
  let end = -1;
  while (depth > 0) {
    openTag.lastIndex = scan;
    const nextOpen = openTag.exec(body);
    const nextClose = body.indexOf(closeTag, scan);
    if (nextClose < 0) { end = body.length; break; }
    if (nextOpen && nextOpen.index < nextClose) { depth++; scan = nextOpen.index + nextOpen[0].length; }
    else { depth--; scan = nextClose + closeTag.length; end = scan; }
  }
  const fragment = body.slice(start, end);
  pos = end;
  if (kind === 'p') {
    const t = textOf(fragment).trim();
    if (t) { lastParas.push(t); if (lastParas.length > 3) lastParas.shift(); }
  } else {
    tableIndex++;
    const rows = [];
    const rowRe = /<w:tr(?:\s[^>]*)?>([\s\S]*?)<\/w:tr>/g;
    let r;
    while ((r = rowRe.exec(fragment)) !== null) {
      const cells = [];
      const cellRe = /<w:tc(?:\s[^>]*)?>([\s\S]*?)<\/w:tc>/g;
      let c;
      while ((c = cellRe.exec(r[1])) !== null) cells.push(textOf(c[1]).trim());
      rows.push(cells);
    }
    const caption = lastParas.join(' | ');
    const file = path.join(outDir, `table${String(tableIndex).padStart(2, '0')}.tsv`);
    fs.writeFileSync(file, `# caption: ${caption}\n` + rows.map(x => x.join('\t')).join('\n') + '\n');
    summary.push(`table${String(tableIndex).padStart(2, '0')}: ${rows.length} rows x ${rows[0] ? rows[0].length : 0} cols; caption: ${caption.slice(0, 200)}`);
    lastParas = [];
  }
}
fs.writeFileSync(path.join(outDir, 'SUMMARY.txt'), summary.join('\n') + '\n');
console.log(summary.join('\n'));
