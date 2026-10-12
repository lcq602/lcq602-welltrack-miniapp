import asyncio
import csv
import hashlib
import io
import json
import logging
import secrets
import sqlite3
import urllib.parse
import urllib.request
from contextlib import asynccontextmanager, suppress
from datetime import date, datetime, timedelta
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Response
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from .config import CORS_ORIGINS, LOCAL_AUTH_ENABLED, MIHEALTH_ENABLED, MIHEALTH_TOKEN_KEY, WECHAT_APP_ID, WECHAT_APP_SECRET
from .db import initialize, read_connection, transaction
from .integrations.mihealth import MiHealthChallenge, MiHealthError, MiHealthVerificationError, connect_mihealth, decrypt_session, encrypt_session, read_mihealth_metrics, read_mihealth_weight_page, read_mihealth_weight_window, refresh_mihealth_session, validate_session_key, verify_mihealth_challenge
from .integrations.vendor.client import AuthError
from .schemas import ExerciseIn, MiHealthConnectIn, MiHealthSyncIn, MiHealthVerifyIn, ProfileIn, RecordIn, WechatLoginIn

MIHEALTH_WEIGHT_SYNC_INTERVAL_SECONDS = 60
MIHEALTH_BACKFILL_PAGES_PER_RUN = 20
MIHEALTH_INCREMENTAL_OVERLAP_DAYS = 7
_LOGGER = logging.getLogger(__name__)

@asynccontextmanager
async def lifespan(application):
    """服务启动时创建数据库表结构。"""
    initialize()
    sync_task = asyncio.create_task(mihealth_weight_sync_loop())
    try:
        yield
    finally:
        sync_task.cancel()
        with suppress(asyncio.CancelledError):
            await sync_task


app = FastAPI(title="WellTrack 健康管理 API", version="0.1.0", description="个人健康记录与体重管理接口；BMI 仅供参考。", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=CORS_ORIGINS, allow_methods=["GET", "POST", "DELETE"], allow_headers=["Authorization", "Content-Type"])

def user_id(authorization: str | None = Header(default=None)):
    """从服务端会话令牌解析用户，拒绝客户端传入 user_id。"""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "请先创建或恢复本地会话")
    digest = hashlib.sha256(authorization[7:].encode()).hexdigest()
    with read_connection() as conn:
        row = conn.execute("SELECT user_id FROM sessions WHERE token_hash=?", (digest,)).fetchone()
    if not row:
        raise HTTPException(401, "会话无效")
    return row["user_id"]


def as_profile(row):
    """把数据库资料行转换成 API 返回对象。"""
    if not row:
        return None
    result = dict(row)
    result["weekly_plan"] = json.loads(result.pop("weekly_plan") or "{}")
    result["reminder_settings"] = json.loads(result.pop("reminder_settings") or "{}")
    result["configured"] = result["height_cm"] is not None
    return result


def get_profile(uid):
    """读取当前用户最新的追加式资料版本。"""
    with read_connection() as conn:
        return as_profile(conn.execute("SELECT height_cm,current_weight,target_weight,start_date,goal,target_date,weekly_plan,reminder_settings FROM profile_versions WHERE user_id=? ORDER BY id DESC LIMIT 1", (uid,)).fetchone())


def record_out(row):
    """返回记录内容并隐藏内部用户标识。"""
    result = dict(row)
    result.pop("user_id", None)
    result["habits"] = json.loads(result["habits"] or "[]")
    return result


def _habits_list(value):
    """兼容解析 SQLite JSON 字符串和已解码的习惯数组。"""
    if isinstance(value, str):
        try:
            value = json.loads(value or "[]")
        except json.JSONDecodeError:
            return []
    return value if isinstance(value, list) else []


def _weight_recorded_ms(readings):
    """提取体重读数中的最大采样时间戳。"""
    stamps = []
    for item in readings:
        try:
            stamps.append(int(datetime.fromisoformat(item["recorded_at"]).timestamp() * 1000))
        except (KeyError, TypeError, ValueError, OverflowError):
            continue
    return max(stamps, default=0)


def _store_weight_sync_page(uid, session, state, readings, expected_cipher):
    """在同一事务内写入体重样本和加密游标，防止断点错位。"""
    session["_welltrack_weight_sync"] = state
    encrypted = encrypt_session(session, MIHEALTH_TOKEN_KEY)
    inserted = 0
    with transaction() as conn:
        saved = conn.execute(
            "UPDATE mihealth_connections SET encrypted_session=?,last_synced_at=CURRENT_TIMESTAMP "
            "WHERE user_id=? AND encrypted_session=?", (encrypted, uid, expected_cipher),
        )
        if saved.rowcount != 1:
            return 0, None
        for item in readings:
            cursor = conn.execute(
                "INSERT OR IGNORE INTO mihealth_imports "
                "(user_id,source_id,metric,recorded_at,value,unit,payload) VALUES(?,?,?,?,?,?,?)",
                (uid, item["source_id"], "weight", item["recorded_at"], item["value"],
                 item.get("unit", "kg"), json.dumps(item.get("payload", {}), ensure_ascii=False, separators=(",", ":"))),
            )
            inserted += cursor.rowcount
    return inserted, encrypted


def _refresh_weight_session(session):
    """刷新过期小米会话并保留 WellTrack 的体重同步游标。"""
    sync_state = session.get("_welltrack_weight_sync")
    refreshed = refresh_mihealth_session(session)
    if sync_state is not None:
        refreshed["_welltrack_weight_sync"] = sync_state
    return refreshed


