package com.xero.ca

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xero.ca.api.NativeCapabilities

/**
 * 服务已启动时，从桌面图标再次进入显示的落地页。
 * 不启动 Splash / 广告 / 脚本引擎，只负责模式说明和悬浮窗权限入口。
 */
class LandingActivity : Activity() {
    private lateinit var mHelp: TextView
    private var mBackCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScriptInterface.onLandingActivityCreate(this)
        val pad = (resources.displayMetrics.density * 12).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        mHelp = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(mHelp)
        layout.addView(Button(this).apply {
            text = "检查悬浮窗权限"
            setOnClickListener { checkOverlayPermission() }
        }, LinearLayout.LayoutParams(-1, -2))
        layout.addView(Button(this).apply {
            text = "退出命令助手"
            setOnClickListener {
                ScriptInterface.requestExit()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(layout)
        updateModeText()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { finish() }
            mBackCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        ScriptInterface.onLandingActivityDestroy(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mBackCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateModeText()
    }

    private fun canFloat(): Boolean = NativeCapabilities.canDrawOverlays(this)

    private fun updateModeText() {
        mHelp.text = if (canFloat()) {
            "当前模式：悬浮窗模式\n您现在可以在屏幕上找到命令助手的悬浮窗，找不到的话请手动打开命令助手的悬浮窗权限"
        } else {
            "当前模式：页面模式\n检测到命令助手没有悬浮窗权限，无法以悬浮窗模式打开命令助手。如果您已给予权限，请手动重启命令助手。"
        }
    }

    private fun checkOverlayPermission() {
        if (canFloat()) {
            updateModeText()
            Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(NativeCapabilities.overlayPermissionIntent(this))
            } catch (e: Exception) {
                // 部分系统没有悬浮窗权限页，退回到应用详情。
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:$packageName".toUri())
                    )
                } catch (e2: Exception) {
                    Toast.makeText(this, "无法打开悬浮窗权限设置", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
