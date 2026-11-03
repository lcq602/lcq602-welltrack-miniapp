#!/usr/bin/env python3
"""通过 Cloudflare REST API 部署 WellTrack 后端 (D1 + Worker + Workers AI 绑定)。
令牌只从环境变量 CLOUDFLARE_API_TOKEN 读取, 不落盘、不打印。
用法: CLOUDFLARE_API_TOKEN=*** python3 deploy_api.py [account_id]
"""
import json
import os
import sys
import urllib.request
import uuid as uuidlib

API = "https://api.cloudflare.com/client/v4"
TOKEN = os.environ.get("CLOUDFLARE_API_TOKEN", "")
ACCOUNT = sys.argv[1] if len(sys.argv) > 1 else "bc311c978850dbf67704d43979335e1b"
BASE = os.path.dirname(os.path.abspath(__file__))


def call(method, path, payload=None, raw=None, content_type="application/json"):
    data = raw if raw is not None else (json.dumps(payload).encode() if payload is not None else None)
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:800]
        raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {body}")


def main():
    if not TOKEN:
        print("缺少 CLOUDFLARE_API_TOKEN")
        return 1
    verify = call("GET", f"/accounts/{ACCOUNT}/tokens/verify")
    print("token 状态:", verify.get("result", {}).get("status"))

    # 1) D1: 已有则复用
    dbs = call("GET", f"/accounts/{ACCOUNT}/d1/database?name=welltrack-db")
    results = dbs.get("result", [])
    if results:
        db_id = results[0]["uuid"]
        print("复用已有 D1:", db_id)
    else:
        created = call("POST", f"/accounts/{ACCOUNT}/d1/database", {"name": "welltrack-db"})
        db_id = created["result"]["uuid"]
        print("已创建 D1 welltrack-db:", db_id)

    # 2) 表结构 (逐条执行, IF NOT EXISTS 保证幂等)
    raw_schema = open(os.path.join(BASE, "schema.sql"), encoding="utf-8").read()
    schema = "\n".join(ln for ln in raw_schema.splitlines() if not ln.strip().startswith("--"))
    statements = [s.strip() for s in schema.split(";") if s.strip()]
    for st in statements:
        call("POST", f"/accounts/{ACCOUNT}/d1/database/{db_id}/query", {"sql": st})
    print(f"表结构已执行 ({len(statements)} 条)")

    # 3) 上传 Worker (模块格式 + 绑定)
    worker_src = open(os.path.join(BASE, "worker.js"), encoding="utf-8").read()
    metadata = {
        "main_module": "worker.js",
        "compatibility_date": "2025-01-01",
        "bindings": [
            {"type": "d1", "name": "DB", "id": db_id},
            {"type": "ai", "name": "AI"},
            {"type": "plain_text", "name": "MIHEALTH_ENABLED", "text": "false"},
            {"type": "plain_text", "name": "AI_MODEL", "text": "@cf/meta/llama-3.3-70b-instruct-fp8-fast"},
        ],
        "observability": {"enabled": True},
    }
    boundary = "----wt" + uuidlib.uuid4().hex
    body = b""
    body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n".encode()
    body += json.dumps(metadata).encode() + b"\r\n"
    body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\nContent-Type: application/javascript+module\r\n\r\n".encode()
    body += worker_src.encode() + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    call("PUT", f"/accounts/{ACCOUNT}/workers/scripts/welltrack-api", raw=body,
         content_type=f"multipart/form-data; boundary={boundary}")
    print("Worker welltrack-api 已上传")

    # 4) 开启 workers.dev 子域
    sub = call("POST", f"/accounts/{ACCOUNT}/workers/scripts/welltrack-api/subdomain", {"enabled": True})
    print("子域开启结果:", sub.get("result"))
    print("DEPLOY_OK db_id=" + db_id)
    return 0


if __name__ == "__main__":
    sys.exit(main())
