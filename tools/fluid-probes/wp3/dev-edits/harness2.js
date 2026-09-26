// One-off source edit used while building the WP3 viewers profile (kept with the logs for the record).
const fs=require('fs');const p='src/fluidGameTest/java/com/wormzjl/createcheme/fluid/gametest/FluidServerBenchmark.java';
let s=fs.readFileSync(p,'utf8');const crlf=s.includes('\r\n');s=s.replace(/\r\n/g,'\n');
function rep(a,b){if(!s.includes(a))throw new Error('missing: '+a.slice(0,100));if(s.indexOf(a)!==s.lastIndexOf(a))throw new Error('ambiguous: '+a.slice(0,100));s=s.replace(a,b);}
const runFields=fs.readFileSync(__dirname+'/harness2-run.java.txt','utf8');
const presentation=fs.readFileSync(__dirname+'/harness2-presentation.java.txt','utf8');
rep('        final List<double[]> fillGaps=new ArrayList<>();\n','        final List<double[]> fillGaps=new ArrayList<>();\n'+runFields);
rep('    private static Map<String,Object> runtimeCounters(Run r,double measuredSeconds) {',presentation+'    private static Map<String,Object> runtimeCounters(Run r,double measuredSeconds) {');
if(crlf)s=s.replace(/\n/g,'\r\n');fs.writeFileSync(p,s);console.log('ok');
