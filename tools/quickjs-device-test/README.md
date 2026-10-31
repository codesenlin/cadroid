# 脚本引擎 / QuickJS worker 冒烟测试

当前架构：**Rhino 跑脚本主逻辑**，QuickJS 只作为纯计算侧载 worker
（`QuickJsWorker` + 脚本侧 `QuickAccel`，引擎绑定 `quickjs-kt`）。

这个脚本做一次端到端冒烟：默认构建 → 安装 → 启动 → 打印 Script/CA 日志。

```bash
# adb 已连上设备（真机无线调试或模拟器均可）
tools/quickjs-device-test/run.sh

# 想等久一点 / 打更多日志
WAIT_SECONDS=30 LINES=120 tools/quickjs-device-test/run.sh
```

## 判断标准

| 日志 | 含义 |
|---|---|
| `Script: Script Compiled in ...` / `脚本执行完毕` | 脚本正常编译执行（Rhino） |
| `Script: QuickAccel: QuickJS worker 可用（quickjs-kt 16KB native）` | 侧载 worker 初始化成功 |
| `Script: QuickAccel: 命令库同步到 worker ... ok=true` | 首次用到联想匹配时命令库同步成功 |
| `E CA` / `FATAL EXCEPTION` / `ANR in com.xero.ca` | 有问题，看对应堆栈 |

worker 不是必须的：库缺失或 API < 23 时启动日志会少一行 worker 可用，
脚本会自动走原 JS 实现，功能不受影响。

## 检查 16KB page 对齐（Android 15+ 设备要求）

```bash
unzip -o app/build/outputs/apk/debug/app-debug.apk 'lib/*' -d /tmp/apk-libs
for f in $(find /tmp/apk-libs -name '*.so'); do
  echo "$f: $(readelf -lW "$f" | awk '/LOAD/{print $NF}' | sort -u | tr '\n' ' ')"
done
# 期望：全部 0x4000
```
