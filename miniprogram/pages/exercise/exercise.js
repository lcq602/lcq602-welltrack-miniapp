const { api } = require('../../utils/api.js')
Page({
  data: { kind: '有氧', duration: '', exercise_date: `${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}-${String(new Date().getDate()).padStart(2, '0')}`, note: '', items: [] },
  // 页面显示时刷新运动历史。
  onShow() { this.load() },
  // 读取运动安排和历史运动。
  async load() { try { const r = await api('/api/v1/exercises'); this.setData({ items: r.data }) } catch (e) { wx.showToast({ title: e.message || '加载失败', icon: 'none' }) } },
  // 更新运动日期、时长或备注。
  onInput(e) { this.setData({ [e.currentTarget.dataset.key]: e.detail.value }) },
  // 更新运动类型选择。
  onKind(e) { this.setData({ kind: e.detail.value }) },
  // 新增一条运动记录。
  async save() { const d = this.data; try { await api('/api/v1/exercises', 'POST', { exercise_date: d.exercise_date, kind: d.kind, duration_minutes: Number(d.duration), note: d.note }); wx.showToast({ title: '运动已记录' }); this.setData({ duration: '', note: '' }); this.load() } catch (e) { wx.showToast({ title: e.message || '保存失败', icon: 'none' }) } }
})
