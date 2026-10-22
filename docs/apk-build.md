# APK / AAB 构建说明

## 当前产物形态

| 命令 | 产物 | 用途 |
|---|---|---|
| `./gradlew :app:assembleRelease` | `app/build/outputs/apk/release/app-release-unsigned.apk` | 通用 APK（含全部 ABI） |
| `./gradlew :app:bundleRelease` | `app/build/outputs/bundle/release/app-release.aab` | App Bundle，供 Google Play 上架 |
| `./tools/build-apks.sh` | `build/dist/命令助手.apks` | 由 AAB 拆出的分体包 |

正式包仍由 CA 的 `cabuildtools shellBuildRelease` 产出（对 APK 签名 + 放进 `build/dist`）。

## 分体包（.apks）实测收益

`bundletool get-size total --dimensions=ABI`：

| 目标 ABI | 下载体积 |
|---|---|
| arm64-v8a | 4.04 MB |
| armeabi-v7a | 4.03 MB |
| x86 | 3.90 MB |

对照通用 APK：**4.13 MB**。

**只省约 90 KB（2%）。** 原因：

- 全项目只有一个原生库 `libturingad.so`（来自优量汇 AAR），三种 ABI 合计约 750 KB
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
