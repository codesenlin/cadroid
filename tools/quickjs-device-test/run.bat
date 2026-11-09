@echo off
setlocal EnableExtensions
chcp 65001 >nul

rem 脚本引擎 / QuickJS worker 真机冒烟测试，Windows 版 run.sh
rem 前置：adb 已连接设备；脚本会构建 debug APK、安装、启动并收集日志。
cd /d "%~dp0..\.."

if not defined ADB set "ADB=adb"
if not exist "%ADB%" where "%ADB%" >nul 2>nul
if errorlevel 1 (
    if exist "%USERPROFILE%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB=%USERPROFILE%\Android\Sdk\platform-tools\adb.exe"
    ) else if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    ) else (
        echo 找不到 adb（可设置环境变量 ADB=...） 1>&2
        exit /b 1
    )
)

if not defined WAIT_SECONDS set "WAIT_SECONDS=18"
if not defined LINES set "LINES=80"
set "APK=app\build\outputs\apk\debug\app-debug.apk"

echo == 构建 debug APK ==
call gradlew.bat :app:assembleDebug --console=plain -q
if errorlevel 1 exit /b 1

echo == 安装 ==
"%ADB%" install -r -t "%APK%" >nul
if errorlevel 1 exit /b 1

echo == 启动并收集日志 ==
"%ADB%" logcat -c
"%ADB%" shell am force-stop com.xero.ca
"%ADB%" shell am start -n com.xero.ca/.MainActivity -W >nul 2>nul
timeout /t %WAIT_SECONDS% /nobreak >nul

"%ADB%" logcat -d -s Script:* CA:* AndroidRuntime:E | powershell -NoProfile -Command "$input | Select-Object -Last ([int]$env:LINES)"

echo.
echo == 关键行 ==
"%ADB%" logcat -d -s Script:* CA:* | powershell -NoProfile -Command "$input | Select-String -Pattern 'Script Compiled|脚本执行完毕|QuickAccel|E CA' | Select-Object -Last 10"
exit /b 0
