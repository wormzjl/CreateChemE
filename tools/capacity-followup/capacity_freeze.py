"""Seal the prospective plan, source-backed runtime and initial evidence before fits."""
from capacity_common import *
from capacity_register import verify_plan
import hashlib
import zipfile


def main():
    plan=verify_plan();assert not (OUT/'fits').exists()
    cache=ROOT/'.neural-cache/capacity-followup-v1';cache.mkdir(parents=True,exist_ok=True)
    paths={OUT/'training-plan.json'}
    for entry in plan['sources']+plan['imports']+plan['frozenInputs']+plan['initialChecks']:
        paths.add(ROOT/entry['path'])
    for entry in plan['runtime']:
        path=Path(entry['path'])
        if path.is_relative_to(ROOT):paths.add(path)
    for entry in (plan['screeningPanel'],plan['validationInputs'],plan['warmup']):paths.add(ROOT/entry['path'])
    for control in plan['controls'].values():paths.add(ROOT/control['path']);paths.add(ROOT/control['weights']['path'])
    paths.update(path for path in (OUT/'preflight').rglob('*') if path.is_file())
    entries=[];archive=cache/'registration.zip'
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as z:
        for path in sorted(paths):
            content=path.read_bytes();name=path.relative_to(ROOT).as_posix();z.writestr(name,content)
            entries.append(dict(entry=name,bytes=len(content),sha256=hashlib.sha256(content).hexdigest()))
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None and set(z.namelist())=={entry['entry'] for entry in entries}
        for entry in entries:assert hashlib.sha256(z.read(entry['entry'])).hexdigest()==entry['sha256']
    manifest=dict(revision='capacity-registration-cache-v1',archive=info(archive),entries=entries,
                  plan=info(OUT/'training-plan.json'),createdBeforeNewFits=True,newFits=4,checkpoints=12,
                  externalRuntime=[entry for entry in plan['runtime'] if not Path(entry['path']).is_relative_to(ROOT)],
                  predecessors=plan['predecessors'],productionDefaultChanged=False)
    freeze(cache/'registration-manifest.json',manifest)
    freeze(ROOT/'tools/capacity-followup/registration-manifest.json',manifest)
    print(json.dumps({'registrationSealed':True,'entries':len(entries),'sha256':manifest['archive']['sha256']}),flush=True)


if __name__=='__main__':main()
