const { request, hasSession, clearSession } = require('../../api')
const statusNames = { OPEN: '已开放', UPCOMING: '尚未开放', CLOSED: '已结束' }

function hongKongTime(value) {
  return new Date(new Date(value).getTime() + 8 * 60 * 60 * 1000).toISOString().slice(0, 16).replace('T', ' ')
}

Page({
  data: { busy: false, error: '', items: [], page: 1, total: 0 },

  onShow() { this.load(this.data.page) },

  async load(page) {
    if (this.data.busy) return
    if (!hasSession()) {
      wx.reLaunch({ url: '/pages/auth/index' })
      return
    }
    this.setData({ busy: true, error: '', items: [] })
    try {
      const result = await request('GET', '/me/papers', { page })
      const items = result.items.map((paper) => ({
        ...paper,
        typeLabel: paper.type === 'FIXED' ? '固定卷' : '时段卷',
        statusLabel: statusNames[paper.status],
        window: paper.opensAt && paper.closesAt ? hongKongTime(paper.opensAt) + ' 至 ' + hongKongTime(paper.closesAt) : '',
      }))
      this.setData({ items, page: result.page, total: result.total })
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
