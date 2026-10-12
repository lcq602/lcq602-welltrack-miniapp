from datetime import date
from pathlib import Path
from fastapi.testclient import TestClient
from app import db
from app.main import app


def client_at(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "DB_PATH", tmp_path / "test.sqlite3")
    db.initialize()
    return TestClient(app)


def login(client):
    response = client.post("/api/v1/auth/local")
    assert response.status_code == 200
    client.headers.update({"Authorization": f"Bearer {response.json()['token']}"})


def test_local_setup_records_and_analytics(tmp_path, monkeypatch):
    """验证首次设置、日常新增、去重和统计汇总。"""
    client = client_at(tmp_path, monkeypatch)
    login(client)
    profile = {"height_cm": 170, "current_weight": 70, "target_weight": 66, "start_date": str(date.today()), "goal": "weight_loss", "weekly_plan": {"mon": "walk"}}
    assert client.post("/api/v1/profile", json=profile).status_code == 201
    record = {"record_date": str(date.today()), "weight": 69.8, "waist_cm": 84, "water_ml": 1800, "sleep_hours": 7.5, "habits": ["walk"], "diet": "balanced"}
    assert client.post("/api/v1/records", json=record).status_code == 201
    assert client.post("/api/v1/records", json=record).status_code == 409
    assert client.get("/api/v1/dashboard").json()["current_weight"] == 69.8
    dashboard = client.get("/api/v1/dashboard").json()
    assert dashboard["record_days_7d"] == 1
    assert dashboard["weekly_completion"] == 14
    assert client.get("/api/v1/analytics").json()["days"][0]["waist_cm"] == 84
    calendar = client.get(f"/api/v1/analytics?period=calendar&month={date.today():%Y-%m}").json()
    assert calendar["start"] == date.today().replace(day=1).isoformat()
    assert calendar["days"][0]["record_date"] == str(date.today())
    exercise = {"exercise_date": str(date.today()), "kind": "力量训练", "duration_minutes": 30}
    assert client.post("/api/v1/exercises", json=exercise).status_code == 201
    assert client.get("/api/v1/analytics").json()["exercise_count"] == 1
    assert "balanced" in client.get("/api/v1/export.csv").text
    backup = client.get("/api/v1/backup").json()
    assert backup["version"] == 1 and len(backup["records"]) == 1


def test_profile_accepts_optional_target_date_as_null(tmp_path, monkeypatch):
    """验证首次设置提交可选目标日期为空时仍可保存。"""
    client = client_at(tmp_path, monkeypatch)
    login(client)
    profile = {
        "height_cm": 170,
        "current_weight": 70,
        "target_weight": 66,
        "start_date": str(date.today()),
        "target_date": None,
        "goal": "weight_loss",
        "weekly_plan": {"mon": "有氧或散步", "tue": "休息", "wed": "力量训练", "thu": "休息", "fri": "有氧运动", "sat": "自由活动", "sun": "休息"},
    }
    assert client.post("/api/v1/profile", json=profile).status_code == 201
    profile["target_date"] = ""
    assert client.post("/api/v1/profile", json=profile).status_code == 422


def test_user_records_are_isolated(tmp_path, monkeypatch):
    """验证不同会话不能读取其他用户记录。"""
    client = client_at(tmp_path, monkeypatch)
    login(client)
    record = {"record_date": str(date.today()), "weight": 72}
    assert client.post("/api/v1/records", json=record).status_code == 201
    client.headers.pop("Authorization")
    login(client)
    assert client.get("/api/v1/records").json() == []


def test_requires_bearer_and_rejects_invalid_input(tmp_path, monkeypatch):
    """验证会话认证和数值边界。"""
    client = client_at(tmp_path, monkeypatch)
    assert client.get("/api/v1/profile").status_code == 401
    assert client.get("/api/v1/integrations/mihealth/status").status_code == 401
    assert client.post("/api/v1/integrations/mihealth/sync").status_code == 401
    login(client)
    assert client.post("/api/v1/records", json={"record_date": str(date.today()), "weight": 2}).status_code == 422
    assert client.post("/api/v1/auth/wechat", json={"code": "local-test"}).status_code == 503


