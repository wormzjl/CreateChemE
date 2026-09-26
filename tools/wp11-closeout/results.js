// WP11: sums the JUnit XML results of one or more Gradle test tasks (build/test-results/<task>): classes, tests,
// failures, errors, skipped; lists failing and skipped test cases. Usage: node tools/wp11-closeout/results.js <task> ...
const fs = require("fs"), path = require("path");
for (const task of process.argv.slice(2)) {
  const dir = path.join("build", "test-results", task);
  let t = 0, f = 0, e = 0, s = 0, files = 0; const bad = [], skipped = [];
  for (const n of fs.readdirSync(dir)) {
    if (!n.endsWith(".xml")) continue;
    files++;
    const x = fs.readFileSync(path.join(dir, n), "utf8");
    const head = x.match(/<testsuite [^>]*>/)[0];
    const g = k => +((head.match(new RegExp(' ' + k + '="([0-9]+)"')) || [0, 0])[1]);
    t += g("tests"); f += g("failures"); e += g("errors"); s += g("skipped");
    for (const m of x.matchAll(/<testcase name="([^"]*)" classname="([^"]*)"[^>]*?(\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const body = m[4] || "";
      if (/<failure|<error/.test(body)) bad.push(m[2] + "." + m[1] + " :: " + (body.match(/message="([^"]{0,400})/) || [, ""])[1]);
      if (/<skipped/.test(body)) skipped.push(m[2] + "." + m[1]);
    }
  }
  console.log(`${task}: classes ${files}, tests ${t}, failures ${f}, errors ${e}, skipped ${s}`);
  for (const b of bad) console.log("  FAILED " + b);
  for (const b of skipped) console.log("  skipped " + b);
}
