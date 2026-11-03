# WellTrack 个人健康管理小程序

本项目将前端和后端分为两个独立目录：

- `miniprogram/`：微信原生小程序。请在微信开发者工具中导入此目录。
- `backend/`：FastAPI、SQLite schema、uv 环境、锁文件和后端启动脚本。
- `docs/`：设计说明、实施计划与修改记录。

后端启动与接口文档见 [backend/README.md](backend/README.md) 和 [backend/API.md](backend/API.md)。

## 安卓 App (原生版)

`android/` 是由小程序同构转化的原生 Android 应用 (非套壳): 页面结构、文案与主题与小程序一致，
共用同一 FastAPI 后端。构建方式见 [android/README.md](android/README.md)，
GitHub Actions 也会自动构建 APK 并提供下载 (构建产物 `welltrack-apk`)。

## 微信开发者工具

1. 可直接导入项目根目录（根配置会把源码指向 `miniprogram/`），或直接导入 `miniprogram/`；使用无 AppID/游客模式即可。
2. 在“详情 → 本地设置”勾选“不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书”。
3. 小程序默认 API 地址为 `https://qelora.art`，服务器需配置有效 HTTPS 证书，并将该域名反向代理到 FastAPI 后端的 8000 端口。微信开发者工具如果提示域名不合法，可在“详情 → 本地设置”开启本地调试的域名校验豁免；真机及正式版还需在小程序后台配置 `request` 合法域名。

小程序通过 HTTP API 访问后端，SQLite 文件只由后端读写。

## 自动化打包 (GitHub Actions)

- 每次推送到 `main` 或提 PR，`.github/workflows/build.yml` 会自动跑后端测试 (uv + pytest)、
  小程序 JS/JSON 检查，并把小程序包、后端包、全量包作为构建产物 (Artifacts) 提供下载，保留 30 天。
- 打 `v*` 标签 (如 `v1.0.0`) 发布时，压缩包会自动附到 GitHub Releases。
- 本地打包与 CI 用同一个脚本：`bash scripts/package.sh [输出目录]`。
- 小程序正式上传 (可选)：在仓库 Secrets 配置 `WECHAT_APPID`、`WECHAT_PRIVATE_KEY` 后，
  手动运行 `.github/workflows/upload-miniprogram.yml` 即可用 miniprogram-ci 上传体验版；
  还需在微信公众平台把 GitHub 的出口 IP 加入上传白名单。未配置 Secret 时该流程自动跳过。

## 数据写入范围

当前版本按已确认的安全范围只读与新增：资料/计划使用追加版本；每日记录和运动可新增、查询，不支持编辑、删除或备份导入。CSV 与 JSON 可导出。
