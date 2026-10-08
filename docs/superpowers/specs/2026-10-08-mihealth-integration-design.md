# 小米运动健康本地只读同步设计

## 目标

在本地开发版中，允许当前 WellTrack 用户连接自己的小米运动健康账号，按需同步本人可读取的体重、睡眠、步数和运动摘要。小程序继续只经 FastAPI 访问数据；小米账号凭证只在后端使用。此适配器使用参考仓库所采用的非官方、未公开接口，接口可变且不代表小米官方支持。

## 方案比较

1. **官方开放平台 OAuth**：稳定且正式，但需要账号服务和运动健康权限审批；当前用户没有可用权限，暂不能用于个人开发测试。
2. **本机非官方只读连接器（推荐）**：参考 `Aaron-gx/mihealth-api` 的账号登录、会话续期、限速与增量同步模式，移植所需的最小只读能力；小米凭证只从本地 WellTrack 页面提交至本机 FastAPI，密码只用于当次登录，不持久化。会话令牌使用环境变量密钥加密后保存。优点是可在个人开发机使用；风险是未公开接口可能变化、账号认证可能需要验证码/二次验证，也需用户自行评估小米服务条款。
3. **手动导出导入**：不需要保存登录态，风险较低；但不能自动同步，且小米导出文件格式需另行适配。

## 推荐设计

采用方案 2，仅限本机个人开发；不实现 Root、ADB、Frida 或 Cookie 数据库提取，不向小米写入任何数据，也不提供亲友账号/数据访问。若小米登录要求验证码/二次验证，登录接口返回明确的待验证状态；不绕过安全挑战。后端不得记录请求密码、令牌或健康数据明细。

小程序“我的”保留基础资料和小米运动健康连接卡片。连接、状态查看、主动同步、解绑均经带当前 WellTrack Bearer 会话的 `/api/v1/integrations/mihealth/*` 接口完成。登录密码仅由 HTTPS/本机 HTTP 开发链路发送到本地后端，并在登录函数返回后释放；生产环境不允许启用此未官方支持连接器。令牌以 Fernet 加密，密钥通过 `MIHEALTH_TOKEN_KEY` 环境变量提供，不提供默认密钥。

同步只读取所需指标。体重和睡眠可映射为日常记录新增；运动摘要映射为运动记录新增；步数在当前日常记录模型中没有专门字段，暂存于小米导入数据表，并可在连接状态中展示最近同步数量。已有日期的日常记录不覆盖；重复上游条目通过来源记录唯一键跳过。成功同步仅新增数据；不开放编辑、删除或备份恢复。

## 数据库审批项（实现前须确认）

需新增以下 SQLite 表；仅创建新表及索引，不修改或迁移现有用户数据：

```sql
CREATE TABLE IF NOT EXISTS mihealth_connections (
  user_id TEXT PRIMARY KEY REFERENCES users(id),
  encrypted_session TEXT NOT NULL,
  linked_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_synced_at TEXT
);
CREATE TABLE IF NOT EXISTS mihealth_imports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL REFERENCES users(id),
  source_id TEXT NOT NULL,
  metric TEXT NOT NULL,
  recorded_at TEXT NOT NULL,
  value REAL,
  unit TEXT NOT NULL DEFAULT '',
  payload TEXT NOT NULL DEFAULT '{}',
  UNIQUE(user_id, source_id, metric)
);
CREATE INDEX IF NOT EXISTS idx_mihealth_imports_user_time
  ON mihealth_imports(user_id, recorded_at);
```

`mihealth_connections` 存每个 WellTrack 用户各自加密的会话；`mihealth_imports` 保存必要的只读同步值与去重键。所有读写查询必须同时带 `user_id`。解绑只删除当前用户自己的连接令牌；保留已导入的只读历史数据。以上 DDL 会扩展本地数据库 schema，但不会更新、删除或重写现有行。

## API 与 UI

- `GET /api/v1/integrations/mihealth/status`：返回是否已连接、最后同步时间及数量，不返回令牌。
- `POST /api/v1/integrations/mihealth/connect`：接收严格校验的账号标识和密码，服务端发起小米登录；请求体、响应和日志均不回显密码。
- `POST /api/v1/integrations/mihealth/sync`：同步有限时间窗内已授权的只读指标，按用户隔离并幂等去重。
- `DELETE /api/v1/integrations/mihealth/connection`：删除当前用户保存的加密会话；不删除已导入数据。

登录暂不支持的验证码/二次验证需要给出可读说明，不得伪报绑定成功。默认配置关闭 (`MIHEALTH_ENABLED=false`)；显式启用、配置加密密钥后才提供连接入口。使用 `httpx` 异步客户端请求有限的固定小米端点，设置连接/读取超时和低并发/节流，不实现通用 URL 代理。

## 验证

新增单元测试覆盖加密往返、无密钥时拒绝连接、密码不落日志/库、用户间状态与记录隔离、重复同步幂等、已有日期不覆盖、错误认证和关闭开关。默认只用 mock HTTP，不用真实小米账号或凭证。运行现有后端测试并检查 API 文档、小程序 JS/JSON/WXML 结构和差异。

## 安全与限制

小米登录流和健康 API 为非官方接口，可能随时变化、触发风控或要求额外验证；实现不能保证始终可用。个人健康数据留在用户本机 SQLite，导出/连接状态仅向当前 Bearer 用户公开。正式线上部署应关闭本功能并优先使用小米官方审批后的 OAuth 接口。
