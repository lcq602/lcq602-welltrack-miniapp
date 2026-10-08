import sqlite3
from contextlib import contextmanager
from .config import DB_PATH

SCHEMA = """
PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS users (
 id TEXT PRIMARY KEY, wechat_openid TEXT UNIQUE, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS profile_versions (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id),
 height_cm REAL NOT NULL, current_weight REAL NOT NULL, target_weight REAL NOT NULL,
 start_date TEXT NOT NULL, goal TEXT NOT NULL, target_date TEXT,
 weekly_plan TEXT NOT NULL DEFAULT '{}', reminder_settings TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_profile_versions_user ON profile_versions(user_id,id);
CREATE TABLE IF NOT EXISTS sessions (
 token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id),
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS daily_records (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id),
 record_date TEXT NOT NULL, weight REAL, waist_cm REAL, diet TEXT NOT NULL DEFAULT '', water_ml INTEGER,
 sleep_hours REAL, habits TEXT NOT NULL DEFAULT '[]', note TEXT NOT NULL DEFAULT '',
 UNIQUE(user_id, record_date)
);
CREATE INDEX IF NOT EXISTS idx_records_user_date ON daily_records(user_id, record_date);
CREATE TABLE IF NOT EXISTS exercises (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id),
 exercise_date TEXT NOT NULL, kind TEXT NOT NULL, duration_minutes INTEGER NOT NULL,
 note TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_exercises_user_date ON exercises(user_id, exercise_date);
"""


def connect():
    """创建启用外键的 SQLite 连接。"""
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def initialize():
    """初始化数据表和索引。"""
    conn = connect()
    try:
        conn.executescript(SCHEMA)
    finally:
        conn.close()


@contextmanager
def read_connection():
    """读取完成后关闭 SQLite 连接。"""
    conn = connect()
    try:
        yield conn
    finally:
        conn.close()


@contextmanager
def transaction():
    """提供提交或回滚的数据库事务。"""
    conn = connect()
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
