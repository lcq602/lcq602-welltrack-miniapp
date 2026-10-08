# WellTrack API 契约

服务运行后，交互式 OpenAPI 文档位于 `/docs`，机器可读契约位于 `/openapi.json`。除健康检查和创建会话外，接口均需 `Authorization: Bearer <token>`。用户标识只从服务端会话解析，客户端不能指定其他用户。

## 会话

| 方法与路径 | 用途 | 成功响应 |
| --- | --- | --- |
| `GET /health` | 存活检查 | `{"ok": true}` |
| `POST /api/v1/auth/local` | 本地开发创建匿名会话；需开启 `WELLTRACK_LOCAL_AUTH` | `token`、`configured` |
| `POST /api/v1/auth/wechat` | 服务端使用微信登录凭证换取会话 | `token`、`configured` |

## 资料与首页

| 方法与路径 | 用途 | 请求/响应要点 |
| --- | --- | --- |
| `GET /api/v1/profile` | 读取当前用户最新资料版本 | 身高、起始/目标体重、目标类型、日期、每周安排、提醒偏好 |
| `POST /api/v1/profile` | 新增一份资料/计划版本 | 必填 `height_cm`、`current_weight`、`target_weight`、`start_date`、`goal`；`target_date` 可省略或为 `null` |
| `GET /api/v1/dashboard` | 首页摘要 | 近 7 天记录和运动次数、当前/目标体重、BMI 参考、目标进度、待办、记录覆盖天数；连接小米后当前体重优先取最新小米读数 |

`goal` 可取 `weight_loss`（减重）、`fat_loss`（减脂）、`maintenance`（维持）或 `healthy_habits`（健康习惯）。目标与提醒变更通过新增资料版本保留历史，不覆盖旧版本。BMI 仅供参考，不用于疾病诊断。

## 日常与运动

| 方法与路径 | 用途 | 请求/响应要点 |
| --- | --- | --- |
| `GET /api/v1/records?start=YYYY-MM-DD&end=YYYY-MM-DD&limit=100&offset=0` | 按日期范围分页读取个人日常记录 | 默认读取近一年，单页最多 366 条 |
| `POST /api/v1/records` | 新增一条日常记录 | 日期、体重、腰围、饮食、饮水、睡眠、习惯和备注；同一用户同日重复新增返回 409 |
| `GET /api/v1/exercises?start=YYYY-MM-DD&end=YYYY-MM-DD` | 读取个人运动记录 | 默认近 30 天至未来一年，最多 500 条 |
| `POST /api/v1/exercises` | 新增一条运动记录 | 日期、类型、分钟数和备注 |

数值范围由 Pydantic 校验；超过边界或日期格式错误返回 422。读写 SQL 均带当前用户范围。当前版本按既有授权仅开放读取和新增，因此不提供日常/运动记录编辑、删除及 JSON 导入恢复接口。

## 分析与导出

| 方法与路径 | 用途 | 请求/响应要点 |
| --- | --- | --- |
| `GET /api/v1/analytics?period=week` | 周报 | 当前自然周的逐日体重/腰围、运动次数/分钟、平均睡眠、习惯记录率 |
| `GET /api/v1/analytics?period=month&month=YYYY-MM` | 月报 | 指定月或当前月的聚合；`month` 可省略 |
| `GET /api/v1/analytics?period=calendar&month=YYYY-MM` | 日历数据 | 返回指定自然月有记录的日期及同一组指标 |
| `GET /api/v1/export.csv` | 导出当前用户 CSV | 包含日常、运动和小米同步记录；公式前缀会转义 |
| `GET /api/v1/backup` | 下载 JSON 备份 | 带版本号的资料、日常、运动和小米只读记录；只导出不恢复 |

首页的 `record_days_7d` 表示过去七个自然日中存在日常记录的天数，`weekly_completion` 为该值除以 7 的百分比；不表示运动计划或习惯已经完成。

## 小米运动健康（本地实验接入）

此连接器基于非官方、未公开的小米健康接口，默认关闭，兼容性不保证。仅用于本机个人账号，只读同步；不实现 Root/ADB/Frida/Cookie 提取。启用前在后端 `.env` 设置 `MIHEALTH_ENABLED=true` 和有效的 Fernet `MIHEALTH_TOKEN_KEY`；密钥可用以下命令生成，必须保密并备份，否则重启后无法解密已保存会话：

```powershell
uv run python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

| 方法与路径 | 用途 | 请求/响应要点 |
| --- | --- | --- |
| `GET /api/v1/integrations/mihealth/status` | 读取当前用户连接状态 | 返回绑定、同步时间、导入数量和最新指标；如有未过期挑战，返回 challenge ID 与遮罩提示，不返回会话 |
| `POST /api/v1/integrations/mihealth/connect` | 连接用户自己的小米账号 | `username`、`password`；密码只用于当前请求，不保存或返回 |
| `POST /api/v1/integrations/mihealth/verify` | 完成短信/邮箱二次验证 | `challenge_id` 和本次收到的数字验证码；成功后建立绑定 |
| `POST /api/v1/integrations/mihealth/sync` | 手动同步本人健康摘要 | 可选 `days`，范围 1–90；幂等跳过已同步条目 |
| `DELETE /api/v1/integrations/mihealth/connection` | 移除当前用户绑定 | 只删除该用户加密会话，保留已导入读数 |

后端启动后每 60 秒会为已连接用户自动同步体重：先分批回填小米可提供的完整历史，分页游标加密保存在该用户会话中；回填完成后按最近采样时间回看 7 天并增量去重。后端关闭期间不会同步。首页和周/月分析优先展示对应日期最新的小米体重；原始手工记录与计划起始体重不被改写。小米要求短信或邮箱二次验证时，后端会启动验证并返回遮罩后的接收提示；验证码通过 `verify` 接口当次提交，不会写入数据库。SQLite 仅保存按用户隔离、Fernet 加密的短时挑战会话与上下文，10 分钟过期，最多验证 5 次；成功、过期或超限后挑战记录删除。此接口不应暴露到公网，也不应在聊天、日志或截图中粘贴小米密码、验证码或令牌。上游接口变更时可关闭 `MIHEALTH_ENABLED` 并继续使用手动记录。参考：Aaron-gx/mihealth-api（MIT，保留其许可与来源说明）；shkyyy18/mi_fitness_data_bridge（只参考本地优先/幂等架构，当前 AGPL-3.0-only 代码未复制或依赖）。

`connect` 普通登录成功返回 HTTP 200；账号要求二次验证时返回 HTTP 202 和 `challenge_id`、验证方式、遮罩提示及过期时间。`verify` 请求示例：`{"challenge_id":"<连接响应中的 ID>","code":"123456"}`。验证码错误返回 HTTP 422 并给出剩余次数；过期返回 HTTP 410；连续 5 次错误后挑战失效，需要重新发起连接。