def test_append_only_profile_and_export_safety(tmp_path, monkeypatch):
    """验证资料以新增版本保存，并转义 CSV 公式前缀。"""
    client = client_at(tmp_path, monkeypatch)
    login(client)
    base = {"height_cm": 170, "current_weight": 70, "target_weight": 66, "start_date": str(date.today()), "goal": "weight_loss"}
    assert client.post("/api/v1/profile", json=base).status_code == 201
    revised = {**base, "target_weight": 65}
    assert client.post("/api/v1/profile", json=revised).status_code == 201
    assert client.get("/api/v1/profile").json()["target_weight"] == 65
    assert client.post("/api/v1/records", json={"record_date": str(date.today()), "diet": "=1+1"}).status_code == 201
    assert "'=1+1" in client.get("/api/v1/export.csv").text
    routes = {route.path for route in app.routes}
    assert "/api/v1/backup/restore" not in routes
    assert "/api/v1/records/{record_id}" not in routes


def test_openapi_contains_documented_read_and_create_api_surface():
    """确保 API 说明覆盖当前公开的数据接口。"""
    paths = app.openapi()["paths"]
    expected = {
        "/health", "/api/v1/auth/local", "/api/v1/auth/wechat", "/api/v1/profile",
        "/api/v1/dashboard", "/api/v1/records", "/api/v1/exercises",
        "/api/v1/analytics", "/api/v1/export.csv", "/api/v1/backup",
        "/api/v1/integrations/mihealth/status", "/api/v1/integrations/mihealth/connect",
        "/api/v1/integrations/mihealth/verify", "/api/v1/integrations/mihealth/sync",
        "/api/v1/integrations/mihealth/connection",
    }
    assert expected.issubset(paths)
    assert "get" in paths["/api/v1/records"] and "post" in paths["/api/v1/records"]
    assert "get" in paths["/api/v1/exercises"] and "post" in paths["/api/v1/exercises"]
    documentation = (Path(__file__).parents[1] / "API.md").read_text(encoding="utf-8")
    assert all(path in documentation for path in expected)


def test_mihealth_connection_and_imports_are_isolated_and_append_only(tmp_path, monkeypatch):
    """验证小米连接按用户隔离，重复同步去重且解绑保留只读导入记录。"""
    import app.main as main
    from cryptography.fernet import Fernet

    client = client_at(tmp_path, monkeypatch)
    login(client)
    monkeypatch.setattr(main, "MIHEALTH_ENABLED", True)
    monkeypatch.setattr(main, "MIHEALTH_TOKEN_KEY", Fernet.generate_key().decode())
    monkeypatch.setattr(main, "connect_mihealth", lambda username, password: {
        "ssecurity": "security-secret", "service_token": "service-secret",
        "cuser_id": "xiaomi-user", "user_id": "42", "pass_token": "refresh-secret",
    })

    connected = client.post("/api/v1/integrations/mihealth/connect", json={"username": "me", "password": "temporary-secret"})
    assert connected.status_code == 200
    assert connected.json()["connected"] is True
    assert "service-secret" not in connected.text
    with db.read_connection() as conn:
        stored = conn.execute("SELECT encrypted_session FROM mihealth_connections").fetchone()[0]
        assert "refresh-secret" not in stored
        assert conn.execute("SELECT COUNT(*) FROM mihealth_connections").fetchone()[0] == 1

    assert client.post("/api/v1/records", json={"record_date": str(date.today()), "weight": 70}).status_code == 201
    readings = [
        {"source_id": "scale-1", "metric": "weight", "recorded_at": "2026-10-08T07:00:00", "value": 69.8, "unit": "kg", "payload": {}},
        {"source_id": "sleep-1", "metric": "sleep_hours", "recorded_at": "2026-10-08T08:00:00", "value": 7.5, "unit": "h", "payload": {}},
        {"source_id": "steps-20261008", "metric": "steps", "recorded_at": "2026-10-08T23:59:00", "value": 8300, "unit": "steps", "payload": {}},
        {"source_id": "steps-20261008-later", "metric": "steps", "recorded_at": "2026-10-08T23:58:00", "value": 900, "unit": "steps", "payload": {}},
    ]
    monkeypatch.setattr(main, "read_mihealth_metrics", lambda session, start_ms, end_ms: readings)
    first = client.post("/api/v1/integrations/mihealth/sync", json={"days": 7})
    second = client.post("/api/v1/integrations/mihealth/sync", json={"days": 7})
    assert first.status_code == 200 and first.json()["imported"] == 4
    assert second.status_code == 200 and second.json()["imported"] == 0
    status = client.get("/api/v1/integrations/mihealth/status").json()
    assert status["latest"]["steps"]["value"] == 9200
    assert status["latest"]["steps"]["recorded_at"] == "2026-10-08"
    assert status["latest"]["weight"]["recorded_at"] == "2026-10-08T07:00:00"
    assert client.get("/api/v1/records").json()[0]["weight"] == 70
    assert "steps" in client.get("/api/v1/export.csv").text
    assert len(client.get("/api/v1/backup").json()["mihealth_data"]) == 4

    other = TestClient(app)
    login(other)
    assert other.get("/api/v1/integrations/mihealth/status").json()["connected"] is False
    assert other.post("/api/v1/integrations/mihealth/sync").status_code == 409

    unbound = client.delete("/api/v1/integrations/mihealth/connection")
    assert unbound.status_code == 200
    assert client.get("/api/v1/integrations/mihealth/status").json()["connected"] is False
    with db.read_connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM mihealth_imports").fetchone()[0] == 4