def sync_connected_mihealth_weights_once():
    """为所有已绑定用户续跑历史回填或同步最近一周体重。"""
    if not MIHEALTH_ENABLED or not MIHEALTH_TOKEN_KEY:
        return {"users": 0, "imported": 0}
    validate_session_key(MIHEALTH_TOKEN_KEY)
    with read_connection() as conn:
        connections = conn.execute(
            "SELECT user_id,encrypted_session FROM mihealth_connections ORDER BY user_id"
        ).fetchall()
    total_imported = 0
    for connection in connections:
        uid = connection["user_id"]
        expected_cipher = connection["encrypted_session"]
        try:
            session = decrypt_session(expected_cipher, MIHEALTH_TOKEN_KEY)
            state = session.get("_welltrack_weight_sync") or {
                "backfill_complete": False, "last_recorded_ms": 0,
            }
            end_ms = int(datetime.now().timestamp() * 1000)
            if not state.get("backfill_complete"):
                state.setdefault("backfill_end_ms", end_ms)
                next_key = state.get("next_key")
                for _ in range(MIHEALTH_BACKFILL_PAGES_PER_RUN):
                    try:
                        readings, next_key, has_more = read_mihealth_weight_page(
                            session, state["backfill_end_ms"], next_key
                        )
                    except AuthError:
                        session = _refresh_weight_session(session)
                        state = session.get("_welltrack_weight_sync") or state
                        readings, next_key, has_more = read_mihealth_weight_page(
                            session, state["backfill_end_ms"], next_key
                        )
                    state["last_recorded_ms"] = max(
                        int(state.get("last_recorded_ms", 0)), _weight_recorded_ms(readings)
                    )
                    state["next_key"] = next_key
                    if not has_more:
                        state["backfill_complete"] = True
                        state.pop("next_key", None)
                        state.pop("backfill_end_ms", None)
                    inserted, updated_cipher = _store_weight_sync_page(
                        uid, session, state, readings, expected_cipher
                    )
                    if updated_cipher is None:
                        break
                    expected_cipher = updated_cipher
                    total_imported += inserted
                    if state["backfill_complete"]:
                        break
            else:
                latest_ms = int(state.get("last_recorded_ms", 0))
                overlap_ms = MIHEALTH_INCREMENTAL_OVERLAP_DAYS * 86_400_000
                start_ms = max(0, latest_ms - overlap_ms) if latest_ms else end_ms - overlap_ms
                try:
                    readings = read_mihealth_weight_window(session, start_ms, end_ms)
                except AuthError:
                    session = _refresh_weight_session(session)
                    state = session.get("_welltrack_weight_sync") or state
                    readings = read_mihealth_weight_window(session, start_ms, end_ms)
                state["last_recorded_ms"] = max(latest_ms, _weight_recorded_ms(readings))
                inserted, _ = _store_weight_sync_page(
                    uid, session, state, readings, expected_cipher
                )
                total_imported += inserted
        except Exception as exc:
            _LOGGER.warning("Automatic Xiaomi weight sync failed (%s)", type(exc).__name__)
    return {"users": len(connections), "imported": total_imported}


async def mihealth_weight_sync_loop():
    """后端运行期间每分钟执行一次只读体重同步。"""
    loop = asyncio.get_running_loop()
    while True:
        cycle_started = loop.time()
        try:
            await asyncio.to_thread(sync_connected_mihealth_weights_once)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            _LOGGER.warning("Automatic Xiaomi weight sync cycle failed (%s)", type(exc).__name__)
        elapsed = loop.time() - cycle_started
        await asyncio.sleep(max(0, MIHEALTH_WEIGHT_SYNC_INTERVAL_SECONDS - elapsed))


def _merge_mihealth_weights(records, imported_rows):
    """按日期合并小米测量，优先用于展示而不改写手工记录。"""
    by_date = {item["record_date"]: item for item in records}
    for row in imported_rows:
        day = row["record_date"]
        item = by_date.get(day)
        if item is None:
            item = {"record_date": day, "weight": None, "waist_cm": None,
                    "diet": "", "water_ml": None, "sleep_hours": None,
                    "habits": [], "note": ""}
            by_date[day] = item
        item["weight"] = row["value"]
        item["weight_source"] = "mihealth"
    for item in records:
        item.setdefault("weight_source", "manual" if item.get("weight") is not None else None)
    return [by_date[day] for day in sorted(by_date)]


