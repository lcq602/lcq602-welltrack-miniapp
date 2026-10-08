const { api } = require('../../utils/api.js')
Page({
  data: { period: 'week', summary: null, bars: [] },
  // 页面显示时更新统计数据。
  onShow() { this.load() },
  // 读取周统计并计算趋势图柱高。
  async load() { try { const r = await api(`/api/v1/analytics?period=${this.data.period}`); const days = r.data.days; const weights = days.map(x => x.weight).filter(x => x != null); const min = weights.length ? Math.min(...weights) : 0; const max = weights.length ? Math.max(...weights) : 1; this.setData({ summary: r.data, bars: days.map(x => ({ ...x, short_date: x.record_date.slice(5), height: x.weight == null ? 4 : Math.max(8, ((x.weight - min + 1) / (max - min + 1)) * 120) })) }) } catch (e) { wx.showToast({ title: e.message || '统计读取失败', icon: 'none' }) } },
  // 切换统计周期。
  onPeriod(e) { this.setData({ period: e.currentTarget.dataset.period }, () => this.load()) }
})
