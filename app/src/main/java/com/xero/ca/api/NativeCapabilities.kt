package com.xero.ca.api

import android.Manifest
import android.app.LocaleManager
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.app.AppOpsManager
import android.os.Build
import android.os.Process
import android.os.Environment
import android.os.LocaleList
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.WeakHashMap
import androidx.core.net.toUri

/**
 * cadroid 对 Android 原生能力的稳定封装。
 *
 * 这里集中处理版本判断和窗口/权限细节，避免 ScriptInterface、Activity
 * 和 CA 脚本桥各自写一份 API 33/34 的条件分支。
 */
object NativeCapabilities {
    const val PREDICTIVE_BACK_MIN_SDK = Build.VERSION_CODES.TIRAMISU

    private val backCallbacks = WeakHashMap<View, Pair<Any, OnBackInvokedCallback>>()

    fun isPredictiveBackSupported(): Boolean = Build.VERSION.SDK_INT >= PREDICTIVE_BACK_MIN_SDK

    fun isSystemDarkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun canDrawOverlays(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(context)) return true
        // 部分 ROM 的 Settings.canDrawOverlays 与服务上下文组合会误报 false，
        // AppOps 是系统实际拦截悬浮窗时使用的同一份授权状态。
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            appOps?.checkOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    /** 打开系统的悬浮窗权限页；极少数 OEM 没有该页面时回退到应用详情。 */
    fun overlayPermissionIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri()
            )
        }

    /**
     * 给任意已附着到 WindowManager/Activity 的 View 注册返回回调。
     *
     * 同一个 View 只注册一次；重新挂到新的 dispatcher 时会替换旧回调。
     * API < 33 返回 false，调用方继续走普通 KeyEvent 路径。
     */
    fun registerBackCallback(view: View, onBack: () -> Unit): Boolean {
        return isPredictiveBackSupported() && try {
            val dispatcher = view.findOnBackInvokedDispatcher() ?: return false
            if (backCallbacks[view]?.first === dispatcher) return true
            val callback = OnBackInvokedCallback { onBack() }
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
            backCallbacks[view] = dispatcher to callback
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Android 12+ 的系统强调色；低版本返回蓝色兜底。 */
    fun systemAccentColor(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return context.getColor(android.R.color.system_accent1_500)
            } catch (t: Throwable) {
                // 某些精简系统没有动态色资源，走兜底。
            }
        }
        return 0xFF4A7CB5.toInt()
    }

    /** Android 13+ 的应用级语言；返回逗号分隔的 BCP 47 标签，空串表示跟随系统。 */
    fun applicationLocales(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return ""
        return try {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return ""
            manager.applicationLocales.toLanguageTags()
        } catch (t: Throwable) {
            ""
        }
    }

    fun setApplicationLocales(context: Context, languageTags: String): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && try {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return false
            manager.applicationLocales = if (languageTags.isBlank()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(languageTags)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Android 13 的运行时通知权限 + 通知渠道总开关。 */
    fun areNotificationsEnabled(context: Context): Boolean {
        return !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notificationSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri()
            )
        }

    /**
     * 保存图片到系统相册。
     *
     * API 29+ 走 MediaStore，不依赖 WRITE_EXTERNAL_STORAGE；
     * 低版本回退到公共 Pictures 目录并通知媒体扫描。
     */
    fun saveBitmapToPictures(context: Context, bitmap: Bitmap, displayName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveBitmapViaMediaStore(context, bitmap, displayName)
            } else {
                saveBitmapToPublicPictures(context, bitmap, displayName)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun saveBitmapViaMediaStore(
        context: Context, bitmap: Bitmap, displayName: String
    ): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("compress failed")
                }
            } ?: throw IllegalStateException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        } catch (t: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveBitmapToPublicPictures(
        context: Context, bitmap: Bitmap, displayName: String
    ): String? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, displayName)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        return file.absolutePath
    }

    /** 让脚本创建的 WebView 跟随系统深色模式。 */
    fun configureWebView(webView: WebView) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                webView.settings.isAlgorithmicDarkeningAllowed = true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                webView.settings.forceDark = WebSettings.FORCE_DARK_AUTO
            }
        } catch (t: Throwable) {
            // WebView provider 异常时保持默认主题。
        }
    }

    /** Android 13+ 的系统照片选择器；低版本返回 null。 */
    fun photoPickerIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            null
        }
}