def test_mihealth_disabled_missing_key_and_invalid_input(tmp_path, monkeypatch):
    """验证连接器默认关闭、缺少加密密钥拒绝连接并校验同步范围。"""
    import app.main as main
    client = client_at(tmp_path, monkeypatch)
    login(client)
    monkeypatch.setattr(main, "MIHEALTH_ENABLED", False)
    assert client.get("/api/v1/integrations/mihealth/status").status_code == 200
    assert client.post("/api/v1/integrations/mihealth/connect", json={"username": "me", "password": "secret"}).status_code == 404
    monkeypatch.setattr(main, "MIHEALTH_ENABLED", True)
    monkeypatch.setattr(main, "MIHEALTH_TOKEN_KEY", "")
    assert client.post("/api/v1/integrations/mihealth/connect", json={"username": "me", "password": "secret"}).status_code == 503
    monkeypatch.setattr(main, "MIHEALTH_TOKEN_KEY", "invalid-key")
    login_attempts = []
    monkeypatch.setattr(main, "connect_mihealth", lambda *args: login_attempts.append(args))
    assert client.post("/api/v1/integrations/mihealth/connect", json={"username": "me", "password": "secret"}).status_code == 503
    assert login_attempts == []
    assert client.get("/api/v1/integrations/mihealth/status").json()["ready"] is False
    from cryptography.fernet import Fernet
    monkeypatch.setattr(main, "MIHEALTH_TOKEN_KEY", Fernet.generate_key().decode())
    assert client.post("/api/v1/integrations/mihealth/connect", json={"username": "   ", "password": "secret"}).status_code == 422
    assert client.post("/api/v1/integrations/mihealth/sync", json={"days": 366}).status_code == 422


def test_mihealth_connector_normalizes_upstream_samples(monkeypatch):
    """验证上游体重、睡眠、逐分钟步数和运动记录的规范化。"""
    from app.integrations import mihealth

    class FakeClient:
        def __init__(self, *args, **kwargs):
            pass

        def get_fitness_data_all(self, key, start_ms, end_ms, max_pages):
            samples = {
                "weight": [{"sid": "scale", "time": 1791435600, "value": '{"weight":69.8}'}],
                "sleep": [{"sid": "band", "time": 1791439200, "value": '{"sleep_deep_duration":90,"sleep_light_duration":240,"sleep_rem_duration":30}'}],
                "steps": [
                    {"sid": "band", "time": 1791435600, "value": '{"steps":4000}'},
                    {"sid": "phone", "time": 1791435660, "value": '{"steps":2500}'},
                ],
                "calories": [{"sid": "band", "time": 1791435600, "value": '{"calories":380.5}'}],
            }
            return {"data_list": samples.get(key, [])}

        def get_sport_records_all(self, start_ms, end_ms, max_pages):
            return {"sport_records": [{"sid": "band", "key": "run", "time": 1791435600, "value": '{"sport_type":"running","duration":1800,"calories":150}'}]}

    monkeypatch.setattr(mihealth.mi_client, "MiHealthClient", FakeClient)
    readings = mihealth.read_mihealth_metrics(
        {"ssecurity": "s", "service_token": "t", "cuser_id": "u"}, 0, 1
    )
    by_metric = {row["metric"]: row for row in readings}
    assert by_metric["weight"]["value"] == 69.8
    assert by_metric["sleep_hours"]["value"] == 6
    step_rows = [row for row in readings if row["metric"] == "steps"]
    assert sum(row["value"] for row in step_rows) == 6500
    assert len(step_rows) == 2
    assert by_metric["workout_minutes"]["value"] == 30
    cal_rows = [row for row in readings if row["metric"] == "calories"]
    assert sum(row["value"] for row in cal_rows) == 530.5


