const { api } = require('../../utils/api.js')

Page({
  data: { loading: true, dashboard: null, configured: false, goalLabel: '减重', goalOptions: ['减重', '减脂', '维持', '健康习惯'], form: { height_cm: '', current_weight: '', target_weight: '', start_date: '', target_date: '', goal: 'weight_loss' } },
  // 页面显示时刷新首页汇总。
  onShow() { this.load() },
  // 读取首页汇总及首次设置状态。
  async load() {
    try {
      const res = await api('/api/v1/dashboard')
      this.setData({ dashboard: res.data, configured: res.data.configured, loading: false })
    } catch (err) { this.setData({ loading: false }); wx.showToast({ title: err.message || '连接失败', icon: 'none' }) }
  },
  // 更新首次设置表单字段。
  onInput(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }) },
  // 把选择器序号转换为 API 使用的目标枚举。
  onGoal(e) { const i = Number(e.detail.value); this.setData({ 'form.goal': ['weight_loss', 'fat_loss', 'maintenance', 'healthy_habits'][i], goalLabel: this.data.goalOptions[i] }) },
  // 首次保存健康资料与目标。
  async saveProfile() {
    const data = { ...this.data.form, height_cm: Number(this.data.form.height_cm), current_weight: Number(this.data.form.current_weight), target_weight: Number(this.data.form.target_weight), weekly_plan: { mon: '有氧或散步', tue: '休息', wed: '力量训练', thu: '休息', fri: '有氧运动', sat: '自由活动', sun: '休息' } }
    if (!data.start_date) { const now = new Date(); data.start_date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}` }
    if (!data.height_cm || !data.current_weight || !data.target_weight) return wx.showToast({ title: '请完整填写身高和体重', icon: 'none' })
    try { await api('/api/v1/profile', 'POST', data); wx.showToast({ title: '已保存' }); this.load() }
    catch (err) { wx.showToast({ title: err.message || '保存失败', icon: 'none' }) }
  },
  // 跳转到日常记录页。
  openRecords() { wx.switchTab({ url: '/pages/records/records' }) },
  // 打开运动记录页。
  openExercise() { wx.navigateTo({ url: '/pages/exercise/exercise' }) }
})
