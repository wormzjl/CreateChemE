"""Seal the prospective specification and executable sources before fitting."""
from common import *
from register_followup import verify_plan
import zipfile


def main():
    plan=verify_plan();assert not (OUT/'fits').exists()
    cache=ROOT/'.neural-cache/trace-followup-v1';cache.mkdir(parents=True,exist_ok=True)
    paths={OUT/'training-plan.json'}
    for record in plan['sources']+plan['dependencies']+plan['frozenInputs']+plan['initialChecks']+[plan[k] for k in ('incumbent','screeningPanel','validationInputs','warmup')]:
        paths.add(ROOT/record['path'])
    for control in plan['controls'].values():paths.add(ROOT/control['path']);paths.add(ROOT/control['weights']['path'])
    archive=cache/'registration.zip';entries=[]
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as z:
        for path in sorted(paths):
            data=path.read_bytes();name=path.relative_to(ROOT).as_posix();z.writestr(name,data)
            entries.append(dict(entry=name,bytes=len(data),sha256=hashlib.sha256(data).hexdigest()))
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None and set(z.namelist())=={entry['entry'] for entry in entries}
        for entry in entries:
            data=z.read(entry['entry']);assert len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==entry['sha256']
    manifest=dict(revision='trace-followup-prospective-registration-v1',archive=info(archive),entries=entries,
        plan=info(OUT/'training-plan.json'),createdBeforeNewFits=True,newFits=10,registeredCheckpoints=30,
        predecessors=plan['predecessors'],productionDefaultsChanged=False)
    freeze(cache/'registration-manifest.json',manifest);freeze(ROOT/'tools/trace-followup/registration-manifest.json',manifest)
    print(json.dumps({'registrationSealed':True,'entries':len(entries),'archiveSha256':manifest['archive']['sha256']}))


if __name__=='__main__':main()