def test_mihealth_session_is_encrypted_and_bad_keys_are_rejected():
    """验证续期会话密文不可读，缺失或错误密钥不能创建会话。"""
    from cryptography.fernet import Fernet
    from app.integrations.mihealth import MiHealthError, decrypt_session, encrypt_session

    key = Fernet.generate_key().decode()
    session = {"service_token": "sensitive-token", "pass_token": "refresh-token"}
    encrypted = encrypt_session(session, key)
    assert "sensitive-token" not in encrypted
    assert decrypt_session(encrypted, key) == session
    try:
        encrypt_session(session, "")
    except MiHealthError:
        pass
    else:
        raise AssertionError("missing key must be rejected")


def test_mihealth_login_uses_one_time_password_and_reports_challenges(monkeypatch):
    """验证账号密码只传给登录函数，并将额外验证转为明确提示。"""
    from app.integrations import mihealth

    login_calls = []
    session = {"ssecurity": "s", "service_token": "t", "cuser_id": "c", "pass_token": "p"}
    monkeypatch.setattr(mihealth.mi_login, "login_with_password", lambda username, password: login_calls.append((username, password)) or dict(session))
    result = mihealth.connect_mihealth("person", "one-time-password")
    assert result["region"] == "cn" and login_calls == [("person", "one-time-password")]

    def requires_second_factor(*args):
        raise mihealth.mi_login.Need2FA("https://account.xiaomi.com/challenge")

    monkeypatch.setattr(mihealth.mi_login, "login_with_password", requires_second_factor)
    try:
        mihealth.connect_mihealth("person", "one-time-password")
    except mihealth.MiHealthChallenge as error:
        assert "二次验证" in str(error)
    else:
        raise AssertionError("extra account verification must not be bypassed")


def test_mihealth_sms_challenge_is_encrypted_limited_and_completed(tmp_path, monkeypatch):
    """验证短信挑战按用户加密保存、限制尝试次数且成功后清除。"""
    import app.main as main
    from cryptography.fernet import Fernet
    from app.integrations.mihealth import MiHealthChallenge, MiHealthVerificationError

    client = client_at(tmp_path, monkeypatch)
    login(client)
    monkeypatch.setattr(main, "MIHEALTH_ENABLED", True)
    key = Fernet.generate_key().decode()
    monkeypatch.setattr(main, "MIHEALTH_TOKEN_KEY", key)
    state = {"context": "private-context", "flag": 4, "method": "Phone", "session": {"cookies": []}}

    def begin(*args):
        raise MiHealthChallenge("需要短信验证", state=state, hint="138****4602")

    monkeypatch.setattr(main, "connect_mihealth", begin)
    started = client.post("/api/v1/integrations/mihealth/connect", json={"username": "me", "password": "secret"})
    assert started.status_code == 202
    challenge_id = started.json()["challenge_id"]
    assert started.json()["hint"] == "138****4602"
    assert client.get("/api/v1/integrations/mihealth/status").json()["challenge"]["challenge_id"] == challenge_id
    with db.read_connection() as conn:
        row = conn.execute("SELECT user_id,encrypted_state,attempts,expires_at FROM mihealth_challenges").fetchone()
        assert row["user_id"] and row["attempts"] == 0
        assert "private-context" not in row["encrypted_state"]
        assert "138****4602" not in row["encrypted_state"]
        assert row["expires_at"] > 0

    def reject_code(challenge, code):
        assert challenge["context"] == "private-context" and code == "123456"
        raise MiHealthVerificationError("验证码不正确")

    monkeypatch.setattr(main, "verify_mihealth_challenge", reject_code)
    bad_code = client.post("/api/v1/integrations/mihealth/verify", json={"challenge_id": challenge_id, "code": "123456"})
    assert bad_code.status_code == 422
    with db.read_connection() as conn:
        row = conn.execute("SELECT attempts,encrypted_state FROM mihealth_challenges WHERE challenge_id=?", (challenge_id,)).fetchone()
        assert row["attempts"] == 1 and "123456" not in row["encrypted_state"]

    monkeypatch.setattr(main, "verify_mihealth_challenge", lambda challenge, code: {
        "ssecurity": "s", "service_token": "service-token", "cuser_id": "c", "pass_token": "p"
    })
    completed = client.post("/api/v1/integrations/mihealth/verify", json={"challenge_id": challenge_id, "code": "654321"})
    assert completed.status_code == 200 and completed.json()["connected"] is True
    with db.read_connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM mihealth_challenges").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM mihealth_connections").fetchone()[0] == 1


