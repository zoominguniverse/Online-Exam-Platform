const apiOrigin = require('../../api-origin')

Page({
  data: { version: '', error: '', loading: false },

  onLoad() {
    this.loadVersion()
  },

  openStudent() {
    wx.navigateTo({ url: '/pages/auth/index' })
  },

  loadVersion() {
    if (this.data.loading) return
    this.setData({ loading: true, error: '', version: '' })
    const failure = () => this.setData({ error: '暂时无法连接考试服务，请稍后重试。' })
    wx.request({
      url: apiOrigin + '/api/v1/version',
      timeout: 5000,
      success: ({ statusCode, data }) => {
        if (statusCode === 200 && data && data.code === 0 && data.data &&
            typeof data.data.apiVersion === 'string') {
          this.setData({ version: data.data.apiVersion })
        } else {
          failure()
        }
      },
      fail: failure,
      complete: () => this.setData({ loading: false }),
    })
  },
})
