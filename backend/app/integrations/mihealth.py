"""小米运动健康本机只读连接与摘要转换。"""

import json
from datetime import datetime

from cryptography.fernet import Fernet, InvalidToken

from .vendor import client as mi_client
from .vendor import login as mi_login


class MiHealthError(Exception):
    """连接器配置或上游访问失败。"""


class MiHealthChallenge(MiHealthError):
    """小米要求额外验证时携带短时验证状态。"""
    def __init__(self, message: str, state: dict | None = None, hint: str = ""):
        super().__init__(message)
        self.state = state
        self.hint = hint


class MiHealthVerificationError(MiHealthError):
    """短信或邮箱验证码被上游拒绝。"""


def serialize_login_session(session) -> dict:
    """只序列化重试二次验证所需的 cookie 会话。"""
    return {
        "device_id": session.__dict__.get("_mih_device_id", ""),
        "user_id": session.__dict__.get("_mih_user_id", ""),
        "cookies": [{
            "name": cookie.name, "value": cookie.value, "domain": cookie.domain,
            "path": cookie.path, "secure": cookie.secure, "expires": cookie.expires,
        } for cookie in session.cookies],
    }


def restore_login_session(state: dict):
    """从加密状态还原小米认证请求会话。"""
    session = mi_login.new_login_session()
    session.__dict__["_mih_device_id"] = state.get("device_id", "")
    for cookie in state.get("cookies", []):
        session.cookies.set(cookie["name"], cookie["value"], domain=cookie.get("domain"),
                            path=cookie.get("path") or "/", secure=bool(cookie.get("secure")),
                            expires=cookie.get("expires"))
    if state.get("user_id"):
        session.__dict__["_mih_user_id"] = state["user_id"]
    return session


def _cipher(key: str) -> Fernet:
    """严格校验会话加密密钥。"""
    if not key:
        raise MiHealthError("尚未设置小米会话加密密钥")
    try:
        return Fernet(key.encode("ascii"))
    except (ValueError, UnicodeEncodeError):
        raise MiHealthError("小米会话加密密钥格式无效") from None


def validate_session_key(key: str) -> None:
    """在访问上游前验证令牌密钥配置。"""
    _cipher(key)


def encrypt_session(session: dict, key: str) -> str:
    """将会话加密成可安全保存的文本。"""
    serialized = json.dumps(session, ensure_ascii=False, separators=(",", ":")).encode()
    return _cipher(key).encrypt(serialized).decode("ascii")


def decrypt_session(blob: str, key: str) -> dict:
    """解密用户会话且不回显密文或令牌。"""
    try:
        cleartext = _cipher(key).decrypt(blob.encode("ascii"))
        result = json.loads(cleartext)
    except (InvalidToken, UnicodeEncodeError, json.JSONDecodeError):
        raise MiHealthError("小米会话无法解密，请重新绑定") from None
    if not isinstance(result, dict):
        raise MiHealthError("小米会话格式无效，请重新绑定")
    return result


def connect_mihealth(username: str, password: str) -> dict:
    """短暂使用登录密码并返回小米服务会话。"""
    try:
        session = mi_login.login_with_password(username, password)
    except mi_login.Need2FA as exc:
        if exc.session is None:
            raise MiHealthChallenge("小米账号要求短信或邮箱二次验证") from None
        try:
            challenge = mi_login.start_2fa(exc.session, exc.notification_url)
        except mi_login.LoginError:
            raise MiHealthError("无法启动小米短信验证，请稍后重试") from None
        state = {
            "session": serialize_login_session(exc.session),
            "context": challenge["context"], "flag": challenge["flag"],
            "method": challenge["method"], "hint": challenge["hint"],
            "sid": exc.raw.get("sid") or mi_login.SID_HEALTH,
        }
        method_label = "短信" if challenge["method"] == "Phone" else "邮箱"
        raise MiHealthChallenge(f"小米已发送{method_label}验证码", state=state,
                                hint=challenge["hint"]) from None
    except mi_login.LoginError as exc:
        if getattr(exc, "captcha_url", None):
            raise MiHealthChallenge("小米账号要求验证码，当前连接流程暂不支持") from None
        raise MiHealthError("小米账号登录失败，请检查账号密码或稍后重试") from None
    required = ("ssecurity", "service_token", "cuser_id", "pass_token")
    if not all(session.get(field) for field in required):
        raise MiHealthError("小米登录未返回完整健康会话")
    session["region"] = "cn"
    return session


