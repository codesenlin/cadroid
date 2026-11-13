# PEWS-API Gradle 包装模块

`PEWS-API/` 是从上游 `jocopa3/PEWS-API` 以 git subtree 引入的源码，保持上游原样。

这个模块只负责把源码接入 Android 构建：

- `preparePewsUpstream` 把 `PEWS-API/src` 同步到 `build/pews-upstream-src`
- 排除桌面 GUI / AWT：`ServerGUI`、`TextToBlockListener`、`ChatFormatCode`
- `src/main/java` 提供 Android 兼容实现：
  - `mcpews.WSEncrypt`：适配 Java-WebSocket 1.6
  - `mcpews.MCSocketServer`：适配新 `Draft_6455` / `onStart`
  - `mcpews.util.ChatFormatCode`：去掉 `java.awt.Color`，保留字符串常量

以后更新上游只需：

```bash
git subtree pull --prefix=PEWS-API https://github.com/jocopa3/PEWS-API.git master --squash
```

然后确认 `pews-api-wrapper/src/main/java` 的兼容层仍能通过编译。
