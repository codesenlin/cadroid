package com.xero.ca

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 从 Service 侧启动“需要返回结果”的 Intent 的轻量宿主。
 *
 * 仍然保留这个 Activity，是因为结果回调的起点在 Service；但内部改用
 * Activity Result API，不再手写 onActivityResult/requestCode 分支。
 */
class ReturnDataActivity : ComponentActivity() {
    private lateinit var mLauncher: ActivityResultLauncher<Intent>
    private var mRequestCode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            finishAndRemoveTask()
            ScriptInterface.onActivityResult(mRequestCode, result.resultCode, result.data)
        }

        val lastIntent = intent ?: run {
            finish()
            return
        }
        val target: Intent?
        try {
            @Suppress("DEPRECATION")
            target = lastIntent.getParcelableExtra(EXTRA_INTENT)
            mRequestCode = lastIntent.getIntExtra(EXTRA_REQUEST_CODE, 0)
        } catch (e: ClassCastException) {
            finish()
            return
        }
        if (target == null) {
            finish()
            return
        }
        mLauncher.launch(target)
    }

    companion object {
        const val EXTRA_INTENT = "com.xero.ca.EXTRA_INTENT"
        const val EXTRA_REQUEST_CODE = "com.xero.ca.EXTRA_REQUEST_CODE"

        @JvmStatic
        fun createIntent(context: Context, intent: Intent, requestCode: Int): Intent =
            Intent(context, ReturnDataActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_INTENT, intent)
                .putExtra(EXTRA_REQUEST_CODE, requestCode)
    }
}
