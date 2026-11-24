# Android 原生能力清单

本文记录 cadroid 对 Android 原生能力的利用情况、已经落地的实现，以及后续可替换的候选方案。

基线：

```text
compileSdk = 37
targetSdk  = 34
minSdk     = 21
验证设备   = Android 14（API 34）
```

## 已接入

### 预测性返回

Android 13+ 的悬浮窗不再依赖 Activity 接收返回，而是直接给悬浮窗 View 注册回调：

```kotlin
val dispatcher = view.findOnBackInvokedDispatcher()
dispatcher.registerOnBackInvokedCallback(
    OnBackInvokedDispatcher.PRIORITY_DEFAULT
) {
    bridge.onOverlayBack()
}
```

链路：

```text
系统返回手势
→ 悬浮窗 ViewRootImpl 的 OnBackInvokedDispatcher
→ CA.onOverlayBack()
→ PopupPage.back()
```

相关文件：

- `app/src/main/java/com/xero/ca/ScriptInterface.kt`
  - `registerBackInvoked(view)`
  - `Bridge.onOverlayBack()`
- CA 侧 `src/android/AndroidBridge.kt`、`src/ca/CAMain.kt`、`src/ui/PopupPage.kt`

版本行为：

| 系统 | 行为 |
| --- | --- |
| API 33+ | 走 `OnBackInvokedCallback`，支持预测性返回动画 |
| API < 33 | 没有该 API，继续走普通 `KeyEvent.BACK` 与 `onBackPressed` 兜底 |

`AndroidManifest.xml` 需要：

```xml
<application android:enableOnBackInvokedCallback="true" ... />
```

### 系统照片选择器

图片选择入口从通用的 `ACTION_GET_CONTENT` 改为 Photo Picker：

```text
Android 13+ → MediaStore.ACTION_PICK_IMAGES
Android 12- → ACTION_GET_CONTENT
选择器不可用 → 回退 ACTION_GET_CONTENT
```

实现位置：

```text
CA/src/android/AndroidBridge.kt :: abSelectImage()
```

收益：

- 不需要存储权限
- 系统只把用户选中的图片交给应用
- 不需要自己维护图片选择界面

当前状态：编译、安装通过；背景图片/图标入口仍需在真机上点选确认一次。

### 16 KB 页对齐

`app/build.gradle.kts` 使用：

```kotlin
packaging {
    jniLibs.useLegacyPackaging = false
}
```

配合原生库构建参数 `-Wl,-z,max-page-size=16384`，满足 Android 15+ 的 16 KB page 设备要求。

### 前台服务类型

`ScriptService` 已声明：

```xml
<service
    android:name=".ScriptService"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="script_runtime" />
</service>
```

targetSdk 34 下启动前台服务不会因为缺少类型而抛异常。

### ShortcutManager

快捷方式已经优先走 `ShortcutManager.requestPinShortcut()`，旧的
`com.android.launcher.action.INSTALL_SHORTCUT` 广播只在 API < 25 的旧系统上兜底。

## 可替换候选

### SplashScreen API

Android 12+ 提供系统级启动画面，可以把冷启动时的空白/自定义 Splash 换成系统启动图。

暂不直接替换的原因：

- 当前启动流程包含广告加载、引擎初始化和“模式落地页”
- 需要重新安排“系统启动图 → 广告 → 模式页/主界面”的时序
- 直接套 `installSplashScreen()` 会让广告和 loading 的状态机变得不清楚

建议后续单独做一轮启动流程改造，而不是只换一个主题。

### ActivityResultContracts

当前文件选择、权限请求等通过 `ReturnDataActivity` 和桥接层手动转发结果。

可以改成：

```kotlin
registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> ... }
```

暂不做的原因：

- 脚本层需要同步/异步桥接返回值
- 替换会触及 `ScriptInterface`、`ReturnDataActivity` 和 CA 的 `AndroidBridge`
- 现有实现虽然旧，但结果语义已经稳定

### LocaleManager / 应用级语言

Android 13+ 支持 per-app language。当前 CA 的语言和字符串由 JS `Intl` 管理，原生侧只有少量广告/启动字符串。

优先级低：需要先决定原生资源和 JS 语言设置以哪边为准。

### Edge-to-edge / WindowInsets

targetSdk 35+ 会强制 edge-to-edge。当前 targetSdk 34 尚未触发，但升到 35 之前需要处理：

- 悬浮窗和全屏页面的系统栏 inset
- 键盘弹出时的布局
- 刘海/挖孔屏安全区

### 通知权限

`POST_NOTIFICATIONS` 已在启动流程中检查，targetSdk 34 下无需额外迁移。

## 版本策略

`compileSdk` 保持在 37，`targetSdk` 当前为 34。

暂不直接升到 37 的原因：

- edge-to-edge 强制
- Android 16 对固定方向的忽略
- 前台服务/后台启动限制的进一步收紧
- 需要逐项在 API 34 真机和更高版本上回归

升级时应单独开一轮，不要和普通 refactor 混在一起。

## 验证清单

- [x] 悬浮窗预测性返回
- [x] targetSdk 34 安装启动
- [x] 16 KB page 配置
- [x] 图片选择 Photo Picker 编译/安装
- [ ] Photo Picker 真机点选背景图片
- [ ] SplashScreen API 启动流程改造
- [ ] targetSdk 35+ edge-to-edge 回归
