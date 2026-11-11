import { test, expect, type APIRequestContext, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { mkdirSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'

const password = randomBytes(20).toString('hex')
const suffix = randomBytes(4).toString('hex')
const studentName = 'BrowserStudent-' + suffix
const otherName = 'BrowserOther-' + suffix
const evidence: object[] = []
let paper: string
let owner: string
let questionIds: string[]
let api: APIRequestContext

async function write(request: APIRequestContext, method: string, path: string, data: object) {
  const csrf = await (await request.get('/api/v1/auth/csrf')).json()
  const response = await request.fetch('/api/v1' + path, { method, data,
    headers: { [csrf.data.headerName]: csrf.data.token } })
  expect(response.ok(), `${method} ${path}: ${await response.text()}`).toBeTruthy()
  return (await response.json()).data
}
async function login(page: Page, username = studentName) {
  await page.goto('/student/login')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '学生中心' })).toBeVisible()
}
async function start(page: Page) {
  await login(page)
  await page.getByRole('link', { name: '练习试卷' }).click()
  const started = page.waitForResponse(r => r.url().endsWith(`/papers/${paper}/attempts`) && r.request().method() === 'POST')
  await page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: '浏览器五题型卷-' + suffix }) })
    .getByRole('button', { name: '开始作答' }).click()
  const value = (await (await started).json()).data
  await expect(page.getByRole('status')).toHaveText('已保存')
  return value
}
const short = (page: Page) => page.locator('section').nth(4).locator('textarea')
async function readAttempt(id: string) {
  return (await (await api.get('/api/v1/me/attempts/' + id)).json()).data
}
async function record(page: Page, name: string, details: object) {
  const path = `${process.env.EXAM_E2E_ARTIFACTS}/${name}.png`
  await page.screenshot({ path, fullPage: true })
  const id = (details as { attemptId?: string }).attemptId
  evidence.push({ name, ...details, ...(id ? { database: database(id) } : {}) })
}

function docker(args: string[]) {
  const project = process.env.EXAM_E2E_PROJECT!
  if (!/^exam-browser-[a-f0-9]{10}$/.test(project)) throw new Error('Expected a runner-owned isolated project')
  return execFileSync('docker', args, { encoding: 'utf8', timeout: 30000 }).trim()
}
function database(id: string) {
  if (!/^[a-f0-9-]{36}$/.test(id)) throw new Error('Expected attempt UUID')
  return JSON.parse(docker(['exec', `${process.env.EXAM_E2E_PROJECT}-db-1`, 'psql', '-U', 'exam_migrator',
    '-d', 'exam_java_dev', '-At', '-c', `SELECT json_build_object('status', status, 'version', version,
      'deadlineAt', deadline_at, 'freezeReason', freeze_reason, 'finalScoreMinor', final_score_minor,
      'answers', (SELECT COALESCE(json_agg(json_build_object('questionId', question_id, 'answer', answer)
        ORDER BY question_id), '[]') FROM attempt_answers WHERE attempt_id = a.id))
      FROM attempts a WHERE id = '${id}'`]))
}

test.beforeAll(async ({ playwright }) => {
  api = await playwright.request.newContext({ baseURL: process.env.EXAM_E2E_ORIGIN })
  await write(api, 'POST', '/auth/login', { username: 'admin', password: process.env.EXAM_BOOTSTRAP_ADMIN_PASSWORD })
  const gradeId = (await (await api.get('/api/v1/catalog/grades')).json()).data[0].id
  for (const username of [studentName, otherName]) {
    const user = await write(api, 'POST', '/auth/register', { username, password, gradeId })
    if (username === studentName) owner = user.id
  }
  const subject = await write(api, 'POST', '/admin/subjects', { gradeId, name: '浏览器保存验证-' + suffix })
  questionIds = []
  const options = [{ id: 'A', text: '甲' }, { id: 'B', text: '乙' }]
  for (const variant of [
    { type: 'SINGLE', options, answer: { optionIds: ['A'] } },
    { type: 'MULTIPLE', options, answer: { optionIds: ['A', 'B'] } },
    { type: 'TRUE_FALSE', options: [], answer: { optionIds: ['true'] } },
    { type: 'GAP', options: [], stemHtml: '<span data-gap="1">___</span>', answer: { gaps: ['参考'] } },
    { type: 'SHORT', options: [], answer: { text: '参考' } },
  ]) {
    const question = await write(api, 'POST', '/admin/questions', {
      subjectId: subject.id, difficulty: 3, score: '1.00', stemHtml: '浏览器测试题', ...variant,
    })
    questionIds.push(question.id)
  }
  const created = await write(api, 'POST', '/admin/papers', { subjectId: subject.id, type: 'FIXED',
    draft: { name: '浏览器五题型卷-' + suffix, durationMinutes: 20, groups: [{ name: '题组', questionIds }] } })
  paper = created.paper.id
  await write(api, 'POST', `/admin/papers/${paper}/publish`, { expectedVersion: 1 })
  await write(api, 'POST', '/auth/logout', {})
  await write(api, 'POST', '/auth/login', { username: studentName, password })
})
test.afterAll(async ({ browser }) => {
  mkdirSync(process.env.EXAM_E2E_ARTIFACTS!, { recursive: true })
  writeFileSync(`${process.env.EXAM_E2E_ARTIFACTS}/summary.json`, JSON.stringify({
    timestamp: new Date().toISOString(), node: process.version, browser: browser.version(),
    project: process.env.EXAM_E2E_PROJECT, evidence,
  }, null, 2) + '\n')
  await api?.dispose()
})

