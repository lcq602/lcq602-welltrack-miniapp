const { api } = require('../../utils/api.js')

// 返回本地日期，避免 UTC 时区换算导致日历错一天。
function todayString() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

// 将有记录的日期映射到固定坐标，并仅连接连续日期的数据点。
function buildChart(days, field, start, end, digits) {
  const toDay = value => Date.parse(`${value}T00:00:00Z`) / 86400000
  const first = toDay(start)
  const last = toDay(end)
  const span = Math.max(1, last - first)
  const values = days.filter(day => Number.isFinite(Number(day[field])) && day[field] !== null).map(day => ({ date: day.record_date, value: Number(day[field]) }))
  if (!values.length) return null
  let min = Math.min(...values.map(item => item.value))
  let max = Math.max(...values.map(item => item.value))
  const padding = max === min ? Math.max(1, max * 0.03) : (max - min) * 0.15
  min -= padding
  max += padding
  const points = values.map((item) => ({
    key: item.date,
    date: item.date,
    label: item.date.slice(5),
    value: item.value,
    x: 12 + ((toDay(item.date) - first) / span) * 456,
    y: 150 - ((item.value - min) / (max - min)) * 132,
    text: item.value.toFixed(digits)
  }))
  const segments = []
  for (let index = 1; index < points.length; index += 1) {
    const before = points[index - 1]
    const after = points[index]
    if (toDay(after.date) - toDay(before.date) !== 1) continue
    const dx = after.x - before.x
    const dy = after.y - before.y
    segments.push({ key: `${before.key}-${after.key}`, x: before.x, y: before.y, length: Math.sqrt(dx * dx + dy * dy), angle: Math.atan2(dy, dx) * 180 / Math.PI })
  }
  const labels = Array.from({ length: 5 }, (_, index) => {
    const day = new Date((first + Math.round(span * index / 4)) * 86400000).toISOString().slice(0, 10)
    return { key: `${field}-${index}`, text: day.slice(5), x: 12 + (index / 4) * 456 }
  })
  return { field, unit: field === 'weight' ? 'kg' : 'cm', min: min.toFixed(digits), max: max.toFixed(digits), points, segments, labels }
}

