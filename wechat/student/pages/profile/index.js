const { request, clearSession, hasSession } = require('../../api')

Page({
  data: { account: null, error: '', busy: false, confirming: false },

  onShow() { this.load() },

  async load() {
    if (this.data.busy) return
    if (!hasSession()) {
      this.leave(false)
      return
    }
    this.setData({ account: null, error: '', busy: true })
    try {
      this.setData({ account: await request('GET', '/me') })
    } catch (error) {
      if (error.status === 401) this.leave(false)
      else this.setData({ error: error.message })
    } finally {
      this.setData({ busy: false })
    }
  },

  leave(manual) {
    clearSession()
    this.setData({ account: null, confirming: false })
    wx.reLaunch({ url: '/pages/auth/index' + (manual ? '?manual=1' : '') })
  },

  confirm() { this.setData({ confirming: true }) },
  cancel() { this.setData({ confirming: false }) },

  async unlink() {
    if (this.data.busy) return
    this.setData({ busy: true, error: '' })
    try {
      await request('DELETE', '/auth/wechat/binding')
      this.leave(true)
    } catch (error) {
      if (error.status === 401) this.leave(true)
      else this.setData({ error: error.message })
    } finally {
      this.setData({ busy: false })
    }
  },
})