def mihealth_status(uid):
    """只返回当前用户的连接状态和少量最新指标。"""
    key_ready = False
    if MIHEALTH_ENABLED and MIHEALTH_TOKEN_KEY:
        try:
            validate_session_key(MIHEALTH_TOKEN_KEY)
            key_ready = True
        except MiHealthError:
            pass
    with transaction() as conn:
        conn.execute("DELETE FROM mihealth_challenges WHERE user_id=? AND expires_at<=?",
                     (uid, int(datetime.now().timestamp())))
    with read_connection() as conn:
        connection = conn.execute(
            "SELECT linked_at,last_synced_at FROM mihealth_connections WHERE user_id=?", (uid,)
        ).fetchone()
        count = conn.execute(
            "SELECT COUNT(*) AS n FROM mihealth_imports WHERE user_id=?", (uid,)
        ).fetchone()["n"]
        rows = conn.execute(
            "SELECT metric,recorded_at,value,unit FROM mihealth_imports WHERE user_id=? AND metric<>'steps' AND metric<>'calories' "
            "ORDER BY recorded_at DESC LIMIT 200", (uid,)
        ).fetchall()
        step_total = conn.execute(
            "SELECT substr(recorded_at,1,10) AS day,SUM(value) AS value FROM mihealth_imports "
            "WHERE user_id=? AND metric='steps' GROUP BY substr(recorded_at,1,10) "
            "ORDER BY day DESC LIMIT 1", (uid,)
        ).fetchone()
        cal_total = conn.execute(
            "SELECT substr(recorded_at,1,10) AS day,SUM(value) AS value FROM mihealth_imports "
            "WHERE user_id=? AND metric='calories' GROUP BY substr(recorded_at,1,10) "
            "ORDER BY day DESC LIMIT 1", (uid,)
        ).fetchone()
        today_str = str(date.today())
        today_steps_row = conn.execute(
            "SELECT SUM(value) AS s FROM mihealth_imports WHERE user_id=? AND metric='steps' AND substr(recorded_at,1,10)=?",
            (uid, today_str),
        ).fetchone()
        today_cal_row = conn.execute(
            "SELECT SUM(value) AS s FROM mihealth_imports WHERE user_id=? AND metric='calories' AND substr(recorded_at,1,10)=?",
            (uid, today_str),
        ).fetchone()
        today_sleep_row = conn.execute(
            "SELECT MAX(value) AS s FROM mihealth_imports WHERE user_id=? AND metric='sleep_hours' AND substr(recorded_at,1,10)=?",
            (uid, today_str),
        ).fetchone()
        today_workout_row = conn.execute(
            "SELECT SUM(value) AS s FROM mihealth_imports WHERE user_id=? AND metric='workout_minutes' AND substr(recorded_at,1,10)=?",
            (uid, today_str),
        ).fetchone()
        pending = conn.execute(
            "SELECT challenge_id,encrypted_state,expires_at,attempts FROM mihealth_challenges "
            "WHERE user_id=? ORDER BY created_at DESC LIMIT 1", (uid,)
        ).fetchone()
    latest = {
        "weight": {"recorded_at": None, "value": None, "unit": "kg"},
        "sleep_hours": {"recorded_at": None, "value": None, "unit": "h"},
        "steps": {"recorded_at": None, "value": None, "unit": "steps"},
        "calories": {"recorded_at": None, "value": None, "unit": "kcal"},
        "workout_minutes": {"recorded_at": None, "value": None, "unit": "min"},
    }
    for row in rows:
        if row["metric"] in latest and latest[row["metric"]]["recorded_at"] is None:
            latest[row["metric"]] = {"recorded_at": row["recorded_at"], "value": row["value"], "unit": row["unit"]}
    if step_total and step_total["value"] is not None:
        latest["steps"] = {"recorded_at": step_total["day"], "value": int(step_total["value"]), "unit": "steps"}
    if cal_total and cal_total["value"] is not None:
        latest["calories"] = {"recorded_at": cal_total["day"], "value": round(float(cal_total["value"]), 1), "unit": "kcal"}
    today_steps = int(today_steps_row["s"]) if (today_steps_row and today_steps_row["s"] is not None) else (int(latest["steps"]["value"]) if latest["steps"]["value"] is not None else 0)
    today_calories = round(float(today_cal_row["s"]), 1) if (today_cal_row and today_cal_row["s"] is not None) else (round(float(latest["calories"]["value"]), 1) if latest["calories"]["value"] is not None else 0.0)
    today_sleep = round(float(today_sleep_row["s"]), 1) if (today_sleep_row and today_sleep_row["s"] is not None) else (round(float(latest["sleep_hours"]["value"]), 1) if latest["sleep_hours"]["value"] is not None else None)
    today_workout = round(float(today_workout_row["s"]), 1) if (today_workout_row and today_workout_row["s"] is not None) else (round(float(latest["workout_minutes"]["value"]), 1) if latest["workout_minutes"]["value"] is not None else 0.0)
    today_summary = {
        "steps": today_steps,
        "calories": today_calories,
        "sleep_hours": today_sleep,
        "workout_minutes": today_workout,
    }
    challenge = None
    if pending and pending["expires_at"] > int(datetime.now().timestamp()) and key_ready:
        try:
            challenge_state = decrypt_session(pending["encrypted_state"], MIHEALTH_TOKEN_KEY)
            challenge = {"challenge_id": pending["challenge_id"], "hint": challenge_state.get("hint", ""),
                         "method": challenge_state.get("method", "Phone"),
                         "attempts_remaining": max(0, 5 - pending["attempts"]),
                         "expires_at": pending["expires_at"]}
        except MiHealthError:
            challenge = None
    return {
        "enabled": MIHEALTH_ENABLED,
        "ready": key_ready,
        "connected": bool(connection),
        "linked_at": connection["linked_at"] if connection else None,
        "last_synced_at": connection["last_synced_at"] if connection else None,
        "import_count": count,
        "latest": latest,
        "today": today_summary,
        "challenge": challenge,
    }


def require_mihealth_enabled():
    """拒绝默认关闭或未配置密钥的非官方连接器操作。"""
    if not MIHEALTH_ENABLED:
        raise HTTPException(404, "小米运动健康连接未启用")
    if not MIHEALTH_TOKEN_KEY:
        raise HTTPException(503, "请先在后端 .env 设置小米会话加密密钥")
    try:
        validate_session_key(MIHEALTH_TOKEN_KEY)
    except MiHealthError:
        raise HTTPException(503, "小米会话加密密钥格式无效，请重新配置") from None


@app.get("/api/v1/integrations/mihealth/status")
def mihealth_connection_status(uid: str = Depends(user_id)):
    """读取当前用户的小米健康连接状态，不返回凭证。"""
    return mihealth_status(uid)