test('ET02-N five types persist and restore after refresh', async ({ page }) => {
  const attempt = await start(page)
  await page.locator('section').nth(0).getByRole('radio').first().check()
  await page.locator('section').nth(1).getByRole('checkbox').first().check()
  await page.locator('section').nth(1).getByRole('checkbox').last().check()
  await page.locator('section').nth(2).getByRole('radio').first().check()
  await page.locator('section').nth(3).locator('textarea').fill('填空输入')
  await short(page).fill('服务器确认的简答')
  await expect(page.getByRole('status')).toHaveText('已保存')
  const saved = await readAttempt(attempt.id)
  expect(saved.version).toBeGreaterThan(1)
  expect(saved.savedAnswers).toHaveLength(5)
  await page.reload()
  await expect(short(page)).toHaveValue('服务器确认的简答')
  await expect(page.getByRole('status')).toHaveText('已保存')
  expect((await readAttempt(attempt.id)).deadlineAt).toBe(attempt.deadlineAt)
  for(const width of [375,768,1440]) {
    await page.setViewportSize({width,height:900})
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1)).toBeTruthy()
    await page.getByRole('button',{name:'准备交卷'}).focus()
    await expect(page.getByRole('button',{name:'准备交卷'})).toBeFocused()
  }
  await record(page, 'saved-refresh', { attemptId: attempt.id, version: saved.version, answers: saved.savedAnswers })
  await page.locator('section').nth(1).getByRole('checkbox').first().uncheck()
  await page.locator('section').nth(1).getByRole('checkbox').last().uncheck()
  await expect(page.getByRole('status')).toHaveText('已保存')
  expect((await readAttempt(attempt.id)).savedAnswers.some((a: any) => a.questionId === questionIds[1])).toBe(false)
})

test('ET02-F offline draft retries before deadline and logout clears owner cache', async ({ page, context }) => {
  const attempt = await start(page)
  await context.setOffline(true)
  await short(page).fill('离线保留的答案')
  await expect(page.getByRole('status')).toHaveText('保存失败，请重试')
  const key = `exam-draft:${owner}:${attempt.id}`
  expect(await page.evaluate(k => JSON.parse(localStorage.getItem(k)!), key)).toMatchObject({
    baseVersion: 1, answers: { [questionIds[4]]: { text: '离线保留的答案' } },
  })
  expect((await readAttempt(attempt.id)).savedAnswers).toHaveLength(0)
  await record(page, 'offline-pending', { attemptId: attempt.id, serverVersion: 1, localDraftRetained: true })
  await context.setOffline(false)
  await expect(page.getByRole('status')).toHaveText('已保存')
  expect((await readAttempt(attempt.id)).savedAnswers[0].answer.text).toBe('离线保留的答案')
  expect(await page.evaluate(k => localStorage.getItem(k), key)).toBeNull()
  await record(page, 'online-recovered', { attemptId: attempt.id, version: (await readAttempt(attempt.id)).version })
  await page.route(`**/attempts/${attempt.id}/answers`, route => route.abort('failed'))
  await short(page).fill('退出前未同步草稿')
  await expect(page.getByRole('status')).toHaveText('保存失败，请重试')
  await page.getByRole('link', { name: '返回学生中心' }).click()
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '学生登录' })).toBeVisible()
  expect(await page.evaluate(() => Object.keys(localStorage).filter(k => k.startsWith('exam-draft:')))).toEqual([])
  await login(page, otherName)
  await page.goto(`/student/attempts/${attempt.id}`)
  await expect(page.getByRole('alert')).toContainText('答卷不存在')
  await expect(short(page)).toHaveCount(0)
})

test('ET02-B independent browser sessions preserve losing draft and server winner', async ({ page, browser }) => {
  const attempt = await start(page)
  const second = await browser.newContext()
  try {
    const loser = await second.newPage()
    await login(loser)
    await loser.goto(`/student/attempts/${attempt.id}`)
    await expect(loser.getByRole('status')).toHaveText('已保存')
    await short(page).fill('先保存的服务器答案')
    await expect(page.getByRole('status')).toHaveText('已保存')
    await short(loser).fill('旧版本的本地答案')
    await expect(loser.getByRole('status')).toHaveText('版本冲突')
    expect((await readAttempt(attempt.id)).savedAnswers[0].answer.text).toBe('先保存的服务器答案')
    await expect(short(loser)).toHaveValue('旧版本的本地答案')
    await expect(short(loser)).toBeDisabled()
    expect(await loser.evaluate(k => JSON.parse(localStorage.getItem(k)!), `exam-draft:${owner}:${attempt.id}`))
      .toMatchObject({ baseVersion: 1, answers: { [questionIds[4]]: { text: '旧版本的本地答案' } } })
    await record(loser, 'version-conflict', { attemptId: attempt.id, serverVersion: 2, localDraftRetained: true })
    await expect(loser.getByText('服务器已保存：先保存的服务器答案')).toBeVisible()
    await loser.getByRole('button', { name: '加载服务器答案并丢弃本地草稿' }).click()
    await expect(short(loser)).toHaveValue('先保存的服务器答案')
    await expect(loser.getByRole('status')).toHaveText('已保存')
  } finally { await second.close() }
})

