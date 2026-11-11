import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { readFileSync } from 'node:fs'
export const root=fileURLToPath(new URL('../../',import.meta.url))
export const nginx='nginx:1.30.6-alpine@sha256:583b7f2fce1cdb8da5ad5c39ebd3c33b98b87d493190e548bdee1fa8c619c311'
export function args() { const values={};for(let i=2;i<process.argv.length;i+=2){if(!process.argv[i].startsWith('--')||!process.argv[i+1])throw new Error('Use --name value');values[process.argv[i].slice(2)]=process.argv[i+1]}return values }
export function projectName(value) { if(!/^[a-z][a-z0-9-]{1,50}$/.test(value||''))throw new Error('Explicit valid --project is required');return value }
export function docker(argv,options={}) {
  const result=spawnSync('docker',argv,{cwd:root,env:process.env,encoding:'utf8',maxBuffer:32*1024*1024,...options})
  if(result.error||result.status!==0)throw new Error(`Docker ${argv[0]} failed (exit ${result.status}): ${(result.stderr||result.error?.message||'').slice(-1000)}`)
  return typeof result.stdout==='string'?result.stdout.trim():result.stdout
}
export function compose(project,argv,envFile) {
  // Explicit offline execution is limited to our random operations-test projects.
  // Production projects keep their normal pull policy.
  if(process.env.EXAM_TEST_OFFLINE_IMAGES==='1'&&/^exam-(release|restore)-test-[a-f0-9]{10}$/.test(project)&&['up','run'].includes(argv[0]))argv=[argv[0],'--pull','never',...argv.slice(1)]
  return docker(['compose','-p',project,'-f','deploy/compose.prod.yml',...(envFile?['--env-file',envFile]:[]),...argv])
}
export function hash(path) {return createHash('sha256').update(readFileSync(path)).digest('hex')}
export async function until(check,label,attempts=100) {let lastError='';for(let i=0;i<attempts;i++){try{if(await check())return}catch(error){lastError=error.message}await new Promise(resolve=>setTimeout(resolve,500))}throw new Error('Timeout: '+label+(lastError?' ('+lastError+')':''))}
export function sql(project,statement,envFile) {return compose(project,['exec','-T','db','psql','-U','exam_migrator','-d','exam_platform','-At','-v','ON_ERROR_STOP=1','-c',statement],envFile)}
