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
<application
    android:enableOnBackInvokedCallback="true">
    <!-- 其它配置 -->
</application>
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

### WebView 深色模式

`NativeCapabilities.configureWebView()` 统一处理：

```text
API 33+   settings.isAlgorithmicDarkeningAllowed = true
API 29-32 settings.forceDark = FORCE_DARK_AUTO
```

`RhinoWebView` 初始化时自动调用，网页没有自带深色样式时由系统反色。

### SAF 文件选择

普通文件选择和图片以外的导入入口已改为 Storage Access Framework：

```text
ACTION_OPEN_DOCUMENT
+ CATEGORY_OPENABLE
+ READ / PERSISTABLE URI 权限
```

直接读写 Minecraft 游戏目录的部分仍保留 `MANAGE_EXTERNAL_STORAGE`，因为需要稳定路径；
用户主动选择的文件走 SAF，不再需要额外存储权限。

没有全盘访问权限时，导入/打开/导出/保存菜单会隐藏「内置文件浏览器」选项，
只保留系统文件选择器，并弹出一次提示：

```text
未开启所有文件访问，已使用系统文件选择器
```

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

### 包可见性

不再申请 `QUERY_ALL_PACKAGES`，改为 `<queries>` 声明启动器入口：

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

Android/data 浏览改为直接读取目录，应用标签拿不到时回退显示包名。

### ShortcutManager

快捷方式已经优先走 `ShortcutManager.requestPinShortcut()`，旧的
`com.android.launcher.action.INSTALL_SHORTCUT` 广播只在 API < 25 的旧系统上兜底。

## 可替换候选

### SplashScreen API

已接入，依赖：

```kotlin
implementation("androidx.core:core-splashscreen:1.0.1")
```

`SplashActivity` 的顺序：

```text
installSplashScreen()
→ 自定义启动布局
→ 广告加载
```

系统启动图会在以下任一时刻收起：

- 广告开始展示（`onPresent`）
- 广告结束/没有广告（`onComplete`）
- `AdProvider` 完成（`runAfterComplete`）

模式落地页（`EXTRA_SHOW_LANDING`）直接跳过广告，立即收起系统启动图。

### ActivityResultContracts

`ReturnDataActivity` 已迁移到 Activity Result API：

```kotlin
registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    ScriptInterface.onActivityResult(requestCode, result.resultCode, result.data)
}
```

它仍然作为 Service 侧发起带结果 Intent 的轻量宿主，但不再重写
`onActivityResult()`，也不再依赖手写 requestCode 分支。桥接层和 CA 的
`startActivityForResult` 调用方式保持不变。

权限请求也已迁移：

```kotlin
val launcher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { result ->
    // 处理权限结果
}
```

`PermissionRequestActivity` 不再重写 `onRequestPermissionsResult()`；API 21-22
仍走 `PackageManager.checkPermission` 的同步兜底。

### 动态配色

Android 12+ 的 `system_accent1_500` 通过 `NativeCapabilities.systemAccentColor()` 暴露给 CA；
主题列表在支持动态色的设备上增加「动态配色」，用系统强调色替换高亮色和按钮色，
背景保持中性浅色/深色。API 31 以下不显示该主题。

### LocaleManager / 应用级语言

已接入 API 33+ 的 `LocaleManager`：

```text
设置 → 语言 → 跟随系统 / 简体中文 / English
```

CA 设置页调用 `ScriptInterface.setApplicationLocales()`，原生资源和 CA 的
`Intl` 在下次启动时都会读取新的应用语言。

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
- [x] SplashScreen API 接入
- [ ] targetSdk 35+ edge-to-edge 回归
