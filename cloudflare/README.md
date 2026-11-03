# WellTrack API — Cloudflare 版

与 FastAPI 版接口契约一致的 Cloudflare 实现: Worker (worker.js) + D1 (schema.sql) + Workers AI 智能体。

## 接口
- 与原后端口径一致: /health、/api/v1/auth/local|wechat、profile、dashboard、records、exercises、analytics、export.csv、backup
- 新增: POST /api/v1/import (导入旧后端 backup JSON 做数据迁移, 含小米导入数据; 导入后自动标记已连接)
- 新增智能体: POST /api/v1/agent/chat {message, history?} (自动携带用户健康数据上下文)、GET /api/v1/agent/daily-insight (每日洞察, 当日缓存)
- 小米: status/sync/unbind 与数据合并读取已实现; connect/verify 的上游登录协议为第二阶段 (当前返回 501)

## 绑定与变量
- D1 绑定 DB; Workers AI 绑定 AI
- vars: MIHEALTH_ENABLED、AI_MODEL (默认 @cf/meta/llama-3.1-8b-instruct)
- secrets (wrangler secret put): WECHAT_APP_ID / 微信密钥 / MIHEALTH_TOKEN_KEY

## 部署
- 方式一: wrangler (wrangler.toml, 先建 D1 并回填 database_id, 执行 schema.sql, wrangler deploy)
- 方式二: Cloudflare REST API (建 D1 → 执行 schema → 上传脚本并声明绑定 → 开启 workers.dev 子域)
