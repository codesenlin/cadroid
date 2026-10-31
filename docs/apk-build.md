# APK / AAB 构建说明

## 当前产物形态

| 命令                             | 产物                                                     | 用途                            |
|----------------------------------|----------------------------------------------------------|---------------------------------|
| `./gradlew :app:assembleRelease` | `app/build/outputs/apk/release/app-release-unsigned.apk` | 通用 APK（含全部 ABI）          |
| `./gradlew :app:bundleRelease`   | `app/build/outputs/bundle/release/app-release.aab`       | App Bundle，供 Google Play 上架 |
| `./tools/build-apks.sh`          | `build/dist/命令助手.apks`                               | 由 AAB 拆出的分体包             |

正式包仍由 CA 的 `cabuildtools shellBuildRelease` 产出（对 APK 签名 + 放进 `build/dist`）。

## 分体包（.apks）实测收益（旧测量，未含 quickjs-kt）

> 下面这组数据是引入 QuickJS 侧载 worker **之前**测的（当时全项目只有一个原生库
> `libturingad.so`，约 750 KB）。现在多了一个 `libquickjs.so`（quickjs-kt，四个 ABI
> 合计约 3.6 MB），分体包相对通用 APK 的收益会明显变大；需要时用 bundletool 重测。
> 当前通用 release APK 实测 **6.10 MB**（未签名，含 GDT）。
>
> 另：`libquickjs.so` / `libturingad.so` 均为 **16KB page 对齐**（Android 15+ 16KB
> 设备的要求），来源分别是 quickjs-kt 1.0.15 与优量汇 AAR。

`bundletool get-size total --dimensions=ABI`：

| 目标 ABI    | 下载体积 |
|-------------|----------|
| arm64-v8a   | 4.04 MB  |
| armeabi-v7a | 4.03 MB  |
| x86         | 3.90 MB  |

对照通用 APK：**4.13 MB**。

**（旧测量）只省约 90 KB（2%）。** 原因：

- 当时全项目只有一个原生库 `libturingad.so`（来自优量汇 AAR），三种 ABI 合计约 750 KB
- 没有按密度/语言拆分的资源（图片只有 8 张）
- AAB 的 `base-master.apk` 本身仍是 3.75 MB

所以**默认不产出 .apks**，只有需要上架 Google Play 时再跑 `tools/build-apks.sh`。

## bundletool

不入库（32 MB），按需下载：

```sh
curl -L -o tools/bundletool.jar \
  https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar
```

国内网络不通时走代理前缀：`https://v4.gh-proxy.org/` + 上面的 URL。

## 安装 .apks

```sh
java -jar tools/bundletool.jar install-apks --apks=build/dist/命令助手.apks
```

需要设备在线（`adb devices` 能看到）。bundletool 会根据设备 ABI 自动挑分体。

## 为什么不用 splits（Gradle 直接出多个 APK）

`android { splits { abi { ... } } }` 也能按 ABI 拆，但：

- 收益同样是那 90 KB
- 产物变成一堆 `app-arm64-v8a-release.apk`，分发时要自己判断设备架构
- 不能上架 Google Play（Play 只收 AAB）

需要上架时用 AAB 路线即可，没必要两套并存。

## 只装 base 包（不带 ABI 分体）能不能用

**能。** 实测过：

1. 把 release APK 里的 `lib/**` 全部删掉（模拟只装了 base-master，没有 ABI 分体）
2. 重新签名、装到 Android 14 真机
3. 应用正常启动，无崩溃

为此在代码里做了运行期保护：

- `GdtState.ready`：优量汇是否**真的**初始化成功（区别于构建期的 `BuildConfig.GDT_ENABLED`）
- `XApplication` 里 GDT 初始化包了 `try/catch (Throwable)` —— 缺 `libturingad.so` 时抛的是
  `UnsatisfiedLinkError`，那是 Error 不是 Exception，普通 `catch (e: Exception)` 接不住
- `AdProvider.isAdEnabled()` 同时看 `GDT_ENABLED`（构建期）和 `GdtState.ready`（运行期）

结果：**没有原生库时，应用照常运行，只是广告关掉。**

## Rhino 兼容编译模式（debug 也走 R8）

调试包是 `debuggable + minifyEnabled` 的组合，这是故意的：

- Rhino 1.9 的 `SlotMapOwner$ThreadedAccess` 用了 `MethodHandle.invoke`
- minSdk 21 下 D8 不做兼容转换，R8 会
- 所以调试包也必须走 R8 的 dex 流程，否则 `assembleDebug` 直接
  `Error while dexing`

