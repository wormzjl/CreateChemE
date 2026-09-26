"""Append-only recovery checkpoints of a live salvage journal outside build/."""
from pathlib import Path
import hashlib,json,zipfile
from salvage_nplus1 import AREA,CACHE,digest
p=AREA/'attempt-001/evaluation.jsonl'
data=p.read_bytes();end=data.rfind(b'\n')+1;complete=data[:end]
rows=[json.loads(x) for x in complete.splitlines()]
assert len({r['id'] for r in rows})==len(rows)
target=CACHE/('checkpoint-%04d.zip'%len(rows))
if not target.exists():
    with zipfile.ZipFile(target,'x',zipfile.ZIP_DEFLATED) as z:
        z.writestr('evaluation.jsonl',complete)
        z.writestr('checkpoint.json',json.dumps({'completed':len(rows),'partialTrailingBytesExcluded':len(data)-end,'planSha256':digest(AREA/'plan.json'),'journalSha256':hashlib.sha256(complete).hexdigest(),'policy':'Preserve this completed ID set; never retry native failures. Resume only following documented execution failure with separately registered remaining-input revision.'},indent=2))
print(target,len(rows))
