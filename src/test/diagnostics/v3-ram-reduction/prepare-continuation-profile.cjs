const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const pkg = 'com/wormzjl/createcheme/science/column/v3/';
const output = 'build/generated/continuation-profile/';
const helper = 'com.wormzjl.createcheme.science.column.v3.V3ContinuationProfile';
const manifest = [];

function endBrace(s, start) {
    let depth = 0, state = '';
    for (let i = start; i < s.length; i++) {
        const c = s[i], n = s[i + 1];
        if (state === '//') { if (c === '\n') state = ''; continue; }
        if (state === '/*') { if (c === '*' && n === '/') { state = ''; i++; } continue; }
        if (state === '"' || state === "'") { if (c === '\\') i++; else if (c === state) state = ''; continue; }
        if (c === '/' && (n === '/' || n === '*')) { state = c + n; i++; continue; }
        if (c === '"' || c === "'") { state = c; continue; }
        if (c === '{') depth++;
        if (c === '}' && --depth === 0) return i;
    }
    throw new Error('Unbalanced Java method');
}
function method(s, marker, transform, signatureHint = '') {
    let at = s.indexOf(marker);
    if (at < 0) throw new Error('Missing method ' + marker);
    if (signatureHint) {
        while (at >= 0 && !s.slice(at, s.indexOf(') {', at)).includes(signatureHint)) at = s.indexOf(marker, at + marker.length);
        if (at < 0) throw new Error('Missing signature ' + signatureHint);
    }
    const open = s.indexOf(') {', at) + 2;
    if (open < 2) throw new Error('Missing body');
    const close = endBrace(s, open);
    return s.slice(0, open + 1) + transform(s.slice(open + 1, close)) + s.slice(close);
}
const wrapped = (body, enter) => `\n        try (var profileScope = ${helper}.enter(${enter})) {${body}\n        }\n    `;
function replaceOnce(s, before, after) {
    if (s.split(before).length !== 2) throw new Error('Expected unique anchor: ' + before);
    return s.replace(before, after);
}
function generate(name, transform) {
    const relative = pkg + name + '.java';
    const source = fs.readFileSync('src/main/java/' + relative, 'utf8').replaceAll('\r\n', '\n');
    const target = output + relative;
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, transform(source));
    manifest.push({ source: 'src/main/java/' + relative, sha256: crypto.createHash('sha256').update(source).digest('hex') });
}

generate('V3ColumnCalculator', s => {
    s = method(s, 'private static V3SolvePass solveSingleProblem(', body => {
        body = replaceOnce(body, '        return new V3SolvePass(attempt, audit, feedMolarEnthalpy, solvePath,',
            '        profileScope.finishPass(attempt, audit);\n        return new V3SolvePass(attempt, audit, feedMolarEnthalpy, solvePath,');
        return wrapped(body, '"pass", problem, solvePath, maximumIterations, budget.toString()');
    }, 'RungBudget budget');
    return replaceOnce(s, '                return new V3ColumnOutcome.Success(result, diagnostics);',
        `                ${helper}.published(selected.problem(), converged.state(), result);\n                return new V3ColumnOutcome.Success(result, diagnostics);`);
});
generate('V3SimultaneousColumnSolver', s => {
    s = method(s, 'private static Attempt solve(', body => {
        body = body.replace(/return new Attempt\.(Converged|Failure)\(([\s\S]*?)\);/g,
            'return profileScope.finishAttempt(new Attempt.$1($2));');
        body = replaceOnce(body, '            trace.sampledState(iteration, state, residual, merit);',
            '            trace.sampledState(iteration, state, residual, merit);\n            profileScope.iteration(iteration, maximumResidual, merit);');
        body = replaceOnce(body, '                if (!localBlockAccepted) trace.localBlockDirection(iteration, false);',
            `                if (!localBlockAccepted) { ${helper}.count(${helper}.LOCAL_REJECTED); trace.localBlockDirection(iteration, false); }`);
        return wrapped(body, '"newton", problem, null, maximumIterations, budget.toString()');
    });
    s = method(s, 'private static AcceptedTrial armijoTrial(', body => replaceOnce(body,
        '            double step = Math.scalb(1.0, -lineSearch);',
        `            ${helper}.count(${helper}.LINE_TRIAL);\n            double step = Math.scalb(1.0, -lineSearch);`));
    return method(s, 'private static VerifiedFinalNewton verifyFinalNewtonCorrection(', body =>
        `\n        ${helper}.count(${helper}.VERIFY);` + body);
});
generate('V3ColumnInitializer', s => method(s, 'static Seed initialize(', body =>
    wrapped(body, '"initializer", problem, mode.toString(), 0, ""'), 'Mode mode'));
generate('V3FreeWaterContinuation', s => method(s, 'static Result run(', body =>
    wrapped(body, '"free-water", untruncated, null, 0, ""')));
generate('V3EnergyShiftPredictor', s => method(s, 'static Prediction predict(', body =>
    wrapped(body, '"energy-predictor", problem, null, 0, ""')));
generate('V3MeshResidualEvaluator', s => {
    s = method(s, 'V3MeshResidual evaluate(', body => `\n        ${helper}.count(${helper}.RESIDUAL);` + body);
    return method(s, 'LocalNodeTerms localTerms(', body => `\n        ${helper}.count(${helper}.LOCAL_TERMS);` + body);
});
generate('V3FiniteDifferenceJacobian', s => method(s, 'private static Jacobian evaluate(', body =>
    `\n        ${helper}.count(${helper}.FD);` + body, 'boolean compact'));
generate('V3BlockJacobianAssembler', s => method(s, 'static V3BlockJacobian assembleLocal(', body =>
    `\n        ${helper}.count(${helper}.LOCAL_JACOBIAN);` + body, 'AnalyticTally tally'));
generate('linalg/V3BandedPivotedSolver', s => method(s, 'public static Result solve(', body =>
    `\n        ${helper}.count(${helper}.LINEAR);` + body, 'Workspace workspace'));
generate('thermo/V3PengRobinsonThermo', s => method(s, 'void evaluateInto(', body =>
    `\n        ${helper}.count(${helper}.FUGACITY);` + body));
fs.writeFileSync(output + 'manifest.json', JSON.stringify(manifest, null, 2));
console.log('Generated ' + manifest.length + ' diagnostic copies; production sources are unchanged.');
