const { api } = require('../../utils/api.js')

Page({
  data: {
    loading: true,
    loadError: '',
    saving: false,
    dashboard: null,
    configured: false,
    goalLabel: '减重',
    goalOptions: ['减重', '减脂', '维持', '健康习惯'],
    form: {
      height_cm: '',
      current_weight: '',
      target_weight: '',
      start_date: '',
      target_date: '',
      goal: 'weight_loss'
    }
  },

  // 页面显示时刷新首页汇总
  onShow() {
    const tab = this.getTabBar && this.getTabBar()
    if (tab) tab.setData({ selected: 0 })
    this.load()
    if (this._dashboardTimer) clearInterval(this._dashboardTimer)
    this._dashboardTimer = setInterval(() => this.load(), 60000)
  },

  // 页面隐藏或销毁时停止定时刷新
  onHide() {
    if (this._dashboardTimer) clearInterval(this._dashboardTimer)
    this._dashboardTimer = null
  },
  onUnload() {
    if (this._dashboardTimer) clearInterval(this._dashboardTimer)
    this._dashboardTimer = null
  },

  // 读取首页汇总及首次设置状态
  async load() {
    this.setData({ loading: !this.data.dashboard, loadError: '' })
    try {
      const res = await api('/api/v1/dashboard')
      const goalLabels = { weight_loss: '减重', fat_loss: '减脂', maintenance: '维持', healthy_habits: '健康习惯' }
      const values = (res.data.records || []).map(item => item.weight).filter(value => value !== null && value !== undefined)
      const min = values.length ? Math.min(...values) : 0
      const max = values.length ? Math.max(...values) : 1
      const recordsByDate = new Map((res.data.records || []).map(item => [item.record_date, item]))
      
      const now = new Date()
      const weekdays = ['日', '一', '二', '三', '四', '五', '六']
      const todayFullLabel = `${now.getMonth() + 1}月${now.getDate()}日 周${weekdays[now.getDay()]}`

      const trend = Array.from({ length: 7 }, (_, index) => {
        const date = new Date()
        date.setDate(date.getDate() - (6 - index))
        const recordDate = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
        const record = recordsByDate.get(recordDate)
        const isToday = index === 6
        return {
          record_date: recordDate,
          day_label: recordDate.slice(5),
          weekday: weekdays[date.getDay()],
          weight: record ? record.weight : null,
          has_record: Boolean(record),
          is_today: isToday,
          bar_height: record && record.weight != null ? Math.max(20, 32 + ((record.weight - min) / Math.max(1, max - min)) * 90) : 8
        }
      })

      // 计算相比昨日波动
      let diffLabel = ''
      if (trend[6].weight != null && trend[5].weight != null) {
        const diff = Number((trend[6].weight - trend[5].weight).toFixed(1))
        if (diff < 0) diffLabel = `↓ ${Math.abs(diff)} kg 较昨日`
        else if (diff > 0) diffLabel = `↑ ${diff} kg 较昨日`
        else diffLabel = '持平 较昨日'
      }

      // 计算 BMI 状态标签
      let bmiStatus = '健康正常'
      let bmiPillClass = 'pill-emerald'
      const bmiNum = Number(res.data.bmi)
      if (bmiNum > 0) {
        if (bmiNum < 18.5) { bmiStatus = '偏瘦'; bmiPillClass = 'pill-blue' }
        else if (bmiNum <= 23.9) { bmiStatus = '健康正常'; bmiPillClass = 'pill-emerald' }
        else if (bmiNum <= 27.9) { bmiStatus = '超重'; bmiPillClass = 'pill-amber' }
        else { bmiStatus = '偏高'; bmiPillClass = 'pill-purple' }
      }

      // 计算剩余目标差值
      let remainingWeight = ''
      if (res.data.current_weight != null && res.data.target_weight != null) {
        const rem = Number((Math.abs(res.data.current_weight - res.data.target_weight)).toFixed(1))
        remainingWeight = rem > 0 ? `还差 ${rem} kg` : '已达成目标'
      }

      const goal = res.data.profile && res.data.profile.goal
      const goalProgress = Math.min(100, Math.max(0, goal === 'healthy_habits' ? (res.data.weekly_completion || 0) : (res.data.progress || 0)))

      const mihealth = res.data.mihealth || { connected: false, today: { steps: 0, calories: 0, sleep_hours: null, workout_minutes: 0 }, latest: {} }
      const stepGoal = 8000
      const stepProgress = Math.min(100, Math.round(((mihealth.today && mihealth.today.steps || 0) / stepGoal) * 100))
      const calGoal = 400
      const calProgress = Math.min(100, Math.round(((mihealth.today && mihealth.today.calories || 0) / calGoal) * 100))

      this.setData({
        dashboard: {
          ...res.data,
          trend,
          diff_label: diffLabel,
          bmi_status: bmiStatus,
          bmi_pill_class: bmiPillClass,
          remaining_weight: remainingWeight,
          today_full_label: todayFullLabel,
          goal_key: goal,
          today_label: res.data.today.slice(5),
          goal_label: goalLabels[goal] || '健康计划',
          goal_progress: goalProgress,
          show_goal_progress: goal !== 'maintenance',
          progress_label: goal === 'healthy_habits' ? '近7天记录覆盖' : '目标进度',
          mihealth,
          step_progress: stepProgress,
          cal_progress: calProgress,
        },
        configured: res.data.configured,
        loading: false
      })
    } catch (err) {
      this.setData({ loading: false, loadError: err.message || '暂时无法连接健康服务' })
    }
  },

  // 手动快捷同步小米运动健康数据
  async syncMiHealth() {
    if (this.data.syncingMi) return
    this.setData({ syncingMi: true })
    try {
      await api('/api/v1/integrations/mihealth/sync', 'POST', { days: 14 })
      wx.showToast({ title: '手环数据已更新', icon: 'success' })
      await this.load()
    } catch (err) {
      wx.showToast({ title: err.message || '同步遇到问题，请在“我的”页面查看连接', icon: 'none' })
    } finally {
      this.setData({ syncingMi: false })
    }
  },

  // 打开我的设置页面
  openMine() {
    wx.switchTab({ url: '/pages/mine/mine' })
  },

  // 连接异常时重试加载
  retryLoad() {
    this.load()
  },

  // 表单输入绑定
  onInput(e) {
    this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value })
  },

  // 目标选择
  onGoal(e) {
    const i = Number(e.detail.value)
    this.setData({
      'form.goal': ['weight_loss', 'fat_loss', 'maintenance', 'healthy_habits'][i],
      goalLabel: this.data.goalOptions[i]
    })
  },

  // 首次保存健康资料
  async saveProfile() {
    const data = {
      ...this.data.form,
      target_date: this.data.form.target_date || null,
      height_cm: Number(this.data.form.height_cm),
      current_weight: Number(this.data.form.current_weight),
      target_weight: Number(this.data.form.target_weight),
      weekly_plan: {
        mon: '有氧或散步',
        tue: '休息',
        wed: '力量训练',
        thu: '休息',
        fri: '有氧运动',
        sat: '自由活动',
        sun: '休息'
      }
    }
    if (!data.start_date) {
      const now = new Date()
      data.start_date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
    }
    if (!data.height_cm || !data.current_weight || !data.target_weight) {
      return wx.showToast({ title: '请完整填写身高和体重', icon: 'none' })
    }
    if (data.height_cm <= 80 || data.height_cm > 250 || [data.current_weight, data.target_weight].some(value => value <= 20 || value > 500)) {
      return wx.showToast({ title: '请检查身高和体重范围', icon: 'none' })
    }
    if (this.data.saving) return
    this.setData({ saving: true })
    try {
      await api('/api/v1/profile', 'POST', data)
      wx.showToast({ title: '计划已建立', icon: 'success' })
      this.load()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  // 页面跳转
  openRecords() { wx.switchTab({ url: '/pages/records/records' }) },
  openPlan() { wx.switchTab({ url: '/pages/plan/plan' }) },
  openAnalysis() { wx.switchTab({ url: '/pages/analysis/analysis' }) },
  openExercise() { wx.navigateTo({ url: '/pages/exercise/exercise' }) }
})
