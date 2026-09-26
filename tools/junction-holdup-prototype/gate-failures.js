// Review 8.6: print every failed test case and its message from a folder of JUnit XML files.
// Usage: node gate-failures.js <folder>
const fs=require('fs'),path=require('path');const dir=process.argv[2]||'.';
for(const f of fs.readdirSync(dir).filter(f=>f.endsWith('.xml'))){
  const s=fs.readFileSync(path.join(dir,f),'utf8');
  const re=/<testcase name="([^"]*)"[^>]*>\s*<failure message="([^"]*)"/g;let m;
  while((m=re.exec(s))){const msg=m[2].replace(/&#10;/g,' | ').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&quot;/g,'"').replace(/&amp;/g,'&');
    console.log(f.replace('TEST-com.wormzjl.createcheme.','').replace('.xml','')+'.'+m[1]+' :: '+msg.slice(0,Number(process.argv[3]||600)));}
}
