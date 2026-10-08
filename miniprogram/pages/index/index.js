const { api } = require('../../utils/api.js')

Page({
  data: { loading: true, loadError: '', saving: false, dashboard: null, configured: false, goalLabel: '减重', goalOptions: ['减重', '减脂', '维持', '健康习惯'], form: { height_cm: '', current_weight: '', target_weight: '', start_date: '', target_date: '', goal: 'weight_loss' } },
  // 页面显示时刷新首页汇总。
  onShow() {
    const tab = this.getTabBar && this.getTabBar()
    if (tab) tab.setData({ selected: 0 })
    this.load()
    if (this._dashboardTimer) clearInterval(this._dashboardTimer)
    this._dashboardTimer = setInterval(() => this.load(), 60000)
  },
  // 页面隐藏或销毁时停止定时刷新。
  onHide() { if (this._dashboardTimer) clearInterval(this._dashboardTimer); this._dashboardTimer = null },
  onUnload() { if (this._dashboardTimer) clearInterval(this._dashboardTimer); this._dashboardTimer = null },
  // 读取首页汇总及首次设置状态。
  async load() {
    this.setData({ loading: !this.data.dashboard, loadError: '' })
    try {
      const res = await api('/api/v1/dashboard')
      const goalLabels = { weight_loss: '减重', fat_loss: '减脂', maintenance: '维持', healthy_habits: '健康习惯' }
      const values = (res.data.records || []).map(item => item.weight).filter(value => value !== null && value !== undefined)
      const min = values.length ? Math.min(...values) : 0
      const max = values.length ? Math.max(...values) : 1
      const recordsByDate = new Map((res.data.records || []).map(item => [item.record_date, item]))
      const trend = Array.from({ length: 7 }, (_, index) => {
        const date = new Date()
        date.setDate(date.getDate() - (6 - index))
        const recordDate = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
        const record = recordsByDate.get(recordDate)
        return { record_date: recordDate, day_label: recordDate.slice(5), weight: record ? record.weight : null, has_record: Boolean(record), bar_height: record && record.weight != null ? Math.max(14, 26 + ((record.weight - min) / Math.max(1, max - min)) * 86) : 6 }
      })
      const goal = res.data.profile && res.data.profile.goal
      const goalProgress = goal === 'healthy_habits' ? res.data.weekly_completion : res.data.progress
      this.setData({ dashboard: { ...res.data, trend, goal_key: goal, today_label: res.data.today.slice(5), goal_label: goalLabels[goal] || '健康计划', goal_progress: goalProgress, show_goal_progress: goal !== 'maintenance', progress_label: goal === 'healthy_habits' ? '近7天记录覆盖' : '目标进度' }, configured: res.data.configured, loading: false })
    } catch (err) { this.setData({ loading: false, loadError: err.message || '暂时无法连接健康服务' }) }
  },
  // 连接异常时允许用户主动重试首页加载。
  retryLoad() { this.load() },
  // 更新首次设置表单字段。
  onInput(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }) },
  // 把选择器序号转换为 API 使用的目标枚举。
  onGoal(e) { const i = Number(e.detail.value); this.setData({ 'form.goal': ['weight_loss', 'fat_loss', 'maintenance', 'healthy_habits'][i], goalLabel: this.data.goalOptions[i] }) },
  // 首次保存健康资料与目标。
  async saveProfile() {
    const data = { ...this.data.form, target_date: this.data.form.target_date || null, height_cm: Number(this.data.form.height_cm), current_weight: Number(this.data.form.current_weight), target_weight: Number(this.data.form.target_weight), weekly_plan: { mon: '有氧或散步', tue: '休息', wed: '力量训练', thu: '休息', fri: '有氧运动', sat: '自由活动', sun: '休息' } }
    if (!data.start_date) { const now = new Date(); data.start_date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}` }
    if (!data.height_cm || !data.current_weight || !data.target_weight) return wx.showToast({ title: '请完整填写身高和体重', icon: 'none' })
    if (data.height_cm <= 80 || data.height_cm > 250 || [data.current_weight, data.target_weight].some(value => value <= 20 || value > 500)) return wx.showToast({ title: '请检查身高和体重范围', icon: 'none' })
    if (this.data.saving) return
    this.setData({ saving: true })
    try { await api('/api/v1/profile', 'POST', data); wx.showToast({ title: '计划已建立', icon: 'success' }); this.load() }
    catch (err) { wx.showToast({ title: err.message || '保存失败', icon: 'none' }) }
    finally { this.setData({ saving: false }) }
  },
  // 跳转到日常记录页。
  openRecords() { wx.switchTab({ url: '/pages/records/records' }) },
  // 打开每周计划页。
  openPlan() { wx.switchTab({ url: '/pages/plan/plan' }) },
  // 打开统计分析页。
  openAnalysis() { wx.switchTab({ url: '/pages/analysis/analysis' }) },
  // 打开运动记录页。
  openExercise() { wx.navigateTo({ url: '/pages/exercise/exercise' }) }
})
