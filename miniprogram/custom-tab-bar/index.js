Component({
  data: {
    selected: 0,
    list: [
      { key: 'home', text: '首页', icon: '⌂', pagePath: '/pages/index/index' },
      { key: 'plan', text: '计划', icon: '▣', pagePath: '/pages/plan/plan' },
      { key: 'records', text: '记录', icon: '✓', pagePath: '/pages/records/records' },
      { key: 'analysis', text: '趋势', icon: '↗', pagePath: '/pages/analysis/analysis' },
      { key: 'mine', text: '我的', icon: '●', pagePath: '/pages/mine/mine' }
    ]
  },
  methods: {
    // 切换主导航，并让目标页面成为当前选中项。
    switchTab(event) {
      const selected = Number(event.currentTarget.dataset.index)
      const page = this.data.list[selected]
      if (!page) return
      wx.switchTab({ url: page.pagePath })
    }
  }
})
