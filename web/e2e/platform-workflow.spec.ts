import { test,expect,chromium,type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { writeFileSync } from 'node:fs'
import { png } from '../../scripts/lib/png.mjs'
import { freePort } from '../../scripts/lib/ports.mjs'
import {checkFormFields,failWriteAndKeepInput} from './ui-checks'

const suffix=randomBytes(4).toString('hex')
const username='WebWorkflow-'+suffix
const password=randomBytes(20).toString('hex')
const adminOrigin=process.env.EXAM_E2E_ADMIN_ORIGIN!
const studentOrigin=process.env.EXAM_E2E_ORIGIN!
const image=png(96,48)
const formulas=[String.raw`\frac{1}{2}`,String.raw`x_i^2`,String.raw`\begin{bmatrix}1&2\\3&4\end{bmatrix}`]
const artifact=(name:string)=>`${process.env.EXAM_E2E_ARTIFACTS}/${name}`

test('Both Web entries show the real API version and recover from network failure',async({browser})=>{
  const versions:string[]=[];const context=await browser.newContext();const page=await context.newPage()
  try {
    for(const [origin,entry] of [[adminOrigin,'/admin/'],[studentOrigin,'/student/']]) {
      await page.goto(origin+entry);await expect(page.getByText('API 版本 v1',{exact:true})).toBeVisible()
      versions.push(await page.getByText('API 版本 v1',{exact:true}).innerText())
      await page.route('**/api/v1/version',route=>route.abort('failed'));await page.reload()
      await expect(page.getByRole('alert')).toContainText('暂时无法连接考试服务')
      await page.unroute('**/api/v1/version');await page.getByRole('button',{name:'重试',exact:true}).click()
      await expect(page.getByText('API 版本 v1',{exact:true})).toBeVisible()
    }
    expect(versions[0]).toBe(versions[1])
    await page.goto(studentOrigin+'/student/login');const username=page.getByLabel('用户名',{exact:true});await username.focus();await page.keyboard.press('Tab')
    await expect(username).toHaveAttribute('aria-invalid','true');const description=await username.getAttribute('aria-describedby');await expect(page.locator('#'+description)).toHaveText('请填写此项。')
    await username.fill('WebFieldCheck');await expect(username).not.toHaveAttribute('aria-invalid','true');await expect(page.locator('#'+description)).toHaveCount(0)
    writeFileSync(artifact('web-entry-summary.json'),JSON.stringify({versions,networkFailureRecovery:true,fieldBlurValidation:true,node:process.version},null,2)+'\n')
  }finally{await context.close()}
})

async function keyboardLogin(page:Page,admin=false) {
  await page.goto((admin?adminOrigin:studentOrigin)+(admin?'/admin/login':'/student/login'))
  await page.getByLabel('用户名').focus();await page.keyboard.insertText(admin?'admin':username)
  await expect(page.getByLabel('用户名')).toBeFocused();await expect(page.getByLabel('用户名')).toHaveCSS('outline-width','3px')
  await page.keyboard.press('Tab');await page.keyboard.insertText(admin?process.env.EXAM_BOOTSTRAP_ADMIN_PASSWORD!:password)
  await page.keyboard.press('Tab');await page.keyboard.press('Enter')
  await expect(page.getByRole('heading',{name:admin?'管理首页':'学生中心',exact:true})).toBeVisible()
}
async function shot(page:Page,name:string) {await settled(page);await page.screenshot({path:artifact(name+'.png'),fullPage:true})}
async function settled(page:Page) {
  await expect(page.locator('main')).toBeVisible()
  await expect(page.locator('main [role=status]').filter({hasText:/正在/})).toHaveCount(0)
}
async function loseOnce(page:Page,path:string,reconcile?:string) {
  const requests:{body:string|null;key:string|undefined}[]=[]
  await page.route('**/api/v1'+path,async route=>{
    if(route.request().method()!=='POST'){await route.continue();return}
    requests.push({body:route.request().postData(),key:route.request().headers()['idempotency-key']})
    if(requests.length===1){
      if(reconcile)await page.route('**/api/v1'+reconcile,route=>route.abort('failed'),{times:1})
      const committed=await route.fetch();expect(committed.ok()).toBeTruthy();await route.abort('failed')
    } else await route.continue()
  })
  return requests
}
async function contrast(page:Page) {
  return page.locator('main').evaluate(main=>{
    const rgb=(value:string)=>value.match(/[\d.]+/g)!.slice(0,3).map(Number)
    const luminance=(value:string)=>rgb(value).map(v=>v/255).map(v=>v<=.04045?v/12.92:((v+.055)/1.055)**2.4).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0)
    const pairs=[...main.querySelectorAll('h1,h2,p,a,label,button,td,th')].filter(element=>(element as HTMLElement).innerText.trim()&&element.getClientRects().length&&!(element as HTMLButtonElement).disabled).map(element=>{
      const style=getComputedStyle(element);let parent:Element|null=element;let background='rgb(246, 248, 252)'
      while(parent){const value=getComputedStyle(parent).backgroundColor;if(value!=='rgba(0, 0, 0, 0)'&&value!=='transparent'){background=value;break}parent=parent.parentElement}
      const a=luminance(style.color),b=luminance(background)
      return {ratio:(Math.max(a,b)+.05)/(Math.min(a,b)+.05),text:(element as HTMLElement).innerText.slice(0,30)}
    })
    return {checked:pairs.length,minimum:Math.min(...pairs.map(item=>item.ratio)),failures:pairs.filter(item=>item.ratio<4.5)}
  })
}
async function failureRecovery(page:Page,url:string,endpoint:string) {
  const pattern='**/api/v1'+endpoint
  let release:()=>void=()=>{};const delayed=new Promise<void>(resolve=>release=resolve)
  await page.route(pattern,async route=>{await delayed;await route.abort('failed')})
  await page.goto(url);await expect(page.getByRole('status').filter({hasText:/正在/}).first()).toBeVisible()
  release();await expect(page.getByRole('alert').first()).toBeVisible();await page.unroute(pattern)
  await page.getByRole('button',{name:/^(重试|重试加载|重新加载|刷新列表|刷新统计)$/}).first().click()
  await settled(page);await expect(page.getByRole('alert')).toHaveCount(0)
}

