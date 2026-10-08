const { api } = require('../../utils/api.js')
Page({
  data: { form: { record_date: `${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}-${String(new Date().getDate()).padStart(2, '0')}`, weight: '', waist_cm: '', water_ml: '', sleep_hours: '', diet: '', note: '' }, records: [], habits: [], habitItems: ['运动', '均衡饮食', '喝够水', '按时睡眠', '减少久坐'].map(name => ({ name, selected: false })), loading: false },
  // 页面显示时刷新历史记录。
  onShow() { this.load() },
  // 读取当前用户的近期记录。
  async load() { try { const r = await api('/api/v1/records?limit=60'); this.setData({ records: r.data.map(x => ({ ...x, habit_text: x.habits.join(' · ') })) }) } catch (e) { wx.showToast({ title: e.message || '读取失败', icon: 'none' }) } },
  // 更新记录表单字段。
  onInput(e) { this.setData({ [`form.${e.currentTarget.dataset.key}`]: e.detail.value }) },
  // 切换每日习惯选择。
  onHabit(e) { const name = e.currentTarget.dataset.item; const habitItems = this.data.habitItems.map(x => x.name === name ? { ...x, selected: !x.selected } : x); this.setData({ habitItems, habits: habitItems.filter(x => x.selected).map(x => x.name) }) },
  // 新增一条每日健康记录。
  async save() {
    const f = this.data.form
    const body = { ...f, weight: f.weight ? Number(f.weight) : null, waist_cm: f.waist_cm ? Number(f.waist_cm) : null, water_ml: f.water_ml ? Number(f.water_ml) : null, sleep_hours: f.sleep_hours ? Number(f.sleep_hours) : null, habits: this.data.habits }
    try { await api('/api/v1/records', 'POST', body); wx.showToast({ title: '记录已新增' }); this.setData({ 'form.note': '', 'form.diet': '', habits: [], habitItems: this.data.habitItems.map(x => ({ ...x, selected: false })) }); this.load() } catch (e) { wx.showToast({ title: e.message || '保存失败', icon: 'none' }) }
  }
})
