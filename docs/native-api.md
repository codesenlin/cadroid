# cadroid 原生能力 API

`com.xero.ca.api.NativeCapabilities` 是 cadroid 对 Android 原生能力的统一封装。
目标是把版本判断、权限判断和窗口细节从 `ScriptInterface`、Activity 和 CA 脚本桥里收出来，
避免每个入口重复写 `Build.VERSION.SDK_INT` 分支。

源码：

```text
app/src/main/java/com/xero/ca/api/NativeCapabilities.kt
```

## API

| 方法 | 作用 | 低版本行为 |
| --- | --- | --- |
| `isPredictiveBackSupported()` | 是否支持 `OnBackInvokedCallback` | API < 33 返回 `false` |
| `registerBackCallback(view, onBack)` | 给 View 注册预测性返回 | API < 33 返回 `false` |
| `unregisterBackCallback(view)` | 注销回调 | 无操作 |
| `isSystemDarkMode(context)` | 当前是否系统深色 | 读取 `uiMode` |
| `canDrawOverlays(context)` | 是否有悬浮窗权限 | API < 23 返回 `true` |
| `overlayPermissionIntent(context)` | 打开悬浮窗权限页 | 回退应用详情页 |
| `photoPickerIntent()` | 系统照片选择器 Intent | API < 33 返回 `null` |

## 预测性返回

```kotlin
val ok = NativeCapabilities.registerBackCallback(view) {
    // 处理返回
}
if (!ok) {
    // API < 33：继续走普通 KeyEvent.BACK / onBackPressed
}
```

CA 的悬浮窗通过 `ScriptInterface.registerBackInvoked(view)` 调用这套 API，
最终转发到 `CA.onOverlayBack()` → `PopupPage.back()`。

同一个 View 重复注册不会叠加回调；View 换到新的 `ViewRootImpl` 时会替换旧回调。

## 系统照片选择器

```kotlin
val intent = NativeCapabilities.photoPickerIntent()
if (intent != null) {
    // Android 13+：MediaStore.ACTION_PICK_IMAGES
} else {
    // Android 12-：ACTION_GET_CONTENT
}
```

CA 侧的 `abSelectImage` 已经改为优先调用 `ScriptInterface.createPhotoPickerIntent()`，
不可用时回退 `ACTION_GET_CONTENT`。

## 悬浮窗权限

```kotlin
if (!NativeCapabilities.canDrawOverlays(context)) {
    startActivity(NativeCapabilities.overlayPermissionIntent(context))
}
```

`LandingActivity` 的「检查悬浮窗权限」按钮使用这套 API，权限已开启时只给 Toast，
不重复打开系统页面。

## 版本基线

```text
compileSdk = 37
targetSdk  = 34
minSdk     = 21
```

API 33 是预测性返回和系统照片选择器的分界线；API 23 是 `canDrawOverlays` 的分界线。
