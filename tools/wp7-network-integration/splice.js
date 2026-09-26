// Replaces the text between two marker lines of a file with the content of a block file.
// usage: node splice.js <file> <block-file> <start-marker> <end-marker>
const fs = require('fs');
const [file, blockFile, startMarker, endMarker] = process.argv.slice(2);
let s = fs.readFileSync(file, 'utf8');
const start = s.indexOf(startMarker);
const end = s.indexOf(endMarker, start + 1);
if (start < 0 || end < 0) throw new Error('markers not found: ' + start + ' ' + end);
if (s.indexOf(startMarker, start + 1) >= 0 && s.indexOf(startMarker, start + 1) < end) throw new Error('start marker repeats');
s = s.slice(0, start) + fs.readFileSync(blockFile, 'utf8') + s.slice(end);
fs.writeFileSync(file, s);
