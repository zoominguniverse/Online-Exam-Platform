import { randomBytes } from 'node:crypto'
import { mkdirSync,writeFileSync } from 'node:fs'
import { resolve,join } from 'node:path'
import { performance } from 'node:perf_hooks'
import os from 'node:os'
import { args,compose,projectName,sql,until } from './lib/compose.mjs'
import { Api } from './lib/http.mjs'
if(process.versions.node!=='22.22.2')throw new Error('Use Node 22.22.2')
const input=args();const project=projectName(input.project)
if(!project.startsWith('exam-release-test-'))throw new Error('Capacity run requires a dedicated exam-release-test-* project')
const port=compose(project,['port','web','80'],input['env-file']).split(':').at(-1)
const origin='http://127.0.0.1:'+port
const directory=resolve(input.directory||'tmp/capacity-'+Date.now());mkdirSync(directory,{recursive:true})
const seconds=Number(input.seconds||600)
if(!Number.isInteger(seconds)||seconds<600)throw new Error('Acceptance capacity runs require at least 600 seconds')
const password=randomBytes(24).toString('hex');const suffix=randomBytes(4).toString('hex')
const admin=new Api(origin);await admin.login('admin',process.env.EXAM_BOOTSTRAP_ADMIN_PASSWORD)
const grade=(await admin.get('/catalog/grades'))[0].id
const subject=await admin.write('POST','/admin/subjects',{gradeId:grade,name:'Capacity-'+suffix})
const question=await admin.write('POST','/admin/questions',{subjectId:subject.id,type:'SHORT',difficulty:1,score:'1.00',stemHtml:'容量验证题',options:[],answer:{text:'参考'}})
const paper=await admin.write('POST','/admin/papers',{subjectId:subject.id,type:'FIXED',draft:{name:'容量卷-'+suffix,durationMinutes:20,groups:[{name:'容量',questionIds:[question.id]}]}})
await admin.write('POST',`/admin/papers/${paper.paper.id}/publish`,{expectedVersion:1})
const clients=[]
for(let index=0;index<100;index++) {
  const api=new Api(origin);const username=`Capacity-${suffix}-${index}`
  await api.write('POST','/auth/register',{username,password,gradeId:grade});await api.login(username,password)
  const attempt=await api.write('POST',`/me/papers/${paper.paper.id}/attempts`,undefined,'start-'+index)
  clients.push({api,id:attempt.id,version:attempt.version,index})
}
const started=performance.now();const list=[];const save=[];const submit=[];const errors=[]
const summary=(values)=>{const ordered=[...values].sort((a,b)=>a-b);return{count:ordered.length,p95Millis:ordered.length?ordered[Math.ceil(ordered.length*.95)-1]:0,maxMillis:ordered.at(-1)||0}}
const progress=setInterval(()=>writeFileSync(join(directory,'progress.json'),JSON.stringify({elapsedSeconds:(performance.now()-started)/1000,activeSessions:100,lists:summary(list),saves:summary(save),errors:errors.length},null,2)),30000)
try {
  await Promise.all(clients.map(async client=>{
    await new Promise(resolve=>setTimeout(resolve,client.index*25))
    let iteration=0
    while(performance.now()-started<seconds*1000){
      const cycle=performance.now();let at=performance.now()
      try{const value=await client.api.write('PUT',`/me/attempts/${client.id}/answers`,{expectedVersion:client.version,answerItems:[{questionId:question.id,answer:{text:'答案 '+iteration}}]});client.version=value.version;save.push(performance.now()-at)}catch(error){errors.push({operation:'save',message:error.message});try{client.version=(await client.api.get('/me/attempts/'+client.id)).version}catch{}}
      if(iteration%2===0){at=performance.now();try{await client.api.get('/me/papers');list.push(performance.now()-at)}catch(error){errors.push({operation:'list',message:error.message})}}
      iteration++;await new Promise(resolve=>setTimeout(resolve,Math.max(0,5000-(performance.now()-cycle))))
    }
  }))
  await Promise.all(clients.map(async client=>{const at=performance.now();try{await client.api.write('POST',`/me/attempts/${client.id}/submit`,{expectedVersion:client.version,answerItems:[{questionId:question.id,answer:{text:'最终答案'}}]},'capacity-final-'+client.index);submit.push(performance.now()-at)}catch(error){errors.push({operation:'submit',message:error.message})}}))
}finally{clearInterval(progress)}
const successful=list.length+save.length+submit.length;const successRate=successful/(successful+errors.length)
// A separate controlled deadline phase follows the unmodified 600-second load window.
// Only these runner-owned attempts are shortened; no host clock or shared data changes.
const deadlineIds=await Promise.all(clients.map(client=>client.api.write('POST',`/me/papers/${paper.paper.id}/attempts`,undefined,'deadline-'+client.index).then(value=>value.id)))
if(deadlineIds.some(id=>!/^[-a-f0-9]{36}$/.test(id)))throw new Error('Invalid owned attempt ID')
const selected=deadlineIds.map(id=>`'${id}'`).join(',')
sql(project,`UPDATE attempts SET deadline_at=clock_timestamp()+interval '2 seconds' WHERE id IN (${selected})`)
await until(()=>Number(sql(project,`SELECT count(*) FROM attempts WHERE id IN (${selected}) AND freeze_reason='DEADLINE'`))===100,'100 scheduled deadlines',100)
const lags=JSON.parse(sql(project,`SELECT json_agg(EXTRACT(EPOCH FROM (frozen_at-deadline_at))*1000) FROM attempts WHERE id IN (${selected})`)).map(Number)
const deadlines={count:lags.length,...summary(lags),controlledTestDeadlines:true,includesHttpFallback:false}
const report={startedAt:new Date(Date.now()-(performance.now()-started)).toISOString(),durationSeconds:(performance.now()-started)/1000,activeSessions:100,
  hardware:{hostCpu:os.cpus()[0].model,hostLogicalCpus:os.cpus().length,hostMemoryBytes:os.totalmem(),apiQuota:'2 CPU / 4 GiB',databaseQuota:'2 CPU / 2 GiB'},
  data:{users:Number(sql(project,'SELECT count(*) FROM users')),attempts:Number(sql(project,'SELECT count(*) FROM attempts'))},
  lists:summary(list),saves:summary(save),submits:summary(submit),deadlines,successRate,errors,
  passed:successRate>=.99&&summary(list).p95Millis<=500&&summary(save).p95Millis<=1000&&summary(submit).p95Millis<=1000&&deadlines.count===100&&deadlines.maxMillis<=30000}
writeFileSync(join(directory,'report.json'),JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));if(!report.passed)process.exitCode=1
