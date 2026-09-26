import re,glob,sys,html
d=sys.argv[1]; out_lines=sys.argv[2]; out_names=sys.argv[3]
lines=[];names=[];fails=0;skipped=0;classes=set()
for f in sorted(glob.glob(d+'/TEST-*.xml')):
    s=open(f).read()
    for n,c in re.findall(r'<testcase name="([^"]*)" classname="([^"]*)"',s):
        names.append(c+'.'+html.unescape(n)); classes.add(c)
    fails+=len(re.findall(r'<failure',s))+len(re.findall(r'<error',s)); skipped+=len(re.findall(r'<skipped',s))
    m=re.search(r'<system-out><!\[CDATA\[(.*?)\]\]></system-out>',s,re.S)
    if m:
        for l in m.group(1).splitlines():
            if l.startswith('MIXED_GAS_') or l.startswith('LIQUID_JUNCTION'):
                l=re.sub(r'\bms=[0-9.]+','ms=*',l); l=re.sub(r'\bbytes=[0-9.]+','bytes=*',l); l=re.sub(r'\ballocatedMB=[0-9.]+','allocatedMB=*',l)
                lines.append(l)
open(out_lines,'w').write('\n'.join(lines)+'\n'); open(out_names,'w').write('\n'.join(sorted(names))+'\n')
print('tests',len(names),'classes',len(classes),'failures+errors',fails,'skipped',skipped,'junction lines',len(lines))
