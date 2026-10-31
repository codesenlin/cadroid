# QuickJS（E2）设备冒烟测试

换绑定后（`io.github.taoweiji.quickjs:quickjs-android`）只有 Android AAR，
没有桌面 JVM 版，所以测试走真机/模拟器：

```bash
# 先起一个模拟器（x86_64 即可）
~/Android/Sdk/emulator/emulator -avd <AVD> -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -read-only &

# 跑测试（构建 → 安装 → 启动 → 打印 logcat）
tools/quickjs-device-test/run.sh

# 想等久一点 / 打更多日志
WAIT_SECONDS=30 LINES=120 tools/quickjs-device-test/run.sh
```

判断标准：

- `Script: 脚本引擎: QuickJS（ABI: ...）` —— 引擎选中
- `Script: QuickJS: prelude 已加载` —— 桥 + prelude 初始化成功
- `Script: Script Compiled in ...` —— 脚本能编译
- 后续报错位置就是当前 E2 的缺口（JS 对象/接口适配器等）
