// Extracts a JUnit XML report's system-out: node xmlout.js <report.xml> [out]
const fs=require('fs');const s=fs.readFileSync(process.argv[2],'utf8');
const m=/<system-out><!\[CDATA\[([\s\S]*?)\]\]><\/system-out>/.exec(s);const out=m?m[1]:'';
if(process.argv[3])fs.writeFileSync(process.argv[3],out);else process.stdout.write(out);