@app.post("/api/v1/integrations/mihealth/connect")
def mihealth_connect(payload: MiHealthConnectIn, uid: str = Depends(user_id)):
    """使用一次性账号密码连接当前用户自己的小米健康账号。"""
    require_mihealth_enabled()
    try:
        session = connect_mihealth(payload.username, payload.password)
        encrypted = encrypt_session(session, MIHEALTH_TOKEN_KEY)
    except MiHealthChallenge as exc:
        if not exc.state:
            raise HTTPException(428, str(exc)) from None
        challenge_id = secrets.token_urlsafe(32)
        expires_at = int(datetime.now().timestamp()) + 600
        encrypted_state = encrypt_session(exc.state, MIHEALTH_TOKEN_KEY)
        with transaction() as conn:
            conn.execute("DELETE FROM mihealth_challenges WHERE user_id=?", (uid,))
            conn.execute(
                "INSERT INTO mihealth_challenges(challenge_id,user_id,encrypted_state,expires_at) VALUES(?,?,?,?)",
                (challenge_id, uid, encrypted_state, expires_at),
            )
        return JSONResponse(status_code=202, content={"requires_verification": True,
            "challenge_id": challenge_id, "hint": exc.hint,
            "method": exc.state.get("method", "Phone"), "attempts_remaining": 5,
            "expires_at": expires_at})
    except MiHealthError as exc:
        raise HTTPException(502, str(exc)) from None
    with transaction() as conn:
        conn.execute(
            "INSERT INTO mihealth_connections(user_id,encrypted_session) VALUES(?,?) "
            "ON CONFLICT(user_id) DO UPDATE SET encrypted_session=excluded.encrypted_session, "
            "linked_at=CURRENT_TIMESTAMP,last_synced_at=NULL",
            (uid, encrypted),
        )
        conn.execute("DELETE FROM mihealth_challenges WHERE user_id=?", (uid,))
    return {"connected": True, "linked_at": datetime.now().isoformat(timespec="seconds")}


@app.post("/api/v1/integrations/mihealth/verify")
def mihealth_verify(payload: MiHealthVerifyIn, uid: str = Depends(user_id)):
    """使用一次性验证码完成当前用户的小米账号连接。"""
    require_mihealth_enabled()
    now = int(datetime.now().timestamp())
    expired = False
    exhausted = False
    with transaction() as conn:
        pending = conn.execute(
            "SELECT encrypted_state,expires_at,attempts FROM mihealth_challenges "
            "WHERE challenge_id=? AND user_id=?", (payload.challenge_id, uid)
        ).fetchone()
        if not pending:
            raise HTTPException(404, "验证请求不存在或已完成")
        if pending["expires_at"] <= now:
            conn.execute("DELETE FROM mihealth_challenges WHERE challenge_id=? AND user_id=?",
                         (payload.challenge_id, uid))
            expired = True
        elif pending["attempts"] >= 5:
            conn.execute("DELETE FROM mihealth_challenges WHERE challenge_id=? AND user_id=?",
                         (payload.challenge_id, uid))
            exhausted = True
        else:
            conn.execute("UPDATE mihealth_challenges SET attempts=attempts+1 WHERE challenge_id=? AND user_id=?",
                         (payload.challenge_id, uid))
            encrypted_state = pending["encrypted_state"]
            attempts = pending["attempts"] + 1
    if expired:
        raise HTTPException(410, "验证码已过期，请重新连接")
    if exhausted:
        raise HTTPException(429, "验证码尝试次数过多，请重新连接")
    try:
        state = decrypt_session(encrypted_state, MIHEALTH_TOKEN_KEY)
        session = verify_mihealth_challenge(state, payload.code)
        encrypted_session = encrypt_session(session, MIHEALTH_TOKEN_KEY)
    except MiHealthVerificationError:
        remaining = max(0, 5 - attempts)
        with transaction() as conn:
            if remaining == 0:
                conn.execute("DELETE FROM mihealth_challenges WHERE challenge_id=? AND user_id=?",
                             (payload.challenge_id, uid))
        if remaining == 0:
            raise HTTPException(429, "验证码尝试次数过多，请重新连接") from None
        raise HTTPException(422, f"验证码不正确，还可尝试 {remaining} 次") from None
    except MiHealthError as exc:
        raise HTTPException(502, str(exc)) from None
    with transaction() as conn:
        conn.execute(
            "INSERT INTO mihealth_connections(user_id,encrypted_session) VALUES(?,?) "
            "ON CONFLICT(user_id) DO UPDATE SET encrypted_session=excluded.encrypted_session, "
            "linked_at=CURRENT_TIMESTAMP,last_synced_at=NULL", (uid, encrypted_session),
        )
        conn.execute("DELETE FROM mihealth_challenges WHERE challenge_id=? AND user_id=?",
                     (payload.challenge_id, uid))
    return {"connected": True, "linked_at": datetime.now().isoformat(timespec="seconds")}


@app.post("/api/v1/integrations/mihealth/sync")
def mihealth_sync(payload: MiHealthSyncIn = MiHealthSyncIn(), uid: str = Depends(user_id)):
    """按用户主动同步最近至多 90 天的只读小米健康摘要。"""
    require_mihealth_enabled()
    with read_connection() as conn:
        connection = conn.execute(
            "SELECT encrypted_session FROM mihealth_connections WHERE user_id=?", (uid,)
        ).fetchone()
    if not connection:
        raise HTTPException(409, "请先绑定小米运动健康")
    try:
        session = decrypt_session(connection["encrypted_session"], MIHEALTH_TOKEN_KEY)
        end_ms = int(datetime.now().timestamp() * 1000)
        start_ms = end_ms - payload.days * 86_400_000
        try:
            readings = read_mihealth_metrics(session, start_ms, end_ms)
        except AuthError:
            sync_state = session.get("_welltrack_weight_sync")
            session = refresh_mihealth_session(session)
            if sync_state is not None:
                session["_welltrack_weight_sync"] = sync_state
            readings = read_mihealth_metrics(session, start_ms, end_ms)
    except MiHealthError as exc:
        raise HTTPException(502, str(exc)) from None
    except Exception:
        raise HTTPException(502, "小米运动健康暂时无法同步，请稍后重试") from None
    imported = 0
    synced_at = datetime.now().isoformat(timespec="seconds")
    with transaction() as conn:
        current = conn.execute(
            "SELECT encrypted_session FROM mihealth_connections WHERE user_id=?", (uid,)
        ).fetchone()
        if not current:
            raise HTTPException(409, "小米连接已解除，请重新绑定")
        current_session = decrypt_session(current["encrypted_session"], MIHEALTH_TOKEN_KEY)
        if current_session.get("_welltrack_weight_sync") is not None:
            session["_welltrack_weight_sync"] = current_session["_welltrack_weight_sync"]
        encrypted = encrypt_session(session, MIHEALTH_TOKEN_KEY)
        conn.execute(
            "UPDATE mihealth_connections SET encrypted_session=?,last_synced_at=CURRENT_TIMESTAMP "
            "WHERE user_id=?", (encrypted, uid),
        )
        for item in readings:
            cursor = conn.execute(
                "INSERT OR IGNORE INTO mihealth_imports "
                "(user_id,source_id,metric,recorded_at,value,unit,payload) VALUES(?,?,?,?,?,?,?)",
                (uid, item["source_id"], item["metric"], item["recorded_at"], item["value"],
                 item["unit"], json.dumps(item["payload"], ensure_ascii=False, separators=(",", ":"))),
            )
            imported += cursor.rowcount
    return {"imported": imported, "received": len(readings), "days": payload.days, "synced_at": synced_at}


