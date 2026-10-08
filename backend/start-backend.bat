@echo off
setlocal
cd /d "%~dp0"
if not exist ".env" (
  if not exist ".env.example" (
    echo [ERROR] .env.example was not found in %CD%
    pause
    exit /b 1
  )
  copy /y ".env.example" ".env" >nul
  echo Created backend\.env from .env.example. Review it before using integrations.
)
where uv >nul 2>nul
if errorlevel 1 (
  echo [ERROR] uv is not installed or not available in PATH.
  echo Install uv, reopen this window, and double-click this file again.
  pause
  exit /b 1
)
echo Syncing backend dependencies...
uv sync
if errorlevel 1 (
  echo [ERROR] uv sync failed. Review the error above and retry.
  pause
  exit /b 1
)
echo Starting WellTrack API at http://127.0.0.1:8000
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload --env-file .env
if errorlevel 1 (
  echo [ERROR] Backend stopped with an error.
  pause
  exit /b 1
)
endlocal
