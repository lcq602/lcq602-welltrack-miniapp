# WellTrack 界面改版实施计划

> **For agentic workers:** Execute inline in this session. Steps use checkbox syntax.

**Goal:** 改善健康管理小程序的数据层级、日常记录操作和分析可读性，并补全后端接口说明及 dashboard 统计口径。

**Architecture:** 保持原生小程序和现有 FastAPI/SQLite 路由；通过 WXML/WXSS/JS 重整页面，后端只增加兼容性 dashboard 字段并用 API.md 明确现有接口契约。

**Tech Stack:** 微信原生小程序、FastAPI、SQLite、uv、pytest。

## 全局约束

- 页面仅通过 HTTP API 访问数据库。
- 记录和计划仍按读取与新增实现，不增加 UPDATE、DELETE 或批量导入。
- 不引入前端依赖；API 既有路径和字段保持兼容。
- 健康提示不做诊断、不建议极端节食、不承诺速度。
- 修改代码后追加 docs/修改记录.md。

---

### Task 1：统一视觉系统与首页

Files: miniprogram/app.wxss、miniprogram/app.json、miniprogram/pages/index/*

- [x] 设定冷雾灰、墨蓝、电光钴蓝与荧光青柠的全局样式层级。
- [x] 首页重排成主要数字、今日行动、趋势和首次设置。
- [x] 根据管理目标调整目标进度文案，避免维持目标显示成减重进度。

### Task 2：计划、日常和运动交互

Files: miniprogram/pages/plan/*、records/*、exercise/*

- [x] 星期映射统一为后端 mon–sun，并可编辑目标/日期/周安排。
- [x] 日常记录分组、校验空记录、处理重复日期并清空成功后的表单。
- [x] 使用运动类型选项数组并校验时长，优化历史呈现。

### Task 3：统计日历、个人页与 API 文档

Files: miniprogram/pages/analysis/*、mine/*、backend/app/main.py、backend/tests/test_api.py、backend/API.md、README

- [x] 增加 dashboard 近七天记录天数，统一覆盖率计算口径。
- [x] 用自然月日历展示每日记录并允许切换月份。
- [x] 整理个人设置、提醒偏好和导出入口。
- [x] 列出全部 API、字段和授权边界。

### Task 4：验证与记录

Files: docs/修改记录.md

- [x] 运行后端测试与 Python 编译检查。
- [x] 检查全部前端 JS、JSON 和 WXML。
- [x] 追加本次迭代记录并说明无法完成的模拟器验证。

微信开发者工具已安装，但 CLI 编译需要在 IDE 安全设置中开启服务端口。本次未开启该远程控制入口，因此未做开发者工具实际编译和模拟器视觉验收。
