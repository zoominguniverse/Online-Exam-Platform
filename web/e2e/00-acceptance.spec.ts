import {test,expect,type Page,type APIRequestContext} from '@playwright/test'
import {randomBytes,randomUUID} from 'node:crypto'
import {writeFileSync} from 'node:fs'
import {execFileSync} from 'node:child_process'
import {png} from '../../scripts/lib/png.mjs'
import {checkFormFields,failWriteAndKeepInput} from './ui-checks'

const adminOrigin=process.env.EXAM_E2E_ADMIN_ORIGIN!,origin=process.env.EXAM_E2E_ORIGIN!
const tag=randomBytes(4).toString('hex'),password=randomBytes(20).toString('hex')
const names={a:'AcceptanceA-'+tag,b:'AcceptanceB-'+tag,out:'AcceptanceOut-'+tag}
const formChecks:unknown[]=[],writeChecks:unknown[]=[]
async function login(page:Page,name:string,admin=false){await page.goto((admin?adminOrigin:origin)+(admin?'/admin/login':'/student/login'));await page.getByLabel('用户名',{exact:true}).fill(name);const secret=admin?process.env.EXAM_BOOTSTRAP_ADMIN_PASSWORD!:password;await page.getByLabel('密码',{exact:true}).fill(secret);formChecks.push(await checkFormFields(page));if(!writeChecks.length){writeChecks.push(await failWriteAndKeepInput(page,page.locator('form button').last(),'/auth/login'));await expect(page.getByLabel('密码',{exact:true})).toHaveValue('');await page.getByLabel('密码',{exact:true}).fill(secret)}await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByRole('heading',{name:admin?'管理首页':'学生中心',exact:true})).toBeVisible()}
async function call(api:APIRequestContext,method:string,path:string,data?:object,expected=200){const csrf=(await(await api.get('/api/v1/auth/csrf')).json()).data;const response=await api.fetch('/api/v1'+path,{method,data,headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':randomBytes(16).toString('hex')}});expect(response.status(),method+' '+path).toBe(expected);return(await response.json()).data}
async function value(api:APIRequestContext,path:string){const response=await api.get('/api/v1'+path);expect(response.ok()).toBeTruthy();return(await response.json()).data}
async function keyboardTo(page:Page,role:'button'|'radio'|'checkbox'|'textbox',name:string){for(let i=0;i<120;i++){const control=page.getByRole(role,{name,exact:true});if(await control.evaluate(node=>node===document.activeElement)){await expect(control).toHaveCSS('outline-width','3px');return control}await page.keyboard.press('Tab')}throw new Error('Keyboard could not reach '+name)}

test('Remaining Web acceptance uses real windows, independent sessions, empty views and canonical routes',async({browser})=>{
  test.setTimeout(240000)
  const contexts=await Promise.all(Array.from({length:5},()=>browser.newContext()))
  const [admin,a,b,peer,outsider]=await Promise.all(contexts.map(context=>context.newPage()))
  const evidence:Record<string,unknown>={node:process.version,browser:browser.version(),project:process.env.EXAM_E2E_PROJECT}
  try{
    await login(admin,'admin',true)
    const emptyViews=[]
    for(const [route,text] of [['/users/student','没有匹配的账号。'],['/subjects','暂无学科。'],['/questions','暂无题目。'],['/papers','暂无试卷。'],['/tasks','暂无年级任务。'],['/attempts','暂无符合条件的记录。'],['/messages','暂无消息。'],['/questions/new','暂无学科，请先创建学科。'],['/papers/new','暂无学科，请先创建学科。']]){
      await admin.goto(adminOrigin+'/admin'+route);await expect(admin.getByText(text,{exact:false})).toBeVisible();emptyViews.push('/admin'+route)
    }
    await admin.goto(adminOrigin+'/admin/logs');await admin.getByLabel('请求标识').fill(randomUUID());await admin.getByRole('button',{name:'筛选日志'}).click();await expect(admin.getByText('暂无动态。')).toBeVisible();emptyViews.push('/admin/logs (no matching request)')
    const grade=(await value(admin.request,'/catalog/grades'))[0].id
    const users:Record<string,string>={}
    for(const key of ['a','b'] as const)users[key]=(await call(admin.request,'POST','/auth/register',{username:names[key],password,gradeId:grade},201)).id
    await login(a,names.a);await login(b,names.b)
    for(const [route,text] of [['/papers','暂无练习试卷。'],['/tasks','暂无年级任务。'],['/records','暂无符合条件的记录。'],['/mistakes','暂无错题。'],['/messages','暂无消息。']]){await a.goto(origin+'/student'+route);await expect(a.getByText(text,{exact:false})).toBeVisible();emptyViews.push('/student'+route)}
    // Controlled empty-history fixture in this runner's private PostgreSQL only.
    const project=process.env.EXAM_E2E_PROJECT!;expect(project).toMatch(/^exam-browser-[a-f0-9]{10}$/);expect(users.b).toMatch(/^[a-f0-9-]{36}$/)
    execFileSync('docker',['exec',project+'-db-1','psql','-U','exam_migrator','-d','exam_java_dev','-v','ON_ERROR_STOP=1','-c',`DELETE FROM audit_events WHERE actor_id='${users.b}'`],{stdio:'pipe'})
    await b.goto(origin+'/student/activities');await expect(b.getByText('暂无动态。')).toBeVisible();emptyViews.push('/student/activities (own fixture history removed)')
    evidence.emptyViews=emptyViews;evidence.emptyNotApplicable={adminUsers:'Last enabled administrator cannot be absent',details:'Missing identifiers use a recoverable not-found state',profile:'An authenticated account always has a profile; optional fields may be blank',dashboard:'Zero counters and date-series are the empty representation'}
    const subject=await call(admin.request,'POST','/admin/subjects',{gradeId:grade,name:'验收学科-'+tag},201)
    const question=await call(admin.request,'POST','/admin/questions',{subjectId:subject.id,type:'SINGLE',difficulty:1,score:'1.00',stemHtml:'键盘作答验收',options:[{id:'A',text:'正确选项'},{id:'B',text:'其他选项'}],answer:{optionIds:['A']}},201)
    const now=Date.now(),windows=[{status:'UPCOMING',from:now+3600000,to:now+7200000},{status:'OPEN',from:now-3600000,to:now+3600000},{status:'CLOSED',from:now-7200000,to:now-3600000}]
    async function paper(type:string,name:string,window?:{from:number;to:number}){const draft={name,durationMinutes:20,groups:[{name:'验收题组',questionIds:[question.id]}],...(window?{opensAt:new Date(window.from).toISOString(),closesAt:new Date(window.to).toISOString()}:{})};const result=await call(admin.request,'POST','/admin/papers',{subjectId:subject.id,type,draft},201);await call(admin.request,'POST','/admin/papers/'+result.paper.id+'/publish',{expectedVersion:1});return result.paper.id as string}
    const timed=[]
    for(const window of windows)timed.push({...window,id:await paper('TIMED','时段-'+window.status+'-'+tag,window)})
    await a.goto(origin+'/student/papers')
    const list=await value(a.request,'/me/papers?pageSize=100')
    for(const item of timed){const row=a.getByRole('listitem').filter({has:a.getByRole('heading',{name:'时段-'+item.status+'-'+tag})});expect(list.items.find((paper:{id:string})=>paper.id===item.id).status).toBe(item.status);await expect(row).toContainText('香港时间');if(item.status==='OPEN')await expect(row.getByRole('button',{name:'开始作答'})).toBeEnabled();else{await expect(row.getByRole('button',{name:'开始作答'})).toBeDisabled();await call(a.request,'POST','/me/papers/'+item.id+'/attempts',undefined,409)}}
    evidence.timedWindows=timed.map(({status,id})=>({status,id,uiMatchesApi:true,closedStartRejected:status!=='OPEN'}))
    // A real delayed request verifies repeat clicks cannot create a second ordinary attempt.
    const open=timed.find(item=>item.status==='OPEN')!;let starts=0;let release=()=>{};const delayed=new Promise<void>(resolve=>release=resolve)
    await a.route('**/api/v1/me/papers/'+open.id+'/attempts',async route=>{starts++;await delayed;await route.continue()})
    const start=a.getByRole('listitem').filter({has:a.getByRole('heading',{name:'时段-OPEN-'+tag})}).getByRole('button',{name:'开始作答'})
    await start.click();await expect(start).toBeDisabled();await expect.poll(()=>starts).toBe(1);await a.keyboard.press('Enter');expect(starts).toBe(1);release();await expect(a.getByRole('status')).toHaveText('已保存');await a.unroute('**/api/v1/me/papers/'+open.id+'/attempts')
    const attempt=a.url().split('/').at(-1)!;await a.goto(origin+'/student/exam/'+attempt);await expect(a.getByRole('status')).toHaveText('已保存')
    // Complete the full answer/confirm path with keyboard events only.
    await a.keyboard.press('Control+Home');await a.keyboard.press('Tab');const answer=await keyboardTo(a,'radio','正确选项');await a.keyboard.press('Space');await expect(answer).toBeChecked();await keyboardTo(a,'button','准备交卷');await a.keyboard.press('Enter');await expect(a.getByRole('dialog')).toBeVisible();await keyboardTo(a,'button','确认交卷并冻结答案');await a.keyboard.press('Enter');await expect(a.getByRole('heading',{name:'答卷结果与回看'})).toBeVisible();await expect(a.getByText('最终成绩：1.00',{exact:false})).toBeVisible();evidence.keyboardAnswer={attempt,starts,completed:true}
    const taskPapers=[await paper('TASK','资格卷甲-'+tag),await paper('TASK','资格卷乙-'+tag)]
    const task=await call(admin.request,'POST','/admin/tasks',{name:'资格任务-'+tag,gradeId:grade,paperIds:taskPapers},201);await call(admin.request,'POST','/admin/tasks/'+task.id+'/publish',{expectedVersion:1})
    users.out=(await call(admin.request,'POST','/auth/register',{username:names.out,password,gradeId:grade},201)).id;await login(outsider,names.out)
    const forbidden=await outsider.request.get(origin+'/api/v1/me/tasks/'+task.id);expect(forbidden.status()).toBe(404);await outsider.goto(origin+'/student/tasks/'+task.id);await expect(outsider.getByRole('alert')).toContainText('不存在');await expect(outsider.getByRole('button',{name:'进入答卷'})).toHaveCount(0)
    await a.goto(origin+'/student/tasks/'+task.id);await expect(a.getByRole('button',{name:'进入答卷'})).toHaveCount(2);evidence.taskEligibility={targetPapers:2,outsiderHttpStatus:404,outsiderPageRejected:true}
    const sent=await call(admin.request,'POST','/admin/messages',{title:'群发验收-'+tag,body:'A、B、A去重',recipientIds:[users.a,users.b,users.a]});expect(sent.message.recipientCount).toBe(2)
    for(const page of [a,b]){await page.goto(origin+'/student/messages');await expect(page.getByRole('link',{name:'群发验收-'+tag})).toHaveCount(1)}
    await login(peer,names.a)
    for(const page of [a,peer,a,peer]){await page.goto(origin+'/student/messages/'+sent.message.id);await expect(page.getByText('A、B、A去重',{exact:true})).toBeVisible()}
    await admin.goto(adminOrigin+'/admin/messages/'+sent.message.id);await expect(admin.getByText('收件 2 人 · 已读 1 人')).toBeVisible();const received=await value(admin.request,'/admin/messages/'+sent.message.id);expect(received.recipients.filter((recipient:{readAt:string|null})=>recipient.readAt)).toHaveLength(1);evidence.messageSessions={recipients:2,independentReaderSessions:2,reads:4,readCount:1}
    const before=await value(a.request,'/me/profile');await a.goto(origin+'/student/profile');await a.getByLabel('姓名',{exact:true}).fill('跨会话学生');await a.getByLabel('手机号').fill('+852 23456789');await a.getByLabel('生日').fill('2001-02-03');formChecks.push(await checkFormFields(a));writeChecks.push(await failWriteAndKeepInput(a,a.getByRole('button',{name:'保存资料'}),'/me/profile','PATCH'));await a.getByRole('button',{name:'保存资料'}).click();await expect(a.getByText('资料已保存。')).toBeVisible();await a.getByLabel(/更新头像/).setInputFiles({name:'profile.png',mimeType:'image/png',buffer:png(96,48)});await expect(a.getByText('头像已保存。')).toBeVisible()
    for(const page of [peer,a]){if(page===a){await page.getByRole('button',{name:'退出当前会话'}).click();await login(page,names.a)}await page.goto(origin+'/student/profile');await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('跨会话学生');await expect(page.getByLabel('手机号')).toHaveValue('+852 23456789');await expect(page.getByLabel('生日')).toHaveValue('2001-02-03');await expect.poll(()=>page.getByRole('img',{name:'我的头像'}).evaluate((node:HTMLImageElement)=>node.naturalWidth)).toBe(96);expect((await value(page.request,'/me/profile')).createdAt).toBe(before.createdAt)}
    const adminBefore=await value(admin.request,'/me/profile');await admin.goto(adminOrigin+'/admin/profile');await admin.getByLabel('姓名',{exact:true}).fill('验收管理员');await admin.getByLabel('手机号').fill('+852 87654321');formChecks.push(await checkFormFields(admin));writeChecks.push(await failWriteAndKeepInput(admin,admin.getByRole('button',{name:'保存资料'}),'/me/profile','PATCH'));await admin.getByRole('button',{name:'保存资料'}).click();await expect(admin.getByText('资料已保存。')).toBeVisible();await admin.reload();await expect(admin.getByLabel('手机号')).toHaveValue('+852 87654321');expect((await value(admin.request,'/me/profile')).createdAt).toBe(adminBefore.createdAt);evidence.profiles={newLoginAndIndependentSession:true,avatarWidth:96,adminPhoneUpdated:true,createdAtUnchanged:true}
    await admin.goto(adminOrigin+'/admin/users/new/students');await admin.getByLabel('用户名',{exact:true}).fill('FormStudent-'+tag);await admin.getByLabel('初始密码').fill(password);await admin.getByRole('combobox',{name:'年级',exact:true}).selectOption(grade);formChecks.push(await checkFormFields(admin));writeChecks.push(await failWriteAndKeepInput(admin,admin.getByRole('button',{name:'创建',exact:true}),'/admin/users'));await expect(admin.getByLabel('初始密码')).toHaveValue('');await admin.getByLabel('初始密码').fill(password);await admin.getByRole('button',{name:'创建',exact:true}).click();await expect(admin.getByRole('heading',{name:'编辑账号'})).toBeVisible();formChecks.push(await checkFormFields(admin));writeChecks.push(await failWriteAndKeepInput(admin,admin.getByRole('button',{name:'保存',exact:true}),'/admin/users/'+admin.url().split('/').at(-2),'PATCH'));await admin.getByRole('button',{name:'保存',exact:true}).click();await expect(admin.getByText('账号已保存。')).toBeVisible()
    const routes=[['/users/student','学生列表'],['/users/admin','管理员列表'],['/logs','操作日志'],['/subjects/'+subject.id+'/edit','编辑学科'],['/tasks/'+task.id+'/edit','资格任务-'+tag]]
    for(const [route,heading] of routes){await admin.goto(adminOrigin+'/admin'+route);await expect(admin.getByRole('heading',{name:heading,exact:true})).toBeVisible()}
    await a.goto(origin+'/student/attempts/'+attempt+'/grade');await expect(a.getByRole('heading',{name:'答卷结果与回看'})).toBeVisible();evidence.canonicalRoutes=[...routes.map(([route])=>'/admin'+route),'/student/exam/'+attempt,'/student/attempts/'+attempt+'/grade'];evidence.result='passed'
  }finally{evidence.formFields=formChecks;evidence.failedWrites=writeChecks;writeFileSync(process.env.EXAM_E2E_ARTIFACTS+'/acceptance-summary.json',JSON.stringify(evidence,null,2)+'\n');await Promise.allSettled(contexts.map(context=>context.close()))}
})
