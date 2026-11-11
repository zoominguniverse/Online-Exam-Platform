import { readFileSync,openSync,closeSync,writeFileSync } from 'node:fs'
import { join,resolve } from 'node:path'
import { args,compose,docker,projectName,nginx,hash,sql,until } from './lib/compose.mjs'
const input=args();const project=projectName(input.project);const started=Date.now()
if(!input.directory)throw new Error('--directory is required')
const directory=resolve(input.directory);const manifest=JSON.parse(readFileSync(join(directory,'manifest.json'),'utf8'))
if(manifest.project===project)throw new Error('Restore requires a different, empty Compose project')
for(const name of ['database.dump','attachments.tar','deletions.csv'])if(hash(join(directory,name))!==manifest.files[name])throw new Error('Backup checksum mismatch: '+name)
compose(project,['up','-d','db'],input['env-file'])
await until(()=>sql(project,'SELECT 1',input['env-file'])==='1','restore database')
if(sql(project,"SELECT count(*) FROM information_schema.tables WHERE table_schema='public'",input['env-file'])!=='0')throw new Error('Target database is not empty; refusing to overwrite')
docker(['volume','create','--label',`com.docker.compose.project=${project}`,'--label','com.docker.compose.volume=attachments',`${project}_attachments`])
if(docker(['run','--rm','--mount',`type=volume,source=${project}_attachments,target=/attachments`,nginx,'sh','-c','find /attachments -mindepth 1 -maxdepth 1 | head -n 1']))throw new Error('Target attachment volume is not empty')
const dump=openSync(join(directory,'database.dump'),'r')
try{docker(['compose','-p',project,'-f','deploy/compose.prod.yml',...(input['env-file']?['--env-file',input['env-file']]:[]),'exec','-T','db','pg_restore','-U','exam_migrator','-d','exam_platform','--no-owner'],{encoding:null,stdio:[dump,'pipe','pipe']})}finally{closeSync(dump)}
const archive=join(directory,'attachments.tar')
let file=openSync(archive,'r');let names
try{names=docker(['run','--rm','-i',nginx,'tar','-tf','-'],{stdio:[file,'pipe','pipe']})}finally{closeSync(file)}
if(names.split(/\r?\n/).some(name=>!/^\.\/$|^(?:\.\/)?[a-f0-9-]{36}\.(?:img|part)$/.test(name)))throw new Error('Unexpected attachment archive member')
file=openSync(archive,'r')
try{docker(['run','--rm','-i','--mount',`type=volume,source=${project}_attachments,target=/attachments`,nginx,'sh','-c','tar -C /attachments -xf - && chown -R 10001:10001 /attachments'],{encoding:null,stdio:[file,'pipe','pipe']})}finally{closeSync(file)}
const ledger=readFileSync(input['deletion-ledger']||join(directory,'deletions.csv'),'utf8').trim().split(/\r?\n/).slice(1)
const deletions=[]
for(const row of ledger){if(!row)continue;const id=row.split(',')[0];if(!/^[a-f0-9-]{36}$/.test(id))throw new Error('Invalid deletion ledger UUID');deletions.push(id)}
const ids=deletions.map(id=>`'${id}'`).join(',')
sql(project,`BEGIN; DELETE FROM web_sessions; DELETE FROM wechat_tokens; ${ids?`
 UPDATE users SET username='deleted_'||left(replace(id::text,'-',''),24),username_normalized='deleted_'||left(replace(id::text,'-',''),24),password_hash=NULL,status='DELETED',grade_id=NULL,version=version+1 WHERE id IN (${ids});
 UPDATE profiles SET name='',phone='',birthday=NULL,avatar_id=NULL,version=version+1 WHERE user_id IN (${ids});
 DELETE FROM wechat_bindings WHERE user_id IN (${ids});
 INSERT INTO deleted_account_ledger(user_id,deleted_at) SELECT id,CURRENT_TIMESTAMP FROM users WHERE id IN (${ids}) ON CONFLICT DO NOTHING;`:''} COMMIT;`,input['env-file'])
const report={project,sourceProject:manifest.project,startedAt:new Date(started).toISOString(),restoredAt:new Date().toISOString(),restoreMillis:Date.now()-started,rpoSeconds:Math.max(0,(started-new Date(manifest.createdAt).getTime())/1000),deletionsReplayed:deletions.length,oldSessionsRevoked:true}
writeFileSync(join(directory,'restore-'+project+'.json'),JSON.stringify(report,null,2)+'\n')
console.log(JSON.stringify(report))
