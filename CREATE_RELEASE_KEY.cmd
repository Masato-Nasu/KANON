@echo off
setlocal
cd /d "%~dp0"

set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" set "PS=%WINDIR%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" set "PS=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"

if not exist "%PS%" (
  echo Windows PowerShell could not be found.
  pause
  exit /b 1
)

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0CREATE_RELEASE_KEY.ps1"
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" echo Release key setup exited with code %RC%.
pause
exit /b %RC%
