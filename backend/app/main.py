import csv
import hashlib
import io
import json
import secrets
import sqlite3
import urllib.parse
import urllib.request
from contextlib import asynccontextmanager
from datetime import date, timedelta
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Response
from fastapi.middleware.cors import CORSMiddleware
from .config import CORS_ORIGINS, LOCAL_AUTH_ENABLED, WECHAT_APP_ID, WECHAT_APP_SECRET
from .db import initialize, read_connection, transaction
from .schemas import ExerciseIn, ProfileIn, RecordIn, WechatLoginIn

@asynccontextmanager
async def lifespan(application):
    """服务启动时创建数据库表结构。"""
    initialize()
    yield


app = FastAPI(title="WellTrack 健康管理 API", version="0.1.0", description="个人健康记录与体重管理接口；BMI 仅供参考。", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=CORS_ORIGINS, allow_methods=["GET", "POST"], allow_headers=["Authorization", "Content-Type"])

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
    """汇总首页最近七天趋势与目标进度。"""
    p = get_profile(uid)
    today = date.today()
    start = today - timedelta(days=6)
    with read_connection() as conn:
        rows = conn.execute("SELECT * FROM daily_records WHERE user_id=? AND record_date BETWEEN ? AND ? ORDER BY record_date", (uid, str(start), str(today))).fetchall()
        done = conn.execute("SELECT COUNT(*) n FROM daily_records WHERE user_id=? AND record_date=? AND (weight IS NOT NULL OR water_ml IS NOT NULL OR sleep_hours IS NOT NULL OR length(habits)>2)", (uid, str(today))).fetchone()["n"]
        exercise_count = conn.execute("SELECT COUNT(*) n FROM exercises WHERE user_id=? AND exercise_date BETWEEN ? AND ?", (uid, str(start), str(today))).fetchone()["n"]
    records = [record_out(r) for r in rows]
    latest_weight = next((r["weight"] for r in reversed(records) if r["weight"] is not None), None) or (p["current_weight"] if p else None)
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
    return {"profile": p, "today": str(today), "current_weight": latest_weight, "target_weight": p["target_weight"] if p else None,
            "progress": progress, "bmi": bmi, "bmi_label": "仅供参考，不用于诊断", "records": records,
            "exercise_count_7d": exercise_count, "weekly_completion": min(100, round(done / 7 * 100)),
            "todos": todos[goal], "configured": bool(p and p["configured"])}

@app.get("/api/v1/records")
def list_records(start: date | None = None, end: date | None = None, limit: int = Query(100, ge=1, le=366), offset: int = Query(0, ge=0), uid: str = Depends(user_id)):
    """按日期范围分页读取当前用户记录。"""
    start, end = start or date.today() - timedelta(days=365), end or date.today()
    with read_connection() as conn:
        rows = conn.execute("SELECT * FROM daily_records WHERE user_id=? AND record_date BETWEEN ? AND ? ORDER BY record_date DESC LIMIT ? OFFSET ?", (uid, str(start), str(end), limit, offset)).fetchall()
    return [record_out(r) for r in rows]

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
    """按周或月聚合体重、腰围、运动和睡眠数据。"""
    anchor = date.fromisoformat(f"{month}-01") if month else date.today()
    if period == "week":
        start = anchor - timedelta(days=anchor.weekday())
        end = start + timedelta(days=6)
    else:
        start = anchor.replace(day=1)
        end = (start.replace(day=28) + timedelta(days=4)).replace(day=1) - timedelta(days=1)
    with read_connection() as conn:
        rows = conn.execute("SELECT record_date,weight,waist_cm,sleep_hours,habits FROM daily_records WHERE user_id=? AND record_date BETWEEN ? AND ? ORDER BY record_date", (uid, str(start), str(end))).fetchall()
        ex = conn.execute("SELECT COUNT(*) n,SUM(duration_minutes) minutes FROM exercises WHERE user_id=? AND exercise_date BETWEEN ? AND ?", (uid, str(start), str(end))).fetchone()
    values = [dict(r) for r in rows]
    sleeps = [r["sleep_hours"] for r in values if r["sleep_hours"] is not None]
    return {"period": period, "start": str(start), "end": str(end), "days": [{**r, "habits": json.loads(r["habits"] or "[]")} for r in values],
            "exercise_count": ex["n"] or 0, "exercise_minutes": ex["minutes"] or 0,
            "average_sleep": round(sum(sleeps) / len(sleeps), 1) if sleeps else None,
            "habit_completion": round(sum(bool(json.loads(r["habits"] or "[]")) for r in values) / max(1, (end-start).days+1) * 100)}

@app.get("/api/v1/export.csv")
def export_csv(uid: str = Depends(user_id)):
    """导出当前用户 CSV 并防止表格公式注入。"""
    with read_connection() as conn:
        rows = conn.execute("SELECT record_date,weight,waist_cm,diet,water_ml,sleep_hours,habits,note FROM daily_records WHERE user_id=? ORDER BY record_date", (uid,)).fetchall()
        exercises = conn.execute("SELECT exercise_date,kind,duration_minutes,note FROM exercises WHERE user_id=? ORDER BY exercise_date", (uid,)).fetchall()
    output = io.StringIO(newline="")
    writer = csv.writer(output)
    writer.writerow(["记录类型", "日期", "体重kg", "腰围cm", "饮食", "饮水ml", "睡眠小时", "习惯", "运动类型", "运动分钟", "备注"])
    def safe(value):
        """为可能被表格软件执行的 CSV 文本添加安全前缀。"""
        text = "" if value is None else str(value)
        return "'" + text if text.lstrip().startswith(("=", "+", "-", "@")) else text
    for r in rows:
        writer.writerow(["日常", safe(r["record_date"]), safe(r["weight"]), safe(r["waist_cm"]), safe(r["diet"]), safe(r["water_ml"]), safe(r["sleep_hours"]), safe(r["habits"]), "", "", safe(r["note"])])
    for r in exercises:
        writer.writerow(["运动", safe(r["exercise_date"]), "", "", "", "", "", "", safe(r["kind"]), safe(r["duration_minutes"]), safe(r["note"])])
    return Response(output.getvalue(), media_type="text/csv; charset=utf-8", headers={"Content-Disposition": "attachment; filename=welltrack-records.csv"})

@app.get("/api/v1/backup")
def backup(uid: str = Depends(user_id)):
    """导出当前用户的版本化 JSON 备份。"""
    with read_connection() as conn:
        records = [record_out(r) for r in conn.execute("SELECT * FROM daily_records WHERE user_id=? ORDER BY record_date", (uid,))]
        exercises = [dict(r) for r in conn.execute("SELECT exercise_date,kind,duration_minutes,note FROM exercises WHERE user_id=? ORDER BY exercise_date", (uid,))]
    return {"version": 1, "profile": get_profile(uid), "records": records, "exercises": exercises}

