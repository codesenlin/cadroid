# 为什么 QuickJS 只做「侧载」，以及性能前后对比

> 这份文档是给后来者的**结论 + 证据**：2026-10 我们完整实现过 QuickJS 主引擎
> （含 Java 反射桥），真机跑通后又整体删掉，最终只保留 QuickJS 作为纯计算侧载
> worker。**如果你正打算把主引擎从 Rhino 换成 QuickJS：先读第 6 节的验证清单。**
>
> 相关文档：[迁移过程记录](migration-kotlin-quickjs.md)（E0-E2.17 的详细踩坑）、
> [APK 构建与 16KB 说明](apk-build.md)、
> CA 仓库 `docs/data-and-accel.md`（引擎/Java 加速选型数据）。

## 一、一句话结论

**QuickJS 引擎本身快，但它的「Java 通信桥」比 Rhino 慢几十倍；而这套脚本的瓶颈
恰恰是每操作上千次的 Java/UI 调用。** 所以：

- 主引擎：**Rhino**（反射/UI 密集逻辑最快，JS 直接 JNI 绑定 Java，调用微秒级）
- QuickJS：只做**纯计算侧载 worker**（JSON 进 / JSON 出，不接 Java 反射桥）
- 两边各干擅长的：Rhino 负责 99% 的脚本逻辑，QuickJS 负责算法/数据处理

## 二、我们试过什么（2026-10 的完整迁移）

| 阶段 | 内容 | 结果 |
|---|---|---|
| E0 | 抽出 `JsEngine` / `JsContext` / `JsScope` 抽象 | ✅ 保留 |
| E1 | 接入 QuickJS 绑定，跑通引擎最小可用 | ✅ 当时 |
| E2.1-E2.9 | **手写完整 Java 桥**：`java`/`android`/`Packages` 命名空间、反射、重载解析、`instanceof`、SAM/接口适配器、Spanned 常量、异常映射……+ JS 侧 prelude 代理层 | ✅ 真机跑通主界面 |
| E2.10-E2.12 | 双引擎自适应（看门狗 + 健康回退 + 同进程切换）、架构转向侧载 | ⚠️ 复杂度高 |
| E2.15-E2.16 | 16KB page 兼容、换 `quickjs-kt` | ✅ 保留 |
| E2.16-E2.17 | **删除 QuickJS 主引擎全部代码**（桥 / prelude / 健康监控 / 引擎选择器） | ✅ 合计 -2246 行 |

主引擎相关代码最多时：**Kotlin ~1600 行**（`QuickJsBridgeHost` 1165 + `QuickJsEngine`
300 + 健康监控/选择器 134）+ **JS prelude 497 行**，加上持续打补丁的十几个兼容坑
（G.ui 语义、`httpGetAsync` 回调 NPE、`Context disposed` 崩溃、重载/字段/接口
反射边界……），现已全部删除。

## 三、为什么主引擎迁移不划算（数据）

### 3.1 引擎本身不慢——这是最容易误判的地方

| 指标（真机 OPPO PERM10 / Android 14 / arm64） | Rhino | QuickJS | 结论 |
|---|---|---|---|
| 编译 2.7 MB 脚本 | 5~6 s | **645 ms** | QuickJS 快得多 |
| 脚本顶层执行 | 107~110 ms | 307 ms | 都被"编译"掩盖 |
| 纯 JS 计算（n=50000 排序+取模） | 2400~2471 ms | **49~61 ms** | **QuickJS 快 ~40-47x** |

桌面 JVM 参考（`rhino-1.9.2` vs `quickjs-jvm`，仅方向性参考）：

| 指标 | Rhino | QuickJS | 结论 |
|---|---|---|---|
| 编译 ~250 KB 脚本 | 29 ms | **8 ms** | QuickJS ~3.6x |
| 表构建+访问 20k | 141 ms | **181 ms** | Rhino 快 1.3x |
| 正则匹配 50k | 254 ms | **512 ms** | **Rhino 快 2x**（用 java.util.regex） |
| 纯函数调用 1M | 1211 ms | **958 ms** | QuickJS 快 1.3x |

**所以"QuickJS 比 Rhino 快"只在纯计算成立，而且不是所有计算都成立。**

### 3.2 真正贵的是桥通信

