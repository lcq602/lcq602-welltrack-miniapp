# WellTrack FastAPI 后端

本目录独立管理 FastAPI、SQLite 和 Python 依赖，不依赖小程序目录。

## 启动

需要 Python 3.11+ 和 uv。在 PowerShell 中：

```powershell
Set-Location .\backend
uv sync
Copy-Item .env.example .env
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload --env-file .env
```

后续可以双击本目录的 `start-backend.bat`。API 文档在 <http://127.0.0.1:8000/docs>，存活检查为 <http://127.0.0.1:8000/health>。后端首次启动会建立 schema 和索引，数据库默认保存在 `backend/data/welltrack.sqlite3`。

## 测试

在 `backend/` 目录执行：

```powershell
uv run pytest -q
uv run python -m compileall -q app
```

## 配置与接口

`.env.example` 提供本地匿名会话、数据目录、CORS 和微信登录配置项。微信 AppID/Secret 只放在后端 `.env`，服务端通过微信 `jscode2session` 校验登录 code；本地开发可直接使用匿名会话，不需要 AppID。

主要 API 位于 `/api/v1`，所有个人数据由 Bearer 会话映射到服务端用户 ID。包括个人资料/计划版本、日常记录新增与查询、运动安排新增与查询、首页与周期分析、CSV/JSON 导出。API 字段可在 FastAPI OpenAPI 页面查看。

正式部署前关闭 `WELLTRACK_LOCAL_AUTH`、启用 HTTPS，并将 `WELLTRACK_CORS_ORIGINS` 设置为允许来源。CSV 导出会转义表格公式前缀；查询使用参数化 SQL 并按用户范围过滤。

## 已知范围

当前只授权读取与新增，因此不提供记录编辑、删除和备份导入。微信订阅消息提醒也未接入；保存的提醒设置仅代表偏好。