@app.delete("/api/v1/integrations/mihealth/connection")
def mihealth_unbind(uid: str = Depends(user_id)):
    """解绑当前用户的小米会话但保留已导入的只读记录。"""
    with transaction() as conn:
        conn.execute("DELETE FROM mihealth_connections WHERE user_id=?", (uid,))
        conn.execute("DELETE FROM mihealth_challenges WHERE user_id=?", (uid,))
    return {"connected": False}

@app.get("/health")
def health():
    """返回存活探针结果。"""
    return {"ok": True}

@app.post("/api/v1/auth/local")
def local_session():
    """本地开发创建匿名用户和随机会话。"""
    if not LOCAL_AUTH_ENABLED:
        raise HTTPException(404, "本地登录已关闭")
    uid, token = secrets.token_hex(16), secrets.token_urlsafe(32)
    digest = hashlib.sha256(token.encode()).hexdigest()
    with transaction() as conn:
        conn.execute("INSERT INTO users(id) VALUES(?)", (uid,))
        conn.execute("INSERT INTO sessions(token_hash,user_id) VALUES(?,?)", (digest, uid))
    return {"token": token, "configured": False}

@app.post("/api/v1/auth/wechat")
def wechat_session(payload: WechatLoginIn):
    """由服务端用微信 AppID 和 Secret 校验登录 code 并签发会话。"""
    code = payload.code
    if not isinstance(code, str) or not code or len(code) > 256:
        raise HTTPException(422, "微信登录凭证无效")
    if not WECHAT_APP_ID or not WECHAT_APP_SECRET:
        raise HTTPException(503, "微信登录尚未配置；本地开发请使用本地会话")
    query = urllib.parse.urlencode({"appid": WECHAT_APP_ID, "secret": WECHAT_APP_SECRET, "js_code": code, "grant_type": "authorization_code"})
    try:
        with urllib.request.urlopen(f"https://api.weixin.qq.com/sns/jscode2session?{query}", timeout=8) as response:
            result = json.loads(response.read(65536))
    except Exception:
        raise HTTPException(502, "微信登录服务暂时不可用")
    openid = result.get("openid")
    if not openid or result.get("errcode"):
        raise HTTPException(401, "微信登录凭证校验失败")
    token = secrets.token_urlsafe(32)
    digest = hashlib.sha256(token.encode()).hexdigest()
    with transaction() as conn:
        row = conn.execute("SELECT id FROM users WHERE wechat_openid=?", (openid,)).fetchone()
        uid = row["id"] if row else secrets.token_hex(16)
        if not row:
            conn.execute("INSERT INTO users(id,wechat_openid) VALUES(?,?)", (uid, openid))
        conn.execute("INSERT INTO sessions(token_hash,user_id) VALUES(?,?)", (digest, uid))
    return {"token": token, "configured": get_profile(uid) is not None}

@app.get("/api/v1/profile")
def profile(uid: str = Depends(user_id)):
    """返回当前用户最新资料版本。"""
    return get_profile(uid)

@app.post("/api/v1/profile", status_code=201)
def save_profile(payload: ProfileIn, uid: str = Depends(user_id)):
    """以追加版本方式保存用户目标和每周计划。"""
    values = (payload.height_cm, payload.current_weight, payload.target_weight, str(payload.start_date),
              payload.goal, str(payload.target_date) if payload.target_date else None,
              json.dumps(payload.weekly_plan, ensure_ascii=False), json.dumps(payload.reminder_settings, ensure_ascii=False), uid)
    with transaction() as conn:
        conn.execute("INSERT INTO profile_versions(height_cm,current_weight,target_weight,start_date,goal,target_date,weekly_plan,reminder_settings,user_id) VALUES(?,?,?,?,?,?,?,?,?)", values)
    return get_profile(uid)

