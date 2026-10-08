const { api } = require('../../utils/api.js')
Page({
  data: { profile: null, form: {}, loading: true, weekOptions: ['有氧运动', '力量训练', '散步', '休息', '拉伸', '自由活动'], days: [] },
  // 页面显示时重新读取最新计划版本。
  onShow() { this.load() },
  // 加载计划资料。
  async load() { try { const r = await api('/api/v1/profile'); const names = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']; const templates = { fat_loss: ['力量训练', '有氧运动', '休息', '力量训练', '散步', '有氧运动', '休息'], weight_loss: ['散步', '休息', '有氧运动', '休息', '散步', '自由活动', '休息'], maintenance: ['散步', '力量训练', '休息', '有氧运动', '休息', '自由活动', '休息'], healthy_habits: ['散步', '拉伸', '休息', '有氧运动', '拉伸', '自由活动', '休息'] }; const defaults = r.data && templates[r.data.goal] || templates.weight_loss; const days = names.map((name, i) => { const value = (r.data && r.data.weekly_plan[`day${i + 1}`]) || defaults[i]; return { name, key: `day${i + 1}`, value, choice: this.data.weekOptions.indexOf(value) >= 0 ? this.data.weekOptions.indexOf(value) : 0 } }); this.setData({ profile: r.data, form: r.data || {}, days, loading: false }) } catch (e) { wx.showToast({ title: e.message || '加载失败', icon: 'none' }) } },
  // 更新目标日期或体重输入。
  onInput(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }) },
  // 更新指定星期的安排。
  onPlanPick(e) { const index = Number(e.currentTarget.dataset.index); const days = this.data.days.slice(); days[index].choice = Number(e.detail.value); days[index].value = this.data.weekOptions[days[index].choice]; this.setData({ days }) },
  // 追加保存一份新的阶段计划。
  async save() {
    const f = this.data.form
    if (!f.height_cm || !f.current_weight || !f.target_weight || !f.start_date || !f.goal) return wx.showToast({ title: '请先在首页完成个人设置', icon: 'none' })
    const weekly_plan = {}; this.data.days.forEach(day => { weekly_plan[day.key] = day.value })
    const payload = { height_cm: Number(f.height_cm), current_weight: Number(f.current_weight), target_weight: Number(f.target_weight), start_date: f.start_date, target_date: f.target_date || null, goal: f.goal, weekly_plan, reminder_settings: f.reminder_settings || {} }
    try { await api('/api/v1/profile', 'POST', payload); wx.showToast({ title: '新计划已保存' }); this.load() } catch (e) { wx.showToast({ title: e.message || '保存失败', icon: 'none' }) }
  }
})
