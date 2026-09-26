import sys,json
# usage: rep.py file edits.json  ; edits: list of [old,new,count(optional)]
path=sys.argv[1]; edits=json.load(open(sys.argv[2]))
s=open(path).read()
for e in edits:
    old,new=e[0],e[1]; cnt=e[2] if len(e)>2 else 1
    n=s.count(old)
    if n!=cnt: sys.exit(f"expected {cnt} got {n} for: {old[:120]!r}")
    s=s.replace(old,new)
open(path,'w').write(s)
print("ok",len(edits))
