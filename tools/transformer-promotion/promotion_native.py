"""Isolated Java build and execution for the transformer-promotion campaign.

The core is rebuilt from this worktree's sources with only Gson on the compiler classpath, exactly as the
predecessor campaigns did, so no stale project bytecode can satisfy a dependency. Unlike those campaigns
there is no derived offline initializer in the source list: the initializer, its anchor, its weights and
its correction rule are all production now, so what is compiled here is the shipped code.
"""
from promotion_common import *
from datetime import datetime, timezone
import argparse
import subprocess

JAVA = Path('C:/Program Files/Java/jdk-21.0.11/bin')
CORE_CLASSES = OUT / 'native-core'
GSON = Path('C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/'
            'b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar')
DEPENDENCIES = [CORE_CLASSES, ROOT / 'src/main/resources', GSON]

# The production science layer, plus the two offline helpers the probe borrows and the probe itself.
# `V3BoundedEvaluation` is the shared ten-worker scheduler every campaign in this line has used.
TOOL_SOURCES = ['tools/neural/V3BoundedEvaluation.java',
                'tools/transformer-promotion/java/V3PromotionEvaluationProbe.java',
                'tools/transformer-promotion/java/V3PromotionDecodeCheck.java']


def core_sources():
    science = sorted((ROOT / 'src/main/java/com/wormzjl/createcheme/science/column/v3').rglob('*.java'))
    return science + [ROOT / path for path in TOOL_SOURCES]


def classpath():
    assert all(path.exists() for path in DEPENDENCIES), DEPENDENCIES
    return os.pathsep.join(str(path) for path in DEPENDENCIES)


def logged(command, log):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('x', encoding='utf-8') as stream:
        process = subprocess.Popen([str(x) for x in command], cwd=ROOT, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
        for line in process.stdout:
            stream.write(line); stream.flush(); print(line, end='', flush=True)
        code = process.wait()
    if code:
        raise RuntimeError(f'Native command failed ({code}); preserve {log} and diagnose before continuing.')


def next_log(pattern):
    attempt = 1
    while (OUT / 'logs' / (pattern % attempt)).exists():
        attempt += 1
    return OUT / 'logs' / (pattern % attempt)


def rebuild_core():
    frozen = verify_frozen()
    sources = core_sources()
    CORE_CLASSES.mkdir(parents=True, exist_ok=False)
    logged([JAVA / 'javac.exe', '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8',
            '-cp', GSON, '-d', CORE_CLASSES, *sources], next_log('core-source-rebuild-%d.log'))
    freeze(OUT / 'native-core-build.json', dict(
        passed=True, createdUtc=datetime.now(timezone.utc).isoformat(), frozen=frozen,
        sources=[info(p) for p in sources],
        compiledClasses=[info(p) for p in sorted(CORE_CLASSES.rglob('*.class'))],
        orderedRuntimeClasspath=classpath(), noPreexistingProjectClasspath=True,
        workers=WORKERS, requestDeadlineSeconds=DEADLINE_SECONDS, neuralBudgetMillis=NEURAL_BUDGET_MILLIS,
        maximumIterations=MAXIMUM_ITERATIONS,
        note='No study-owned initializer, decoder or budget is compiled here; all three are production.'))
    print(f'Rebuilt {len(sources)} sources into an isolated native core.', flush=True)


def verify_core():
    build = read(OUT / 'native-core-build.json')
    assert build['passed'] and build['noPreexistingProjectClasspath']
    for entry in build['sources'] + build['compiledClasses']:
        assert digest(ROOT / entry['path']) == entry['sha256'], entry['path']
    assert build['orderedRuntimeClasspath'] == classpath()
    assert digest(BUNDLED_ARTIFACT) == BASE_WEIGHTS_SHA
    return build


def execute(main, args, log):
    verify_core()
    command = [JAVA / 'java.exe', f'-Xmx{HEAP_BYTES // 1024 ** 3}g', '-cp', classpath(),
               'com.wormzjl.createcheme.science.column.v3.' + main]
    command += [arg.relative_to(ROOT).as_posix() if isinstance(arg, Path) else str(arg) for arg in args]
    logged(command, log)


def decode():
    path = decode_path()
    if path.exists():
        print(f'Reusing decode dump {path}', flush=True)
        return
    execute('V3PromotionDecodeCheck', [path, INPUTS / 'validation-inputs.jsonl'], next_log('decode-%d.log'))


def campaign(block):
    directory = run_directory(block)
    if directory.exists():
        print(f'Reusing block {block} at {directory}', flush=True)
        return
    execute('V3PromotionEvaluationProbe',
            [directory, INPUTS / 'validation-inputs.jsonl', WORKERS, DEADLINE_SECONDS,
             NEURAL_BUDGET_MILLIS, MAXIMUM_ITERATIONS, INPUTS / 'warmup.json'],
            next_log(f'campaign-block{block}-%d.log'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['rebuild-core', 'verify-core', 'decode', 'block1', 'block2'])
    mode = parser.parse_args().mode
    if mode == 'rebuild-core':
        rebuild_core()
    elif mode == 'verify-core':
        print(json.dumps({'verifiedClasses': len(verify_core()['compiledClasses'])}))
    elif mode == 'decode':
        decode()
    else:
        campaign(int(mode[-1]))