def test_mihealth_sms_challenge_is_user_scoped_and_expires(tmp_path, monkeypatch):
    """验证短信挑战不可跨用户使用，过期后拒绝且删除。"""
    import app.main as main
    from cryptography.fernet import Fernet
    from app.integrations.mihealth import MiHealthChallenge

    client = client_at(tmp_path, monkeypatch)
    login(client)
    monkeypatch.setattr(main, "MIHEALTH_ENABLED", True)
    monkeypatch.setattr(main, "MIHEALTH_TOKEN_KEY", Fernet.generate_key().decode())
    monkeypatch.setattr(main, "connect_mihealth", lambda *args: (_ for _ in ()).throw(
        MiHealthChallenge("需要短信验证", state={"context": "ctx", "session": {}}, hint="138****4602")))
    started = client.post("/api/v1/integrations/mihealth/connect", json={"username": "me", "password": "secret"})
    challenge_id = started.json()["challenge_id"]

    other = TestClient(app)
    login(other)
    assert other.post("/api/v1/integrations/mihealth/verify", json={"challenge_id": challenge_id, "code": "654321"}).status_code == 404

    with db.transaction() as conn:
        conn.execute("UPDATE mihealth_challenges SET expires_at=0 WHERE challenge_id=?", (challenge_id,))
    assert client.post("/api/v1/integrations/mihealth/verify", json={"challenge_id": challenge_id, "code": "654321"}).status_code == 410
    with db.read_connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM mihealth_challenges").fetchone()[0] == 0


def test_mihealth_login_round_trips_sms_session_for_code_submission(monkeypatch):
    """验证登录挑战的 cookie 会话可加密往返并用于提交短信码。"""
    import requests
    from app.integrations import mihealth

    login_session = requests.Session()
    login_session.cookies.set("challenge_cookie", "cookie-secret", domain="account.xiaomi.com")
    login_session.__dict__["_mih_device_id"] = "device-hash"
    login_session.__dict__["_mih_user_id"] = "xiaomi-user"

    def requires_code(*args):
        raise mihealth.mi_login.Need2FA("https://account.xiaomi.com/challenge", session=login_session)

    monkeypatch.setattr(mihealth.mi_login, "login_with_password", requires_code)
    monkeypatch.setattr(mihealth.mi_login, "start_2fa", lambda session, url: {
        "context": "sms-context", "flag": 4, "method": "Phone", "hint": "138****4602"
    })
    try:
        mihealth.connect_mihealth("person", "one-time-password")
    except mihealth.MiHealthChallenge as error:
        state = error.state
        assert error.hint == "138****4602"
    else:
        raise AssertionError("SMS accounts must enter the verification flow")

    def finish(session, code, context, flag, sid):
        assert session.__dict__["_mih_device_id"] == "device-hash"
        assert session.__dict__["_mih_user_id"] == "xiaomi-user"
        assert session.cookies.get("challenge_cookie", domain="account.xiaomi.com") == "cookie-secret"
        assert (code, context, flag) == ("123456", "sms-context", 4)
        return {"ssecurity": "s", "service_token": "t", "cuser_id": "c", "pass_token": "p"}

    monkeypatch.setattr(mihealth.mi_login, "finish_2fa", finish)
    assert mihealth.verify_mihealth_challenge(state, "123456")["service_token"] == "t"


