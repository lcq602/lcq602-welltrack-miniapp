# WellTrack 安卓 App (原生版)

由微信小程序同构转化的**原生 Android 应用**（Java + 框架 API，无 WebView 套壳、无第三方依赖）：
页面结构、文案、主题（Serene Balance：翠绿 #059669 / 底 #f6f8fb / 白卡片）与小程序保持一致，
底部 5 个 Tab 为 首页 / 计划 / 记录 / 趋势 / 我的，运动页以覆盖层打开。
数据仍走原 FastAPI 后端（默认 `https://qelora.art`，可在「我的」页修改接口地址），
登录会话方式与小程序 `utils/api.js` 等价（本地匿名会话 + 401 自动重建）。

## 结构

```
android/
├── settings.gradle / build.gradle / app/build.gradle   # 标准 Gradle 工程
└── app/src/main/
    ├── AndroidManifest.xml          # 包名 art.qelora.welltrack
    ├── java/art/qelora/welltrack/
    │   ├── MainActivity.java        # Tab 导航 + 覆盖页
    │   ├── api/ApiClient.java       # 接口层 (对应小程序 utils/api.js)
    │   ├── ui/                      # 主题控件工厂 + 页面基类
    │   └── pages/                   # Index/Plan/Records/Exercise/Analysis/Mine 六个页面
    └── res/                         # 图标 (五档 mipmap) / Tab 矢量图标 / 主题
```

## 构建

方式一（推荐）：用 Android Studio 打开 `android/` 目录，或 `./gradlew :app:assembleRelease`（需自备签名配置）。

方式二（离线脚本，与 CI 同款）：仓库根目录执行，需要 JDK 17 与 Android SDK（build-tools 34 / platform 34）：

```bash
export WELLTRACK_KEYSTORE=~/welltrack.keystore   # 不存在时脚本会生成
export WELLTRACK_KS_PASS='你的keystore口令'       # 只走环境变量, 不要写进仓库
bash scripts/build-apk.sh                        # 产物 dist/welltrack.apk
```

GitHub Actions 的 `build.yml` 里有 `android-apk` 任务：每次推送自动构建 APK（CI 内生成临时签名）并上传为构建产物。

## 说明

- 与小程序共用同一后端与数据，两个端可并行使用；小程序目录 `miniprogram/` 保持不变。
- 升级版本时必须沿用同一 keystore 签名，否则无法覆盖安装。
- 小米运动健康为实验功能，依赖后端开启连接器并设置加密密钥（见 backend/README.md）。
