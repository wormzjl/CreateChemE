// Sets level.dat Data.allowCommands to 1 (so the singleplayer client may run the scenario functions); the dedicated
// server does not read it. Minimal gzip-NBT patch: finds the TAG_Byte named allowCommands and sets its value.
const fs=require('fs'),zlib=require('zlib');const p=process.argv[2];
const raw=zlib.gunzipSync(fs.readFileSync(p));const name=Buffer.from('allowCommands');
const tag=Buffer.concat([Buffer.from([1,0,name.length]),name]);const i=raw.indexOf(tag);
if(i<0||raw.indexOf(tag,i+1)>=0)throw new Error('allowCommands tag not found exactly once');
console.log('allowCommands was',raw[i+tag.length]);raw[i+tag.length]=1;fs.writeFileSync(p,zlib.gzipSync(raw));console.log('set to 1');
