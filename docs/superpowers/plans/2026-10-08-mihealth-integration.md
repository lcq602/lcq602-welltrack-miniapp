# 小米运动健康本地只读同步实施计划

> **For agentic workers:** 本轮由当前会话逐项执行。按下列任务实现并验证，不触碰其他未提交改动。

**Goal:** 在本地 WellTrack 中连接用户自己的小米运动健康账号，只读同步体重、睡眠、步数和运动摘要，并提供可双击启动的后端 BAT。

**Architecture:** 参考 `Aaron-gx/mihealth-api` 的 MIT 登录和只读接口协议，在 WellTrack 中维护最小适配模块并保留其许可；参考 `shkyyy18/mi_fitness_data_bridge` 的本地优先和幂等思路，但不复制或依赖该仓库目前的 AGPL-3.0-only 代码。使用 WellTrack 用户 Bearer 会话隔离连接与导入。新表只作新增，不迁移既有数据；会话令牌用环境变量加密密钥保护。小程序“我的”提供连接、同步、状态和解绑入口。

**Tech Stack:** 微信原生小程序、Python 3.11+、FastAPI、SQLite、uv、cryptography/Fernet、requests。

## Global Constraints

- `MIHEALTH_ENABLED` 默认关闭；账号密码仅在后端执行当次小米登录时使用，不保存、不记录日志。
- 不实现 Root、ADB、Frida、Cookie 提取、亲友账号或对小米写入。
- 所有连接状态与导入记录都必须按服务端解析出的 `user_id` 隔离。
- 数据库仅新增连接/导入表和索引；不更新、删除或重写现有行。
- 已有日期日常记录保持不变；上游重复数据幂等跳过。
- 复制 MIT 代码须保留上游 LICENSE 与来源说明；请求只访问固定的小米端点且设超时/节流。
- 必须追加 `docs/修改记录.md`；不得提交真实密钥或数据库文件。

---

### Task 1: 后端连接与同步存储

**Files:**
- Modify: `backend/app/db.py`
- Modify: `backend/app/config.py`
- Create: `backend/app/integrations/__init__.py`
- Create: `backend/app/integrations/mihealth.py`
- Test: `backend/tests/test_api.py`

**Interfaces:**
- `encrypt_session(session: dict) -> str` / `decrypt_session(blob: str) -> dict`
- SQLite connection table keyed by `user_id`; imported rows unique on `(user_id, source_id, metric)`.

- [x] Add tests for missing key rejection, encrypted session round-trip, connection status isolation, and import deduplication.
- [x] Run only the new integration tests to confirm failure before implementation.
- [x] Add additive `CREATE TABLE IF NOT EXISTS` definitions and indexes; all queries include `user_id`.
- [x] Implement Fernet session encryption using `MIHEALTH_TOKEN_KEY`; disable connector if feature flag or key is missing.
- [x] Rerun the new tests and verify no secrets appear in returned status.

### Task 2: Vendor the minimum reference client and implement connection service

**Files:**
- Create: `backend/app/integrations/vendor/login.py`
- Create: `backend/app/integrations/vendor/client.py`
- Create: `backend/app/integrations/MIHEALTH_MIT_LICENSE.txt`
- Create: `backend/app/integrations/vendor/NOTICE.md`
- Modify: `backend/pyproject.toml`
- Modify: `backend/uv.lock`
- Test: `backend/tests/test_api.py`

**Interfaces:**
- `connect_mihealth(username: str, password: str) -> dict`: return session material only to caller; caller encrypts before persistence.
- `read_mihealth_metrics(session: dict, start_ms: int, end_ms: int) -> list[dict]`: fixed set of health keys and workout summaries.

- [x] Add unit tests with patched login/network functions for success, auth failure, and credential non-persistence.
- [x] Adapt only the minimum login/session and metric query protocol from the MIT reference, keep attribution/license notice, and omit its Flask server and device credential extraction tools; use the AGPL reference only for architecture ideas.
- [x] Add bounded HTTP request timeouts and translate authentication/service failures to non-sensitive errors.
- [x] Add `cryptography` and `requests` with uv; regenerate lockfile.
- [x] Run integration unit tests using mocked network responses.

### Task 3: FastAPI connection, status, sync, and unbind endpoints

**Files:**
- Modify: `backend/app/main.py`
- Modify: `backend/app/schemas.py`
- Modify: `backend/tests/test_api.py`
- Modify: `backend/API.md`

**Interfaces:**
- `GET /api/v1/integrations/mihealth/status`
- `POST /api/v1/integrations/mihealth/connect`
- `POST /api/v1/integrations/mihealth/sync`
- `DELETE /api/v1/integrations/mihealth/connection`

- [x] Test all routes unauthenticated and with two isolated Bearer users.
- [x] Test connection disabled/missing key, mock successful link, password absent from database, sync deduplication, existing daily date skip, and unbind preserving imported history.
- [x] Implement strict request schema, fixed date-window cap, parameterized SQL, and safe public status shape.
- [x] Store weight/sleep/workout/step summaries only in the new append-only integration table; never overwrite or merge manual daily records.
- [x] Document payloads, feature flag, local-only limitations, and failure modes.
- [x] Run the complete backend test suite.

### Task 4: My page integration UI and startup BAT

**Files:**
- Modify: `miniprogram/pages/mine/mine.js`
- Modify: `miniprogram/pages/mine/mine.wxml`
- Modify: `miniprogram/pages/mine/mine.wxss`
- Modify: `backend/start-backend.bat`
- Modify: `backend/README.md`
- Modify: `backend/.env.example`
- Modify: `miniprogram/pages/records/records.js`
- Modify: `miniprogram/pages/records/records.wxml`
- Modify: `miniprogram/pages/records/records.wxss`
- Modify: `docs/修改记录.md`

- [x] Add miniprogram API stubs and verify JS syntax before UI wiring.
- [x] Replace unrelated reminder/API/login/export sections in “我的” with basic profile summary and a Xiaomi health connection card, consistent with prior user request; move CSV/JSON export controls to the records page so export remains available.
- [x] Provide connect form, syncing state, last-sync summary, explicit unbind confirmation, and safe user-facing errors; do not log credentials.
- [x] Make BAT change to its own directory, check `uv` and `.env`, create `.env` from example when missing, run `uv sync`, then bind Uvicorn to `0.0.0.0:8000`; preserve readable startup errors.
- [x] Document `.env` feature flag and generated Fernet key command without placing a real key in tracked files; update `backend/.env.example` only.
- [x] Append modification history with date/iteration and run backend tests, compileall, JS syntax, JSON parse, WXML structure, `git diff --check`, and inspect final status to ensure existing user changes are untouched.

## Final Review

- Verify OpenAPI includes the four routes and no endpoint accepts a client-supplied `user_id`.
- Verify no schema operation modifies or deletes existing data; only new tables/indexes and append-only imports are present.
- Verify the lockfile and MIT attribution exist, the `.env` samples contain no real secrets, and no SQLite file is tracked.
- Report unverified live Xiaomi login explicitly; tests must use mock network and no real credentials.
