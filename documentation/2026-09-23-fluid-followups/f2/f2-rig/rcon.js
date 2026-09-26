// Minimal Source RCON client for the dedicated server (enable-rcon=true in server.properties).
// Library: const {Rcon} = require('./rcon'); CLI: node rcon.js "command" ["command" ...]
const net = require('net');
class Rcon {
  constructor(host = '127.0.0.1', port = 25575, password = 'wp5bench') { Object.assign(this, {host, port, password}); this.id = 10; this.buf = Buffer.alloc(0); this.waiting = new Map(); }
  connect(timeoutMs = 5000) {
    return new Promise((resolve, reject) => {
      const s = net.createConnection({host: this.host, port: this.port});
      const t = setTimeout(() => { s.destroy(); reject(new Error('rcon connect timeout')); }, timeoutMs);
      s.on('error', e => { clearTimeout(t); reject(e); });
      s.on('data', d => this.onData(d));
      s.on('connect', async () => {
        clearTimeout(t); this.socket = s;
        try { const r = await this.send(this.password, 3); if (r.id === -1) throw new Error('rcon auth failed'); resolve(this); } catch (e) { reject(e); }
      });
    });
  }
  onData(d) {
    this.buf = Buffer.concat([this.buf, d]);
    while (this.buf.length >= 4) {
      const len = this.buf.readInt32LE(0); if (this.buf.length < 4 + len) break;
      const id = this.buf.readInt32LE(4), type = this.buf.readInt32LE(8), body = this.buf.slice(12, 4 + len - 2).toString('utf8');
      this.buf = this.buf.slice(4 + len);
      const key = this.waiting.has(id) ? id : (id === -1 ? [...this.waiting.keys()][0] : id);
      const w = this.waiting.get(key); if (w) { this.waiting.delete(key); w({id, type, body}); }
    }
  }
  send(body, type = 2, timeoutMs = 3600000) {
    const id = this.id++; const payload = Buffer.from(body, 'utf8');
    const b = Buffer.alloc(14 + payload.length); b.writeInt32LE(10 + payload.length, 0); b.writeInt32LE(id, 4); b.writeInt32LE(type, 8); payload.copy(b, 12); b.writeInt16LE(0, 12 + payload.length);
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.waiting.delete(id); reject(new Error('rcon timeout: ' + body)); }, timeoutMs);
      this.waiting.set(id, r => { clearTimeout(t); resolve(r); });
      this.socket.write(b);
    });
  }
  async command(cmd, timeoutMs) { return (await this.send(cmd, 2, timeoutMs)).body; }
  close() { if (this.socket) this.socket.end(); }
}
module.exports = {Rcon};
if (require.main === module) (async () => {
  const r = await new Rcon().connect();
  for (const c of process.argv.slice(2)) console.log('> ' + c + '\n' + await r.command(c));
  r.close();
})().catch(e => { console.error(e.message); process.exit(1); });