这个模式用开关控制（`app/build.gradle` 的 `rhinoCompat`）：

```properties
# gradle.properties
ca.rhinoCompat=true     # 默认：调试包也走 R8
```

```bash
./gradlew :app:assembleDebug -Pca.rhinoCompat=false   # 关掉（当前会 dex 失败）
```

两个相关改动：

1. AGP 9.5 会对 `debuggable + minifyEnabled` 发警告（提示优化/混淆会被关闭）。
   我们本来就不需要优化/混淆、只需要 R8 dexing，所以在
   `gradle.properties` 里用 `android.sync.suppressAgpWarnings=DEBUGGABLE_AND_MINIFIED_ENABLED` 关掉。
2. 目前 **Rhino 仍是脚本主引擎**（`QuickJsEngine` 那套实验实现已在 E2.15 移除），
   所以 `ca.rhinoCompat` 要保持 `true`；只有真正彻底去掉 Rhino 之后才能关掉，
   调试包才会恢复成普通的 debuggable 构建。

## 可选组件：优量汇（广告）

优量汇依赖原生库 `libturingad.so`，而它**只有 arm 版本**（armeabi / armeabi-v7a /
arm64-v8a），没有 x86 / x86_64。所以它天然是"小众架构跑不了"的那类依赖。

两种构建方式：

| 命令                                                | APK         | 原生库                                                                | 架构覆盖                   |
|-----------------------------------------------------|-------------|-----------------------------------------------------------------------|----------------------------|
| `./gradlew :app:assembleRelease`                    | **6.10 MB** | `libquickjs.so`（全 4 种）+ `libturingad.so`（arm ×3） | arm 完整；x86 靠运行期降级 |
| `./gradlew :app:assembleRelease -Pca.withGdt=false` | **3.39 MB** | 只有 `libquickjs.so`（全 4 种）                        | **全架构**                 |

（以上为未签名 release APK 实测体积，2026-10-31；含 quickjs-kt 侧载 worker 的 .so。）

`-Pca.withGdt=false` 时走 `src/stub/gdt` 的空实现，广告相关代码编译进空壳，
`BuildConfig.GDT_ENABLED = false`，运行期不会尝试加载原生库。

装了 GDT 的包在非 ARM 设备上也不会崩：

- 启动时 `GdtSupport.isAbiSupported()` 先查 `Build.SUPPORTED_ABIS`，不含 ARM 就
  直接跳过初始化（`GdtState.reason` 记原因）
- 即使是 ARM，`.so` 加载失败（例如 AAB 只装了 base 包）也有
  `catch (Throwable)` 兜底（`UnsatisfiedLinkError` 是 Error 不是 Exception）
- `AdProvider.isAdEnabled()` 同时要求 `GDT_ENABLED && GdtState.ready`，没就绪就不请求广告

## 原则：原生库必须可选，小众架构才可用

厂商的原生库永远只覆盖主流 ARM。所以能不能在 x86 / riscv64 / 未来新架构上运行，
取决于**缺了那个库时应用会不会崩**，而不是取决于库本身。

三层保障（以优量汇为例）：

1. **构建期**：`GDT_ENABLED`（或 `-Pca.withGdt=false`）决定要不要装这个 SDK
2. **运行期 ABI 预检**：`GdtSupport.isAbiSupported()` —— 设备架构不在 so 覆盖范围
   （GDT 只覆盖 ARM）就跳过初始化，连加载都不尝试
3. **运行期加载兜底**：`catch (Throwable)` + `GdtState.ready` —— 库在但加载失败时降级

脚本引擎也是同样的思路：主引擎固定 Rhino（纯 Java，任何架构可用）；QuickJS
只作为纯计算侧载 worker（`quickjs-kt`，四 ABI），库缺失/API 过低时 worker
静默不可用，脚本功能不受影响（见 `docs/migration-kotlin-quickjs.md`）。

实测（把 release APK 的 `lib/**` 全删掉再装到 Android 14）：应用正常启动，
只是广告关闭。x86 设备就属于这种情况。

### 新增原生库依赖时的检查清单

1. 这个库覆盖哪些 ABI？（`unzip -l xxx.aar | grep .so`）
2. 缺失时会抛什么？—— `UnsatisfiedLinkError` 是 **Error 不是 Exception**，
   必须 `catch (Throwable)` 才接得住
3. 有没有运行期开关让上层跳过相关功能？（参考 `GdtState.ready`）
