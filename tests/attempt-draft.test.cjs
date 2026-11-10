const assert = require('node:assert/strict')
const test = require('node:test')

test('小程序保存中继续编辑、未知结果重试及退出清理草稿', async () => {
  const storage = new Map()
  const pending = []
  const remote = {
    id: 'attempt-1', version: 1, status: 'IN_PROGRESS', serverNow: '2026-11-10T00:00:00Z',
    deadlineAt: '2026-11-10T01:00:00Z', savedAnswers: [],
    questions: [{ questionId: 'question-1', type: 'SHORT', stemHtml: '题目', options: [] }],
  }
  let page
  global.Page = definition => {
    page = { ...definition, data: { ...definition.data }, setData(patch) { Object.assign(this.data, patch) } }
  }
  global.wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key),
    getStorageInfoSync: () => ({ keys: [...storage.keys()] }),
    request(options) {
      const path = new URL(options.url).pathname
      if (path.endsWith('/attempt-1') && options.method === 'GET') {
        options.success({ statusCode: 200, data: { code: 0, data: { ...remote } } }); return
      }
      if (path.endsWith('/attempt-1/answers') && options.method === 'PUT') {
        pending.push(options); return
      }
      assert.fail('Unexpected request ' + path)
    },
  }
  const api = require('../wechat/student/api')
  api.startSession({ token: 'token-one', user: { id: 'owner-one' } })
  try {
    require('../wechat/student/pages/attempt/index.js')
    page.setData({ id: 'attempt-1' }); page.revision = 0; page.baseVersion = 0; page.dead = false; page.busy = false
    await page.load()
    page.edit({ currentTarget: { dataset: { id: 'question-1', type: 'SHORT' } }, detail: { value: '第一次' } })
    const first = page.save()
    assert.equal(page.data.status, '保存中')
    page.edit({ currentTarget: { dataset: { id: 'question-1', type: 'SHORT' } }, detail: { value: '第二次' } })
    remote.version = 2
    remote.savedAnswers = [{ questionId: 'question-1', answer: { text: '第一次' } }]
    pending.shift().success({ statusCode: 200, data: { code: 0, data: { ...remote } } })
    await first
    assert.equal(page.data.status, '待同步')
    assert.equal(storage.get('exam-draft:owner-one:attempt-1').answers['question-1'].text, '第二次')
    const second = page.save()
    pending.shift().fail()
    await second
    assert.equal(page.data.status, '保存失败，待重试')
    remote.version = 3
    remote.savedAnswers = [{ questionId: 'question-1', answer: { text: '第二次' } }]
    const confirmed = page.save()
    page.edit({ currentTarget: { dataset: { id: 'question-1', type: 'SHORT' } }, detail: { value: '第三次' } })
    await confirmed
    assert.equal(page.data.status, '待同步')
    assert.equal(storage.get('exam-draft:owner-one:attempt-1').answers['question-1'].text, '第三次')
    const third = page.save()
    remote.version = 4
    remote.savedAnswers = [{ questionId: 'question-1', answer: { text: '第三次' } }]
    pending.shift().success({ statusCode: 200, data: { code: 0, data: { ...remote } } })
    await third
    assert.equal(page.data.status, '已保存')
    assert.equal(pending.length, 0)
    assert.equal(storage.has('exam-draft:owner-one:attempt-1'), false)
    page.edit({ currentTarget: { dataset: { id: 'question-1', type: 'SHORT' } }, detail: { value: '待同步' } })
    const conflict = page.save()
    remote.version = 5
    remote.savedAnswers = [{ questionId: 'question-1', answer: { text: '另一端' } }]
    pending.shift().success({ statusCode: 409, data: { code: 'VERSION_CONFLICT', message: '旧版本' } })
    await conflict
    assert.match(page.data.status, /^版本冲突/)
    assert.equal(storage.get('exam-draft:owner-one:attempt-1').answers['question-1'].text, '待同步')
    api.clearSession()
    assert.equal(storage.has('exam-draft:owner-one:attempt-1'), false)
    api.startSession({ token: 'token-two', user: { id: 'owner-two' } })
    assert.equal(page.active(), false)
  } finally {
    page.onUnload()
    api.clearSession()
    delete global.Page
    delete global.wx
  }
})
