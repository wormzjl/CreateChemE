import re,glob,collections,sys
def parse(files):
    out=[]
    for f in files:
        s=open(f).read()
        out+=[(c,re.sub(r'\(.*\)$','',n)) for n,c in re.findall(r'<testcase name="([^"]*)" classname="([^"]*)"',s)]
    return out
m=parse(['/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad/wt-harness/build/cloud-harness/reports/science/TEST-junit-jupiter.xml','/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad/wt-harness/build/cloud-harness/reports/runtime/TEST-junit-jupiter.xml'])
r=parse(glob.glob('/tmp/claude-0/-home-user-CreateChemE/cfcc6b94-4f24-5f12-ba62-46e9aaaec421/scratchpad/wt-harness/tools/junction-holdup-prototype/run156-gates/TEST-*.xml'))
mc=collections.Counter(c for c,n in m); rc=collections.Counter(c for c,n in r)
print('harness',len(m),'tests in',len(mc),'classes; gradle run156',len(r),'in',len(rc))
for c in sorted(set(mc)|set(rc)):
    if mc[c]!=rc[c]: print(f'  {c}: harness {mc[c]} gradle {rc[c]}')
ms=set(m); rs=set(r)
print('same names (common classes):', sum(1 for x in rs if x in ms), 'names only in gradle within harness classes:', [x for x in rs-ms if x[0] in mc][:10])