def test_mihealth_sms_challenge_deletes_after_five_rejected_codes(tmp_path, monkeypatch):
    """验证连续五次验证码失败后挑战被删除并拒绝继续尝试。"""
    import app.main as main
    from cryptography.fernet import Fernet
    from app.integrations.mihealth import MiHealthChallenge, MiHealthVerificationError

    client = client_at(tmp_path, monkeypatch)
    login(client)
    monkeypatch.setattr(main, "MIHEALTH_ENABLED", True)
    monkeypatch.setattr(main, "MIHEALTH_TOKEN_KEY", Fernet.generate_key().decode())
    monkeypatch.setattr(main, "connect_mihealth", lambda *args: (_ for _ in ()).throw(
        MiHealthChallenge("需要短信验证", state={"context": "ctx", "session": {}}, hint="***4602")))
    challenge = client.post("/api/v1/integrations/mihealth/connect", json={"username": "me", "password": "secret"}).json()
    monkeypatch.setattr(main, "verify_mihealth_challenge", lambda *args: (_ for _ in ()).throw(
        MiHealthVerificationError("invalid code")))
    for attempt in range(5):
        response = client.post("/api/v1/integrations/mihealth/verify", json={
            "challenge_id": challenge["challenge_id"], "code": "123456"
        })
        assert response.status_code == (429 if attempt == 4 else 422)
    with db.read_connection() as conn:
        assert conn.execute("SELECT COUNT(*) FROM mihealth_challenges").fetchone()[0] == 0


def test_background_mihealth_weight_backfill_resumes_then_syncs_incrementally(tmp_path, monkeypatch):
    """验证体重全量回填可续跑，完成后用重叠窗口增量且来源键去重。"""
    from datetime import datetime, timedelta
    from cryptography.fernet import Fernet
    import app.main as main

    client = client_at(tmp_path, monkeypatch)
    login(client)
    monkeypatch.setattr(main, "MIHEALTH_ENABLED", True)
    monkeypatch.setattr(main, "MIHEALTH_TOKEN_KEY", Fernet.generate_key().decode())
    monkeypatch.setattr(main, "MIHEALTH_BACKFILL_PAGES_PER_RUN", 1)
    monkeypatch.setattr(main, "connect_mihealth", lambda *args: {
        "ssecurity": "s", "service_token": "t", "cuser_id": "c", "pass_token": "p", "region": "cn"
    })
    assert client.post("/api/v1/integrations/mihealth/connect", json={"username": "me", "password": "secret"}).status_code == 200
    assert client.post("/api/v1/records", json={"record_date": str(date.today()), "weight": 80}).status_code == 201

    stamp_one = datetime.now() - timedelta(days=2)
    stamp_two = datetime.now() - timedelta(days=1)
    stamp_new = datetime.now()
    item_one = {"source_id": "weight:scale:one", "metric": "weight", "recorded_at": stamp_one.isoformat(timespec="seconds"), "value": 78.4, "unit": "kg", "payload": {}}
    item_two = {"source_id": "weight:scale:two", "metric": "weight", "recorded_at": stamp_two.isoformat(timespec="seconds"), "value": 77.9, "unit": "kg", "payload": {}}
    item_new = {"source_id": "weight:scale:new", "metric": "weight", "recorded_at": stamp_new.isoformat(timespec="seconds"), "value": 77.5, "unit": "kg", "payload": {}}
    page_calls = []

    def page_reader(session, end_ms, next_key):
        page_calls.append(next_key)
        if next_key is None:
            return [item_two], "older-page", True
        return [item_one], None, False

    incremental_calls = []
    def window_reader(session, start_ms, end_ms):
        incremental_calls.append((start_ms, end_ms))
        return [item_two, item_new]

    monkeypatch.setattr(main, "read_mihealth_weight_page", page_reader)
    monkeypatch.setattr(main, "read_mihealth_weight_window", window_reader)
    first = main.sync_connected_mihealth_weights_once()
    assert first["imported"] == 1 and page_calls == [None]
    with db.read_connection() as conn:
        saved = conn.execute("SELECT encrypted_session FROM mihealth_connections").fetchone()[0]
        from app.integrations.mihealth import decrypt_session
        state = decrypt_session(saved, main.MIHEALTH_TOKEN_KEY)["_welltrack_weight_sync"]
        assert state["backfill_complete"] is False and state["next_key"] == "older-page"

    second = main.sync_connected_mihealth_weights_once()
    assert second["imported"] == 1 and page_calls == [None, "older-page"]
    third = main.sync_connected_mihealth_weights_once()
    assert third["imported"] == 1 and len(incremental_calls) == 1
    assert incremental_calls[0][0] <= int(stamp_two.timestamp() * 1000) - 6 * 86_400_000
    assert client.get("/api/v1/dashboard").json()["current_weight"] == 77.5
    with db.read_connection() as conn:
        rows = conn.execute("SELECT COUNT(*) n FROM mihealth_imports WHERE metric='weight'").fetchone()["n"]
        manual = conn.execute("SELECT weight FROM daily_records WHERE user_id=(SELECT user_id FROM sessions LIMIT 1) AND record_date=?", (str(date.today()),)).fetchone()["weight"]
        assert rows == 3 and manual == 80


