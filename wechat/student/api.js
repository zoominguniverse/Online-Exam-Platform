const origin = require('./api-origin')
let token = ''

function request(method, path, data) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: origin + '/api/v1' + path,
      method,
      data,
      timeout: 5000,
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success: ({ statusCode, data: body }) => {
        if (statusCode >= 200 && statusCode < 300 && body && body.code === 0) {
          resolve(body.data)
          return
        }
        reject({
          status: statusCode,
          message: (body && body.message) || '考试服务暂不可用，请稍后重试。',
        })
      },
      fail: () => reject({ status: 0, message: '暂时无法连接考试服务，请稍后重试。' }),
    })
  })
}

function freshCode() {
  return new Promise((resolve, reject) => {
    const failure = () => reject({ status: 0, message: '暂时无法获取微信登录码，请重试。' })
    wx.login({ timeout: 5000, success: ({ code }) => code ? resolve(code) : failure(), fail: failure })
  })
}

function startSession(session) { token = session.token }
function clearSession() { token = '' }
function hasSession() { return Boolean(token) }

module.exports = { request, freshCode, startSession, clearSession, hasSession }