test('edit during save and response loss reconciles without losing later changes', async ({ page }) => {
  const attempt = await start(page)
  let release!: () => void
  let reached!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const intercepted = new Promise<void>(resolve => { reached = resolve })
  let once = true
  // Forward to the real API and commit to PostgreSQL; only the first response is lost.
  await page.route(`**/attempts/${attempt.id}/answers`, async route => {
    if (!once) { await route.continue(); return }
    once = false
    const response = await route.fetch()
    expect(response.status()).toBe(200)
    reached()
    await gate
    await route.abort('failed')
  })
  await short(page).fill('响应丢失的已保存答案')
  await intercepted
  await short(page).fill('保存期间继续编辑的答案')
  release()
  await expect(page.getByRole('status')).toHaveText('保存失败，请重试')
  expect((await readAttempt(attempt.id)).savedAnswers[0].answer.text).toBe('响应丢失的已保存答案')
  await page.getByRole('button', { name: '重试同步' }).click()
  await expect(page.getByRole('status')).toHaveText('已保存')
  const saved = await readAttempt(attempt.id)
  expect(saved.savedAnswers[0].answer.text).toBe('保存期间继续编辑的答案')
  expect(saved.version).toBe(3)
  await record(page, 'lost-response-reconciled', { attemptId: attempt.id, version: saved.version })
})

test('switching account in another tab hides old answers while a save response is delayed', async ({ page, context }) => {
  const attempt = await start(page)
  let release!: () => void
  let reached!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  const intercepted = new Promise<void>(resolve => { reached = resolve })
  await page.route(`**/attempts/${attempt.id}/answers`, async route => {
    const response = await route.fetch()
    reached()
    await gate
    await route.fulfill({ response }).catch(() => {})
  })
  await short(page).fill('切换账号前的答案')
  await intercepted
  const other = await context.newPage()
  await login(other, otherName)
  await expect(page.getByRole('heading', { name: '学生登录' })).toBeVisible()
  release()
  await expect(short(page)).toHaveCount(0)
  expect(await page.evaluate(() => Object.keys(localStorage).filter(k => k.startsWith('exam-draft:')))).toEqual([])
  await record(page, 'account-switch', { oldAttemptId: attempt.id, oldAnswersHidden: true, oldDraftsCleared: true })
})

test('ET03-F real API restart freezes only confirmed answers before offline retry', async ({ page, context }) => {
  test.setTimeout(60000)
  const attempt = await start(page)
  await short(page).fill('停机前确认的答案')
  await expect(page.getByRole('status')).toHaveText('已保存')
  await context.setOffline(true)
  await short(page).fill('离线且未确认的答案')
  await expect(page.getByRole('status')).toHaveText('保存失败，请重试')
  const project = process.env.EXAM_E2E_PROJECT!
  docker(['stop', `${project}-api-1`])
  // Shorten only this isolated test attempt while the real API process is stopped.
  docker(['exec', `${project}-db-1`, 'psql', '-U', 'exam_migrator', '-d', 'exam_java_dev', '-c',
    `UPDATE attempts SET deadline_at = started_at + interval '1 millisecond' WHERE id = '${attempt.id}'`])
  const deadline = database(attempt.id).deadlineAt
  docker(['start', `${project}-api-1`])
  // Inspect PostgreSQL directly: no request may cause read-time freezing here.
  await expect.poll(() => database(attempt.id).status, { timeout: 25000, intervals: [500] }).toBe('WAIT_SELF_GRADE')
  const frozen = database(attempt.id)
  expect(frozen.freezeReason).toBe('DEADLINE')
  expect(frozen.answers).toEqual([{ questionId: questionIds[4], answer: { text: '停机前确认的答案' } }])
  await context.setOffline(false)
  await expect(page.getByRole('heading',{name:'答卷结果与回看'})).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('未同步内容未计入')
  await expect(short(page)).toHaveCount(0)
  expect(database(attempt.id)).toEqual(frozen)
  const latest = await readAttempt(attempt.id)
  expect(new Date(latest.deadlineAt).getTime()).toBe(new Date(deadline).getTime())
  await expect(page.getByText('停机前确认的答案',{exact:false})).toBeVisible()
  await record(page, 'restart-deadline', { attemptId: attempt.id, apiProcessRestarted: true,
    frozenBeforeClientReconnect: true, offlineAnswerExcluded: true })
})
