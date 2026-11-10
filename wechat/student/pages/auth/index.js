const { request, freshCode, startSession, clearSession } = require('../../api')

Page({
  data: { mode: 'bind', unbound: false, busy: false, error: '', grades: [], gradeNames: [], gradeIndex: -1, password: '' },

  onLoad({ manual } = {}) {
    clearSession()
    if (manual === '1') this.setData({ unbound: true })
    else this.autoLogin()
  },

  async autoLogin() {
    if (this.data.busy) return
    this.setData({ busy: true, error: '' })
    try {
      this.enter(await request('POST', '/auth/wechat/login', { code: await freshCode() }))
    } catch (error) {
      if (error.status === 404) this.setData({ unbound: true })
      else this.setData({ error: error.message })
    } finally {
      this.setData({ busy: false })
    }
  },

  enter(session) {
    startSession(session)
    wx.reLaunch({ url: '/pages/profile/index' })
  },

  async changeMode({ currentTarget }) {
    if (this.data.busy) return
    const mode = currentTarget.dataset.mode
    this.setData({ mode, error: '', password: '', gradeIndex: -1 })
    if (mode !== 'register' || this.data.grades.length) return
    this.setData({ busy: true })
    try {
      const grades = await request('GET', '/catalog/grades')
      this.setData({ grades, gradeNames: grades.map((grade) => grade.name) })
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ busy: false })
    }
  },

  selectGrade({ detail }) {
    this.setData({ gradeIndex: Number(detail.value) })
  },

  async submit({ detail }) {
    if (this.data.busy) return
    const { username, password } = detail.value
    const registration = this.data.mode === 'register'
    const grade = this.data.grades[this.data.gradeIndex]
    if (!username || !password || (registration && !grade)) {
      this.setData({ error: '请填写用户名、密码及所需年级。' })
      return
    }
    this.setData({ busy: true, error: '' })
    try {
      const data = { username, password, code: await freshCode() }
      if (registration) data.gradeId = grade.id
      this.enter(await request('POST', registration ? '/auth/wechat/register' : '/auth/wechat/bind', data))
    } catch (error) {
      this.setData({ error: error.message })
    } finally {
      this.setData({ busy: false, password: '' })
    }
  },

  updatePassword({ detail }) {
    this.setData({ password: detail.value })
  },
})