@app.get("/api/v1/dashboard")
def dashboard(uid: str = Depends(user_id)):
    """汇总首页最近七天趋势与目标进度，包含小米健康今日活力数据。"""
    p = get_profile(uid)
    today = date.today()
    start = today - timedelta(days=6)
    with read_connection() as conn:
        rows = conn.execute("SELECT * FROM daily_records WHERE user_id=? AND record_date BETWEEN ? AND ? ORDER BY record_date", (uid, str(start), str(today))).fetchall()
        exercise_count = conn.execute("SELECT COUNT(*) n FROM exercises WHERE user_id=? AND exercise_date BETWEEN ? AND ?", (uid, str(start), str(today))).fetchone()["n"]
        imported_weights = conn.execute(
            "SELECT substr(recorded_at,1,10) AS record_date,recorded_at,value FROM mihealth_imports "
            "WHERE user_id=? AND metric='weight' AND recorded_at>=? AND recorded_at<? ORDER BY recorded_at",
            (uid, f"{start}T00:00:00", f"{today + timedelta(days=1)}T00:00:00"),
        ).fetchall()
        latest_imported_weight = conn.execute(
            "SELECT recorded_at,value FROM mihealth_imports WHERE user_id=? AND metric='weight' "
            "ORDER BY recorded_at DESC,id DESC LIMIT 1", (uid,),
        ).fetchone()
    manual_records = [record_out(r) for r in rows]
    records = _merge_mihealth_weights(manual_records, imported_weights)
    latest_manual_weight = next((r["weight"] for r in reversed(manual_records) if r["weight"] is not None), None)
    latest_weight = latest_imported_weight["value"] if latest_imported_weight else (latest_manual_weight or (p["current_weight"] if p else None))
    weight_source = "mihealth" if latest_imported_weight else "manual" if latest_manual_weight is not None else "profile"
    bmi = round(latest_weight / (p["height_cm"] / 100) ** 2, 1) if latest_weight and p and p["height_cm"] else None
    delta = (p["current_weight"] - p["target_weight"]) if p and p["current_weight"] and p["target_weight"] else 0
    achieved = (p["current_weight"] - latest_weight) if p and latest_weight else 0
    progress = max(0, min(100, round(achieved / delta * 100))) if delta else 100
    goal = p["goal"] if p else "weight_loss"
    todos = {
        "weight_loss": ["记录体重趋势", "安排日常活动", "保持饮食与睡眠习惯"],
        "fat_loss": ["安排力量训练", "记录腰围", "安排有氧运动"],
        "maintenance": ["记录体重范围", "完成日常活动", "保持睡眠习惯"],
        "healthy_habits": ["安排运动", "记录饮食", "记录饮水与睡眠", "减少久坐"],
    }
    mi = mihealth_status(uid)
    return {
        "profile": p, "today": str(today), "current_weight": latest_weight, "current_weight_source": weight_source, "target_weight": p["target_weight"] if p else None,
        "progress": progress, "bmi": bmi, "bmi_label": "仅供参考，不用于诊断", "records": records,
        "exercise_count_7d": exercise_count, "record_days_7d": len(manual_records), "weekly_completion": min(100, round(len(manual_records) / 7 * 100)),
        "todos": todos[goal], "configured": bool(p and p["configured"]),
        "mihealth": {
            "connected": mi["connected"],
            "last_synced_at": mi["last_synced_at"],
            "today": mi["today"],
            "latest": mi["latest"],
        },
    }

@app.get("/api/v1/records")
def list_records(start: date | None = None, end: date | None = None, limit: int = Query(100, ge=1, le=366), offset: int = Query(0, ge=0), uid: str = Depends(user_id)):
    """按日期范围分页读取当前用户记录，包含并合并小米手环体重与活力指标。"""
    start, end = start or date.today() - timedelta(days=365), end or date.today()
    start_str, end_str = str(start), str(end)
    with read_connection() as conn:
        manual_rows = conn.execute(
            "SELECT * FROM daily_records WHERE user_id=? AND record_date BETWEEN ? AND ? ORDER BY record_date DESC",
            (uid, start_str, end_str),
        ).fetchall()
        mi_rows = conn.execute(
            "SELECT substr(recorded_at,1,10) AS day, metric, recorded_at, value, unit, payload "
            "FROM mihealth_imports "
            "WHERE user_id=? AND recorded_at>=? AND recorded_at<? "
            "ORDER BY recorded_at ASC",
            (uid, f"{start_str}T00:00:00", f"{end + timedelta(days=1)}T00:00:00"),
        ).fetchall()

    mi_by_day = {}
    for r in mi_rows:
        day = r["day"]
        if day not in mi_by_day:
            mi_by_day[day] = {
                "weight": None,
                "sleep_hours": None,
                "steps": 0,
                "calories": 0.0,
                "workout_minutes": 0.0,
                "has_data": False,
            }
        metric = r["metric"]
        val = r["value"]
        if metric == "weight" and val is not None:
            mi_by_day[day]["weight"] = val
            mi_by_day[day]["has_data"] = True
        elif metric == "sleep_hours" and val is not None:
            mi_by_day[day]["sleep_hours"] = val
            mi_by_day[day]["has_data"] = True
        elif metric == "steps" and val is not None:
            mi_by_day[day]["steps"] += int(val)
            mi_by_day[day]["has_data"] = True
        elif metric == "calories" and val is not None:
            mi_by_day[day]["calories"] += round(float(val), 1)
            mi_by_day[day]["has_data"] = True
        elif metric == "workout_minutes" and val is not None:
            mi_by_day[day]["workout_minutes"] += round(float(val), 1)
            mi_by_day[day]["has_data"] = True

    by_day = {}
    for r in manual_rows:
        rec = record_out(r)
        day = rec["record_date"]
        mi = mi_by_day.get(day)
        rec["mihealth_weight"] = mi["weight"] if mi else None
        rec["mihealth_sleep_hours"] = mi["sleep_hours"] if mi else None
        rec["steps"] = mi["steps"] if mi and mi["steps"] > 0 else None
        rec["calories"] = round(mi["calories"], 1) if mi and mi["calories"] > 0 else None
        rec["workout_minutes"] = round(mi["workout_minutes"], 1) if mi and mi["workout_minutes"] > 0 else None
        rec["is_mihealth_only"] = False

        if rec.get("weight") is not None:
            rec["weight_source"] = "manual"
        elif mi and mi["weight"] is not None:
            rec["weight"] = mi["weight"]
            rec["weight_source"] = "mihealth"
        else:
            rec["weight_source"] = None

        if rec.get("sleep_hours") is not None:
            rec["sleep_source"] = "manual"
        elif mi and mi["sleep_hours"] is not None:
            rec["sleep_hours"] = mi["sleep_hours"]
            rec["sleep_source"] = "mihealth"
        else:
            rec["sleep_source"] = None

        by_day[day] = rec

    for day, mi in mi_by_day.items():
        if day not in by_day and mi["has_data"]:
            by_day[day] = {
                "id": None,
                "record_date": day,
                "weight": mi["weight"],
                "weight_source": "mihealth" if mi["weight"] is not None else None,
                "mihealth_weight": mi["weight"],
                "waist_cm": None,
                "diet": "",
                "water_ml": None,
                "sleep_hours": mi["sleep_hours"],
                "sleep_source": "mihealth" if mi["sleep_hours"] is not None else None,
                "mihealth_sleep_hours": mi["sleep_hours"],
                "habits": [],
                "note": "",
                "steps": mi["steps"] if mi["steps"] > 0 else None,
                "calories": round(mi["calories"], 1) if mi["calories"] > 0 else None,
                "workout_minutes": round(mi["workout_minutes"], 1) if mi["workout_minutes"] > 0 else None,
                "is_mihealth_only": True,
            }

    all_records = sorted(by_day.values(), key=lambda x: x["record_date"], reverse=True)
    return all_records[offset:offset + limit]

