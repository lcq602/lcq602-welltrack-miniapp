# WellTrack 个人健康管理小程序

本项目将前端和后端分为两个独立目录：

- `miniprogram/`：微信原生小程序。请在微信开发者工具中导入此目录。
- `backend/`：FastAPI、SQLite schema、uv 环境、锁文件和后端启动脚本。
- `docs/`：设计说明、实施计划与修改记录。

后端启动与接口文档见 [backend/README.md](backend/README.md) 和 [backend/API.md](backend/API.md)。

## 微信开发者工具

1. 可直接导入项目根目录（根配置会把源码指向 `miniprogram/`），或直接导入 `miniprogram/`；使用无 AppID/游客模式即可。
2. 在“详情 → 本地设置”勾选“不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书”。
3. 小程序默认 API 地址为 `https://qelora.art`，服务器需配置有效 HTTPS 证书，并将该域名反向代理到 FastAPI 后端的 8000 端口。微信开发者工具如果提示域名不合法，可在“详情 → 本地设置”开启本地调试的域名校验豁免；真机及正式版还需在小程序后台配置 `request` 合法域名。

小程序通过 HTTP API 访问后端，SQLite 文件只由后端读写。

## 数据写入范围

当前版本按已确认的安全范围只读与新增：资料/计划使用追加版本；每日记录和运动可新增、查询，不支持编辑、删除或备份导入。CSV 与 JSON 可导出。
