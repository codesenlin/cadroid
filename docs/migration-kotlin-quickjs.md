# 壳子迁移记录：Kotlin 化 + JS 引擎评估

> 这份文档记录两件并行的事：把 cadroid 壳子从 Java 迁到 Kotlin，以及评估把核心脚本
> 的 JS 引擎从 Rhino 换成 QuickJS。目的是让后来的人（或压缩后的上下文）能接上进度。

## 一、Kotlin 化

### 目标与边界

44 个 Java 文件 / 4117 行，其中：

| 处理          | 文件                                             | 原因                                                                                         |
|---------------|--------------------------------------------------|----------------------------------------------------------------------------------------------|
| **迁移**      | `app/src/main/java/com/**`（36 个）              | 应用逻辑                                                                                     |
| **保留 Java** | `app/src/stub/**`（5 个）                        | GDT / TalkingData 的桩，必须与外部 SDK 的 ABI 逐字节一致，Kotlin 会加 `INSTANCE`、改方法签名 |
| **保留 Java** | `config/{standard,template}/Secret.java`（2 个） | 构建时生成 `Secret` 类用的来源模板                                                           |
| **保留 Java** | `javax/lang/model/SourceVersion.java`（1 个）    | 给 Rhino 用的 Java 8 API 桩                                                                  |

### 阶段

