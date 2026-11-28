package com.xero.ca

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processIntent()
        finish()
    }

    private fun processIntent() {
        val intent = intent ?: return
        // 桌面图标永远先到权限页；主界面只能从权限页/悬浮球进入。
        if (intent.action == Intent.ACTION_MAIN && ScriptInterface.hasBridge()) {
            startActivity(
                Intent(this, LandingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } else {
            ScriptInterface.callIntent(this, intent)
        }
    }
}