def test_dashboard_and_analytics_use_latest_mihealth_weight_without_changing_manual_record(tmp_path, monkeypatch):
    """验证首页当前体重和趋势采用小米读数，同时保留手工日常记录。"""
    client = client_at(tmp_path, monkeypatch)
    login(client)
    profile = {"height_cm": 170, "current_weight": 80, "target_weight": 70,
               "start_date": str(date.today()), "goal": "weight_loss"}
    assert client.post("/api/v1/profile", json=profile).status_code == 201
    assert client.post("/api/v1/records", json={"record_date": str(date.today()), "weight": 80}).status_code == 201
    user = client.get("/api/v1/profile")
    with db.read_connection() as conn:
        uid = conn.execute("SELECT user_id FROM sessions LIMIT 1").fetchone()["user_id"]
    with db.transaction() as conn:
        conn.execute("INSERT INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit) VALUES(?,?,?,?,?,?)",
                     (uid, "weight:scale:today", "weight", f"{date.today()}T09:30:00", 72.4, "kg"))

    dashboard = client.get("/api/v1/dashboard").json()
    assert dashboard["current_weight"] == 72.4
    today = next(row for row in dashboard["records"] if row["record_date"] == str(date.today()))
    assert today["weight"] == 72.4 and today["weight_source"] == "mihealth"
    week = client.get("/api/v1/analytics?period=week").json()
    assert next(row for row in week["days"] if row["record_date"] == str(date.today()))["weight"] == 72.4
    assert client.get("/api/v1/records").json()[0]["weight"] == 80
    assert user.json()["current_weight"] == 80


def test_mihealth_connector_rejects_truncated_pages(monkeypatch):
    """验证上游分页未读完时不把部分数据误报为完整同步。"""
    from app.integrations import mihealth

    class TruncatedClient:
        def __init__(self, *args, **kwargs):
            pass

        def get_fitness_data_all(self, *args, **kwargs):
            return {"data_list": [], "has_more": True}

        def get_sport_records_all(self, *args, **kwargs):
            return {"sport_records": []}

    monkeypatch.setattr(mihealth.mi_client, "MiHealthClient", TruncatedClient)
    try:
        mihealth.read_mihealth_metrics({"ssecurity": "s", "service_token": "t", "cuser_id": "u"}, 0, 1)
    except mihealth.MiHealthError as error:
        assert "缩短" in str(error)
    else:
        raise AssertionError("truncated upstream result must not be reported as complete")


