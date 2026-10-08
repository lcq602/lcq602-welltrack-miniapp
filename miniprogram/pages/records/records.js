const { api } = require('../../utils/api.js')
Page({
  data: { form: { record_date: `${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}-${String(new Date().getDate()).padStart(2, '0')}`, weight: '', waist_cm: '', water_ml: '', sleep_hours: '', diet: '', note: '' }, records: [], habits: [], habitItems: ['运动', '均衡饮食', '喝够水', '按时睡眠', '减少久坐'].map(name => ({ name, selected: false })), loading: true, hasLoaded: false, saving: false, alreadySaved: false },
  // 页面显示时刷新历史记录。
  onShow() { const tab = this.getTabBar && this.getTabBar(); if (tab) tab.setData({ selected: 2 }); this.load() },
  // 读取当前用户的近期记录。
  async load() { this.setData({ loading: !this.data.hasLoaded }); try { const r = await api('/api/v1/records?limit=60'); const records = r.data.map(x => ({ ...x, habit_text: x.habits.join(' · ') })); this.setData({ records, loading: false, hasLoaded: true, alreadySaved: records.some(item => item.record_date === this.data.form.record_date) }) } catch (e) { this.setData({ loading: false, hasLoaded: true }); wx.showToast({ title: e.message || '读取失败', icon: 'none' }) } },
  // 更新记录表单字段。
  onInput(e) { const key = e.currentTarget.dataset.key; const update = { [`form.${key}`]: e.detail.value }; if (key === 'record_date') update.alreadySaved = this.data.records.some(item => item.record_date === e.detail.value); this.setData(update) },
  // 切换每日习惯选择。
  onHabit(e) { const name = e.currentTarget.dataset.item; const habitItems = this.data.habitItems.map(x => x.name === name ? { ...x, selected: !x.selected } : x); this.setData({ habitItems, habits: habitItems.filter(x => x.selected).map(x => x.name) }) },
  // 新增一条每日健康记录。
  async save() {
    const f = this.data.form
    if (this.data.loading || this.data.saving || this.data.alreadySaved) return wx.showToast({ title: this.data.alreadySaved ? '这一天已有记录，请选择其他日期' : '正在加载或保存', icon: 'none' })
    const hasValue = [f.weight, f.waist_cm, f.water_ml, f.sleep_hours, f.diet, f.note].some(value => value !== '' && value !== null) || this.data.habits.length > 0
    if (!hasValue) return wx.showToast({ title: '先填写一项数据或选择一个习惯', icon: 'none' })
    const ranges = [['体重', f.weight, 20, 500, false], ['腰围', f.waist_cm, 20, 300, false], ['饮水', f.water_ml, 0, 10000, true], ['睡眠', f.sleep_hours, 0, 24, true]]
    for (const [label, value, min, max, minInclusive] of ranges) if (value !== '' && (!Number.isFinite(Number(value)) || (minInclusive ? Number(value) < min : Number(value) <= min) || Number(value) > max)) return wx.showToast({ title: `${label}超出可记录范围`, icon: 'none' })
    const body = { ...f, weight: f.weight ? Number(f.weight) : null, waist_cm: f.waist_cm ? Number(f.waist_cm) : null, water_ml: f.water_ml ? Number(f.water_ml) : null, sleep_hours: f.sleep_hours ? Number(f.sleep_hours) : null, habits: this.data.habits }
    this.setData({ saving: true })
    try { await api('/api/v1/records', 'POST', body); wx.showToast({ title: '今天已记录', icon: 'success' }); this.setData({ form: { record_date: f.record_date, weight: '', waist_cm: '', water_ml: '', sleep_hours: '', diet: '', note: '' }, habits: [], saving: false, alreadySaved: true, habitItems: this.data.habitItems.map(x => ({ ...x, selected: false })) }); this.load() } catch (e) { this.setData({ saving: false }); wx.showToast({ title: String(e.message || '').includes('409') ? '这一天已有记录，请换一天查看' : (e.message || '保存失败'), icon: 'none' }) }
  },
  // 下载当前用户导出文件并保存到小程序本地。
  download(path, name) {
    const apiUrl = wx.getStorageSync('welltrack_api') || 'http://127.0.0.1:8000'
    const token = wx.getStorageSync('welltrack_token')
    wx.downloadFile({ url: `${apiUrl}${path}`, header: token ? { Authorization: `Bearer ${token}` } : {}, success: result => {
      if (result.statusCode !== 200) return wx.showToast({ title: '导出失败', icon: 'none' })
      wx.saveFile({ tempFilePath: result.tempFilePath, success: saved => wx.showModal({ title: `${name}已保存`, content: saved.savedFilePath, showCancel: false }), fail: () => wx.showToast({ title: '文件保存失败', icon: 'none' }) })
    }, fail: () => wx.showToast({ title: '连接失败', icon: 'none' }) })
  },
  exportCsv() { this.download('/api/v1/export.csv', 'CSV 数据') },
  exportBackup() { this.download('/api/v1/backup', 'JSON 备份') }
})
