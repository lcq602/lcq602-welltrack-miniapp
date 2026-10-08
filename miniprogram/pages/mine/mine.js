const { api } = require('../../utils/api.js')
Page({
  data: { profile: null, apiUrl: wx.getStorageSync('welltrack_api') || 'http://127.0.0.1:8000' },
  // 页面显示时加载个人资料。
  onShow() { this.load() },
  // 加载当前资料摘要。
  async load() { try { const r = await api('/api/v1/profile'); const labels = { weight_loss: '减重', fat_loss: '减脂', maintenance: '维持', healthy_habits: '健康习惯' }; this.setData({ profile: r.data, goalLabel: r.data ? labels[r.data.goal] : '', reminderEnabled: Boolean(r.data && r.data.reminder_settings.enabled) }) } catch (e) { wx.showToast({ title: e.message || '加载失败', icon: 'none' }) } },
  // 更新开发机 API 地址输入。
  onUrl(e) { this.setData({ apiUrl: e.detail.value }) },
  // 保存本机的 API 地址设置。
  saveUrl() { wx.setStorageSync('welltrack_api', this.data.apiUrl.replace(/\/$/, '')); wx.showToast({ title: 'API 地址已保存' }) },
  // 以追加版本保存提醒偏好。
  async onReminder(e) {
    const p = this.data.profile
    if (!p) return wx.showToast({ title: '请先完成个人设置', icon: 'none' })
    const body = { height_cm: p.height_cm, current_weight: p.current_weight, target_weight: p.target_weight, start_date: p.start_date, target_date: p.target_date, goal: p.goal, weekly_plan: p.weekly_plan, reminder_settings: { enabled: e.detail.value } }
    try { await api('/api/v1/profile', 'POST', body); this.setData({ reminderEnabled: e.detail.value }); wx.showToast({ title: '偏好已保存' }) } catch (err) { wx.showToast({ title: err.message || '保存失败', icon: 'none' }) }
  },
  // 尝试切换到微信服务端登录模式。
  async useWechatLogin() {
    wx.setStorageSync('welltrack_auth_mode', 'wechat')
    wx.removeStorageSync('welltrack_token')
    try { await api('/api/v1/profile'); wx.showToast({ title: '微信登录已启用' }) }
    catch (e) { wx.setStorageSync('welltrack_auth_mode', 'local'); wx.removeStorageSync('welltrack_token'); wx.showToast({ title: e.message || '微信登录不可用', icon: 'none' }); this.load() }
  },
  // 将服务端文件保存到小程序本地。
  download(path, name) {
    const token = wx.getStorageSync('welltrack_token')
    wx.downloadFile({ url: this.data.apiUrl + path, header: { Authorization: `Bearer ${token}` }, success: res => {
      if (res.statusCode !== 200) return wx.showToast({ title: '导出失败', icon: 'none' })
      wx.saveFile({ tempFilePath: res.tempFilePath, success: saved => wx.showModal({ title: `${name}已保存`, content: saved.savedFilePath, showCancel: false }), fail: () => wx.showToast({ title: '文件保存失败', icon: 'none' }) })
    }, fail: () => wx.showToast({ title: '连接失败', icon: 'none' }) })
  },
  // 下载当前用户 CSV 文件。
  exportCsv() { this.download('/api/v1/export.csv', 'CSV 数据') },
  // 下载当前用户 JSON 备份文件。
  exportBackup() { this.download('/api/v1/backup', 'JSON 备份') }
})
