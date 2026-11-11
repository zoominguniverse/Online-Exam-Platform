import { spawnSync } from 'node:child_process'
import { readdirSync,readFileSync,writeFileSync,mkdirSync } from 'node:fs'
import { join,resolve } from 'node:path'
if(process.versions.node!=='22.22.2')throw new Error('Use Node 22.22.2')
const scopeIndex=process.argv.indexOf('--scope');const scope=scopeIndex<0?'platform':process.argv[scopeIndex+1]
if(!['web','platform'].includes(scope))throw new Error('--scope must be web or platform')
const directory=resolve('tmp/exam-gate-'+Date.now());mkdirSync(directory,{recursive:true})
const report={scope,startedAt:new Date().toISOString(),checks:[],passed:false};let failed=false
const run=(command,args)=>{const result=spawnSync(command,args,{stdio:'inherit',shell:process.platform==='win32'&&/^(npm|openspec)$/.test(command)});report.checks.push({command:[command,...args].join(' '),exitCode:result.status});if(result.status!==0)failed=true}
const candidates=prefix=>readdirSync('tmp',{withFileTypes:true}).filter(item=>item.isDirectory()&&item.name.startsWith(prefix)).map(item=>{try{return{path:join('tmp',item.name,'report.json'),report:JSON.parse(readFileSync(join('tmp',item.name,'report.json'),'utf8'))}}catch{return null}}).filter(Boolean)
if(scope==='platform') {
 const tasks=readFileSync('openspec/changes/build-online-exam-platform/tasks.md','utf8');const remaining=(tasks.match(/^- \[ \]/gm)||[]).length
 const gaps=[];if(remaining)gaps.push(`${remaining} tasks await authorized review/completion`)
 if(gaps.length){report.checks.push({name:'platform-evidence-preflight',passed:false,gaps});failed=true;console.error(gaps.join('\n'))}
}
if(!failed) {
 run(process.execPath,['scripts/test.mjs']);if(!failed)run(process.execPath,['scripts/test-browser.mjs']);if(!failed)run('npm',['--prefix','web','run','build'])
 if(!failed)run(process.execPath,['scripts/check-contracts.cjs'])
 if(!failed)run('openspec',['validate','build-online-exam-platform','--strict'])
 run('git',['diff','--check'])
 for(const [name,prefix,predicate] of [['operations-and-capacity','exam-release-test-',value=>value.passed&&value.checks.some(check=>check.name==='100-session-10-minute-capacity-budget'&&check.passed&&check.deadlines?.count===100)],['paired-release-and-recovery','exam-release-test-',value=>value.passed&&value.checks.some(check=>check.name==='rollback-to-real-previous-api-and-web-keeps-historical-answers')&&value.checks.some(check=>check.recoveryIncludesRuntimeLoginAndImage)],['mutation-assertions','exam-mutations-',value=>value.passed&&value.cases.length===2]]) {
  const matches=candidates(prefix).filter(value=>predicate(value.report));report.checks.push({name,passed:matches.length>0,evidence:matches.map(value=>value.path)});if(!matches.length){console.error('Missing evidence: '+name);failed=true}
 }
}
report.passed=!failed;report.finishedAt=new Date().toISOString();writeFileSync(join(directory,'report.json'),JSON.stringify(report,null,2)+'\n');console.log('Gate report: '+directory);process.exitCode=failed?1:0