def test_mihealth_weight_and_vitality_in_records_history_and_analytics(tmp_path, monkeypatch):
    """验证手环同步的体重和活力指标进入历史记录列表、首页看板与分析图表。"""
    from datetime import date, timedelta
    client = client_at(tmp_path, monkeypatch)
    login(client)
    today = date.today()
    yesterday = today - timedelta(days=1)

    # 用户昨天手工填写了日常状态，但未填体重
    manual_rec = {
        "record_date": str(yesterday),
        "weight": None,
        "waist_cm": 82.0,
        "water_ml": 2000,
        "sleep_hours": None,
        "habits": ["喝够水", "散步"],
        "diet": "清淡",
        "note": "感觉良好",
    }
    assert client.post("/api/v1/records", json=manual_rec).status_code == 201

    with db.read_connection() as conn:
        uid = conn.execute("SELECT user_id FROM sessions LIMIT 1").fetchone()["user_id"]

    # 模拟手环同步数据：
    # 1. 昨天：同步了体重 68.8kg，步数 6000，卡路里 350.0
    # 2. 今天：用户未手工记录任何数据，手环自动同步了体重 68.5kg，步数 7500，卡路里 420.5，睡眠 7.5h，运动 30min
    with db.transaction() as conn:
        conn.execute("INSERT INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit) VALUES(?,?,?,?,?,?)",
                     (uid, "scale:yesterday", "weight", f"{yesterday}T08:00:00", 68.8, "kg"))
        conn.execute("INSERT INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit) VALUES(?,?,?,?,?,?)",
                     (uid, "steps:yesterday", "steps", f"{yesterday}T23:59:00", 6000, "steps"))
        conn.execute("INSERT INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit) VALUES(?,?,?,?,?,?)",
                     (uid, "cal:yesterday", "calories", f"{yesterday}T23:59:00", 350.0, "kcal"))

        conn.execute("INSERT INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit) VALUES(?,?,?,?,?,?)",
                     (uid, "scale:today", "weight", f"{today}T08:00:00", 68.5, "kg"))
        conn.execute("INSERT INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit) VALUES(?,?,?,?,?,?)",
                     (uid, "steps:today", "steps", f"{today}T18:00:00", 7500, "steps"))
        conn.execute("INSERT INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit) VALUES(?,?,?,?,?,?)",
                     (uid, "cal:today", "calories", f"{today}T18:00:00", 420.5, "kcal"))
        conn.execute("INSERT INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit) VALUES(?,?,?,?,?,?)",
                     (uid, "sleep:today", "sleep_hours", f"{today}T07:30:00", 7.5, "h"))
        conn.execute("INSERT INTO mihealth_imports(user_id,source_id,metric,recorded_at,value,unit) VALUES(?,?,?,?,?,?)",
                     (uid, "workout:today", "workout_minutes", f"{today}T19:00:00", 30.0, "min"))

    # 1. 验证历史记录接口 GET /api/v1/records 必须包含今天的只读同步记录与昨天的合并记录
    records = client.get("/api/v1/records").json()
    assert len(records) == 2

    today_item = next(r for r in records if r["record_date"] == str(today))
    assert today_item["weight"] == 68.5
    assert today_item["weight_source"] == "mihealth"
    assert today_item["is_mihealth_only"] is True
    assert today_item["id"] is None
    assert today_item["steps"] == 7500
    assert today_item["calories"] == 420.5
    assert today_item["sleep_hours"] == 7.5
    assert today_item["workout_minutes"] == 30.0

    yesterday_item = next(r for r in records if r["record_date"] == str(yesterday))
    assert yesterday_item["weight"] == 68.8
    assert yesterday_item["weight_source"] == "mihealth"
    assert yesterday_item["is_mihealth_only"] is False
    assert yesterday_item["id"] is not None
    assert yesterday_item["water_ml"] == 2000
    assert yesterday_item["habits"] == ["喝够水", "散步"]
    assert yesterday_item["steps"] == 6000
    assert yesterday_item["calories"] == 350.0

    # 2. 验证首页仪表盘 GET /api/v1/dashboard 包含今日手环活力数据
    dashboard = client.get("/api/v1/dashboard").json()
    assert dashboard["current_weight"] == 68.5
    assert dashboard["current_weight_source"] == "mihealth"
    today_vitality = dashboard["mihealth"]["today"]
    assert today_vitality["steps"] == 7500
    assert today_vitality["calories"] == 420.5
    assert today_vitality["sleep_hours"] == 7.5
    assert today_vitality["workout_minutes"] == 30.0

    # 3. 验证分析复盘接口 GET /api/v1/analytics 聚合了步数、卡路里、运动与睡眠
    analysis = client.get("/api/v1/analytics?period=month").json()
    assert analysis["total_steps"] == 13500
    assert analysis["total_calories"] == 770.5
    assert analysis["average_steps"] == 6750
    assert analysis["average_calories"] == 385.2
    assert analysis["average_sleep"] == 7.5

