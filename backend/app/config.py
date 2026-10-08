import os
from pathlib import Path

DATA_DIR = Path(os.getenv("WELLTRACK_DATA_DIR", Path(__file__).resolve().parents[1] / "data"))
DB_PATH = Path(os.getenv("WELLTRACK_DB_PATH", DATA_DIR / "welltrack.sqlite3"))
LOCAL_AUTH_ENABLED = os.getenv("WELLTRACK_LOCAL_AUTH", "true").lower() == "true"
WECHAT_APP_ID = os.getenv("WECHAT_APP_ID", "")
WECHAT_APP_SECRET = os.getenv("WECHAT_APP_SECRET", "")
CORS_ORIGINS = [item.strip() for item in os.getenv("WELLTRACK_CORS_ORIGINS", "*").split(",") if item.strip()]
