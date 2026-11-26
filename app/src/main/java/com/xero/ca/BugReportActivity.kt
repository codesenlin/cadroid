package com.xero.ca

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlin.system.exitProcess

class BugReportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.title = "BugReport"
        val ex = intent?.extras ?: return
        val em = ex.getString("exception", "") ?: ""
        AlertDialog.Builder(this)
            .setTitle(R.string.bug_report_title)
            .setCancelable(false)
            .setMessage(
                  getString(R.string.bug_report_message, em)
            )
            .setPositiveButton(R.string.bug_report_copy_close) { _, _ ->
                setClipText(em)
                finish()
            }
            .setNegativeButton(R.string.bug_report_ignore) { _, _ -> finish() }
            .apply {
                val pied = ex.getInt("pid", 0)
                if (pied != 0) {
                    setNeutralButton(R.string.bug_report_stop) { _, _ ->
                        finish()
                        android.os.Process.killProcess(pied)
                        exitProcess(0)
                    }
                }
            }
            .show()
    }

    private fun setClipText(str: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        // 不能用 cm?.primaryClip = ...：Kotlin 里 ClipboardManager.primaryClip
        // 被识别成只读属性（val），赋值编译不过（'val' cannot be reassigned）
        cm?.setPrimaryClip(ClipData.newPlainText("", str))
    }

    companion object {
        @JvmStatic
        fun createIntent(context: Context, message: String, sourcePid: Int): Intent =
            Intent(context, BugReportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("exception", message)
                putExtra("pid", sourcePid)
            }
    }
}
