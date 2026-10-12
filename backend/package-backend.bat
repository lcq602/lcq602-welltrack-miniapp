@echo off
setlocal EnableExtensions
pushd "%~dp0.." || goto :error
set "PUSHED=1"

where git >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Git was not found in PATH.
  goto :error
)

for /f "delims=" %%I in ('git rev-parse --show-toplevel 2^>nul') do set "GIT_ROOT=%%~fI"
if not defined GIT_ROOT (
  echo [ERROR] Run this script from a Git checkout.
  goto :error
)
if /i not "%GIT_ROOT%"=="%CD%" (
  echo [ERROR] Project root does not match this script location.
  goto :error
)

for /f "delims=" %%I in ('git status --porcelain -- backend') do set "BACKEND_DIRTY=1"
if defined BACKEND_DIRTY (
  echo [WARN] Backend has uncommitted changes. The ZIP will contain committed files from HEAD only.
  if /i not "%~1"=="/force" (
    choice /c YN /n /m "Continue with the committed version? [Y/N] "
    if errorlevel 2 goto :error
  )
)

for /f "delims=" %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%I"
if not defined STAMP (
  echo [ERROR] Could not create a timestamp for the package filename.
  goto :error
)

if not exist "dist" mkdir "dist"
set "OUTPUT=%CD%\dist\welltrack-backend-%STAMP%.zip"
git archive --format=zip --output="%OUTPUT%" HEAD backend
if errorlevel 1 (
  echo [ERROR] Backend packaging failed.
  goto :error
)

echo.
echo Backend package created:
echo %OUTPUT%
popd
pause
exit /b 0

:error
if defined PUSHED popd
pause
exit /b 1
