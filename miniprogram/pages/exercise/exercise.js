const { api } = require('../../utils/api.js')
Page({
  data: { kindOptions: ['有氧运动', '力量训练', '步行', '拉伸'], kind: '有氧运动', kindIndex: 0, duration: '', exercise_date: `${new Date().getFullYear()}-${String(new Date().getMonth() + 1).padStart(2, '0')}-${String(new Date().getDate()).padStart(2, '0')}`, note: '', items: [], loading: true, hasLoaded: false, saving: false },
  // 页面显示时刷新运动历史。
  onShow() { this.load() },
  // 读取运动安排和历史运动。
  async load() { this.setData({ loading: !this.data.hasLoaded }); try { const r = await api('/api/v1/exercises'); this.setData({ items: r.data.map(item => ({ ...item, note_text: item.note ? `${item.exercise_date} · ${item.note}` : item.exercise_date })), loading: false, hasLoaded: true }) } catch (e) { this.setData({ loading: false, hasLoaded: true }); wx.showToast({ title: e.message || '加载失败', icon: 'none' }) } },
  // 更新运动日期、时长或备注。
  onInput(e) { this.setData({ [e.currentTarget.dataset.key]: e.detail.value }) },
  // 更新运动类型选择。
  onKind(e) { const kindIndex = Number(e.detail.value); this.setData({ kindIndex, kind: this.data.kindOptions[kindIndex] }) },
  // 新增一条运动记录。
  async save() { const d = this.data; const minutes = Number(d.duration); if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) return wx.showToast({ title: '时长请输入 1–1440 分钟', icon: 'none' }); this.setData({ saving: true }); try { await api('/api/v1/exercises', 'POST', { exercise_date: d.exercise_date, kind: d.kind, duration_minutes: minutes, note: d.note }); wx.showToast({ title: '活动已记录', icon: 'success' }); this.setData({ duration: '', note: '', saving: false }); this.load() } catch (e) { this.setData({ saving: false }); wx.showToast({ title: e.message || '保存失败', icon: 'none' }) } }
})