@app.post("/api/v1/records", status_code=201)
def create_record(payload: RecordIn, uid: str = Depends(user_id)):
    """新建当日健康记录，同一用户同一天仅保留一条。"""
    try:
        with transaction() as conn:
            cursor = conn.execute("INSERT INTO daily_records(user_id,record_date,weight,waist_cm,diet,water_ml,sleep_hours,habits,note) VALUES(?,?,?,?,?,?,?,?,?)",
                (uid, str(payload.record_date), payload.weight, payload.waist_cm, payload.diet, payload.water_ml, payload.sleep_hours, json.dumps(payload.habits, ensure_ascii=False), payload.note))
            row = conn.execute("SELECT * FROM daily_records WHERE id=? AND user_id=?", (cursor.lastrowid, uid)).fetchone()
    except sqlite3.IntegrityError:
        raise HTTPException(409, "该日期已有记录，请编辑现有记录")
    return record_out(row)

@app.get("/api/v1/exercises")
def list_exercises(start: date | None = None, end: date | None = None, uid: str = Depends(user_id)):
    """读取当前用户指定日期范围内的运动安排。"""
    start, end = start or date.today() - timedelta(days=30), end or date.today() + timedelta(days=365)
    with read_connection() as conn:
        rows = conn.execute("SELECT id,exercise_date,kind,duration_minutes,note FROM exercises WHERE user_id=? AND exercise_date BETWEEN ? AND ? ORDER BY exercise_date DESC,id DESC LIMIT 500", (uid, str(start), str(end))).fetchall()
    return [dict(r) for r in rows]

@app.post("/api/v1/exercises", status_code=201)
def create_exercise(payload: ExerciseIn, uid: str = Depends(user_id)):
    """新增一条当前用户运动安排。"""
    with transaction() as conn:
        cur = conn.execute("INSERT INTO exercises(user_id,exercise_date,kind,duration_minutes,note) VALUES(?,?,?,?,?)", (uid, str(payload.exercise_date), payload.kind, payload.duration_minutes, payload.note))
        row = conn.execute("SELECT * FROM exercises WHERE id=? AND user_id=?", (cur.lastrowid, uid)).fetchone()
    result = dict(row)
    result.pop("user_id", None)
    return result

@app.get("/api/v1/analytics")
def analytics(period: str = Query("week", pattern="^(week|month|calendar)$"), month: str | None = Query(default=None, min_length=7, max_length=7, pattern="^\\d{4}-(0[1-9]|1[0-2])$"), uid: str = Depends(user_id)):
    """按周或月聚合体重、腰围、运动、步数、卡路里和睡眠数据。"""
    anchor = date.fromisoformat(f"{month}-01") if month else date.today()
    if period == "week":
        start = anchor - timedelta(days=anchor.weekday())
        end = start + timedelta(days=6)
    else:
        start = anchor.replace(day=1)
        end = (start.replace(day=28) + timedelta(days=4)).replace(day=1) - timedelta(days=1)
    with read_connection() as conn:
        rows = conn.execute("SELECT record_date,weight,waist_cm,sleep_hours,habits FROM daily_records WHERE user_id=? AND record_date BETWEEN ? AND ? ORDER BY record_date", (uid, str(start), str(end))).fetchall()
        imported_weights = conn.execute(
            "SELECT substr(recorded_at,1,10) AS record_date,recorded_at,value FROM mihealth_imports "
            "WHERE user_id=? AND metric='weight' AND recorded_at>=? AND recorded_at<? ORDER BY recorded_at",
            (uid, f"{start}T00:00:00", f"{end + timedelta(days=1)}T00:00:00"),
        ).fetchall()
        mi_metrics = conn.execute(
            "SELECT substr(recorded_at,1,10) AS day, metric, SUM(value) AS total_val, MAX(value) AS max_val "
            "FROM mihealth_imports "
            "WHERE user_id=? AND metric IN ('steps', 'calories', 'sleep_hours', 'workout_minutes') "
            "AND recorded_at>=? AND recorded_at<? "
            "GROUP BY substr(recorded_at,1,10), metric",
            (uid, f"{start}T00:00:00", f"{end + timedelta(days=1)}T00:00:00"),
        ).fetchall()
        ex = conn.execute("SELECT COUNT(*) n,SUM(duration_minutes) minutes FROM exercises WHERE user_id=? AND exercise_date BETWEEN ? AND ?", (uid, str(start), str(end))).fetchone()

    values = [dict(r) for r in rows]
    values = _merge_mihealth_weights(values, imported_weights)
    by_day_metric = {}
    for r in mi_metrics:
        day = r["day"]
        if day not in by_day_metric:
            by_day_metric[day] = {}
        by_day_metric[day][r["metric"]] = (r["total_val"], r["max_val"])

    existing_days = {r["record_date"]: r for r in values}
    for day, metrics in by_day_metric.items():
        if day not in existing_days:
            item = {"record_date": day, "weight": None, "waist_cm": None, "sleep_hours": None, "habits": []}
            existing_days[day] = item
            values.append(item)
    values.sort(key=lambda r: r["record_date"])

    for r in values:
        day = r["record_date"]
        mets = by_day_metric.get(day, {})
        r["steps"] = int(mets["steps"][0]) if "steps" in mets and mets["steps"][0] is not None else 0
        r["calories"] = round(float(mets["calories"][0]), 1) if "calories" in mets and mets["calories"][0] is not None else 0.0
        if r.get("sleep_hours") is None and "sleep_hours" in mets:
            r["sleep_hours"] = round(float(mets["sleep_hours"][1]), 1)
        r["workout_minutes"] = round(float(mets["workout_minutes"][0]), 1) if "workout_minutes" in mets and mets["workout_minutes"][0] is not None else 0.0

    sleeps = [r["sleep_hours"] for r in values if r.get("sleep_hours") is not None]
    steps_list = [r["steps"] for r in values if r.get("steps")]
    cals_list = [r["calories"] for r in values if r.get("calories")]
    total_steps = sum(steps_list)
    total_calories = round(sum(cals_list), 1)

    return {
        "period": period, "start": str(start), "end": str(end),
        "days": [{**r, "habits": _habits_list(r["habits"])} for r in values],
        "exercise_count": ex["n"] or 0, "exercise_minutes": ex["minutes"] or 0,
        "average_sleep": round(sum(sleeps) / len(sleeps), 1) if sleeps else None,
        "habit_completion": round(sum(bool(_habits_list(r["habits"])) for r in values) / max(1, (end-start).days+1) * 100),
        "total_steps": total_steps,
        "average_steps": int(total_steps / len(steps_list)) if steps_list else 0,
        "total_calories": total_calories,
        "average_calories": round(total_calories / len(cals_list), 1) if cals_list else 0.0,
    }

