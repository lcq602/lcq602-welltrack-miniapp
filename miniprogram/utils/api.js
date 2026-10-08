const API_DEFAULT = 'http://127.0.0.1:8000'

function request(path, method = 'GET', data) {
  // 发送带当前会话令牌的 HTTP 请求。
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync('welltrack_token')
    wx.request({
      url: (wx.getStorageSync('welltrack_api') || API_DEFAULT) + path,
      method,
      data,
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success: res => res.statusCode >= 200 && res.statusCode < 300 ? resolve(res) : reject(new Error(`HTTP ${res.statusCode}: ${res.data?.detail || '请求失败'}`)),
      fail: reject
    })
  })
}

// 按开发配置获取本地或微信服务端校验的会话。
async function createSession() {
  if (wx.getStorageSync('welltrack_auth_mode') === 'wechat') {
    const login = await new Promise((resolve, reject) => wx.login({ success: resolve, fail: reject }))
    const result = await request('/api/v1/auth/wechat', 'POST', { code: login.code })
    wx.setStorageSync('welltrack_token', result.data.token)
    return
  }
  const login = await request('/api/v1/auth/local', 'POST')
  wx.setStorageSync('welltrack_token', login.data.token)
}

// 获取本地开发会话后调用 API，不在客户端生成或指定用户 ID。
async function api(path, method = 'GET', data) {
  if (!wx.getStorageSync('welltrack_token')) {
    await createSession()
  }
  try { return await request(path, method, data) }
  catch (error) {
    if (!String(error.message).includes('401')) throw error
    wx.removeStorageSync('welltrack_token')
    await createSession()
    return request(path, method, data)
  }
}

module.exports = { api }
