#!/usr/bin/env bash
# 本地/CI 通用打包: 生成小程序包、后端包、全量包到指定目录 (默认 dist/)
# 用法: bash scripts/package.sh [输出目录]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/dist}"
VERSION="$(date +%Y%m%d)-$(cd "$ROOT" && git rev-parse --short HEAD 2>/dev/null || echo dev)"
mkdir -p "$OUT"
cd "$ROOT"

# 小程序包: 微信开发者工具可直接导入 (根 project.config.json 已指向 miniprogram/)
rm -rf /tmp/wt-mini && mkdir -p /tmp/wt-mini
cp -r miniprogram /tmp/wt-mini/
cp project.config.json /tmp/wt-mini/
cp README.md /tmp/wt-mini/
(cd /tmp/wt-mini && zip -r -q "$OUT/welltrack-miniprogram-$VERSION.zip" .)

# 后端包: 不含 .venv、数据库、.env
rm -rf /tmp/wt-backend && mkdir -p /tmp/wt-backend
cp -r backend /tmp/wt-backend/
rm -rf /tmp/wt-backend/backend/.venv /tmp/wt-backend/backend/data /tmp/wt-backend/backend/.env \
       /tmp/wt-backend/backend/.pytest_cache
find /tmp/wt-backend -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true
(cd /tmp/wt-backend && zip -r -q "$OUT/welltrack-backend-$VERSION.zip" backend)

# 全量包: 小程序 + 后端 + 文档
rm -rf /tmp/wt-full && mkdir -p /tmp/wt-full/welltrack
cp -r miniprogram backend docs /tmp/wt-full/welltrack/
cp project.config.json README.md /tmp/wt-full/welltrack/
rm -rf /tmp/wt-full/welltrack/backend/.venv /tmp/wt-full/welltrack/backend/data \
       /tmp/wt-full/welltrack/backend/.env /tmp/wt-full/welltrack/backend/.pytest_cache
find /tmp/wt-full -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true
(cd /tmp/wt-full && zip -r -q "$OUT/welltrack-full-$VERSION.zip" welltrack)

echo "打包完成 (版本 $VERSION):"
ls -lh "$OUT"/*.zip
