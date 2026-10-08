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

后续可以双击本目录的 `start-backend.bat`。脚本会检查 `uv`，在缺少 `.env` 时从样例创建，执行 `uv sync` 并以 `0.0.0.0:8000` 启动开发服务器。API 文档在 <http://127.0.0.1:8000/docs>，存活检查为 <http://127.0.0.1:8000/health>。后端首次启动会建立 schema 和索引，数据库默认保存在 `backend/data/welltrack.sqlite3`。

## 测试

在 `backend/` 目录执行：

```powershell
uv run pytest -q
uv run python -m compileall -q app
```

## 配置与接口

`.env.example` 提供本地匿名会话、数据目录、CORS 和微信登录配置项。微信 AppID/Secret 只放在后端 `.env`，服务端通过微信 `jscode2session` 校验登录 code；本地开发可直接使用匿名会话，不需要 AppID。

主要 API 位于 `/api/v1`，所有个人数据由 Bearer 会话映射到服务端用户 ID。接口清单、参数和写入边界见 [API.md](API.md)；运行后可在 FastAPI OpenAPI 页面查看并试调。

正式部署前关闭 `WELLTRACK_LOCAL_AUTH`、启用 HTTPS，并将 `WELLTRACK_CORS_ORIGINS` 设置为允许来源。CSV 导出会转义表格公式前缀；查询使用参数化 SQL 并按用户范围过滤。

### 小米运动健康（本机实验功能）

该连接器默认关闭。要在本机试用，编辑 `backend/.env`，将 `MIHEALTH_ENABLED=true`，并设置独立的 Fernet 密钥：

```powershell
uv run python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

每个 WellTrack 用户的会话凭证和体重历史分页游标加密后保存在本机数据库；密钥丢失后需重新绑定。小米账号密码只用于本次服务端登录请求。短信/邮箱验证码只随验证请求提交，不会入库；加密后的挑战状态在 SQLite 保存 10 分钟，最多允许 5 次尝试。后端运行时每 60 秒自动同步体重，先续跑完整历史回填，再按最近 7 天增量去重；后端关闭时暂停。首页与分析页优先显示小米体重，手工记录与起始基线不改写。接口为非官方实验性接口，不应暴露到公网。连接/同步与验证码表单位于小程序“我的”。

## 已知范围

当前只授权读取与新增，因此不提供记录编辑、删除和备份导入。微信订阅消息提醒也未接入；保存的提醒设置仅代表偏好。