@app.get("/api/v1/export.csv")
def export_csv(uid: str = Depends(user_id)):
    """导出当前用户 CSV 并防止表格公式注入。"""
    with read_connection() as conn:
        rows = conn.execute("SELECT record_date,weight,waist_cm,diet,water_ml,sleep_hours,habits,note FROM daily_records WHERE user_id=? ORDER BY record_date", (uid,)).fetchall()
        exercises = conn.execute("SELECT exercise_date,kind,duration_minutes,note FROM exercises WHERE user_id=? ORDER BY exercise_date", (uid,)).fetchall()
        mihealth = conn.execute(
            "SELECT metric,recorded_at,value,unit,payload FROM mihealth_imports WHERE user_id=? ORDER BY recorded_at",
            (uid,),
        ).fetchall()
    output = io.StringIO(newline="")
    writer = csv.writer(output)
    writer.writerow(["记录类型", "日期", "体重kg", "腰围cm", "饮食", "饮水ml", "睡眠小时", "习惯", "运动类型", "运动分钟", "卡路里kcal", "步数", "备注"])
    def safe(value):
        """为可能被表格软件执行的 CSV 文本添加安全前缀。"""
        text = "" if value is None else str(value)
        return "'" + text if text.lstrip().startswith(("=", "+", "-", "@")) else text
    for r in rows:
        writer.writerow(["日常", safe(r["record_date"]), safe(r["weight"]), safe(r["waist_cm"]), safe(r["diet"]), safe(r["water_ml"]), safe(r["sleep_hours"]), safe(r["habits"]), "", "", "", "", safe(r["note"])])
    for r in exercises:
        writer.writerow(["运动", safe(r["exercise_date"]), "", "", "", "", "", "", safe(r["kind"]), safe(r["duration_minutes"]), "", "", safe(r["note"])])
    for row in mihealth:
        payload = json.loads(row["payload"] or "{}")
        day = row["recorded_at"][:10]
        metric = row["metric"]
        weight = row["value"] if metric == "weight" else ""
        sleep = row["value"] if metric == "sleep_hours" else ""
        calories = row["value"] if metric == "calories" else ""
        steps = row["value"] if metric == "steps" else ""
        kind = payload.get("kind", metric)
        minutes = row["value"] if metric == "workout_minutes" else ""
        note = f"{row['value']} {row['unit']}" if metric in ("steps", "calories") else "小米运动健康只读同步"
        writer.writerow(["小米同步", safe(day), safe(weight), "", "", "", safe(sleep), "", safe(kind), safe(minutes), safe(calories), safe(steps), safe(note)])
    return Response(output.getvalue(), media_type="text/csv; charset=utf-8", headers={"Content-Disposition": "attachment; filename=welltrack-records.csv"})

@app.get("/api/v1/backup")
def backup(uid: str = Depends(user_id)):
    """导出当前用户的版本化 JSON 备份。"""
    with read_connection() as conn:
        records = [record_out(r) for r in conn.execute("SELECT * FROM daily_records WHERE user_id=? ORDER BY record_date", (uid,))]
        exercises = [dict(r) for r in conn.execute("SELECT exercise_date,kind,duration_minutes,note FROM exercises WHERE user_id=? ORDER BY exercise_date", (uid,))]
        mihealth = [dict(r) for r in conn.execute(
            "SELECT source_id,metric,recorded_at,value,unit,payload FROM mihealth_imports WHERE user_id=? ORDER BY recorded_at",
            (uid,),
        )]
    for item in mihealth:
        item["payload"] = json.loads(item["payload"] or "{}")
    return {"version": 1, "profile": get_profile(uid), "records": records, "exercises": exercises, "mihealth_data": mihealth}