test('Web administrator authors five types; student submits, grades, reviews, messages and profiles',async({browser})=>{
  test.setTimeout(240000)
  const studentContext=await browser.newContext();const adminContext=await browser.newContext()
  const student=await studentContext.newPage();const admin=await adminContext.newPage()
  const evidence:Record<string,unknown>={node:process.version,project:process.env.EXAM_E2E_PROJECT,browser:browser.version(),flow:'Real admin UI -> student Web -> PostgreSQL'}
  const forms=[],failedWrites=[]
  try {
    await student.goto(studentOrigin+'/student/register')
    await student.getByLabel('用户名').fill(username);await student.getByLabel('密码').fill(password)
    await student.getByRole('combobox',{name:'年级',exact:true}).selectOption({label:'演示年级'})
    await student.getByRole('button',{name:'注册',exact:true}).click()
    await expect(student.getByRole('heading',{name:'学生登录'})).toBeVisible()
    await keyboardLogin(student)
    await keyboardLogin(admin,true)
    const owner=(await(await student.request.get(studentOrigin+'/api/v1/me')).json()).data.id
    await admin.goto(adminOrigin+'/admin/subjects')
    await admin.getByLabel('学科名称').fill('Web闭环学科-'+suffix)
    await admin.getByLabel('所属年级').selectOption({label:'演示年级'})
    forms.push(await checkFormFields(admin));failedWrites.push(await failWriteAndKeepInput(admin,admin.getByRole('button',{name:'保存学科'}),'/admin/subjects'))
    const subjectResponse=admin.waitForResponse(response=>response.url().endsWith('/admin/subjects')&&response.request().method()==='POST')
    await admin.getByRole('button',{name:'保存学科'}).click()
    const subject=(await(await subjectResponse).json()).data
    const ids:string[]=[]
    for(const [index,type] of ['SINGLE','MULTIPLE','TRUE_FALSE','GAP','SHORT'].entries()) {
      await admin.goto(adminOrigin+'/admin/questions/new')
      await admin.getByRole('combobox',{name:'学科',exact:true}).selectOption(subject.id)
      await admin.getByRole('combobox',{name:'题型',exact:true}).selectOption(type)
      let stem=`<p>Web ${type} 原始题干 ${suffix}</p>`
      if(type==='SINGLE')stem+=formulas.map(tex=>'<span data-tex="'+tex.replaceAll('&','&amp;')+'"></span>').join('')+'<script>window.examUnsafe=true</script>'
      if(type==='GAP')stem+='<span data-gap="1">____</span>'
      if(type==='SHORT')stem+='<table><tr>'+Array.from({length:20},(_,i)=>'<td>长表格列 '+i+'</td>').join('')+'</tr></table>'
      await admin.getByLabel('题干',{exact:true}).fill(stem)
      if(type==='SINGLE'||type==='MULTIPLE') {
        await admin.getByLabel('选项 1',{exact:true}).fill('甲')
        await admin.getByLabel('选项 2',{exact:true}).fill('乙')
        await admin.getByLabel('正确选项 1',{exact:true}).check()
        if(type==='MULTIPLE')await admin.getByLabel('正确选项 2',{exact:true}).check()
      } else if(type==='GAP')await admin.getByLabel('每行一个参考答案').fill('参考填空')
      else if(type==='SHORT')await admin.getByLabel('参考答案',{exact:true}).fill('参考简答')
      if(type==='SINGLE') {
        const uploaded=admin.waitForResponse(response=>response.url().endsWith('/attachments')&&response.request().method()==='POST')
        await admin.getByLabel('上传题干图片').setInputFiles({name:'private.png',mimeType:'image/png',buffer:image})
        expect((await uploaded).ok()).toBeTruthy()
      }
      await admin.getByLabel('解析',{exact:true}).fill('<p>解析 '+index+'</p>')
      forms.push(await checkFormFields(admin));failedWrites.push(await failWriteAndKeepInput(admin,admin.getByRole('button',{name:'保存题目',exact:true}),'/admin/questions'))
      const created=admin.waitForResponse(response=>response.url().endsWith('/admin/questions')&&response.request().method()==='POST')
      await admin.getByRole('button',{name:'保存题目',exact:true}).click()
      const question=(await(await created).json()).data;ids.push(question.id)
      await expect(admin).toHaveURL(new RegExp(`/questions/${question.id}/edit$`))
      if(type==='SINGLE'){
        await expect(admin.locator('.katex')).toHaveCount(3)
        await expect(admin.locator('.katex-error')).toHaveCount(0)
        expect(await admin.locator('.katex annotation').allTextContents()).toEqual(formulas)
        await expect(admin.locator('.katex mfrac')).toHaveCount(1)
        await expect(admin.locator('.katex msubsup')).toHaveCount(1)
        await expect(admin.locator('.katex mtable')).toHaveCount(1)
        evidence.adminFormulas=await admin.locator('.katex annotation').allTextContents()
      }
    }
    async function paper(name:string) {
      await admin.goto(adminOrigin+'/admin/papers/new')
      await admin.getByLabel('试卷名称').fill(name)
      await admin.getByRole('combobox',{name:'学科',exact:true}).selectOption(subject.id)
      await admin.getByLabel('卷型').selectOption('TASK')
      await admin.getByLabel('时长（分钟）').fill('20')
      await admin.getByRole('button',{name:'添加分组'}).click()
      await admin.getByLabel('分组名称').fill('混合题组')
      for(const id of ids){await admin.getByLabel('从题库选择题目').selectOption(id);await admin.getByRole('button',{name:'加入分组'}).click()}
      const saved=admin.waitForResponse(response=>response.url().endsWith('/admin/papers')&&response.request().method()==='POST')
      forms.push(await checkFormFields(admin));failedWrites.push(await failWriteAndKeepInput(admin,admin.getByRole('button',{name:'保存试卷草稿'}),'/admin/papers'))
      await admin.getByRole('button',{name:'保存试卷草稿'}).click()
      const value=(await(await saved).json()).data
      await expect(admin).toHaveURL(new RegExp(`/papers/${value.paper.id}/edit$`))
      await admin.getByRole('button',{name:'发布已保存草稿'}).click()
      await expect(admin.getByText('已发布保存的草稿。')).toBeVisible()
      return value.paper.id as string
    }
    const firstName='Web闭环卷甲-'+suffix;const secondName='Web闭环卷乙-'+suffix
    const first=await paper(firstName);const second=await paper(secondName)
    await admin.goto(adminOrigin+'/admin/tasks')
    await admin.getByLabel('任务名称').fill('Web闭环任务-'+suffix)
    await admin.getByLabel('目标年级').selectOption({label:'演示年级'})
    for(const id of [first,second]){await admin.getByLabel('添加已发布任务卷').selectOption(id);await admin.getByRole('button',{name:'添加试卷',exact:true}).click()}
    const taskResponse=admin.waitForResponse(response=>response.url().endsWith('/admin/tasks')&&response.request().method()==='POST')
    forms.push(await checkFormFields(admin));failedWrites.push(await failWriteAndKeepInput(admin,admin.getByRole('button',{name:'保存任务草稿'}),'/admin/tasks'))
    await admin.getByRole('button',{name:'保存任务草稿'}).click()
    const task=(await(await taskResponse).json()).data
    await admin.getByRole('button',{name:'发布已保存任务'}).click()
    await expect(admin.getByText('已发布保存的任务草稿。')).toBeVisible()
    await student.goto(studentOrigin+'/student/tasks/'+task.id)
    await expect(student.getByText('已完成 0 / 2 卷')).toBeVisible()
    await student.getByRole('listitem').filter({hasText:firstName}).getByRole('button',{name:'进入答卷'}).click()
    await expect(student.getByRole('status')).toHaveText('已保存')
    const attempt=student.url().split('/').at(-1)!
    await expect(student.locator('.katex').first()).toBeVisible()
    expect(await student.locator('.katex annotation').allTextContents()).toEqual(formulas)
    await expect(student.locator('.katex msubsup')).toHaveCount(1);await expect(student.locator('.katex mtable')).toHaveCount(1)
    evidence.studentFormulas=await student.locator('.katex annotation').allTextContents()
    await expect.poll(()=>student.locator('main img').first().evaluate((node:HTMLImageElement)=>node.naturalWidth)).toBeGreaterThan(0)
    expect(await student.evaluate(()=>(window as any).examUnsafe)).toBeUndefined()
    // Native tab visibility requires a default context without focus emulation.
    const debugPort=await freePort()
    const nativeBrowser=await chromium.launch({headless:false,args:['--force-device-scale-factor=1','--remote-debugging-address=127.0.0.1','--remote-debugging-port='+debugPort]})
    try {
      const connection=await chromium.connectOverCDP('http://127.0.0.1:'+debugPort,{noDefaults:true})
      try {
        const clockContext=connection.contexts()[0]
        await clockContext.addCookies(await studentContext.cookies())
        const clockPage=await clockContext.newPage()
        await clockPage.setViewportSize({width:1280,height:900})
        // Install before application performance.now() anchors exist.
        await clockPage.clock.install({time:new Date()})
        await clockPage.goto(student.url());await expect(clockPage.getByRole('status')).toHaveText('已保存')
        const events:{event:string;trusted:boolean;visibility:string}[]=[]
        await clockPage.exposeFunction('recordLifecycle',(event:{event:string;trusted:boolean;visibility:string})=>events.push(event))
        await clockPage.evaluate(()=>{
          document.addEventListener('visibilitychange',event=>void (window as any).recordLifecycle({event:event.type,trusted:event.isTrusted,visibility:document.visibilityState}))
        })
        await clockPage.clock.setFixedTime(new Date('2040-01-01T00:00:00Z'))
        const background=await clockContext.newPage();await background.goto(studentOrigin+'/student/')
        await background.bringToFront();await expect.poll(()=>clockPage.evaluate(()=>document.visibilityState)).toBe('hidden')
        await new Promise(resolve=>setTimeout(resolve,1200))
        const resumedClock=clockPage.waitForResponse(response=>response.url().endsWith('/me/attempts/'+attempt)&&response.request().method()==='GET')
        await clockPage.bringToFront();await resumedClock;await background.close()
        await expect.poll(()=>clockPage.getByTestId('remaining-time').innerText()).toMatch(/^19:/)
        expect(events).toEqual(expect.arrayContaining([
          {event:'visibilitychange',trusted:true,visibility:'hidden'},
          {event:'visibilitychange',trusted:true,visibility:'visible'},
        ]))
        evidence.browserLifecycle={headed:true,focusEmulation:false,backgroundMillis:1200,events,serverResynchronized:true,remaining:await clockPage.getByTestId('remaining-time').innerText()}
      }finally{await connection.close()}
    }finally{await nativeBrowser.close()}
    await student.locator('section').nth(0).getByRole('radio').last().focus();await student.keyboard.press('Space')
    await student.locator('section').nth(1).getByRole('checkbox').first().check();await student.locator('section').nth(1).getByRole('checkbox').last().check()
    await student.locator('section').nth(2).getByRole('radio').first().check()
    await student.locator('section').nth(3).getByRole('textbox').fill('自行填写')
    await student.getByRole('button',{name:'标记复查第 4 题'}).click()
    await expect(student.getByRole('navigation',{name:'题号导航'}).getByRole('button',{name:'4 · 已答 · 已标记',exact:true})).toBeVisible()
    await student.getByRole('navigation',{name:'题号导航'}).getByRole('button',{name:'5 · 未答',exact:true}).click()
    await student.getByRole('button',{name:'准备交卷'}).click()
    await expect(student.getByRole('dialog')).toContainText('还有 1 题未作答')
    const submissionRequests=await loseOnce(student,'/me/attempts/'+attempt+'/submit','/me/attempts/'+attempt)
    await student.getByRole('button',{name:'确认交卷并冻结答案'}).click()
    await expect(student.getByRole('button',{name:'重试原交卷请求',exact:true})).toBeVisible()
    const submissionResponse=student.waitForResponse(response=>response.url().endsWith('/submit')&&response.request().method()==='POST')
    await student.getByRole('button',{name:'重试原交卷请求',exact:true}).click()
    const submission=await(await submissionResponse).json();expect(submissionRequests).toHaveLength(2);expect(submissionRequests[1]).toEqual(submissionRequests[0])
    await expect(student.getByRole('heading',{name:'答卷结果与回看'})).toBeVisible()
    await expect(student.getByText('客观成绩：2.00',{exact:false})).toBeVisible()
    await student.getByLabel(/第 4 题评分/).fill('0.50')
    await expect(student.getByLabel(/第 5 题评分/)).toBeDisabled()
    await student.getByLabel('我已核对全部主观题评分').check()
    const gradeRequests=await loseOnce(student,'/me/attempts/'+attempt+'/self-grade','/me/attempts/'+attempt+'/review')
    await student.getByRole('button',{name:'确认自行批改'}).click()
    await expect(student.getByRole('button',{name:'重试原批改确认'})).toBeVisible();await expect(student.getByLabel(/第 4 题评分/)).toBeDisabled()
    await student.getByRole('button',{name:'重试原批改确认'}).click();await expect.poll(()=>gradeRequests.length).toBe(2);expect(gradeRequests[1]).toEqual(gradeRequests[0])
    await expect(student.getByText('最终成绩：2.50',{exact:false})).toBeVisible()
    await shot(student,'workflow-graded-review')
    await student.goto(studentOrigin+'/student/tasks/'+task.id)
    await expect(student.getByText('已完成 1 / 2 卷')).toBeVisible()
    await student.getByRole('listitem').filter({hasText:secondName}).getByRole('button',{name:'进入答卷'}).click()
    await expect(student.getByRole('status')).toHaveText('已保存')
    await student.getByRole('button',{name:'准备交卷'}).click();await expect(student.getByRole('dialog')).toContainText('还有 5 题未作答')
    await student.getByRole('button',{name:'确认交卷并冻结答案'}).click()
    await student.getByLabel('我已核对全部主观题评分').check();await student.getByRole('button',{name:'确认自行批改'}).click()
    await expect(student.getByText('最终成绩：0.00',{exact:false})).toBeVisible()
    await student.goto(studentOrigin+'/student/tasks/'+task.id);await expect(student.getByText('已完成 2 / 2 卷 · 任务已完成')).toBeVisible()
    await shot(student,'workflow-task-completed')
    await student.goto(studentOrigin+'/student/records');await expect(student.getByRole('row').filter({hasText:firstName})).toContainText('2.50')
    await student.goto(studentOrigin+'/student/mistakes');await expect(student.locator('main section')).toHaveCount(5)
    await admin.goto(adminOrigin+'/admin/attempts/'+attempt);await expect(admin.getByText('最终成绩：2.50',{exact:false})).toBeVisible()
    await admin.goto(adminOrigin+'/admin/questions/'+ids[0]+'/edit');await admin.getByLabel('题干',{exact:true}).fill('<p>修改后的新题干</p>');await admin.getByRole('button',{name:'保存题目'}).click();await expect(admin.getByText('新版本已保存。')).toBeVisible()
    await admin.getByRole('button',{name:'归档题目'}).click();await admin.getByRole('button',{name:'确认归档'}).click()
    await admin.goto(adminOrigin+'/admin/papers/'+first+'/edit');await admin.getByRole('button',{name:'归档试卷'}).click();await admin.getByRole('button',{name:'确认操作'}).click()
    await student.goto(studentOrigin+'/student/attempts/'+attempt+'/review');await expect(student.getByText('Web SINGLE 原始题干 '+suffix)).toBeVisible();await expect(student.getByText('修改后的新题干')).toHaveCount(0)
    await expect.poll(()=>student.locator('main img').first().evaluate((node:HTMLImageElement)=>node.naturalWidth)).toBeGreaterThan(0)
    await admin.goto(adminOrigin+'/admin/messages/new');await admin.getByLabel('标题',{exact:true}).fill('闭环消息-'+suffix);await admin.getByLabel('正文',{exact:true}).fill('请查看已完成的答卷。')
    const messageRequests=await loseOnce(admin,'/admin/messages')
    await admin.getByLabel(username,{exact:false}).check();await admin.getByRole('button',{name:'确认发送消息'}).click()
    await expect(admin.getByRole('button',{name:'重试原发送请求'})).toBeVisible();await expect(admin.getByLabel('标题',{exact:true})).toBeDisabled();await admin.getByRole('button',{name:'重试原发送请求'}).click();await expect(admin).toHaveURL(/\/messages\/[a-f0-9-]+$/)
    expect(messageRequests).toHaveLength(2);expect(messageRequests[1]).toEqual(messageRequests[0])
    const messageId=admin.url().split('/').at(-1)!
    await student.goto(studentOrigin+'/student/messages');await student.getByRole('link',{name:'闭环消息-'+suffix}).click();await expect(student.getByText('请查看已完成的答卷。')).toBeVisible()
    await student.reload();await admin.reload();await expect(admin.getByText('收件 1 人 · 已读 1 人')).toBeVisible()
    await student.goto(studentOrigin+'/student/profile');await student.getByLabel('姓名',{exact:true}).fill('Web验证学生');await student.getByLabel('手机号').fill('+852 12345678');await student.getByLabel('生日').fill('2000-01-01');await student.getByRole('button',{name:'保存资料'}).click();await expect(student.getByText('资料已保存。')).toBeVisible()
    await student.getByLabel(/更新头像/).setInputFiles({name:'avatar.png',mimeType:'image/png',buffer:image});await expect(student.getByRole('img',{name:'我的头像'})).toBeVisible()
    await student.reload();await expect(student.getByLabel('姓名',{exact:true})).toHaveValue('Web验证学生')
    await admin.goto(adminOrigin+'/admin/activities');await admin.getByLabel('请求标识').fill(submission.requestId);await admin.getByRole('button',{name:'筛选日志'}).click();await expect(admin.getByRole('row').filter({hasText:'Attempt.submit'})).toContainText(attempt)
    await shot(admin,'workflow-audit-request')
    const adminRoutes=['/dashboard','/users/students','/users/admins','/users/student','/users/admin','/users/new/students','/users/new/admins','/users/'+owner+'/edit','/subjects','/subjects/'+subject.id+'/edit','/questions','/questions/new','/questions/'+ids[1]+'/edit','/papers','/papers/new','/papers/'+second+'/edit','/tasks','/tasks/'+task.id+'/edit','/attempts','/attempts/'+attempt,'/messages','/messages/'+messageId,'/messages/new','/profile','/activities','/logs','/','/login']
    const studentRoutes=['/home','/papers','/tasks','/tasks/'+task.id,'/records','/attempts/'+attempt+'/review','/attempts/'+attempt+'/grade','/exam/'+attempt,'/mistakes','/mistakes/'+ids[0],'/profile','/messages','/messages/'+messageId,'/activities','/','/login','/register']
    const contrasts=[]
    for(const width of [375,768,1440]) {
      for(const [page,origin,prefix,routes] of [[admin,adminOrigin,'/admin',adminRoutes],[student,studentOrigin,'/student',studentRoutes]] as const) {
        await page.setViewportSize({width,height:900})
        for(const route of routes) {await page.goto(origin+prefix+route);await settled(page);if(width===1440)forms.push(await checkFormFields(page));expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),`${width}: ${prefix}${route}`).toBeTruthy();const value=await contrast(page);expect(value.failures,`${width}: ${prefix}${route}`).toEqual([]);contrasts.push({route:prefix+route,width,...value})}
      }
      if(width===375){await student.goto(studentOrigin+'/student/attempts/'+attempt+'/review');await shot(student,'workflow-mobile-review')}
    }
    const recoveries:{role:string;route:string}[]=[]
    for(const [page,origin,prefix,cases] of [
      [admin,adminOrigin,'/admin',[
        ['/dashboard','/admin/statistics'],['/users/students','/admin/users?*'],['/users/admins','/admin/users?*'],['/users/new/students','/catalog/grades'],['/users/new/admins','/catalog/grades'],['/users/'+owner+'/edit','/admin/users/'+owner],['/subjects','/admin/subjects?*'],['/questions','/admin/questions?*'],['/questions/new','/admin/subjects?*'],['/questions/'+ids[1]+'/edit','/admin/questions/'+ids[1]],['/papers','/admin/papers?*'],['/papers/new','/admin/subjects?*'],['/papers/'+second+'/edit','/admin/papers/'+second],['/tasks','/admin/tasks?*'],['/attempts','/admin/attempts?*'],['/attempts/'+attempt,'/admin/attempts/'+attempt],['/messages','/admin/messages?*'],['/messages/'+messageId,'/admin/messages/'+messageId],['/messages/new','/admin/users?*'],['/profile','/me/profile'],['/activities','/admin/audit-events?*']]],
      [student,studentOrigin,'/student',[
        ['/papers','/me/papers?*'],['/tasks','/me/tasks?*'],['/tasks/'+task.id,'/me/tasks/'+task.id],['/records','/me/attempts?*'],['/attempts/'+attempt+'/review','/me/attempts/'+attempt+'/review'],['/mistakes','/me/mistakes?*'],['/mistakes/'+ids[0],'/me/mistakes/'+ids[0]],['/profile','/me/profile'],['/messages','/me/messages?*'],['/messages/'+messageId,'/me/messages/'+messageId],['/activities','/me/activities?*']]]
    ] as const)for(const [route,endpoint] of cases){await failureRecovery(page,origin+prefix+route,endpoint);recoveries.push({role:prefix,route})}
    for(const [route,endpoint] of [['/subjects/'+subject.id+'/edit','/admin/subjects/'+subject.id],['/tasks/'+task.id+'/edit','/admin/tasks/'+task.id],['/logs','/admin/audit-events?*']]){await failureRecovery(admin,adminOrigin+'/admin'+route,endpoint);recoveries.push({role:'/admin',route})}
    evidence.failureRecoveries=recoveries
    evidence.formFields=forms;evidence.failedWrites=failedWrites
    evidence.attemptId=attempt;evidence.taskId=task.id;evidence.requestId=submission.requestId;evidence.messageId=messageId;evidence.finalScore='2.50';evidence.routesChecked={admin:adminRoutes.length,student:studentRoutes.length,widths:[375,768,1440]};evidence.contrast=contrasts;evidence.lostResponseRetries=['submit','self-grade','message'];evidence.result='passed';evidence.clientClockChanged=true;evidence.backgroundRecovery=true
  } finally {
    writeFileSync(artifact('workflow-summary.json'),JSON.stringify(evidence,null,2)+'\n')
    await Promise.allSettled([studentContext.close(),adminContext.close()])
  }
})

