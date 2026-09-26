"""Isolated Java compilation/execution using existing local classes and Gson."""
from capacity_common import *
import argparse
import os
import subprocess

JAVA = Path('C:/Program Files/Java/jdk-21.0.11/bin')
CLASSES = ROOT / 'build/neural-capacity-followup/classes-v1'
CORE_CLASSES = ROOT / 'build/neural-capacity-followup/native-core-v2'
GSON = Path('C:/Users/wormz/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar')
DEPENDENCIES = [CORE_CLASSES, ROOT / 'src/main/resources', GSON]


def classpath(include_capacity=True):
    paths = ([CLASSES] if include_capacity else []) + DEPENDENCIES
    assert all(path.exists() for path in paths), paths
    return os.pathsep.join(str(path) for path in paths)


def logged(command, log):
    sys.stdout.reconfigure(encoding='utf-8',errors='replace')
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('x', encoding='utf-8') as stream:
        process = subprocess.Popen([str(x) for x in command], cwd=ROOT, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
        for line in process.stdout:
            stream.write(line); stream.flush(); print(line, end='', flush=True)
        code = process.wait()
    if code:
        raise RuntimeError(f'Native command failed ({code}); preserve {log} and diagnose before continuing.')


def compile_native():
    CLASSES.mkdir(parents=True, exist_ok=True)
    files = sorted((ROOT / 'tools/capacity-followup/java').glob('*.java'))
    assert len(files) >= 4
    attempt = 1
    while (OUT / f'preflight/native-compile-{attempt}.log').exists():
        attempt += 1
    logged([JAVA / 'javac.exe', '-encoding', 'UTF-8', '-cp', classpath(False), '-d', CLASSES, *files],
           OUT / f'preflight/native-compile-{attempt}.log')
    print('Compiled isolated capacity adapters without changing predecessor classes.', flush=True)


def rebuild_core():
    import zipfile
    previous=read(SOURCE/'training-plan.json')
    manifest=read(ROOT/'tools/trace-followup/registration-manifest.json')
    archive=ROOT/manifest['archive']['path']
    assert digest(archive)==manifest['archive']['sha256']
    needed_tools={'V3HybridBaseline.java','V3HybridResidualInitializer.java','V3BoundedEvaluation.java',
                  'V3CandidateModels.java','V3ColumnTransformerInitializer.java',
                  'V3MechanisticTransformerInitializer.java','V3NeuralMvpProbe.java'}
    sources=sorted({ROOT/e['path'] for e in previous['dependencies'] if e['path'].endswith('.java')
                    and (e['path'].startswith('src/main/') or Path(e['path']).name in needed_tools)}
                   | set((ROOT/'tools/trace-followup/java').glob('*.java')))
    with zipfile.ZipFile(archive) as z:
        for source in sources:
            assert z.read(source.relative_to(ROOT).as_posix())==source.read_bytes(),source
    CORE_CLASSES.mkdir(parents=True,exist_ok=False)
    # Only Gson is on the compiler classpath: no stale project bytecode can satisfy a dependency.
    logged([JAVA/'javac.exe','-J-Duser.language=en','-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-cp',GSON,'-d',CORE_CLASSES,*sources],
           OUT/'preflight/core-source-rebuild-v2.log')
    freeze(OUT/'native-core-build.json',dict(passed=True,sourceArchive=manifest['archive'],
        sources=[info(p) for p in sources],classpath=[dict(path=str(GSON),sha256=digest(GSON))],
        compiler=dict(path=str(JAVA/'javac.exe'),sha256=digest(JAVA/'javac.exe')),
        compiledClasses=[info(p) for p in sorted(CORE_CLASSES.rglob('*.class'))],
        noPreexistingProjectClasspath=True))
    print(f'Rebuilt {len(sources)} archive-matched Java sources into an isolated native core.',flush=True)


def execute(main, args, log):
    command = [JAVA / 'java.exe', '-Xmx4g', '-cp', classpath(),
               'com.wormzjl.createcheme.science.column.v3.' + main]
    command += [arg.relative_to(ROOT).as_posix() if isinstance(arg, Path) else str(arg) for arg in args]
    logged(command, log)


if __name__ == '__main__':
    p = argparse.ArgumentParser(); p.add_argument('mode', choices=['compile','rebuild-core'])
    args=p.parse_args(); rebuild_core() if args.mode=='rebuild-core' else compile_native()
