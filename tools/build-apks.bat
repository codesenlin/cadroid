@echo off
setlocal EnableExtensions
chcp 65001 >nul

rem 从 AAB 生成 .apks（分体包），Windows 版 tools/build-apks.sh
rem 用法：set JAVA_HOME=C:\path\to\jdk 然后运行本文件
cd /d "%~dp0.."

if not defined JAVA_HOME (
    echo 需要设置 JAVA_HOME 1>&2
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JAVA_HOME 下找不到 bin\java.exe 1>&2
    exit /b 1
)
if not exist "tools\bundletool.jar" (
    echo 缺少 tools\bundletool.jar，下载命令见 tools\build-apks.bat 头部注释 1>&2
    exit /b 1
)

set "SIG=app\signatures"
set "PASS=android123"
if exist "%SIG%\release.password" set /p PASS=<"%SIG%\release.password"

echo ==^> bundleRelease
call gradlew.bat :app:bundleRelease --console=plain -q
if errorlevel 1 exit /b 1

if not exist "build\dist" mkdir "build\dist"

echo ==^> build-apks
"%JAVA_HOME%\bin\java.exe" -jar tools\bundletool.jar build-apks ^
    --bundle=app\build\outputs\bundle\release\app-release.aab ^
    --output=build\dist\命令助手.apks ^
    --ks="%SIG%\release.keystore" --ks-key-alias=testkey ^
    --ks-pass="pass:%PASS%" --key-pass="pass:%PASS%" ^
    --overwrite
if errorlevel 1 exit /b 1

echo ==^> 完成：build\dist\命令助手.apks
"%JAVA_HOME%\bin\java.exe" -jar tools\bundletool.jar get-size total ^
    --apks=build\dist\命令助手.apks --dimensions=ABI
exit /b %errorlevel%
