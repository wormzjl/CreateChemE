"""Compile and run the request-only admission probe over every prepared population.

    python tools/benchmark-population/run_probe.py [--ratio 0.30]

Compiles only the self-contained ``science/column/v3`` package with Gson as the sole classpath entry, so no
stale project bytecode can satisfy a dependency, then runs ``V3RequestAdmissionProbe`` once per population.
The probe performs no solve: the whole sweep is seconds, so it needs no worker pool and no deadline.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess

import population_common as common


def sources():
    package = sorted((common.ROOT / 'src/main/java/com/wormzjl/createcheme/science/column/v3').rglob('*.java'))
    helpers = [common.ROOT / name for name in common.HELPERS]
    for path in package + helpers:
        assert path.exists(), path
    return package + helpers


def compile_classes():
    classes = common.OUT / 'classes'
    if classes.exists():
        print(f'Reusing compiled classes at {classes}')
        return classes
    classes.mkdir(parents=True)
    command = [common.JAVA_HOME / 'javac.exe', '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8',
               '-encoding', 'UTF-8', '-cp', common.GSON, '-d', classes, *sources()]
    subprocess.run([str(x) for x in command], cwd=common.ROOT, check=True)
    print(f'Compiled {len(sources())} sources into {classes}')
    return classes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ratio', type=float, default=common.PRODUCTION_SCREEN_RATIO)
    parser.add_argument('--label', default='v1')
    args = parser.parse_args()
    classes = compile_classes()
    classpath = os.pathsep.join(str(p) for p in (classes, common.ROOT / 'src/main/resources', common.GSON))
    summary = {}
    for population in common.POPULATIONS:
        directory = common.OUT / args.label / 'admission' / population
        if directory.exists():
            print(f'Reusing {directory}')
        else:
            command = [common.JAVA_HOME / 'java.exe', '-Xmx2g', '-cp', classpath,
                       'com.wormzjl.createcheme.science.column.v3.V3RequestAdmissionProbe',
                       directory, common.OUT / 'inputs' / f'{population}.jsonl', args.ratio]
            subprocess.run([str(x) for x in command], cwd=common.ROOT, check=True)
        meta = json.loads((directory / 'run.json').read_text(encoding='utf-8'))
        summary[population] = {'cases': meta['caseCount'], 'typed': meta['typedInfeasible'],
                               'byGate': meta['typedByGate'], 'seconds': round(meta['elapsedSeconds'], 2)}
    print(json.dumps(summary, indent=1))


if __name__ == '__main__':
    main()
