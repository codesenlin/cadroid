package com.xero.ca.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.WeakHashMap

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

    fun canDrawOverlays(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
        else true

    /** 打开系统的悬浮窗权限页；极少数 OEM 没有该页面时回退到应用详情。 */
    fun overlayPermissionIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }

    /**
     * 给任意已附着到 WindowManager/Activity 的 View 注册返回回调。
     *
     * 同一个 View 只注册一次；重新挂到新的 dispatcher 时会替换旧回调。
     * API < 33 返回 false，调用方继续走普通 KeyEvent 路径。
     */
    fun registerBackCallback(view: View, onBack: () -> Unit): Boolean {
        if (!isPredictiveBackSupported()) return false
        return try {
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

    fun unregisterBackCallback(view: View) {
        if (!isPredictiveBackSupported()) return
        val entry = backCallbacks.remove(view) ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            val dispatcher = entry.first as OnBackInvokedDispatcher
            dispatcher.unregisterOnBackInvokedCallback(entry.second)
        } catch (t: Throwable) {
            // 窗口已经销毁时 dispatcher 可能已解绑，忽略即可。
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
