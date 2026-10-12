#!/usr/bin/env bash
set -Eeuo pipefail

BACKEND_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$BACKEND_DIR"

if ! command -v uv >/dev/null 2>&1; then
  echo "[ERROR] uv 未安装。请先安装 uv，再重新运行此脚本。" >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  if [[ ! -f .env.example ]]; then
    echo "[ERROR] 找不到 .env.example，无法生成配置文件。" >&2
    exit 1
  fi
  cp .env.example .env
  echo "已从 .env.example 创建 .env。请先编辑配置后再次运行。"
  exit 1
fi

if grep -Eiq '^WELLTRACK_LOCAL_AUTH=(true|1)$' .env; then
  echo "[WARN] WELLTRACK_LOCAL_AUTH 已开启，这是匿名本地开发登录；不要将该配置直接用于公网生产环境。" >&2
fi

echo "正在同步 Python 依赖..."
uv sync --locked --no-dev

echo "启动 WellTrack API：0.0.0.0:8000"
exec uv run --no-sync uvicorn app.main:app --host 0.0.0.0 --port 8000 --env-file .env
