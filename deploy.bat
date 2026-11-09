@echo off
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
set "DEPLOY_EXIT=%ERRORLEVEL%"
if "%~1"=="" (
    echo.
    pause
)
exit /b %DEPLOY_EXIT%
