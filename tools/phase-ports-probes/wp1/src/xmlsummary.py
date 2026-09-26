import sys,glob,re,xml.etree.ElementTree as ET
d=sys.argv[1];tests=fail=err=skip=0;classes=0;lines=[];names=[]
for f in sorted(glob.glob(d+'/*.xml')):
    r=ET.parse(f).getroot();classes+=1
    tests+=int(r.get('tests'));fail+=int(r.get('failures'));err+=int(r.get('errors'));skip+=int(r.get('skipped'))
    for tc in r.iter('testcase'):
        names.append(tc.get('classname')+'.'+tc.get('name'))
        if tc.find('failure') is not None or tc.find('error') is not None: print('FAILED',tc.get('classname'),tc.get('name'))
    so=r.find('system-out')
    if so is not None and so.text:
        for l in so.text.splitlines():
            if l.startswith(('MIXED_GAS','LIQUID_JUNCTION')):lines.append(l)
print(f'classes={classes} tests={tests} failures={fail} errors={err} skipped={skip}')
def norm(l):
    l=re.sub(r'\bms=\d+','ms=*',l);l=re.sub(r'allocatedMB=[\d.]+','allocatedMB=*',l);l=re.sub(r'bytes=\d+','bytes=*',l)
    return l
open(sys.argv[2],'w').write('\n'.join(norm(l) for l in lines)+'\n')
open(sys.argv[3],'w').write('\n'.join(sorted(names))+'\n')
print('junction lines',len(lines))
