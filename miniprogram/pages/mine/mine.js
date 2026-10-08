const { api } = require('../../utils/api.js')

Page({
  data: {
    profile: null,
    loading: true,
    miHealth: { enabled: false, ready: false, connected: false, latest: {}, import_count: 0 },
    username: '',
    password: '',
    verificationCode: '',
    verificationCodeReady: false,
    miHealthChallenge: null,
    connecting: false,
    syncing: false
  },
  // 页面显示时加载基础资料与小米连接状态。
  onShow() {
    const tab = this.getTabBar && this.getTabBar()
    if (tab) tab.setData({ selected: 4 })
    this.loadProfile()
    this.loadMiHealth()
  },
  // 读取本人的基础资料。
  async loadProfile() {
    this.setData({ loading: !this.data.profile })
    try {
      const response = await api('/api/v1/profile')
      const labels = { weight_loss: '减重', fat_loss: '减脂', maintenance: '维持', healthy_habits: '健康习惯' }
      this.setData({ profile: response.data, goalLabel: response.data ? labels[response.data.goal] : '', loading: false })
    } catch (error) {
      this.setData({ loading: false })
      wx.showToast({ title: error.message || '资料加载失败', icon: 'none' })
    }
  },
  // 读取当前小程序用户自己的连接状态。
  async loadMiHealth() {
    try {
      const response = await api('/api/v1/integrations/mihealth/status')
      const latest = { ...(response.data.latest || {}) }
      Object.keys(latest).forEach(key => {
        const recordedAt = latest[key].recorded_at
        latest[key] = {
          ...latest[key],
          value_label: latest[key].value === null || latest[key].value === undefined ? '暂无' : String(latest[key].value),
          date_label: recordedAt ? String(recordedAt).slice(0, 10) : '暂无记录'
        }
      })
      this.setData({ miHealth: { ...response.data, latest }, miHealthChallenge: response.data.challenge || null })
    } catch (error) {
      this.setData({ miHealth: { enabled: false, ready: false, connected: false, latest: {}, import_count: 0 } })
    }
  },
  onUsername(event) { this.setData({ username: event.detail.value }) },
  onPassword(event) { this.setData({ password: event.detail.value }) },
  onMiHealthCode(event) {
    const verificationCode = event.detail.value.replace(/\D/g, '').slice(0, 10)
    this.setData({ verificationCode, verificationCodeReady: verificationCode.length >= 4 })
  },
  // 明确提示账号密码会经本地服务发送给小米认证服务。
  async confirmMiHealthConsent() {
    return new Promise(resolve => wx.showModal({
      title: '连接小米运动健康',
      content: '仅用于读取你本人账号的健康摘要。登录信息会由本机后端提交给小米，不会保存在本小程序中。此连接依赖非官方接口。',
      confirmText: '继续连接',
      success: result => resolve(Boolean(result.confirm)),
      fail: () => resolve(false)
    }))
  },
  // 发送一次性登录凭证，成功或失败后立即清空密码输入。
  async connectMiHealth() {
    if (!this.data.miHealth.ready) return wx.showToast({ title: '请先在本机后端启用连接器并设置加密密钥', icon: 'none' })
    if (this.data.connecting) return
    const username = this.data.username.trim()
    const password = this.data.password
    if (!username || !password) return wx.showToast({ title: '请填写小米账号和密码', icon: 'none' })
    if (!await this.confirmMiHealthConsent()) return
    this.setData({ connecting: true, password: '' })
    try {
      const response = await api('/api/v1/integrations/mihealth/connect', 'POST', { username, password })
      if (response.data.requires_verification) {
        this.setData({ miHealthChallenge: response.data, verificationCode: '', verificationCodeReady: false })
        wx.showToast({ title: '验证码已发送', icon: 'success' })
      } else {
        wx.showToast({ title: '小米账号已连接', icon: 'success' })
        this.setData({ username: '', miHealthChallenge: null })
        await this.loadMiHealth()
      }
    } catch (error) {
      wx.showToast({ title: error.message || '连接失败', icon: 'none' })
    } finally {
      this.setData({ connecting: false, password: '' })
    }
  },
  // 把用户刚收到的验证码仅随本次请求发送，不写入本地存储。
  async verifyMiHealthCode() {
    const challenge = this.data.miHealthChallenge
    const code = this.data.verificationCode
    if (!challenge || !this.data.verificationCodeReady || this.data.connecting) return
    this.setData({ connecting: true, verificationCode: '', verificationCodeReady: false })
    try {
      await api('/api/v1/integrations/mihealth/verify', 'POST', {
        challenge_id: challenge.challenge_id,
        code
      })
      wx.showToast({ title: '小米账号已连接', icon: 'success' })
      this.setData({ username: '', miHealthChallenge: null })
      await this.loadMiHealth()
    } catch (error) {
      wx.showToast({ title: error.message || '验证码验证失败', icon: 'none' })
      await this.loadMiHealth()
    } finally {
      this.setData({ connecting: false, verificationCode: '', verificationCodeReady: false })
    }
  },
  // 手动同步最近两周本人健康摘要。
  async syncMiHealth() {
    if (this.data.syncing) return
    this.setData({ syncing: true })
    try {
      const response = await api('/api/v1/integrations/mihealth/sync', 'POST', { days: 14 })
      wx.showToast({ title: `新增 ${response.data.imported} 条健康数据`, icon: 'success' })
      await this.loadMiHealth()
    } catch (error) {
      wx.showToast({ title: error.message || '同步失败', icon: 'none' })
    } finally {
      this.setData({ syncing: false })
    }
  },
  // 解绑只删除加密会话，不删除已经导入的健康摘要。
  unbindMiHealth() {
    wx.showModal({
      title: '解除小米健康连接？',
      content: '将移除本机保存的加密登录会话；已经同步到本机的只读记录会保留。',
      confirmText: '解除连接',
      success: async result => {
        if (!result.confirm) return
        try {
          await api('/api/v1/integrations/mihealth/connection', 'DELETE')
          wx.showToast({ title: '已解除连接', icon: 'success' })
          await this.loadMiHealth()
        } catch (error) {
          wx.showToast({ title: error.message || '解绑失败', icon: 'none' })
        }
      }
    })
  }
})
