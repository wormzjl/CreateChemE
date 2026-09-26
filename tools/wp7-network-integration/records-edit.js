// Shared edit helper of records.js: exact, counted, idempotent replacements that keep a file's line endings.
const fs = require('fs');
module.exports = function edit(file, pairs) {
    const raw = fs.readFileSync(file, 'utf8');
    const crlf = raw.includes('\r\n');
    let s = raw.split('\r\n').join('\n');
    for (const [a, b] of pairs) {
        if (s.split(b).length === 2 && s.split(a).length === 1) continue;
        const n = s.split(a).length - 1;
        if (n !== 1) throw new Error(file + ': ' + n + ' matches of ' + JSON.stringify(a.slice(0, 90)));
        s = s.replace(a, () => b);
    }
    fs.writeFileSync(file, crlf ? s.split('\n').join('\r\n') : s);
};