def verify_mihealth_challenge(state: dict, code: str) -> dict:
    """提交一次性验证码并返回完成登录后的会话。"""
    try:
        session = restore_login_session(state["session"])
        result = mi_login.finish_2fa(session, code, state["context"],
                                    flag=int(state.get("flag", 4)),
                                    sid=state.get("sid") or mi_login.SID_HEALTH)
    except mi_login.LoginError as exc:
        if getattr(exc, "code", None) is not None or "验证码校验未通过" in str(exc):
            raise MiHealthVerificationError("验证码不正确或已过期") from None
        raise MiHealthError("小米验证码验证暂时失败，请重试") from None
    required = ("ssecurity", "service_token", "cuser_id", "pass_token")
    if not all(result.get(field) for field in required):
        raise MiHealthError("小米验证完成，但未返回完整健康会话")
    result["region"] = "cn"
    return result


def _decode(item: dict) -> dict:
    """将上游内嵌 JSON 字符串转为字段对象。"""
    try:
        data = json.loads(item.get("value") or "{}")
    except (TypeError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def _local_datetime(raw):
    """将秒或毫秒时间戳转换成本地 ISO 时间。"""
    try:
        stamp = float(raw)
        if stamp > 100_000_000_000:
            stamp /= 1000
        return datetime.fromtimestamp(stamp).isoformat(timespec="seconds")
    except (TypeError, ValueError, OSError, OverflowError):
        return None


def _weight_samples(items: list[dict]) -> list[dict]:
    """将小米体重样本转换为统一的来源记录。"""
    readings = []
    for item in items:
        detail = _decode(item)
        timestamp = item.get("time") or detail.get("time")
        recorded_at = _local_datetime(timestamp)
        value = detail.get("weight")
        if not recorded_at or not isinstance(value, (int, float)) or value <= 0:
            continue
        readings.append({
            "source_id": f"weight:{item.get('sid', 'xiaomi')}:{timestamp}",
            "metric": "weight", "recorded_at": recorded_at, "value": float(value),
            "unit": "kg", "payload": detail,
        })
    return readings


def read_mihealth_weight_page(session: dict, end_ms: int, next_key=None) -> tuple[list[dict], str | None, bool]:
    """读取一页完整历史体重并返回续页游标。"""
    client = mi_client.MiHealthClient(
        session["ssecurity"], session["service_token"], session["cuser_id"],
        region=session.get("region", "cn"), min_interval=0.5, timeout=12, max_retries=1,
    )
    response = client.get_fitness_data_by_time("weight", 0, end_ms, reverse=True, next_key=next_key)
    result = (response or {}).get("result") or {}
    cursor = result.get("next_key")
    return _weight_samples(result.get("data_list", [])), cursor, bool(result.get("has_more") and cursor)


def read_mihealth_weight_window(session: dict, start_ms: int, end_ms: int) -> list[dict]:
    """读取一个有界增量窗口内的体重样本。"""
    client = mi_client.MiHealthClient(
        session["ssecurity"], session["service_token"], session["cuser_id"],
        region=session.get("region", "cn"), min_interval=0.5, timeout=12, max_retries=1,
    )
    result = client.get_fitness_data_all("weight", start_ms, end_ms, max_pages=40)
    if result.get("has_more"):
        raise MiHealthError("小米体重记录较多，自动增量同步未完成；稍后会继续重试")
    return _weight_samples(result.get("data_list", []))


def read_mihealth_metrics(session: dict, start_ms: int, end_ms: int) -> list[dict]:
    """读取有限日期范围内的体重、睡眠、步数和运动摘要。"""
    client = mi_client.MiHealthClient(
        session["ssecurity"], session["service_token"], session["cuser_id"],
        region=session.get("region", "cn"), min_interval=0.5, timeout=12, max_retries=1,
    )
    readings = []
    for key in ("weight", "sleep", "steps", "calories"):
        try:
            result = client.get_fitness_data_all(key, start_ms, end_ms, max_pages=40)
        except (KeyError, TypeError, AttributeError):
            continue
        if result.get("has_more"):
            raise MiHealthError("小米健康记录较多，请缩短同步日期范围后重试")
        items = result.get("data_list", [])
        for item in items:
            detail = _decode(item)
            timestamp = item.get("time") or detail.get("time") or detail.get("wake_time")
            recorded_at = _local_datetime(timestamp)
            if not recorded_at:
                continue
            metric, value, unit = None, None, ""
            if key == "weight":
                metric, value, unit = "weight", detail.get("weight"), "kg"
            elif key == "sleep":
                mins = sum(float(detail.get(field) or 0) for field in (
                    "sleep_deep_duration", "sleep_light_duration", "sleep_rem_duration"
                ))
                metric, value, unit = "sleep_hours", round(mins / 60, 2), "h"
            elif key == "calories":
                cal = detail.get("calories") if detail.get("calories") is not None else (detail.get("energy") if detail.get("energy") is not None else detail.get("calorie"))
                if isinstance(cal, (int, float)) and cal >= 0:
                    readings.append({
                        "source_id": f"calories:{item.get('sid', 'xiaomi')}:{timestamp}",
                        "metric": "calories", "recorded_at": recorded_at,
                        "value": round(float(cal), 1), "unit": "kcal",
                        "payload": detail,
                    })
                continue
            else:
                count = detail.get("steps")
                if isinstance(count, (int, float)) and count >= 0:
                    readings.append({
                        "source_id": f"steps:{item.get('time')}", "metric": "steps",
                        "recorded_at": recorded_at, "value": float(count), "unit": "steps",
                        "payload": {"source": item.get("sid", "xiaomi")},
                    })
                continue
            if isinstance(value, (int, float)) and value >= 0:
                readings.append({
                    "source_id": f"{key}:{item.get('sid', 'xiaomi')}:{timestamp}",
                    "metric": metric, "recorded_at": recorded_at, "value": float(value),
                    "unit": unit, "payload": detail,
                })
    sport_result = client.get_sport_records_all(start_ms, end_ms, max_pages=20)
    if sport_result.get("has_more"):
        raise MiHealthError("小米运动记录较多，请缩短同步日期范围后重试")
    workouts = sport_result.get("sport_records", [])
    for item in workouts:
        detail = _decode(item)
        recorded_at = _local_datetime(item.get("time"))
        duration = detail.get("duration")
        if not recorded_at or not isinstance(duration, (int, float)) or duration <= 0:
            continue
        activity = str(detail.get("sport_type") or item.get("category") or item.get("key") or "运动")[:40]
        cal = detail.get("calories") or detail.get("calorie")
        cal_val = round(float(cal), 1) if isinstance(cal, (int, float)) and cal > 0 else None
        readings.append({
            "source_id": f"workout:{item.get('sid', 'xiaomi')}:{item.get('time')}:{item.get('key', '')}",
            "metric": "workout_minutes", "recorded_at": recorded_at,
            "value": round(duration / 60, 1), "unit": "min",
            "payload": {"kind": activity, "duration_seconds": duration, "calories": cal_val},
        })
        if cal_val:
            readings.append({
                "source_id": f"workout_cal:{item.get('sid', 'xiaomi')}:{item.get('time')}:{item.get('key', '')}",
                "metric": "calories", "recorded_at": recorded_at,
                "value": cal_val, "unit": "kcal",
                "payload": {"kind": activity, "from_workout": True},
            })
    return readings


def refresh_mihealth_session(session: dict) -> dict:
    """使用上游 refresh token 续期，不再次收集账号密码。"""
    try:
        refreshed = mi_login.refresh_session(session)
    except mi_login.LoginError:
        raise MiHealthError("小米会话已过期，请重新绑定") from None
    refreshed["region"] = session.get("region", "cn")
    return refreshed
