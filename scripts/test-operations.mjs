import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { spawn,spawnSync,execFileSync } from 'node:child_process'
import { mkdirSync,writeFileSync,readFileSync,openSync,closeSync,cpSync } from 'node:fs'
import { join,dirname,resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { freePort } from './lib/ports.mjs'
import { compose,docker,sql,until,root,nginx } from './lib/compose.mjs'
import { Api } from './lib/http.mjs'
import { png } from './lib/png.mjs'
import { chromium } from '../web/node_modules/playwright/index.mjs'
if(process.versions.node!=='22.22.2')throw new Error('Use Node 22.22.2')
const suffix=randomBytes(5).toString('hex');const project='exam-release-test-'+suffix;const restored='exam-restore-test-'+suffix
const directory=resolve('tmp/'+project);mkdirSync(directory,{recursive:true})
const port=await freePort()
const env={...process.env,EXAM_MIGRATION_PASSWORD:randomBytes(24).toString('hex'),EXAM_APP_PASSWORD:randomBytes(24).toString('hex'),EXAM_BOOTSTRAP_ADMIN_PASSWORD:randomBytes(24).toString('hex'),
  EXAM_BLUE_IMAGE:project+'-previous',EXAM_GREEN_IMAGE:project+'-current',EXAM_WEB_IMAGE:project+'-web',EXAM_WEB_PORT:String(port),EXAM_SEED_DEV_GRADE:'true',EXAM_ACTIVE_API:'api-blue'}
Object.assign(process.env,env)
const report={project,restored,startedAt:new Date().toISOString(),checks:[]}
const check=(name,details={})=>{report.checks.push({name,...details});writeFileSync(join(directory,'progress.json'),JSON.stringify(report,null,2));console.log('Verified: '+name)}
const run=(script,args=[],extraEnv={})=>{const result=spawnSync(process.execPath,[script,...args],{cwd:root,env:{...env,...extraEnv},encoding:'utf8',maxBuffer:4*1024*1024});if(result.status!==0)throw new Error(`${script} failed: ${(result.stderr||result.stdout||'').slice(-2000)}`);return result.stdout}
const source=join(directory,'previous');mkdirSync(source,{recursive:true})
for(const path of execFileSync('git',['ls-tree','-r','--name-only','HEAD','backend','web'],{cwd:root,encoding:'utf8'}).trim().split(/\r?\n/)){
  const target=join(source,path);mkdirSync(dirname(target),{recursive:true});writeFileSync(target,execFileSync('git',['show','HEAD:'+path],{cwd:root,maxBuffer:4*1024*1024}))
}
for(const path of ['web/Dockerfile','.dockerignore','deploy/nginx']){const target=join(source,path);mkdirSync(dirname(target),{recursive:true});cpSync(path,target,{recursive:true})}
let capacity
try {
  docker(['build','-t',env.EXAM_BLUE_IMAGE,join(source,'backend')]);check('previous-commit-image-built',{commit:execFileSync('git',['rev-parse','HEAD'],{encoding:'utf8'}).trim()})
  docker(['build','-t',env.EXAM_GREEN_IMAGE,'backend']);docker(['build','-t',env.EXAM_WEB_IMAGE,'-f','web/Dockerfile','.']);check('current-api-and-web-images-built',{nginx})
  const previousWeb=project+'-previous-web';docker(['build','-t',previousWeb,'-f',join(source,'web/Dockerfile'),source])
  compose(project,['up','-d','web','api-green'])
  const origin='http://127.0.0.1:'+port
  await until(async()=>{const response=await fetch(origin+'/readyz',{signal:AbortSignal.timeout(3000)});report.lastStartupStatus=response.status;report.lastStartupBody=await response.text();return response.ok},'production startup')
  assert((await fetch(origin+'/admin/attempts/unknown')).ok);assert((await fetch(origin+'/student/records')).ok)
  check('production-spa-and-health-routes')
  const admin=new Api(origin,{fresh:true});await admin.login('admin',env.EXAM_BOOTSTRAP_ADMIN_PASSWORD)
  const password=randomBytes(24).toString('hex');const grade=(await admin.get('/catalog/grades'))[0].id
  const student=new Api(origin,{fresh:true});await student.write('POST','/auth/register',{username:'OperationsStudent',password,gradeId:grade});await student.login('OperationsStudent',password)
  const doomed=await admin.write('POST','/admin/users',{username:'DeletedAfterBackup',password,role:'STUDENT',gradeId:grade})
  const subject=await admin.write('POST','/admin/subjects',{gradeId:grade,name:'Operations subject'})
  const form=new FormData();form.append('file',new Blob([png()],{type:'image/png'}),'private.png')
  const image=await admin.write('POST','/attachments',form)
  const question=await admin.write('POST','/admin/questions',{subjectId:subject.id,type:'SINGLE',difficulty:1,score:'1.00',stemHtml:`<p>历史私有题目</p><img data-attachment="${image.id}" alt="私有图片">`,options:[{id:'A',text:'甲'},{id:'B',text:'乙'}],answer:{optionIds:['A']}})
  const paper=await admin.write('POST','/admin/papers',{subjectId:subject.id,type:'FIXED',draft:{name:'Operations paper',durationMinutes:20,groups:[{name:'历史题组',questionIds:[question.id]}]}})
  await admin.write('POST',`/admin/papers/${paper.paper.id}/publish`,{expectedVersion:1})
  const attempt=await student.write('POST',`/me/papers/${paper.paper.id}/attempts`,undefined,'operations-start')
  const answers=[{questionId:question.id,answer:{optionId:'A'}}]
  const saved=await student.write('PUT',`/me/attempts/${attempt.id}/answers`,{expectedVersion:1,answerItems:answers})
  run('scripts/release.mjs',['--project',project,'--target','api-green'])
  assert.equal((await student.get('/me/attempts/'+attempt.id)).version,saved.version)
  const frozen=await student.write('POST',`/me/attempts/${attempt.id}/submit`,{expectedVersion:saved.version,answerItems:answers},'operations-final')
  assert.equal(frozen.finalScore,'1.00');check('blue-to-green-preserves-and-completes-existing-attempt',{attemptId:attempt.id})
  run('scripts/release.mjs',['--project',project,'--target','api-blue','--web-image',previousWeb])
  const previous=await student.get('/me/attempts/'+attempt.id);assert.deepEqual(previous.savedAnswers,frozen.savedAnswers);assert.equal(previous.status,'COMPLETED')
  const browser=await chromium.launch({headless:true});try {
    const page=await browser.newPage();await page.goto(origin+'/student/login');await page.getByLabel('用户名').fill('OperationsStudent');await page.getByLabel('密码').fill(password);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL('**/student/home')
    await page.goto(origin+'/student/attempts/'+attempt.id);await page.getByRole('heading',{name:'第 1 题',exact:true}).waitFor();assert(await page.getByRole('radio').first().isChecked());await page.screenshot({path:join(directory,'previous-web-historical-answer.png'),fullPage:true})
  }finally{await browser.close()}
  run('scripts/release.mjs',['--project',project,'--target','api-green','--web-image',env.EXAM_WEB_IMAGE]);check('rollback-to-real-previous-api-and-web-keeps-historical-answers')
  const inflight=await student.write('POST',`/me/papers/${paper.paper.id}/attempts`,undefined,'inflight-start')
  sql(project,`CREATE FUNCTION operations_delay() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='${inflight.id}' THEN PERFORM pg_sleep(2); END IF; RETURN NEW; END $$; CREATE TRIGGER operations_delay BEFORE UPDATE ON attempts FOR EACH ROW EXECUTE FUNCTION operations_delay();`)
  const pending=student.write('POST',`/me/attempts/${inflight.id}/submit`,{expectedVersion:1,answerItems:answers},'inflight-final').catch(()=>null)
  await until(()=>Number(sql(project,"SELECT count(*) FROM pg_stat_activity WHERE wait_event='PgSleep'"))>0,'inflight transaction')
  compose(project,['stop','api-green']);await pending
  compose(project,['start','api-green']);await until(async()=>{try{return(await fetch(origin+'/readyz')).ok}catch{return false}},'graceful restart')
  const completed=await student.write('POST',`/me/attempts/${inflight.id}/submit`,{expectedVersion:1,answerItems:answers},'inflight-final')
  assert.equal(completed.finalScore,'1.00');assert.equal(Number(sql(project,`SELECT count(*) FROM attempt_results WHERE attempt_id='${inflight.id}'`)),1)
  sql(project,'DROP TRIGGER operations_delay ON attempts; DROP FUNCTION operations_delay()');check('graceful-stop-commits-inflight-submission-once')
  const backup=join(directory,'backup');run('scripts/backup.mjs',['--project',project,'--directory',backup]);check('consistent-database-and-private-files-backup')
  run('scripts/release.mjs',['--project',project,'--target','api-green'])
  await admin.write('DELETE','/admin/users/'+doomed.id,{expectedVersion:doomed.version})
  assert.equal(sql(project,`SELECT count(*) FROM deleted_account_ledger WHERE user_id='${doomed.id}'`),'1')
  const ledger=join(directory,'latest-deletions.csv');writeFileSync(ledger,sql(project,'COPY (SELECT user_id,deleted_at FROM deleted_account_ledger) TO STDOUT WITH CSV HEADER')+'\n')
  const oldSession=new Map(student.cookies)
  const restoreStarted=Date.now();run('scripts/restore.mjs',['--project',restored,'--directory',backup,'--deletion-ledger',ledger])
  const restoreReport=JSON.parse(readFileSync(join(backup,'restore-'+restored+'.json'),'utf8'));assert(restoreReport.rpoSeconds<=86400);assert(restoreReport.restoreMillis<=7200000)
  assert.equal(sql(restored,`SELECT status FROM users WHERE id='${doomed.id}'`),'DELETED')
  assert.equal(sql(restored,'SELECT count(*) FROM web_sessions'),'0');assert.equal(sql(restored,'SELECT count(*) FROM wechat_tokens'),'0')
  compose(project,['stop','web'])
  compose(restored,['up','-d','--no-deps','api-green','web'])
  // Restored web initially points to blue; switch its environment template to the current green image.
  compose(restored,['up','-d','--no-deps','api-blue'])
  await until(async()=>{try{return(await fetch(origin+'/readyz')).ok}catch{return false}},'restored runtime')
  run('scripts/release.mjs',['--project',restored,'--target','api-green'])
  const restoredStudent=new Api(origin,{fresh:true});restoredStudent.cookies=oldSession
  assert.equal((await restoredStudent.raw('GET','/me/attempts/'+attempt.id)).status,401)
  await restoredStudent.login('OperationsStudent',password)
  const historical=await restoredStudent.get('/me/attempts/'+attempt.id);assert.equal(historical.finalScore,'1.00');assert.deepEqual(historical.savedAnswers,frozen.savedAnswers)
  const picture=await restoredStudent.raw('GET',`/me/attempts/${attempt.id}/questions/${question.id}/attachments/${image.id}`);assert.equal(picture.status,200);assert.deepEqual(Buffer.from(await picture.arrayBuffer()),png())
  const recoveryMillis=Date.now()-restoreStarted;assert(recoveryMillis<=7200000)
  check('isolated-restore-replays-newer-deletions-revokes-sessions-and-keeps-answers-images',{...restoreReport,recoveryMillis,recoveryIncludesRuntimeLoginAndImage:true})
  compose(restored,['stop','web']);compose(project,['start','web']);await until(async()=>{try{return(await fetch(origin+'/readyz')).ok}catch{return false}},'source runtime')
  // Separate failure injection from the stable capacity window.
  compose(project,['stop','db']);await until(async()=> (await fetch(origin+'/readyz')).status===503,'database outage readiness',30)
  assert.equal((await fetch(origin+'/livez')).status,200)
  compose(project,['start','db']);await until(async()=> (await fetch(origin+'/readyz')).ok,'database recovery')
  check('database-outage-readiness-liveness-and-recovery')
  if(!process.argv.includes('--skip-capacity')) {
    const log=openSync(join(directory,'capacity.log'),'w')
    capacity=spawn(process.execPath,['scripts/capacity.mjs','--project',project,'--directory',join(directory,'capacity')],{cwd:root,env,stdio:['ignore',log,log]})
    closeSync(log)
    const code=await new Promise(resolve=>capacity.once('exit',resolve));if(code!==0)throw new Error('Capacity test failed; inspect capacity/report.json')
    check('100-session-10-minute-capacity-budget',JSON.parse(readFileSync(join(directory,'capacity','report.json'),'utf8')))
  }
  report.passed=true
}catch(error){report.passed=false;report.error=error.message;console.error(error.message);process.exitCode=1}
finally {
  for(const item of [project,restored])try{writeFileSync(join(directory,item+'-services.log'),compose(item,['logs','--no-color','api-blue','api-green','db']))}catch{}
  for(const item of [project,restored])try{compose(item,['down','--volumes'])}catch(error){report.cleanupError=error.message;process.exitCode=1}
  report.finishedAt=new Date().toISOString();writeFileSync(join(directory,'report.json'),JSON.stringify(report,null,2)+'\n');console.log('Operations artifacts: '+directory)
}
