const { api } = require('../../utils/api.js')

Page({
  data: {
    profile: null,
    form: {},
    loading: true,
    saving: false,
    weekOptions: ['有氧运动', '力量训练', '散步', '休息', '拉伸', '自由活动'],
    goalOptions: ['减重', '减脂', '维持', '健康习惯'],
    goalValues: ['weight_loss', 'fat_loss', 'maintenance', 'healthy_habits'],
    days: []
  },

  // 页面显示时重新读取最新计划版本
  onShow() {
    const tab = this.getTabBar && this.getTabBar()
    if (tab) tab.setData({ selected: 1 })
    this.load()
  },

  // 获取活动对应的强度标签
  getActivityTag(activity) {
    const tagMap = {
      '力量训练': '中高强度',
      '有氧运动': '心肺激活',
      '散步': '轻松适度',
      '休息': '恢复日',
      '拉伸': '柔韧放松',
      '自由活动': '生活活动'
    }
    return tagMap[activity] || '日常活动'
  },

  // 加载计划资料
  async load() {
    try {
      const r = await api('/api/v1/profile')
      const names = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
      const keys = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']
      const templates = {
        fat_loss: ['力量训练', '有氧运动', '休息', '力量训练', '散步', '有氧运动', '休息'],
        weight_loss: ['散步', '休息', '有氧运动', '休息', '散步', '自由活动', '休息'],
        maintenance: ['散步', '力量训练', '休息', '有氧运动', '休息', '自由活动', '休息'],
        healthy_habits: ['散步', '拉伸', '休息', '有氧运动', '拉伸', '自由活动', '休息']
      }
      const defaults = (r.data && templates[r.data.goal]) || templates.weight_loss
      const days = names.map((name, i) => {
        const value = (r.data && r.data.weekly_plan && r.data.weekly_plan[keys[i]]) || defaults[i]
        return {
          name,
          key: keys[i],
          value,
          tag: this.getActivityTag(value),
          choice: Math.max(0, this.data.weekOptions.indexOf(value))
        }
      })
      const form = r.data ? { ...r.data, target_date: r.data.target_date || '' } : {}
      const goalIndex = Math.max(0, this.data.goalValues.indexOf(form.goal))

      // 计算计划目标差值
      let deltaText = ''
      if (form.current_weight != null && form.target_weight != null) {
        const delta = Number((form.target_weight - form.current_weight).toFixed(1))
        deltaText = delta > 0 ? `+${delta} kg` : `${delta} kg`
      }

      this.setData({
        profile: r.data,
        form,
        deltaText,
        goalIndex,
        goalLabel: this.data.goalOptions[goalIndex] || '减重',
        days,
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  // 更新目标日期或体重输入
  onInput(e) {
    const key = e.currentTarget.dataset.key
    const value = e.detail.value
    const form = { ...this.data.form, [key]: value }

    let deltaText = this.data.deltaText
    if (key === 'current_weight' || key === 'target_weight') {
      const cur = Number(form.current_weight)
      const tar = Number(form.target_weight)
      if (Number.isFinite(cur) && Number.isFinite(tar)) {
        const delta = Number((tar - cur).toFixed(1))
        deltaText = delta > 0 ? `+${delta} kg` : `${delta} kg`
      }
    }

    this.setData({ form, deltaText })
  },

  // 点击分段胶囊或 picker 切换管理目标
  selectGoal(e) {
    const i = Number(e.currentTarget.dataset.index)
    this.setData({
      'form.goal': this.data.goalValues[i],
      goalIndex: i,
      goalLabel: this.data.goalOptions[i]
    })
  },

  onGoal(e) {
    const i = Number(e.detail.value)
    this.setData({
      'form.goal': this.data.goalValues[i],
      goalIndex: i,
      goalLabel: this.data.goalOptions[i]
    })
  },

  // 更新指定星期的安排
  onPlanPick(e) {
    const index = Number(e.currentTarget.dataset.index)
    const choice = Number(e.detail.value)
    const selectedActivity = this.data.weekOptions[choice]
    const days = this.data.days.slice()
    days[index].choice = choice
    days[index].value = selectedActivity
    days[index].tag = this.getActivityTag(selectedActivity)
    this.setData({ days })
  },

  // 追加保存一份新的阶段计划
  async save() {
    const f = this.data.form
    if (!f.height_cm || !f.current_weight || !f.target_weight || !f.start_date || !f.goal) {
      return wx.showToast({ title: '请先在首页完成个人设置', icon: 'none' })
    }
    if ([f.current_weight, f.target_weight].some(value => !Number.isFinite(Number(value)) || Number(value) <= 20 || Number(value) > 500)) {
      return wx.showToast({ title: '请检查当前体重和目标体重', icon: 'none' })
    }
    if (f.target_date && f.target_date < f.start_date) {
      return wx.showToast({ title: '目标日期不能早于开始日期', icon: 'none' })
    }
    if (this.data.saving) return

    const weekly_plan = {}
    this.data.days.forEach(day => {
      weekly_plan[day.key] = day.value
    })
    const payload = {
      height_cm: Number(f.height_cm),
      current_weight: Number(f.current_weight),
      target_weight: Number(f.target_weight),
      start_date: f.start_date,
      target_date: f.target_date || null,
      goal: f.goal,
      weekly_plan,
      reminder_settings: f.reminder_settings || {}
    }

    this.setData({ saving: true })
    try {
      await api('/api/v1/profile', 'POST', payload)
      wx.showToast({ title: '新计划已保存', icon: 'success' })
      this.load()
    } catch (e) {
      wx.showToast({ title: e.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  }
})
