#!/usr/bin/env bash
# 脚本引擎 / QuickJS worker 真机冒烟测试：
#   默认构建（Rhino 主引擎 + quickjs-kt 侧载 worker）→ 安装 → 启动 → 收集 Script/CA 日志
# 前置：adb 已连上设备（真机无线调试：adb pair/connect；或模拟器）
set -uo pipefail
cd "$(dirname "$0")/../.."

ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  if [ -x "$HOME/Android/Sdk/platform-tools/adb" ]; then
    ADB="$HOME/Android/Sdk/platform-tools/adb"
  else
    echo "找不到 adb（可设置环境变量 ADB=...）"; exit 1
  fi
fi

echo "== 构建 debug APK =="
./gradlew :app:assembleDebug --console=plain -q || exit 1
APK=app/build/outputs/apk/debug/app-debug.apk

echo "== 安装 =="
"$ADB" install -r -t "$APK" >/dev/null || exit 1

echo "== 启动并收集日志 =="
"$ADB" logcat -c
"$ADB" shell am force-stop com.xero.ca
"$ADB" shell am start -n com.xero.ca/.MainActivity -W >/dev/null 2>&1
sleep "${WAIT_SECONDS:-18}"
"$ADB" logcat -d -s Script:* CA:* AndroidRuntime:E | tail -n "${LINES:-80}"

echo
echo "== 关键行 =="
"$ADB" logcat -d -s Script:* CA:* | grep -E "Script Compiled|脚本执行完毕|QuickAccel|E CA" | tail -n 10