Page({
  data: {
    period: 'week',
    chartTab: 'weight', // 'weight' | 'steps' | 'calories' | 'vitality'
    summary: null,
    loadError: '',
    trendRows: [],
    charts: [],
    stepBars: [],
    calBars: [],
    vitalityRows: [],
    calendarCells: [],
    calendarLabel: '',
    calendarMonth: todayString().slice(0, 7),
    weekLabels: ['一', '二', '三', '四', '五', '六', '日'],
    loading: true
  },

  // 页面显示时更新统计数据。
  onShow() {
    const tab = this.getTabBar && this.getTabBar()
    if (tab) tab.setData({ selected: 3 })
    this.load()
  },

  // 切换图表维度 Tab
  onChartTab(event) {
    this.setData({ chartTab: event.currentTarget.dataset.tab })
  },

  // 读取所选周期，并生成多维度可视化图表数据。
  async load() {
    this.setData({ loading: !this.data.summary, loadError: '' })
    try {
      const monthParam = this.data.period === 'week' ? '' : `&month=${this.data.calendarMonth}`
      const response = await api(`/api/v1/analytics?period=${this.data.period}${monthParam}`)
      const summary = response.data
      const sleepLabel = summary.average_sleep === null || summary.average_sleep === undefined ? '—' : summary.average_sleep
      const weights = summary.days.map(day => day.weight).filter(value => value !== null && value !== undefined)
      const min = weights.length ? Math.min(...weights) : 0
      const range = weights.length ? Math.max(...weights) - min : 0
      
      const trendRows = summary.days.map(day => ({
        ...day,
        short_date: day.record_date.slice(5),
        weight_width: day.weight == null ? 0 : range ? 24 + ((day.weight - min) / range) * 76 : 55,
        weight_label: day.weight == null ? '—' : `${day.weight} kg`,
        waist_label: day.waist_cm == null ? '腰围 —' : `腰围 ${day.waist_cm} cm`
      }))

      // 步数柱状图数据计算
      const maxSteps = Math.max(10000, ...(summary.days.map(d => d.steps || 0)))
      const stepBars = summary.days.map(day => {
        const steps = day.steps || 0
        return {
          record_date: day.record_date,
          short_date: day.record_date.slice(5),
          steps,
          is_achieved: steps >= 6000,
          bar_height: Math.max(16, Math.min(180, Math.round((steps / maxSteps) * 180)))
        }
      })

      // 能量消耗柱状图数据计算
      const maxCals = Math.max(500, ...(summary.days.map(d => d.calories || 0)))
      const calBars = summary.days.map(day => {
        const calories = day.calories || 0
        return {
          record_date: day.record_date,
          short_date: day.record_date.slice(5),
          calories,
          bar_height: Math.max(16, Math.min(180, Math.round((calories / maxCals) * 180)))
        }
      })

      // 睡眠与运动复盘对比数据计算
      const vitalityRows = summary.days.map(day => ({
        record_date: day.record_date,
        short_date: day.record_date.slice(5),
        sleep_hours: day.sleep_hours != null ? day.sleep_hours : null,
        sleep_width: day.sleep_hours ? Math.min(100, Math.round((day.sleep_hours / 10) * 100)) : 0,
        workout_minutes: day.workout_minutes != null ? day.workout_minutes : 0,
        workout_width: day.workout_minutes ? Math.min(100, Math.round((day.workout_minutes / 60) * 100)) : 0
      }))

      const charts = this.data.period === 'calendar' ? [] : [
        buildChart(summary.days, 'weight', summary.start, summary.end, 1),
        buildChart(summary.days, 'waist_cm', summary.start, summary.end, 1)
      ].filter(Boolean)

      const cells = []
      if (this.data.period === 'calendar') {
        const [year, month] = this.data.calendarMonth.split('-').map(Number)
        const firstDay = (new Date(year, month - 1, 1).getDay() + 6) % 7
        const daysInMonth = new Date(year, month, 0).getDate()
        const loggedDates = new Set(summary.days.map(day => day.record_date))
        const today = todayString()
        for (let i = 0; i < firstDay; i += 1) cells.push({ key: `blank-${i}`, empty: true, day: '' })
        for (let day = 1; day <= daysInMonth; day += 1) {
          const date = `${this.data.calendarMonth}-${String(day).padStart(2, '0')}`
          const hasRecord = loggedDates.has(date)
          const isToday = date === today
          cells.push({ key: date, day, hasRecord, isToday, recordedClass: hasRecord && !isToday })
        }
      }

      this.setData({
        summary,
        sleepLabel,
        trendRows,
        charts,
        stepBars,
        calBars,
        vitalityRows,
        calendarCells: cells,
        calendarLabel: `${this.data.calendarMonth.slice(0, 4)} 年 ${Number(this.data.calendarMonth.slice(5))} 月`,
        loading: false
      })
    } catch (error) {
      this.setData({ loading: false, loadError: error.message || '统计读取失败' })
    }
  },

  // 统计请求失败时允许重新加载。
  retryLoad() { this.load() },

  // 切换周、月或日历视图。
  onPeriod(event) { this.setData({ period: event.currentTarget.dataset.period }, () => this.load()) },

  // 按月份切换日历，并读取对应月份数据。
  changeMonth(event) {
    const [year, month] = this.data.calendarMonth.split('-').map(Number)
    const next = new Date(year, month - 1 + Number(event.currentTarget.dataset.step), 1)
    const calendarMonth = `${next.getFullYear()}-${String(next.getMonth() + 1).padStart(2, '0')}`
    this.setData({ calendarMonth }, () => this.load())
  }
})
