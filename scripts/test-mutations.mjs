import assert from 'node:assert/strict'
import { randomBytes,createHash } from 'node:crypto'
import { cpSync,mkdirSync,readFileSync,writeFileSync,openSync,closeSync } from 'node:fs'
import { resolve,join } from 'node:path'
import { spawnSync } from 'node:child_process'
if(process.versions.node!=='22.22.2')throw new Error('Use Node 22.22.2')
const directory=resolve('tmp/exam-mutations-'+randomBytes(5).toString('hex'));mkdirSync(directory,{recursive:true})
const cases=[
 {name:'objective-score',file:'service/AttemptLifecycle.java',from:'return reference.equals(selected);',to:'return true;',test:'gradesUnorderedPartialExtraAndMissingSelectionsUsingSnapshotIntegers'},
 {name:'attempt-owner',file:'repository/AttemptRepository.java',from:'FROM attempts WHERE id = :attempt AND owner_id = :owner',to:'FROM attempts WHERE id = :attempt AND CAST(:owner AS uuid) IS NOT NULL',test:'savesWholeAnswerSetWithVersionAndDeadlineChecks'}
]
const report={startedAt:new Date().toISOString(),cases:[],passed:false}
for(const mutation of cases) {
 const context=join(directory,mutation.name);cpSync('backend',context,{recursive:true,filter:path=>!path.split(/[\\/]/).includes('target')})
 const file=join(context,'src/main/java/com/onlineexam/platform',mutation.file);const original=readFileSync(file,'utf8')
 assert.equal(original.split(mutation.from).length,2,'Mutation must match exactly once')
 writeFileSync(file,original.replace(mutation.from,mutation.to))
 const log=join(directory,mutation.name+'.log');const fd=openSync(log,'w')
 const result=spawnSync(process.execPath,['scripts/test.mjs','--java-test=IdentityIntegrationTest#'+mutation.test],{env:{...process.env,EXAM_TEST_BACKEND_CONTEXT:context},stdio:['ignore',fd,fd]});closeSync(fd)
 const output=readFileSync(log,'utf8');const killed=result.status!==0&&/Tests run: 1, Failures: [1-9]/.test(output)&&!/COMPILATION ERROR/.test(output)
 report.cases.push({name:mutation.name,test:mutation.test,killed,exitCode:result.status,log,originalSha256:createHash('sha256').update(original).digest('hex')})
 writeFileSync(join(directory,'report.json'),JSON.stringify(report,null,2)+'\n')
 console.log(mutation.name+': '+(killed?'assertion rejected mutation':'FAILED to prove assertion'))
 if(!killed){process.exitCode=1;break}
}
report.passed=report.cases.length===cases.length&&report.cases.every(item=>item.killed)
report.finishedAt=new Date().toISOString();writeFileSync(join(directory,'report.json'),JSON.stringify(report,null,2)+'\n');console.log('Mutation artifacts: '+directory)
