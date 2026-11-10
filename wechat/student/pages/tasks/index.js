const { request, clearSession, hasSession } = require('../../api')

Page({
  data: { id: '', tasks: [], error: '', busy: false, page: 1, total: 0 },

  onLoad({ id } = {}) { this.setData({ id: id || '' }) },

  onShow() { this.load(this.data.page) },

  async load(page) {
    if (this.data.busy) return
    if (!hasSession()) {
      wx.reLaunch({ url: '/pages/auth/index' })
      return
    }
    this.setData({ busy: true, error: '', tasks: [] })
    try {
      if (this.data.id) {
        const task = await request('GET', '/me/tasks/' + encodeURIComponent(this.data.id))
        this.setData({ tasks: [task] })
      } else {
        const result = await request('GET', '/me/tasks', { page })
        this.setData({ tasks: result.items, page: result.page, total: result.total })
      }
    } catch (error) {
      if (error.status === 401) {
        clearSession()
        wx.reLaunch({ url: '/pages/auth/index' })
      } else {
        this.setData({ error: error.message })
      }
    } finally {
      this.setData({ busy: false })
    }
  },

  previous() { this.load(this.data.page - 1) },
  next() { this.load(this.data.page + 1) },
  retry() { this.load(this.data.page) },
})
