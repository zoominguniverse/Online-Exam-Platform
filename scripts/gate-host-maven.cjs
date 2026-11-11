// Optional local transport for hosts whose Docker JVM cannot reach Maven Central over TLS.
// Upstream remains the official HTTPS repository; Maven still verifies published checksums.
const http=require('node:http'),{spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),{createHash}=require('node:crypto');
const directory=path.resolve('tmp/toolchain/maven-transport-cache');fs.mkdirSync(directory,{recursive:true});
const cache=new Map();let requests=0;
async function artifact(pathname) {
 let value=cache.get(pathname);if(value)return value
 const file=path.join(directory,createHash('sha256').update(pathname).digest('hex')+'.json')
 try{const saved=JSON.parse(fs.readFileSync(file,'utf8'));const bytes=Buffer.from(saved.bytes,'base64');if(saved.source==='https://repo.maven.apache.org/maven2'+pathname&&saved.sha256===createHash('sha256').update(bytes).digest('hex')){value={status:200,bytes,type:saved.type};cache.set(pathname,value);return value}}catch{}
 for(let attempt=0;attempt<3;attempt++)try {
  const incoming=await fetch('https://repo.maven.apache.org/maven2'+pathname,{signal:AbortSignal.timeout(30000)});requests++
  if(incoming.status>=500){await incoming.arrayBuffer();continue}
  value={status:incoming.status,bytes:Buffer.from(await incoming.arrayBuffer()),type:incoming.headers.get('content-type')||'application/octet-stream'}
  if(incoming.ok){cache.set(pathname,value);fs.writeFileSync(file,JSON.stringify({source:'https://repo.maven.apache.org/maven2'+pathname,type:value.type,bytes:value.bytes.toString('base64'),sha256:createHash('sha256').update(value.bytes).digest('hex')}))}
  return value
 }catch(error){if(attempt===2)throw error}
 throw new Error('Maven upstream unavailable after retry')
}
const server=http.createServer(async(req,res)=>{
 try {
  if(!['GET','HEAD'].includes(req.method)||!/^\/[A-Za-z0-9_./-]*$/.test(req.url)){res.writeHead(400).end();return}
  const pathname=new URL(req.url,'http://localhost').pathname;
  const value=await artifact(pathname)
  res.writeHead(value.status,{'Content-Type':value.type,'Content-Length':value.bytes.length});res.end(req.method==='HEAD'?undefined:value.bytes)
 } catch {res.writeHead(502).end('Maven upstream transport unavailable')}
});
server.listen(0,'0.0.0.0',()=>{
 const mirror='http://host.docker.internal:'+server.address().port+'/';console.log('Using temporary host TLS transport to official Maven Central; artifact coordinates and checksums are unchanged.')
 const requested=process.argv.slice(2);const child=spawn(process.execPath,requested.length?requested:['scripts/gate.mjs','--scope','web'],{env:{...process.env,EXAM_TEST_MAVEN_MIRROR:mirror},stdio:'inherit'});
 child.on('error',error=>{console.error(error.message);process.exitCode=1;server.close()});child.on('exit',code=>{console.log('Host Maven transport requests: '+requests);process.exitCode=code??1;server.close()})
});
