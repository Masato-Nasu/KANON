@echo off
setlocal
cd /d "%~dp0"

set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" set "PS=%WINDIR%\System32\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%PS%" set "PS=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"

if not exist "%PS%" (
  echo Windows PowerShell could not be found at the standard Windows path.
  echo Open Windows PowerShell in this folder and run:
  echo   Set-ExecutionPolicy -Scope Process Bypass
  echo   .\BUILD_AND_INSTALL.ps1
  echo.
  pause
  exit /b 1
)

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0BUILD_AND_INSTALL.ps1"
set "RC=%ERRORLEVEL%"
echo.
if not "%RC%"=="0" echo Build script exited with code %RC%.
pause
exit /b %RC%
