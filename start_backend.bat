@echo off
setlocal EnableExtensions
chcp 65001 >nul
title ScholarEase Backend

set "PROJECT_HOME=%~dp0"
set "BACKEND_HOME=%PROJECT_HOME%backend"
set "ES_HOME=D:\elasticsearch-8.10.0"
set "ES_PASSWORD_FILE="

set "ELASTICSEARCH_URIS=https://localhost:9200"
set "ELASTICSEARCH_USERNAME=elastic"
set "ELASTICSEARCH_CA_CERT=file:D:/elasticsearch-8.10.0/config/certs/http_ca.crt"
set "ELASTICSEARCH_INDEX_NAME=scholarEase_base"

echo ============================================================
echo  ScholarEase Backend Startup
echo ============================================================
echo.

if not exist "%BACKEND_HOME%\mvnw.cmd" (
  echo [ERROR] Cannot find backend Maven wrapper:
  echo        %BACKEND_HOME%\mvnw.cmd
  echo.
  pause
  exit /b 1
)

for /f "usebackq delims=" %%F in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$files = Get-ChildItem -LiteralPath 'D:\elasticsearch-8.10.0' -File -Filter '*.txt'; foreach ($file in $files) { $first = Get-Content -LiteralPath $file.FullName -TotalCount 1 -ErrorAction SilentlyContinue; if ($first -eq 'elastic') { Write-Output $file.FullName; break } }"`) do (
  set "ES_PASSWORD_FILE=%%F"
)

if not defined ES_PASSWORD_FILE (
  echo [ERROR] Cannot find Elasticsearch password txt file under:
  echo        %ES_HOME%
  echo [ERROR] Expected a txt file whose first line is elastic.
  echo.
  pause
  exit /b 1
)

for /f "usebackq skip=1 delims=" %%P in ("%ES_PASSWORD_FILE%") do (
  set "ELASTICSEARCH_PASSWORD=%%P"
  goto :password_loaded
)

:password_loaded
if not defined ELASTICSEARCH_PASSWORD (
  echo [ERROR] Failed to read Elasticsearch password from:
  echo        %ES_PASSWORD_FILE%
  echo.
  pause
  exit /b 1
)

if not exist "D:\elasticsearch-8.10.0\config\certs\http_ca.crt" (
  echo [ERROR] Cannot find Elasticsearch CA certificate:
  echo        D:\elasticsearch-8.10.0\config\certs\http_ca.crt
  echo.
  pause
  exit /b 1
)

if /I "%~1"=="--check" (
  echo [INFO] Backend startup configuration check passed.
  echo [INFO] Elasticsearch password was loaded from: %ES_PASSWORD_FILE%
  echo [INFO] Elasticsearch CA certificate exists.
  exit /b 0
)

netstat -ano | findstr /R /C:":8080 .*LISTENING" >nul
if %errorlevel%==0 (
  echo [WARN] Port 8080 is already listening.
  echo [WARN] Stop the old backend process before starting a new one.
  echo.
  pause
  exit /b 1
)

echo [INFO] Backend directory: %BACKEND_HOME%
echo [INFO] Elasticsearch URI: %ELASTICSEARCH_URIS%
echo [INFO] Elasticsearch user: %ELASTICSEARCH_USERNAME%
echo [INFO] Elasticsearch index: %ELASTICSEARCH_INDEX_NAME%
echo [INFO] Elasticsearch password: loaded from local password file
echo.
echo [INFO] Starting backend now. Keep this window open.
echo.

cd /d "%BACKEND_HOME%"
call ".\mvnw.cmd" spring-boot:run

set "EXIT_CODE=%errorlevel%"
echo.
echo ScholarEase backend exited with code %EXIT_CODE%.
pause
exit /b %EXIT_CODE%
