-- WellTrack D1 表结构 (与 FastAPI 版 backend/app/db.py 的 SQLite SCHEMA 一致, 另加智能体缓存表)
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
CREATE TABLE IF NOT EXISTS mihealth_connections (
 user_id TEXT PRIMARY KEY REFERENCES users(id),
 encrypted_session TEXT NOT NULL,
 linked_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_synced_at TEXT
);
CREATE TABLE IF NOT EXISTS mihealth_challenges (
 challenge_id TEXT PRIMARY KEY,
 user_id TEXT NOT NULL REFERENCES users(id),
 encrypted_state TEXT NOT NULL,
 expires_at INTEGER NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_mihealth_challenges_user_expiry ON mihealth_challenges(user_id, expires_at);
CREATE TABLE IF NOT EXISTS mihealth_imports (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 user_id TEXT NOT NULL REFERENCES users(id),
 source_id TEXT NOT NULL,
 metric TEXT NOT NULL,
 recorded_at TEXT NOT NULL,
 value REAL,
 unit TEXT NOT NULL DEFAULT '',
 payload TEXT NOT NULL DEFAULT '{}',
 UNIQUE(user_id, source_id, metric)
);
CREATE INDEX IF NOT EXISTS idx_mihealth_imports_user_time ON mihealth_imports(user_id, recorded_at);
CREATE INDEX IF NOT EXISTS idx_mihealth_imports_user_metric_time ON mihealth_imports(user_id, metric, recorded_at);
CREATE TABLE IF NOT EXISTS agent_cache (
 user_id TEXT NOT NULL, cache_date TEXT NOT NULL, kind TEXT NOT NULL,
 content TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (user_id, cache_date, kind)
);
