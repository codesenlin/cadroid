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
            text = getString(R.string.landing_check_overlay)
            setOnClickListener { checkOverlayPermission() }
        }, LinearLayout.LayoutParams(-1, -2))
        layout.addView(Button(this).apply {
            text = getString(R.string.landing_exit)
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
    @Deprecated("Deprecated in Java", ReplaceWith("finish()"))
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
        mHelp.text = getString(
            if (canFloat()) R.string.landing_mode_float else R.string.landing_mode_page
        )
    }

    private fun checkOverlayPermission() {
        if (canFloat()) {
            updateModeText()
            Toast.makeText(this, R.string.landing_overlay_granted, Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, R.string.landing_overlay_settings_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
