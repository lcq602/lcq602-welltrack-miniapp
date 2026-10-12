const { API_DEFAULT } = require('./utils/api.js')

App({
  // 保持启动逻辑轻量，登录在首次 API 请求时进行。
  onLaunch() {},
  globalData: { apiBase: API_DEFAULT }
})
