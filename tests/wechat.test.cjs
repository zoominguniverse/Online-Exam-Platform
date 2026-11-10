const assert = require('node:assert/strict')
const test = require('node:test')

test('小程序登录、本人资料和退出隔离', async () => {
  const requests = []
  const navigations = []
  const student = { id: 'fixture-student', username: 'Student', role: 'STUDENT' }
  let unbound = false
  let taskVisible = true
  let page
  global.Page = (definition) => {
    page = { ...definition, data: { ...definition.data }, setData(patch) { Object.assign(this.data, patch) } }
  }
  global.wx = {
    login({ success }) { success({ code: 'fixture-code' }) },
    reLaunch({ url }) { navigations.push(url) },
    request(options) {
      const path = new URL(options.url).pathname
      requests.push({ path, method: options.method, token: options.header.Authorization })
      if (path.endsWith('/login')) {
        return options.success(unbound
          ? { statusCode: 404, data: { code: 'NOT_FOUND', message: '此微信账号尚未绑定。' } }
          : { statusCode: 200, data: { code: 0, data: { token: 'fixture-token', user: student } } })
      }
      if (path.endsWith('/me')) {
        return options.success({ statusCode: 200, data: { code: 0, data: student } })
      }
      if (path.endsWith('/me/papers')) {
        return options.success({ statusCode: 200, data: { code: 0, data: {
          page: 1, total: 1, items: [{
            id: 'timed', type: 'TIMED', name: '时段卷', status: 'UPCOMING',
            opensAt: '2026-11-10T00:00:00Z', closesAt: '2026-11-10T01:00:00Z',
          }],
        } } })
      }
      if (path.endsWith('/me/tasks/fixture-task')) {
        return options.success(taskVisible ? { statusCode: 200, data: { code: 0, data: {
          id: 'fixture-task', name: '两卷任务', state: 'PUBLISHED',
          papers: [{ paperId: 'a', status: 'NOT_STARTED' }, { paperId: 'b', status: 'NOT_STARTED' }],
        } } } : { statusCode: 404, data: { code: 'NOT_FOUND', message: '任务不存在。' } })
      }
      if (path.endsWith('/binding') && options.method === 'DELETE') {
        unbound = true
        return options.success({ statusCode: 200, data: { code: 0, data: null } })
      }
      assert.fail('Unexpected request: ' + path)
    },
  }
  try {
    require('../wechat/student/pages/auth/index.js')
    const auth = page
    await auth.autoLogin()
    assert.equal(navigations.at(-1), '/pages/profile/index')

    require('../wechat/student/pages/profile/index.js')
    const profile = page
    await profile.load()
    assert.equal(profile.data.account.id, student.id)
    assert.equal(requests.at(-1).token, 'Bearer fixture-token')
    require('../wechat/student/pages/papers/index.js')
    const papers = page
    await papers.load(1)
    assert.equal(papers.data.items[0].statusLabel, '尚未开放')
    assert.equal(papers.data.items[0].window, '2026-11-10 08:00 至 2026-11-10 09:00')
    require('../wechat/student/pages/tasks/index.js')
    const tasks = page
    tasks.onLoad({ id: 'fixture-task' })
    await tasks.load(1)
    assert.equal(tasks.data.tasks[0].papers.length, 2)
    taskVisible = false
    await tasks.load(1)
    assert.deepEqual(tasks.data.tasks, [])
    assert.equal(tasks.data.error, '任务不存在。')
    await profile.unlink()
    assert.equal(requests.at(-1).token, 'Bearer fixture-token')
    assert.equal(profile.data.account, null)
    assert.equal(navigations.at(-1), '/pages/auth/index?manual=1')

    await auth.autoLogin()
    assert.equal(requests.at(-1).token, undefined)
    assert.equal(auth.data.unbound, true)
    assert.equal(auth.data.password, '')
  } finally {
    delete global.Page
    delete global.wx
  }
})
