import { mkdirSync,openSync,closeSync,writeFileSync } from 'node:fs'
import { resolve,join,dirname } from 'node:path'
import { args,compose,docker,projectName,nginx,hash,until } from './lib/compose.mjs'
const input=args();const project=projectName(input.project)
const directory=resolve(input.directory||'tmp/backups/'+project+'-'+Date.now())
mkdirSync(dirname(directory),{recursive:true});mkdirSync(directory,{recursive:false})
const services=compose(project,['ps','--services','--status','running'],input['env-file']).split(/\r?\n/)
const running=services.filter(name=>['api-blue','api-green'].includes(name))
const createdAt=new Date().toISOString()
try {
  if(running.length)compose(project,['stop',...running],input['env-file'])
  const dump=join(directory,'database.dump');const attachments=join(directory,'attachments.tar')
  for(const [path,command] of [[dump,['compose','-p',project,'-f','deploy/compose.prod.yml',...(input['env-file']?['--env-file',input['env-file']]:[]),'exec','-T','db','pg_dump','-U','exam_migrator','-d','exam_platform','-Fc']],
    [attachments,['run','--rm','--mount',`type=volume,source=${project}_attachments,target=/attachments,readonly`,nginx,'tar','-C','/attachments','-cf','-','.']]]) {
    const output=openSync(path,'wx');try{docker(command,{encoding:null,stdio:['ignore',output,'pipe']})}finally{closeSync(output)}
  }
  const ledger=compose(project,['exec','-T','db','psql','-U','exam_migrator','-d','exam_platform','-At','-c',"COPY (SELECT user_id,deleted_at FROM deleted_account_ledger ORDER BY user_id) TO STDOUT WITH CSV HEADER"],input['env-file'])
  writeFileSync(join(directory,'deletions.csv'),ledger+'\n',{flag:'wx'})
  writeFileSync(join(directory,'manifest.json'),JSON.stringify({project,createdAt,consistentWritePause:true,files:Object.fromEntries(['database.dump','attachments.tar','deletions.csv'].map(name=>[name,hash(join(directory,name))]))},null,2)+'\n',{flag:'wx'})
  console.log(JSON.stringify({project,directory,createdAt,consistent:true}))
}finally{if(running.length){compose(project,['start',...running],input['env-file']);for(const service of running)await until(()=>docker(['run','--rm','--network',project+'_default',nginx,'wget','-q','-O','-',`http://${service}:8080/actuator/health/readiness`]).includes('UP'),'resume '+service);if(services.includes('web'))compose(project,['exec','-T','web','nginx','-s','reload'],input['env-file'])}}