QuickJS 没有 LiveConnect，每个 `java.*` / `android.*` 调用都要走我们自己实现的
桥：JS 侧拼 JSON → JNI → Kotlin 解析 → 反射 invoke → 编码结果 JSON → JS 解析。

| 项目 | 实测 |
|---|---|
| 单次纯 JSON RPC（`__caCall`，如 `classExists`） | 0.3~0.5 ms |
| 单次 raw 方法调用（`__caRaw`，含反射 invoke） | **0.7~1.2 ms** |
| 单次"类代理属性 + 方法调用" | ~2 ms |
| Rhino 同等调用 | **微秒级**（直接 JNI 绑定） |

而命令助手脚本**一次界面构建就约 900~1000 次 Java 调用**：

- 按 0.7~2 ms/次 → 光通信 **0.6~2 s**
- 加上类加载/反射首次成本 → 冷启动单块 **4.4~5 s 主线程阻塞**（输入 ANR 阈值 5 s）
- 优化后热回调仍在 **1.9~2.4 s** 量级；"分片"后每块 300~1200 ms

### 3.3 架构上的硬伤：QuickJS 单线程 + UI 只能主线程

QuickJS 的 context 是单线程的，而脚本大量操作 Android View/Handler（只能在主线程），
所以 **整个 JS 生命周期被迫放在 Android 主线程**：

- 脚本执行与 UI 串行，一次长回调 = 一次界面卡顿
- Rhino 是多线程模型，脚本跑在 `Script_Loader` 后台线程，UI 回调直接执行，
  长脚本不会卡 UI（我们最终也正是靠这一点恢复流畅）

### 3.4 语义债：桥要重写整套 LiveConnect

脚本里有 342 处 `java.*` / `android.*`、`Packages.*`、`instanceof`、`getClass()` 等；
壳子依赖 12 个 Rhino 类（LiveConnect）。要支持这些，桥必须实现：
命名空间解析、类/方法/字段包装、**重载解析**、接口适配器（SAM）、
`instanceof` 语义、异常映射、`undefined`/`null` 区分、类加载器、`Scriptable` 包装……
任何一项缺失都是一个真机崩溃。**这不是"换引擎"，是重写一层桥。**

### 3.5 附带成本：16KB page size

Android 15+ 的 16KB page 设备要求 native 库 ELF LOAD 段 16KB 对齐。我们实测
taoweiji / app.cash / quickjs4a / quickjs-kt 旧版等常见 AAR **全是 4KB 对齐**，
在这些设备上直接加载失败。最终用 `io.github.dokar3:quickjs-kt-android:1.0.15`
（Apache-2.0，四个 ABI 全部 `0x4000`）——这也是后来侧载能成立的前提之一。

## 四、侧载方案（当前实现）

```
Rhino 主引擎（脚本全量逻辑 + Java/UI 反射）
   │
   │  quickWorkerCall("matchCommands", JSON 参数)
   ▼
QuickJS-Worker 线程（独占一个 QuickJS context，任务队列串行）
   │  JSON 进 / JSON 出，不接 Java 反射桥
   ▼
返回 JSON 结果（Rhino 侧 JSON.parse 后继续处理 UI）
```

- 代码：`script/engine/QuickJsWorker.kt`（Kotlin，~140 行）+ CA 仓库
  `modules/utils/QuickAccel.js`（脚本侧桥接）
- 线程模型：worker 线程独占 QuickJS context（单线程 → 任务队列串行）；调用方
  同步等结果（Rhino 脚本线程本来就在后台，不会 ANR）
- 大块数据只同步一次：`quickWorkerSetVar("__commandSnap", json)`，之后每次只传前缀
- 失败自动降级：库缺失 / API < 23 / 执行异常 → 回退原 JS 实现，功能不受影响
- 只暴露 4 个 API：`quickWorkerRegister / quickWorkerCall / quickWorkerSetVar / quickWorkerEval`

### 适合 / 不适合侧载的场景

| 场景 | 是否适合 | 原因 |
|---|---|---|
| 匹配/过滤/排序等算法，数据可一次缓存 | ✅ | 通信量小、计算重 |
| 解析/校验/格式化纯 JS 数据 | ✅ | 结果小 |
| 每次都要把大对象传过去 | ❌ | 序列化成本吃掉收益 |
| UI 构建 / 反射密集 | ❌ | 应留在 Rhino（或搬 Java） |
| 需要对象身份/回调和 Java 对象交互 | ❌ | 没有 Java 桥 |

