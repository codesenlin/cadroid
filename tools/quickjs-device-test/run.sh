#!/usr/bin/env bash
# QuickJS（E2）在 Android 设备/模拟器上的冒烟测试：
#   构建带 -Pca.jsEngine=quickjs 的 debug 包 → 安装 → 启动 → 收集 Script/CA 日志
# 前置：adb 已连上设备（模拟器：emulator -avd <name> -no-window ...）
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT="$(pwd)"

echo "== 构建 quickjs debug APK =="
./gradlew :app:assembleDebug -Pca.jsEngine=quickjs --console=plain -q || exit 1
APK=app/build/outputs/apk/debug/app-debug.apk

echo "== 安装 =="
adb install -r -t "$APK" >/dev/null || exit 1

echo "== 启动并收集日志 =="
adb logcat -c
adb shell am force-stop com.xero.ca
adb shell am start -n com.xero.ca/.MainActivity -W >/dev/null 2>&1
sleep "${WAIT_SECONDS:-18}"
adb logcat -d -s Script:* CA:* AndroidRuntime:E | tail -n "${LINES:-60}"
