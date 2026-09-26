// Review 8.8: when a slice-end check would first see a quantity cross a threshold, against the 0.1 s reference.
// node thresholds.js <xml> <case> <tankId> <P|T|m|liqVol> <threshold[,threshold]> [reopen=off] [--rows]
// For each cadence: the first slice end at or above each threshold, the value there (overshoot), and the crossing time
// interpolated linearly between the two slice ends around it.
const fs=require('fs');
const [xml,name,tank,quantity,thresholds,reopenArg,rowsFlag]=process.argv.slice(2);const reopen=reopenArg||'off';
const s=fs.readFileSync(xml,'utf8').replace(/&#10;/g,'\n');
const byCadence={};
for(const line of s.split(/\r?\n/)){
  if(!line.startsWith('EVENT case='+name+' '))continue;
  const cadence=line.match(/ cadence=(\S+)/)[1];if(!line.includes(' reopen='+reopen+' '))continue;
  const t=Number(line.match(/ t=(\S+)/)[1]);const body=line.match(new RegExp(' tank'+tank+'\\(([^)]*)\\)'));if(!body)continue;
  const value=Number(body[1].match(new RegExp('(?:^|,)'+quantity+'=([-0-9.eE]+)'))[1]);
  (byCadence[cadence]=byCadence[cadence]||[]).push([t,value]);
}
for(const [cadence,rows] of Object.entries(byCadence)){
  for(const threshold of thresholds.split(',').map(Number)){
    let previous=null,found=false;
    for(const [t,v] of rows){
      if(v>=threshold){
        const crossing=previous?previous[0]+(threshold-previous[1])/(v-previous[1])*(t-previous[0]):NaN;
        console.log(`${name} cadence=${cadence} reopen=${reopen} tank${tank}.${quantity} threshold=${threshold}: first slice end at/above t=${t} value=${v} overshoot=${(v-threshold).toPrecision(6)} previous slice end t=${previous?previous[0]:'-'} value=${previous?previous[1]:'-'} interpolated crossing=${crossing.toFixed(3)}`);
        found=true;break;
      }
      previous=[t,v];
    }
    if(!found)console.log(`${name} cadence=${cadence} tank${tank}.${quantity} threshold=${threshold}: never reached; last ${JSON.stringify(rows[rows.length-1])}`);
  }
  if(rowsFlag)console.log('  rows '+cadence+': '+rows.filter((r,i)=>cadence!=='0.1'||i%10===9).map(r=>r[0]+':'+r[1]).join(' '));
}
