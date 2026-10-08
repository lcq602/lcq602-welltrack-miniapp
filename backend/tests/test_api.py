from datetime import date
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
    assert client.get("/api/v1/analytics").json()["days"][0]["waist_cm"] == 84
    exercise = {"exercise_date": str(date.today()), "kind": "力量训练", "duration_minutes": 30}
    assert client.post("/api/v1/exercises", json=exercise).status_code == 201
    assert client.get("/api/v1/analytics").json()["exercise_count"] == 1
    assert "balanced" in client.get("/api/v1/export.csv").text
    backup = client.get("/api/v1/backup").json()
    assert backup["version"] == 1 and len(backup["records"]) == 1


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
