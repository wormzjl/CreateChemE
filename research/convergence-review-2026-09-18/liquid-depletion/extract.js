// Read-only extraction of the liquid-depletion evidence from the classical-solver journals.
// Produces one compact JSON record per request into extracted-<journal>.jsonl so that the
// analysis script never has to re-parse the 84 MB / 36 MB / 14 MB raw journals.
//
// Usage: node extract.js     (paths are relative to ROOT, the worktree root)

const fs = require('fs');
const path = require('path');
const readline = require('readline');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const OUT = __dirname;

const JOURNALS = [
  ['design-v2', 'research/convergence-review/design-v2/classical/cases.jsonl'],
  ['design-g', 'research/convergence-review/design-g/classical/cases.jsonl'],
  ['codex', 'research/crude-regrouping/training/classical/cases.jsonl'],
];

const WITHDRAWAL_TEXT = /side draw on authored tray (\d+) requests ([-0-9.eE+]+) kmol\/h; final internal liquid ([-0-9.eE+]+) kmol\/h \(withdrawal ([-0-9.eE+]+)\)/;

function sum(a) { let s = 0; for (const v of a) s += v; return s; }

function auditChecks(record) {
  const d = record.diagnostics;
  if (!d || !d.acceptanceAudit || !Array.isArray(d.acceptanceAudit.checks)) return [];
  return d.acceptanceAudit.checks;
}

function withdrawalOf(record) {
  const checks = auditChecks(record);
  const sd = checks.find((c) => c.family === 'SIDE_DRAW_SPLIT');
  if (sd && Number.isFinite(sd.value)) {
    return { withdrawal: sd.value, source: 'audit', tray: null, drawKmolH: null, liquidKmolH: null };
  }
  const text = record.failure || '';
  const m = text.match(WITHDRAWAL_TEXT);
  if (m) {
    return {
      withdrawal: Number(m[4]),
      source: 'text',
      tray: Number(m[1]),
      drawKmolH: Number(m[2]),
      liquidKmolH: Number(m[3]),
    };
  }
  return { withdrawal: null, source: null, tray: null, drawKmolH: null, liquidKmolH: null };
}

function summarizeInput(input) {
  const feed = input.feedComponentMolarFlowsMolPerSecond || [];
  const specs = input.specifications || [];
  const reflux = specs.find((s) => 'ratio' in s);
  const kelvin = specs.find((s) => 'kelvin' in s);
  const watts = specs.find((s) => 'watts' in s);
  const steams = input.steamFeeds || [];
  const draws = input.sideDraws || [];
  const pas = input.pumparounds || [];
  return {
    stageCount: input.stageCount,
    feedStageNumber: input.feedStageNumber,
    feedTotal: sum(feed),
    feedTemperatureKelvin: input.feedTemperatureKelvin,
    topPressurePascal: input.topPressurePascal,
    stagePressureDropPascal: input.stagePressureDropPascal,
    reflux: reflux ? reflux.ratio : null,
    condenserKelvin: kelvin ? kelvin.kelvin : null,
    reboilerWatts: watts ? watts.watts : null,
    steamTotal: sum(steams.map((s) => s.molarFlowMolPerSecond)),
    steamCount: steams.length,
    steams: steams.map((s) => [s.stageNumber, s.molarFlowMolPerSecond, s.temperatureKelvin]),
    drawTotal: sum(draws.map((d) => d.molarFlowMolPerSecond)),
    drawCount: draws.length,
    draws: draws.map((d) => [d.trayNumber, d.molarFlowMolPerSecond]),
    pumparoundCount: pas.length,
    pumparounds: pas.map((p) => [p.returnTray, p.drawTray, p.dutyWatts, p.split]),
    packageId: input.packageId,
    assayId: input.assayId,
    componentIds: (input.componentBasis && input.componentBasis.componentIds) || null,
    feedFlows: feed,
  };
}

function nodeTotals(matrix) {
  if (!Array.isArray(matrix)) return null;
  return matrix.map((row) => sum(row));
}

function extractRecord(journal, record) {
  const w = withdrawalOf(record);
  const out = {
    journal,
    id: record.id,
    split: record.split || null,
    family: (record.design && record.design.family) || null,
    source: (record.design && record.design.source) || null,
    input: summarizeInput(record.input),
    success: !!record.success,
    status: record.status || null,
    failureClass: record.failureClass || null,
    failure: record.failure ? String(record.failure).slice(0, 600) : null,
    ms: record.ms,
    waterQualification: record.waterQualification || null,
    equilibriumQualified: record.equilibriumQualified === undefined ? null : record.equilibriumQualified,
    solvePath: (record.diagnostics && record.diagnostics.solvePath) || null,
    withdrawal: w.withdrawal,
    withdrawalSource: w.source,
    withdrawalTray: w.tray,
    withdrawalDrawKmolH: w.drawKmolH,
    withdrawalLiquidKmolH: w.liquidKmolH,
  };
  if (record.success && record.seed) {
    out.liquidTotals = nodeTotals(record.seed.liquid);
    out.vaporTotals = nodeTotals(record.seed.vapor);
    out.freeWater = Array.isArray(record.seed.freeWater) ? record.seed.freeWater.slice() : null;
    out.temperatures = Array.isArray(record.seed.temperatures) ? record.seed.temperatures.slice() : null;
    out.branch = record.seed.branch || null;
  }
  return out;
}

async function run() {
  for (const [name, rel] of JOURNALS) {
    const src = path.join(ROOT, rel);
    const dst = path.join(OUT, `extracted-${name}.jsonl`);
    const outStream = fs.createWriteStream(dst);
    const rl = readline.createInterface({ input: fs.createReadStream(src), crlfDelay: Infinity });
    let n = 0;
    for await (const line of rl) {
      if (!line.trim()) continue;
      const record = JSON.parse(line);
      outStream.write(JSON.stringify(extractRecord(name, record)) + '\n');
      n += 1;
    }
    await new Promise((resolve) => outStream.end(resolve));
    console.log(`${name}: ${n} records -> ${path.basename(dst)}`);
  }

  // The 312-request holdout comparison journal: three solver lanes per request.
  const evalSrc = path.join(ROOT, 'research/convergence-review/eval/packaged-holdoutv2-1/evaluation.jsonl');
  const evalDst = path.join(OUT, 'extracted-holdout.jsonl');
  const evalOut = fs.createWriteStream(evalDst);
  const rl = readline.createInterface({ input: fs.createReadStream(evalSrc), crlfDelay: Infinity });
  let n = 0;
  for await (const line of rl) {
    if (!line.trim()) continue;
    const record = JSON.parse(line);
    const lanes = {};
    for (const lane of ['current', 'neural', 'neuralFirst']) {
      const r = record[lane];
      if (!r) continue;
      const w = withdrawalOf(r);
      lanes[lane] = {
        success: !!r.success,
        status: r.status || null,
        failure: r.failure ? String(r.failure).slice(0, 600) : null,
        ms: r.ms,
        waterQualification: r.waterQualification || null,
        withdrawal: w.withdrawal,
        withdrawalSource: w.source,
      };
    }
    evalOut.write(JSON.stringify({
      journal: 'holdout312',
      id: record.id,
      input: summarizeInput(record.input),
      lanes,
    }) + '\n');
    n += 1;
  }
  await new Promise((resolve) => evalOut.end(resolve));
  console.log(`holdout312: ${n} records -> ${path.basename(evalDst)}`);
}

run().catch((e) => { console.error(e); process.exit(1); });
