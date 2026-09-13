"""Isolated Java build and execution for the neural-budget study.

The core is rebuilt from this worktree's sources with only Gson on the compiler classpath, exactly as the
predecessor campaigns did, so no stale project bytecode can satisfy a dependency and the only difference
from the sealed core is the registered source delta.
"""
from budget_common import *
from budget_register import core_sources, verify_plan
import argparse
import os
import subprocess

JAVA = Path('C:/Program Files/Java/jdk-21.0.11/bin')
CORE_CLASSES = ROOT / 'build/neural-budget/native-core-v1'
GSON = Path('C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/'
            'b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar')
DEPENDENCIES = [CORE_CLASSES, ROOT / 'src/main/resources', GSON]


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
    while (OUT / 'preflight' / (pattern % attempt)).exists():
        attempt += 1
    return OUT / 'preflight' / (pattern % attempt)


def rebuild_core():
    plan = verify_plan()
    _, sources = core_sources()
    assert [info(p) for p in sources] == plan['coreSources'], 'Registered core sources changed'
    CORE_CLASSES.mkdir(parents=True, exist_ok=False)
    logged([JAVA / 'javac.exe', '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8',
            '-cp', GSON, '-d', CORE_CLASSES, *sources], next_log('core-source-rebuild-%d.log'))
    freeze(OUT / 'native-core-build.json', dict(
        passed=True, sources=[info(p) for p in sources], sourceDelta=plan['sourceDelta'],
        classpath=[external(GSON)], compiler=external(JAVA / 'javac.exe'),
        compiledClasses=[info(p) for p in sorted(CORE_CLASSES.rglob('*.class'))],
        orderedRuntimeClasspath=classpath(), noPreexistingProjectClasspath=True))
    print(f'Rebuilt {len(sources)} sources into an isolated native core.', flush=True)


def verify_core():
    build = read(OUT / 'native-core-build.json')
    assert build['passed'] and build['noPreexistingProjectClasspath']
    for entry in build['sources'] + build['compiledClasses']:
        assert digest(ROOT / entry['path']) == entry['sha256'], entry['path']
    assert build['orderedRuntimeClasspath'] == classpath()
    return build


def execute(main, args, log):
    verify_core()
    command = [JAVA / 'java.exe', f'-Xmx{HEAP_BYTES // 1024 ** 3}g', '-cp', classpath(),
               'com.wormzjl.createcheme.science.column.v3.' + main]
    command += [arg.relative_to(ROOT).as_posix() if isinstance(arg, Path) else str(arg) for arg in args]
    logged(command, log)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['rebuild-core', 'verify-core'])
    mode = parser.parse_args().mode
    rebuild_core() if mode == 'rebuild-core' else print(json.dumps({'verifiedClasses': len(verify_core()['compiledClasses'])}))