成本模型：设原耗时 `T`，QuickJS 计算约 `T/28`，通信成本 `C`；`C < 0.96T` 才划算。

## 五、性能前后对比（真机实测）

### 5.1 侧载前后

| 场景 | 侧载前（纯 Rhino） | 侧载后（Rhino + QuickJS worker） | 收益 |
|---|---|---|---|
| 命令联想匹配（3000 条命令） | 162~200 ms/次 | **稳态 ~25 ms/次**（首次同步 17~415 ms，在后台线程） | **~6-8x** |
| 纯计算基准（n=50000 排序+取模） | 2400~2471 ms | **49~61 ms** | **~40-47x** |

说明：命令匹配的收益不如纯计算显著，因为结果（几十条命令名）要 JSON 回传；
但输入联想是高频操作，6-8x 已经能明显跟手。

### 5.2 主引擎 QuickJS vs Rhino（历史数据，为什么放弃）

| 指标 | Rhino（保留） | QuickJS 主引擎（已删除） |
|---|---|---|
| 单次 Java 调用 | 微秒级 | 0.7~2 ms |
| 界面构建（~900-1000 次调用） | 后台线程，不阻塞 UI | 主线程阻塞 4.4~5 s（ANR 风险） |
| 脚本编译 | 5~6 s（后台线程） | 645 ms（主线程） |
| 热回调耗时 | 通常 <100 ms | 1.9~2.4 s |
| 代码量 | 引擎层 ~300 行 | 桥 + prelude ~2100 行（已删） |

### 5.3 APK 体积（引入侧载 worker 后）

| 包 | 体积 | native 库 |
|---|---|---|
| debug（R8） | 8.4 MB | `libquickjs.so`×4 + `libturingad.so`×3 |
| release（含广告） | 6.10 MB | 同上 |
| release（`-Pca.withGdt=false`） | 3.39 MB | `libquickjs.so`×4 |

`libquickjs.so` 四个 ABI 合计约 3.3 MB；所有 `.so` 均 **16KB 对齐**。

## 六、给后来者的验证清单（别再踩同样的坑）

如果将来还想评估"换主引擎"，按顺序做，任何一步不划算就停：

1. **用真实脚本 + 真机**统计：一次典型 UI 操作（打开主界面、输入命令）有多少次
   Java/UI 调用？单次桥调用成本多少？→ 得到"通信预算"
2. **不要用纯计算基准代替**：QuickJS 快 40x 的是纯 JS 计算，不是桥调用
3. 算 **主线程阻塞预算**：单次 UI 构建 >1 s 就要分片，>5 s 必然 ANR；
   引擎是否单线程会直接决定脚本能不能离开主线程
4. 列出必须支持的 LiveConnect 语义清单（命名空间/反射/重载/instanceof/SAM/
   异常/undefined/null/类加载器…），逐项验收，缺一项就是真机崩溃
5. **先问：热点能不能搬 Java？** 实测同一热点：Rhino 28 ms → Java 1.08 ms（26x），
   QuickJS 0.97 ms（28.7x）——**搬 Java 与换 QuickJS 收益基本一样，且没有 native
   库/通信/线程模型的代价**（见 CA 仓库 `docs/data-and-accel.md`）
6. 真要做也优先做 **sidecar（侧载）**：Rhino 保留 Java 桥，QuickJS 只算纯数据
7. 若涉及 16KB page：先查 AAR 里 `.so` 的 LOAD 对齐（`readelf -lW`），4KB 的库
   Android 15+ 直接废；目前已知 16KB 可用：`quickjs-kt-android:1.0.15+`

## 七、附：历史证据在哪

- 完整迁移过程与每个坑：`docs/migration-kotlin-quickjs.md`（E0-E2.17）
- 桥/引擎性能数据：同上 E2.4-E2.9、E2.11、E2.13
- 16KB 对齐排查与选型：同上 E2.15-E2.16、`docs/apk-build.md`
- 引擎/Java 加速选型：CA 仓库 `docs/data-and-accel.md`
- 当前实现：`script/engine/QuickJsWorker.kt`、`modules/utils/QuickAccel.js`
