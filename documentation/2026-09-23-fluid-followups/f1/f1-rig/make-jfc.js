// Builds wp5-window.jfc from Minecraft 1.21.1's own flightrecorder-config.jfc (the profile /jfr start uses):
// jdk.ObjectCount off (with period everyChunk it forces a full "Heap Inspection Initiated GC" at every chunk rotation,
// 18 of them in the 60 s window of the fill100 pilot), thread CPU and thread allocation every 1 s instead of 10 s and
// 5 s, and jdk.ResidentSetSize (absent from Minecraft's profile) every 1 s. minecraft.ServerTickTime is enabled by name,
// as /jfr does; its periodic hook is registered when the server's profiler class initialises.
const fs = require('fs'), path = require('path');
let s = fs.readFileSync(path.join(__dirname, 'minecraft-flightrecorder-config.jfc'), 'utf8');
function set(name, body) {
  const re = new RegExp('<event name="' + name.replace(/\./g, '\\.') + '">[\\s\\S]*?</event>');
  if (!re.test(s)) throw new Error('missing ' + name);
  s = s.replace(re, `<event name="${name}">\n${body}\n    </event>`);
}
set('jdk.ObjectCount', '        <setting name="enabled">false</setting>');
set('jdk.ThreadCPULoad', '        <setting name="enabled">true</setting>\n        <setting name="period">1 s</setting>');
set('jdk.ThreadAllocationStatistics', '        <setting name="enabled">true</setting>\n        <setting name="period">1 s</setting>');
const extra = `    <!-- WP5 additions -->
    <event name="jdk.ResidentSetSize">
        <setting name="enabled">true</setting>
        <setting name="period">1 s</setting>
    </event>
    <event name="minecraft.ServerTickTime">
        <setting name="enabled">true</setting>
        <setting name="period">1 s</setting>
    </event>
`;
s = s.replace('</configuration>', extra + '</configuration>');
s = s.replace('label="Profiling"', 'label="WP5 window"');
fs.writeFileSync(path.join(__dirname, 'wp5-window.jfc'), s);
console.log('wrote wp5-window.jfc');