| 阶段 | 内容                                                                                                       | 状态         |
|------|------------------------------------------------------------------------------------------------------------|--------------|
| P0   | 接入 Kotlin（2.0.21 / jvmTarget 17）+ 首批 5 个无反射风险文件                                              | ✅ `b102f0a` |
| P1   | 12 个低风险文件（Activity/Service/Receiver/Preference/Provider）                                           | ✅ 3 个提交  |
| P2   | 6 个中风险（AdProvider / MCAdapter / XApplication / ScriptService / ScriptTileService / RhinoFrameLayout） | ✅ 2 个提交  |
| P3   | 4 个 JS 桥接面（ScriptInterface 422 / ScriptManager 329 / ScriptFileStream 353 / AnalyticsPlatform 52）    | ✅ 3 个提交  |
| P4   | 6 个类加载器（com/faendir/rhino_android/*）                                                                | ✅ 2 个提交  |
| P5   | 同步到 CA_Reforged/cadroid 并推送                                                                          | ✅ 已并入 subtree 方案（见下方进度更新） |

**结果：36 个应用代码文件全部 Kotlin 化，8 个 Java 刻意保留（stub 5 + config 模板 2 + javax 1）。**

### 迁移中踩到的语义差（都已修正）

1. **`android.content.Context` 与 `org.mozilla.javascript.Context` 同名** —— 只 import Rhino 那个，Android 的写全限定名
2. **包级私有类型不能出现在 public 方法返回类型里** —— `BaseAndroidClassLoader` 是包级私有，Kotlin 不允许 `createClassLoader` 返回它，上移到 `GeneratedClassLoader`
3. **Java 的 `protected` 含同包可见，Kotlin 的只对子类开放** —— `AndroidContextFactory` 在同包调 `reset()`，改成 `internal`
4. **`inner class` 里不能有 `companion object`** —— `TYPE_UPDATE`/`TYPE_CLICK` 挪到外层
5. **Java 的 setter 可能被 Kotlin 解析成私有字段** —— `DirectClassFile.setAttributeFactory` 必须显式调用方法

### 迁移时必须守住的约定

1. **JS 可见的方法保持 `getXxx`/`setXxx` 方法形式**，不要改成 Kotlin 属性。
   属性上的注解只进 Kotlin 元数据，`AndroidBridge.js` 的 Java 反射看不到；属性名也会被改写。
   参考 `Preference`：`getHideSplash()` 是方法，不是 `hideSplash` 属性。
2. **静态成员用 `companion object` + `@JvmStatic`**，常量用 `const val`（Java 侧仍是 `public static final`）。
3. **每次迁移后必须 `assembleDebug` 通过**，并用 `javap` 抽查被 JS 反射的类，确认签名未变。

### 每批的验证方式

```
./gradlew :app:assembleDebug
javap -p -classpath app/build/tmp/kotlin-classes/debug <被 JS 反射的类>
```

## 二、JS 引擎：Rhino → QuickJS

### 现状：Rhino 不只是引擎，它是「Java 桥」

脚本里直接访问 Java/Android 的地方：

| 用法                                                                 | 次数   |
|----------------------------------------------------------------------|--------|
| `java.*`                                                             | 167    |
| `android.*`                                                          | 174    |
| `Packages.*`                                                         | 1      |
| `instanceof java.lang.CharSequence` / `java.io.File` / `G.WebView` … | 数十处 |
| `getClass()`                                                         | 2      |

分布在 13 个脚本文件里（`CA/modules/*.js`、`CA/main.js`）。

壳子这边依赖 12 个 Rhino 类：

```
Context / Scriptable / SecurityController / GeneratedClassLoader / ContextFactory
WrapFactory / StackStyle / ScriptRuntime / Script / RhinoException / Kit / Function
```

这些是 Rhino 的 LiveConnect（JS 直接 new 一个 `java.io.File`、调 Android 方法、
按参数类型做重载解析）能力，**QuickJS 本身完全没有**——它只是一个纯 JS 引擎。

### 结论：这不是"换引擎"，是"重写一层桥"

QuickJS 换上去之后，必须自己实现：

1. `java` / `android` / `Packages` 三套命名空间，且支持运行时解析
2. `Class` / `Method` / `Field` 的包装，含**反射**（脚本里 `getDeclaredMethod` 用于版本探测）
3. 重载解析（Rhino 按参数类型选方法）
4. Java ↔ JS 双向调用：Java 侧 `@ScriptObject` 方法给 JS 调，JS 回调给 Java 用
5. `instanceof` 对 Java 对象的语义、`getClass()`、异常映射、`undefined`/`null` 区分

社区可用的 Android 绑定（都是"能跑 JS"级别，没有 Java 桥）：

- [jmir1/quickjs-android](https://github.com/jmir1/quickjs-android)
- [tylorvan/quickjs-android](https://github.com/tylorvan/quickjs-android)
- [seven332/quickjs-android](https://github.com/seven332/quickjs-android)

### 建议的推进方式

| 阶段   | 内容                                                                                                                          | 产出                                             |
|--------|-------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------|
| **E0** | 定义 `JsEngine` / `JsValue` 接口，把 `ScriptInterface`、`ScriptManager` 改成面向接口；先接一个 `RhinoEngine` 实现（行为不变） | 可测的抽象层，**同时让 P3 的 Kotlin 迁移变简单** |
| **E1** | 用 QuickJS 绑定实现 `QuickJsEngine`，先做到"能执行脚本、能调 `@ScriptObject` 方法"                                            | 最小可用                                         |
| **E2** | 实现 `java`/`android`/`Packages` 命名空间 + 反射 + 重载解析                                                                   | 脚本不改也能跑                                   |
| **E3** | 双引擎并存，用 feature flag 切换，跑同一套脚本做对比                                                                          | 可回滚                                           |
| **E4** | 性能实测（冷启动、补全延迟、内存），确认收益后再决定默认引擎                                                                  | 决策依据                                         |

### 先量再换

换引擎的动机是性能。建议 E0 之前先量三个数：

1. 冷启动到首屏可用的耗时
2. 智能补全单次触发到出结果的延迟
3. 常驻内存

如果瓶颈在 `CA.IntelliSense` 的算法（而不是解释器），优化算法可能比换引擎更快见效。

## 三、两件事的顺序

Kotlin 的 P1/P2 与引擎无关，可以继续做完；**P3（ScriptInterface / ScriptManager）
必须等 E0**，否则要迁移两遍。


## 四、进度更新（2026-10-30）

### P5：已不需要单独做

CA_Reforged 已从 submodule 改成 **git subtree**，`cadroid/` 直接是真实文件；
以后同步用：

```bash
git subtree pull --prefix=cadroid https://github.com/codesenlin/cadroid.git main --squash
```

### E0 第一步：✅ 完成

新增 `app/src/main/java/com/xero/ca/script/engine/`：

| 文件 | 内容 |
|------|------|
| `JsEngine.kt` | 引擎无关的抽象：`JsContext` / `JsScope` / `JsScript` 句柄 + `JsEngine` 接口（enterContext / initScope / compile / execute / callFunction / wrapAsScriptable / iCode 缓存） |
| `RhinoEngine.kt` | `JsEngine` 的 Rhino 实现：把原来散在 `ScriptManager`、`ScriptInterface` 里的 Rhino 细节（Context 参数、WrapFactory、Kit/缓存序列化、NativeJavaObject 包装、StackStyle / emptyArgs 补丁）全部收进来 |

`ScriptManager` 和 `ScriptInterface` **已经不再 import 任何 `org.mozilla.*`**，
行为与迁移前一致，`./gradlew :app:assembleDebug` 通过。

### 下一步：E1（QuickJS 最小可用）

1. 选绑定：`jmir1/quickjs-android`（维护活跃、支持 Android）
2. 新增 `QuickJsEngine`，先实现 `enterContext` / `initScope` / `compile` / `execute`
3. 跑同一份脚本，验证 `ScriptInterface` 的 `@ScriptObject` 方法能被 JS 调到
4. 这一步不用碰 `java` / `android` 命名空间（那是 E2 的桥）

### QuickJS 的 ABI 限制与自动回退（E1 实现要点）

QuickJS 是 native 库（`.so`），不管用哪个绑定，预编译产物都只覆盖部分 ABI：

| ABI | QuickJS | 说明 |
|-----|---------|------|
| arm64-v8a | ✅ | 主流 64 位 ARM |
| x86_64 | ✅ | 模拟器 / Chromebook |
| armeabi-v7a | ⚠️ | 32 位 ARM，部分绑定没有产物 |
| x86 / riscv64 / 其他 | ❌ | 小众架构，基本没有 |

所以引擎层要**有回退链**，已经铺好：

- `JsEngine.isAvailable(context)`：各引擎自己判断当前设备能不能用
- `JsEngines.select(context)`：按优先级挑第一个可用的（QuickJS 不可用就落到 Rhino）
- `ScriptManager`：选中引擎后初始化失败（例如 so 存在但加载失败）会再回退一次 Rhino
- Rhino 是纯 Java，**永远可用，是最终回退**

E1 接 QuickJS 时只要把 `QuickJsEngine()` 加到 `JsEngines.candidates()` 最前面，
回退逻辑不用改；32 位 ARM / 小众架构会自然走 Rhino，脚本行为一致（只是慢一些）。

排查用：`ScriptInterface.getScriptEngine()` 返回当前引擎名。

### 换引擎之后才能收掉的口子

- `gradle.properties` 的 `ca.rhinoCompat=true` → 可改 `false`
  （调试包不再需要 R8 dex，见 `docs/apk-build.md` 的「Rhino 兼容编译模式」）
- 随之可以删掉 `android.sync.suppressAgpWarnings=DEBUGGABLE_AND_MINIFIED_ENABLED`

### 还留在 Rhino 上的部分（E2 范围）

- `script/RhinoFrameLayout.kt`、`RhinoWebView.kt`、`RhinoWebSocketClientHelper.kt`、
  `RhinoWebSocketServerHelper.kt`、`WrappedUndefined.kt` —— 都直接继承 Rhino 的类型
- `com/faendir/rhino_android/*` 五个类加载器