test('Web empty and recoverable error states and role routes are real',async({page})=>{
  await page.goto('/student/register');const name='Empty-'+randomBytes(4).toString('hex')
  await page.getByLabel('用户名').fill(name);await page.getByLabel('密码').fill(password);await page.getByRole('combobox',{name:'年级',exact:true}).selectOption({label:'演示年级'});await page.getByRole('button',{name:'注册',exact:true}).click()
  await expect(page.getByRole('heading',{name:'学生登录'})).toBeVisible();await page.getByLabel('用户名').fill(name);await page.getByLabel('密码').fill(password);await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByRole('heading',{name:'学生中心'})).toBeVisible()
  await page.route('**/api/v1/me/attempts?*',route=>route.abort('failed'));await page.goto('/student/records');await expect(page.getByRole('alert')).toBeVisible();await page.unroute('**/api/v1/me/attempts?*');await page.getByRole('button',{name:'重试',exact:true}).click();await expect(page.getByText('暂无符合条件的记录。',{exact:false})).toBeVisible()
  await page.goto(adminOrigin+'/admin/dashboard');await expect(page.getByRole('heading',{name:'没有访问权限'})).toBeVisible()
  await page.goto(studentOrigin+'/student/unknown-route');await expect(page.getByRole('heading',{name:'页面不存在'})).toBeVisible()
})
