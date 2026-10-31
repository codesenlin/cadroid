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

### E1 状态：✅ 引擎级最小可用（2026-10-30）

绑定选型：**[app.cash.quickjs:quickjs-android:0.9.2](https://repo1.maven.org/maven2/app/cash/quickjs/quickjs-android/0.9.2/)**
（Maven Central，Apache-2.0，AAR 里自带四种 so：arm64-v8a / armeabi-v7a / x86 / x86_64）

| 项 | 状态 |
|----|------|
| `QuickJsEngine`（create / close / compile / execute） | ✅ |
| 字节码磁盘缓存（`script.obj` 换成 qjsc bytecode） | ✅ |
| 启用开关 `-Pca.jsEngine=quickjs` → `BuildConfig.JS_ENGINE` | ✅ |
| `java` / `android` / `Packages` 命名空间 | ❌ E2 |
| `ScriptInterface` 注入（`initScope`） | ❌ E2 |
| `callFunction` / `wrapAsScriptable`（Java ↔ JS 双向调用） | ❌ E2 |

**默认仍是 Rhino**（`BuildConfig.JS_ENGINE = "rhino"`），行为完全不变。
带 QuickJS 依赖后 APK 约 +3.2 MB（四个 ABI 的 libquickjs.so）；
E4 决定默认引擎时再考虑按 ABI 裁剪。

注意：`-Pca.jsEngine=quickjs` 时真实脚本跑不了（脚本一上来就调 `ScriptInterface` / `java.*`），
目前只用于引擎自测。

回退条件：**只有 QuickJS 真的不可用才回退 Rhino**——判断标准是
`QuickJs.create()` 能不能成功（也就是 so 能不能加载），不按架构一刀切。
这个 AAR 自带 arm64-v8a / armeabi-v7a / x86 / x86_64 四种 so，这四种架构
都会尝试 QuickJS；riscv64 / mips 这类没有 so 的架构才会落到 Rhino。

### E2 第一步：Java 桥骨架（2026-10-30）

app.cash.quickjs 的接口代理只支持基础类型，所以桥做成 **String RPC**：

```
JS (prelude)  ── __caBridge.call(requestJson) ──▶  QuickJsBridgeHost（反射）
      ▲                                                   │
      └──────────── responseJson（带类型标签）◀────────────┘
```

新增文件：

| 文件 | 作用 |
|------|------|
| `script/engine/quickjs/QuickJsBridge.kt` | 桥接口（`call(String): String`） |
| `script/engine/quickjs/QuickJsBridgeHost.kt` | 反射实现：类/字段/方法/构造/数组/instanceof，参数重载评分 + varargs |
| `app/src/main/resources/quickjs-prelude.js` | JS 侧：`java`/`android`/`Packages`、Class/对象 Proxy 包装、`ScriptInterface` 注入、`__caCallHook` |
| `tools/quickjs-bridge-smoke/` | 桌面 JVM 冒烟测试（用 quickjs-jvm 跑，不依赖设备） |

已支持：静态/实例字段与方法、构造、`.class`、`Class.forName` /
`getDeclaredMethod` + `invoke`（含 varargs）、数组、`instanceof`、
Java 异常消息、Java 侧调 JS 全局函数（`callFunction`）。

冒烟测试（桌面 JVM）通过：方法/重载/字段/构造/数组/instanceof/异常/varargs/钩子共 16 项。

**还没做（E2 后续）**：把 JS 函数/对象传给 Java（回调、接口实现、Java Proxy）、
`wrapAsScriptable` 的完整语义、Java 异常到 JS 异常类的映射、以及真实脚本在设备上的验证。

### E2 真机/模拟器验证（2026-10-30）

在 x86_64 Android 模拟器（API 37）上装 `-Pca.jsEngine=quickjs` 的 debug 包，循环验证：

| 现象 | 原因 / 修复 |
|------|-------------|
| QuickJS 选中、prelude 加载失败 `not a function` | debug 包 R8 混淆了 `QuickJsBridge` 接口方法名 → 加 keep 规则 |
| `android.os.Handler.<init>` 选了 `Handler(Callback)` 重载 | 参数打分没检查 handle 的实际类型 → 按 `isInstance` 给分 |
| 脚本里 `if (module.onCreate)` 误判为存在 | 桥对所有属性都返回函数；改成 host 区分「字段 / 方法 / 不存在」，不存在返回 undefined |
| `new java.lang.Runnable({...})` → `不能把 JS 对象传给 Java` | **当前绑定的硬限制**：app.cash.quickjs 没有 Java→JS 同步调用，做不了接口适配器 |

进度：QuickJS 已能在模拟器上加载 prelude、编译整份脚本（约 6.3s，模拟器软件渲染下）、
执行到模块加载阶段；**下一个大坑是 `new java.lang.Runnable({...})` 这种"用 JS 对象实现 Java 接口"**，
真实脚本里到处在用（Runnable / OnClickListener / Scriptable / OnTouchListener …）。

结论：app.cash.quickjs 的接口代理只支持基础类型，**没有 Java→JS 的同步回调**，
而接口适配器需要它。要完成 E2 需要换一个支持"把 JS 函数当 Java 对象/回调"的绑定，
候选：`io.github.taoweiji.quickjs:quickjs-android:1.4.6`（Maven Central，Apache-2.0，
四种 ABI，提供 `JSFunction.call` / `registerJavaMethod` / `addJavascriptInterface`）。

### E2.9：修正 G.ui 语义 + 脚本侧反射缓存（2026-10-31 上午）

E2.8 里把 `runOnUiThread` 改成 post 解决了 ANR，但引入新问题：

- 脚本里有一部分代码依赖"已经在主线程时 `G.ui` 同步执行"（例如
  `Common.showProgressDialog` 的 `self.init(o)` 里 `o.text = new TextView(...)`，
  调用方紧接着 `o.text.setText(...)`）——post 之后 `o.text` 还是 undefined
- Rhino 时代这类代码跑在主线程回调里，`runOnUiThread` 本身是内联执行的

→ **还原 `runOnUiThread` 内联语义**（不再拦截改 post）。ANR 靠其他优化消除：
反射缓存 + 去掉启动网络检查 + 回调耗时已降到 2s 以内（< 5s ANR 阈值）。

脚本侧（CA 仓库）补充：
- `L.js findDeclaredMethod` 结果缓存（含未命中）：`NoSuchMethodException`
  在桥里要走原生栈，缓存后布局参数生成不再反复抛异常
- `findConstant` 结果缓存，并且常量先试大写（脚本常量基本都大写，
  少一次必然失败的反射 + 异常）

### E2.8：真机性能优化 —— 从"极卡/ANR"到"速度奇快"（2026-10-31 上午）

真机验证后反馈"极卡、总是无响应"，逐层定位并优化：

1. **反射查找缓存**（最大收益）
   - `findField` / bean getter/setter / `hasMethod` / 构造器候选全部按
     (类, 名称, 签名) 缓存（找不到也缓存）
   - `findField` 补递归接口常量（`Spanned.SPAN_INCLUSIVE_EXCLUSIVE`
     这类接口里的 static final 不会被实现类继承，之前直接丢了）
   - 之前每个属性访问都触发几次 `NoSuchFieldException` + `getMethod`
     异常构造（毫秒级），缓存后降到微秒级

2. **`runOnUiThread` 从内联执行改成 post**
   - QuickJS 的 JS 在主线程，`G.ui(...)` 走 `Context.runOnUiThread` 会在
     主线程内联执行 → 整个主界面构建变成一个 9.8s 大块 → 必 ANR
   - 桥里拦截：主线程上的 `runOnUiThread` 改为 `Handler.post`，
     UI 构建分片，主线程有机会处理输入/绘制

3. **prelude 缓存**
   - 方法闭包缓存（含预编码请求 JSON）：同一方法反复调用不再先探测
   - 类静态方法闭包缓存；包名解析结果缓存（`java.lang.System` 这类高频访问）
   - 去掉属性未命中的调试日志（每次探测都多一次 RPC）

4. **脚本侧：QuickJS 跳过启动自动更新检查**
   - 更新检查是同步网络（连接超时 5s），QuickJS 下会卡主线程 → ANR
   - `Updater.initialize` 在 QuickJS 下直接 return；用户手动检查不受影响

效果：主界面打开从"9.8s 单块 + ANR"变成若干 300~1200ms 分片，实测
"速度奇快"；`setSpan` 等接口常量问题也随反射修复一起解决。

### E2.7：真机验证 + 接口对象留在 JS 侧（2026-10-31 上午）

换真机（OPPO PERM10 / API 34，无线 ADB 配对）验证，**命令助手主界面已经能正常渲染**。

这轮修的两个关键语义：

1. **SAM 适配器的 `toString` 不能转给 JS 函数**
   - `newFunctionAdapter` 原来对所有方法都 `call(fn, ...)`，于是 Java 侧
     打印/日志里调一下 `toString()` 就会"凭空执行" JS 函数（表现为
     `popup.exit` 之类莫名被调用）
   - 现在统一给 `toString`/`hashCode`/`equals` 返回固定值

2. **`new SomeInterface({...})` 的结果留在 JS 侧**
   - Rhino 里接口对象就是那个 JS 对象，方法调用是 JS 直接执行、
     返回的 JS 对象还是同一个对象
   - 之前 QuickJS 每次构造都生成 Java Proxy，JS→JS 调用绕一圈 Java，
     返回 JS 对象时还会丢成 null（`getController` 取 `getItem(-1)` 拿到 null）
   - 现在 prelude 对接口构造返回**直接代理真实 JS 实现**的包装：
     方法在 JS 里直接调用；只有当它作为参数传给 Java API 时，
     参数转换层才按需生成 Java 适配器

真机验证要点：
- 真机 arm64 上 GDT/广告链路正常（模拟器 x86 是缺 so 的噪音）
- 无线调试要 `platform-tools` ≥ 30（系统自带 28 没有 `adb pair`）

### E2.6：启动链路打通 + Class 语义统一（2026-10-31 上午）

**启动链路**：之前 QuickJS 路径下脚本一直不执行，根因是
`ScriptInterface.callIntent` 在有启动页时用 `ACTION_PREPARE`（只编译不跑），
真正触发 `startScript()` 的是广告回调；而启动页 `onSplashActivityCreate`
需要 `ScriptInterface` 实例非空，晚于启动页创建就会直接 finish，广告回调也不会走。
→ `ScriptManager.prepareScript` 现在**先创建 ScriptInterface** 再起线程。

**Class 语义**：Class 值原来编码成 handle（JavaObject 包装），导致
`cls == java.lang.Object`、`clazz == java.lang.Long.TYPE` 这类比较永远为 false，
`findDeclaredMethod` 递归不终止。改成：
- Class 值统一编码成类名（`{t:"c"}`），prelude `getClass(name)` 带缓存，
  类和 `Class` 实例引用是同一个 JS 代理
- 类代理 get 在静态成员之外，回退到 **Class 实例方法**（getMethod/getConstructor/
  getSuperclass/isPrimitive…），走新增的 `classRef` RPC 拿 Class 实例 handle

**其它修复**：
- `Error.captureStackTrace` shim（之前补过，这轮确认生效）
- 接口适配器（newAdapter / newObjectAdapter）遇到 JS 对象没实现的方法时返回
  类型默认值，而不是抛 IllegalStateException（对应 Rhino 的 undefined）
- 脚本里 `view.tag = holder` 这类 Object 属性加 JS 侧"影子属性"，
  取回来还是同一个 JS 对象

**当前进度**：脚本能在 QuickJS 上完整跑完顶层执行、模块初始化，
进入许可协议弹窗构建；剩下的是弹窗/视图构建过程中的零星 Rhino 语义差异。

### E2.5：主线程执行 + 回调式初始化（2026-10-31 早）

昨晚卡在「JS 线程阻塞 + 等 UI 回调」，这轮换了两个大方向：

1. **QuickJS 整个生命周期放到 Android 主线程**
   - `JsEngine.requiresMainThread`（Rhino=false，QuickJS=true）
   - ScriptManager：QuickJS 下用主线程 Looper + Handler，不自己 `Looper.loop()`
     （Rhino 保持原样）
   - 原因：脚本大量直接操作 View/Handler，Android 要求 UI 只能在主线程；
     Rhino 多线程时代靠「回调在哪个线程就在哪跑」自然满足
   - 副作用：脚本里的同步网络请求会触发 `NetworkOnMainThreadException`，
     QuickJS 实验包里先放宽 StrictMode（正式采用前要把这些网络调用改异步）

2. **CA 初始化改成回调式**（CA.js / Threads.js，CA 仓库提交）
   - `CA.initialize` 拆出 `initializeAfterAgreement`；QuickJS 下
     `showAgreement(callback)` 等用户点完协议再继续
   - `Threads.awaitPromise` 在 QuickJS 下只触发异步流程、立即返回默认值

桥继续补的语义：
- `Error.captureStackTrace`（Log.throwError 用的 V8/Rhino API）补 shim
- 类代理作为 `Class` 参数（`clazz.getConstructor(android.content.Context)`）
- JavaBean 属性：`view.tag` ↔ `getTag()/setTag()`
- 字段写入支持 Object 字段（`put` 走 raw 值，可以存 JS 对象）
- 包装类（Integer/Long）作为基本类型参数

当前进度：Rhino 基线在模拟器上能正常显示协议页并初始化；
QuickJS 路径能跑过协议、模块初始化，进入 **MainView 视图构建**，
剩下的是零星的 Rhino 语义差异（如 LayoutParams 传 null 的边界情况）。
模拟器（x86_64、软件渲染）非常慢，UI 级验证建议换真机。

### E2.4：Rhino 兼容垫片 + 阻塞式 API 的坑（2026-10-30 夜）

继续在模拟器上推进，真实脚本现在能跑完大部分模块初始化，补上的兼容点：

| 问题 | 修复 |
|------|------|
| `Packages["[B"]` / `Packages.java.lang.String` | `Packages` 改成 Proxy，支持类名/数组描述符直接取类 |
| `Packages.x.Y(...)` 不写 new（DexPlugin） | 类代理补 `apply` 陷阱 |
| `new org.mozilla.javascript.Scriptable({...})` 当动态命名空间（L.js） | prelude 里直接用 JS Proxy 模拟，属性访问走 `get`/`put`/`has` |
| Java 里访问 `obj.STATIC_FIELD` / `obj.staticMethod()`（`ctx.STORAGE_SERVICE`） | 实例 get/call 回退静态成员 |
| Java Long/Integer 参与 `String()`/比较（DBSettings） | JavaObject 实现 `Symbol.toPrimitive` |
| 数字/布尔/数组传给 String 参数 | 按 Rhino 规则转成字符串 |
| JS 对象传给接口参数（`createFrameLayout({...})`） | 自动生成对象适配器（按方法名取 JS 函数） |
| `Class.getMethod` 按实例 vs 静态调用 | 桥区分 `c`（静态）/`h`（实例），Class 对象也能调实例方法 |
| `Threads.await*` 阻塞等 Future（JS 线程被占） | CA 的 Threads.js 加 QuickJS 分支：同步等待直接内联执行 |

**当前卡点（下一步的核心问题）**：`CA.showAgreementSync` 这类
「JS 线程阻塞 + 等 UI 回调」的同步写法。QuickJS context 是单线程的，
JS 线程阻塞时 UI 线程的回调没法再进 JS，会死锁；`Semaphore.acquire` 那里就卡住了。

两个方向：

1. **（推荐）异步化**：在 CA.js 里给 QuickJS 加分支，把 `showAgreementSync` /
   其它 `Threads.await*` 的同步调用改成回调/续体风格（`showAgreement` 本来就是回调式）
2. 让 QuickJS 支持跨线程重入（native 层不安全，不建议）

### E2.3：原生值 RPC + 线程调度（2026-10-30）

在模拟器上继续跑真实脚本，逐步补齐：

| 问题 | 修复 |
|------|------|
| `new java.lang.Runnable(function(){...})` / 方法参数里直接传 JS 函数 | 桥新增 **raw RPC**（`__caRaw(op, args)`）：参数以 JSArray 原生值传过来，宿主按目标类型转换；函数→接口参数自动生成 SAM 适配器 |
| `new android.content.DialogInterface.OnClickListener({...})` 等嵌套接口 | 桥解析嵌套类 `Outer$Inner`（`android.os.Build.VERSION`、`DialogInterface.OnClickListener`） |
| Java 回调在 UI 线程执行，QuickJS 单线程 context 被跨线程访问 | `ScriptManager` 把 JS 线程/Looper 交给引擎；适配器回调 `post` 回 JS 线程执行（void 异步、有返回值 latch 等 30s） |
| `org` / `com` / `javax` 未定义 | prelude 补顶层命名空间 |
| `android.util.Log.e("CA", e)` 把 JS 对象当 String | 宿主用 JS 的 `String(value)` 做隐式 toString |
| `java.lang.Class.forName(...).getMethod(name, [Class...])` | Class 值改回 handle 包装；varargs 支持「最后一个参数直接传 JS 数组」 |
| `org.mozilla.javascript.Context.getCurrentContext()` 返回 null（L.js / DBSettings.js / DexPlugin.js 用） | prelude 提供 Rhino Context 垫片：`getWrapFactory().wrapJavaClass` / `toObject` / `getApplicationClassLoader` |
| `ScriptInterface.wrapAsScriptable` | QuickJsEngine 用 Rhino 的 NativeJavaObject 实现（脚本 DBSettings 走同一条路径） |

当前进度：脚本能编译、执行到**模块初始化阶段**，剩下的是个别模块的 Rhino 兼容行为差异
（`new org.mozilla.javascript.Scriptable({...})` 被当成模块对象、`cx.toObject` 语义等）。

### E2.2：换绑定 + 接口适配器（2026-10-30）

app.cash.quickjs 没有 Java→JS 同步调用，做不了 `new java.lang.Runnable({...})`，
所以把绑定换成了 **`io.github.taoweiji.quickjs:quickjs-android:1.4.6`**
（Maven Central，Apache-2.0，4 种 ABI，暴露 `JSFunction.call` / `registerJavaMethod`）。

桥的新形态：

```
JS prelude ── __caCall(JSON) ──▶ QuickJsBridgeHost（反射）
           ── __caAdapter(className, jsObject) ──▶ 创建 java.lang.reflect.Proxy
Java 回调 ── Proxy.invoke ──▶ JSFunction.call（com.quickjs 提供）
```

- `new SomeInterface({ 方法: function })` 在 prelude 里识别接口 → 走 `__caAdapter`
- 回调时 Java 参数编码成 JSON、JS 函数返回值再转回 Java
- `ScriptInterface.wrapAsScriptable` 在 QuickJS 下隐藏，脚本自动走普通对象分支

模拟器验证：QuickJS 能选中、prelude 加载、编译脚本（3.6s）、
**接口适配器被成功调用**（Runnable 的 run 跑进了 JS 函数）。

### E2.3（下一个）：JS 线程调度

当前卡住的点：`new java.lang.Runnable({run:...})` 被 Java 在 **UI 线程**执行，
适配器直接在那个线程调 `JSFunction.call`，而 QuickJS 的 context 是**单线程**的，
跨线程调用会读到坏掉的全局状态（表现为 `android.os.Build.VERSION` 变 undefined）。

Rhino 时代脚本可以在多线程（UI 线程回调）里跑，因为每个线程各自 `Context.enter`；
QuickJS 不行。需要：

1. 适配器代理把回调 **marshal 回 JS 线程**（ScriptManager 的 Looper）执行
2. void 方法可以异步 post；有返回值的方法要 latch 等待结果
3. 同一线程内的嵌套回调要避免死锁

### QuickJS 的 ABI 限制与自动回退（E1 实现要点）

QuickJS 是 native 库（`.so`），不管用哪个绑定，预编译产物都只覆盖部分 ABI：

| ABI | QuickJS | 说明 |
|-----|---------|------|
| arm64-v8a | ✅ | 主流 64 位 ARM |
| x86_64 | ✅ | 模拟器 / Chromebook |
| armeabi-v7a | ✅ | 本 AAR 自带 so，能加载就用 |
| x86 | ✅ | 本 AAR 自带 so |
| riscv64 / 其他 | ❌ | 没有 so，回退 Rhino |

所以引擎层要**有回退链**，已经铺好：

- `JsEngine.isAvailable(context)`：各引擎自己判断当前设备能不能用
- `JsEngines.select(context)`：按优先级挑第一个可用的（QuickJS 不可用就落到 Rhino）
- `ScriptManager`：选中引擎后初始化失败（例如 so 存在但加载失败）会再回退一次 Rhino
- Rhino 是纯 Java，**永远可用，是最终回退**

E1 接 QuickJS 时只要把 `QuickJsEngine()` 加到 `JsEngines.candidates()` 最前面，
回退逻辑不用改；32 位 ARM / 小众架构会自然走 Rhino，脚本行为一致（只是慢一些）。

排查用：`ScriptInterface.getScriptEngine()` 返回当前引擎名。

### 先量再换：桌面 JVM 参考数据（2026-10-30）

设备不在线，先用 `app.cash.quickjs:quickjs-jvm:0.9.2`（同一份 QuickJS 代码的 JVM 版）
和 `rhino-1.9.2.jar` 在桌面 JVM 上对比。Rhino 按 Android 上的配置 `optimizationLevel=-1`。
**这不是设备数据，只是方向性参考**；最终结论要等 E2 桥做完后在真机上按真实脚本测（E4）。

| 项目 | QuickJS | Rhino | 结论 |
|------|---------|-------|------|
| 编译 ~250KB 脚本 | 8 ms | 29 ms | QuickJS 快 ~3.6x |
| 冷启动（首次加载 so / 初始化上下文） | 150 ms（首次 load so） | ~1 ms（enter+initStandardObjects） | 首次加载有成本，之后创建很快 |
| 表构建+访问 20k | 181 ms | 141 ms | Rhino 快 1.3x |
| 正则匹配 50k | 512 ms | 254 ms | **Rhino 快 2x**（Rhino 用 java.util.regex，QuickJS 是自带的 libregexp） |
| 纯函数调用 1M | 958 ms | 1211 ms | QuickJS 快 1.3x |

解读：

- QuickJS 的优势在**编译速度**（冷启动有利）和部分纯 JS 循环；
- **正则/字符串密集**的场景 Rhino 反而更快——而补全/匹配正是这类负载；
- 真实脚本还有大量 Java ↔ JS 桥调用：Rhino 走 LiveConnect，QuickJS 走 E2 要自己写的桥，
  这部分开销未知，可能吃掉上面的收益；
- 所以 E4 在设备上用**真实脚本**测冷启动 / 补全延迟 / 内存，再决定默认引擎。

### 换引擎之后才能收掉的口子

- `gradle.properties` 的 `ca.rhinoCompat=true` → 可改 `false`
  （调试包不再需要 R8 dex，见 `docs/apk-build.md` 的「Rhino 兼容编译模式」）
- 随之可以删掉 `android.sync.suppressAgpWarnings=DEBUGGABLE_AND_MINIFIED_ENABLED`

### 还留在 Rhino 上的部分（E2 范围）

- `script/RhinoFrameLayout.kt`、`RhinoWebView.kt`、`RhinoWebSocketClientHelper.kt`、
  `RhinoWebSocketServerHelper.kt`、`WrappedUndefined.kt` —— 都直接继承 Rhino 的类型
- `com/faendir/rhino_android/*` 五个类加载器
