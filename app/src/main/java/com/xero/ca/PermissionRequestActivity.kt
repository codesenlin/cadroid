package com.xero.ca

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.xero.ca.script.ScriptObject

class PermissionRequestActivity : ComponentActivity() {
    private var mCallback: Callback? = null
    private var mBackCallback: OnBackInvokedCallback? = null
    private lateinit var mLauncher: ActivityResultLauncher<Array<String>>
    private var mPendingRequestCode = 0
    private var mPendingPermissions: Array<String> = emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        ScriptInterface.onBeginPermissionRequest(this)
        if (mCallback == null) {
            Handler().post { finish() }
        }
        super.onCreate(savedInstanceState)
        mLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val permissions = mPendingPermissions
            val grantResults = IntArray(permissions.size) { i ->
                if (result[permissions[i]] == true) {
                    PackageManager.PERMISSION_GRANTED
                } else {
                    PackageManager.PERMISSION_DENIED
                }
            }
            mCallback?.onRequestPermissionsResult(
                this, mPendingRequestCode, permissions, grantResults
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { cancelAndFinish() }
            mBackCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cancelAndFinish()
    }

    private fun cancelAndFinish() {
        // onDestroy 会触发 onEndPermissionRequest，脚本层据此回调“用户取消”。
        finish()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mBackCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        mCallback?.onEndPermissionRequest(this)
        super.onDestroy()
    }

    @ScriptObject
    override fun shouldShowRequestPermissionRationale(permission: String): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && super.shouldShowRequestPermissionRationale(permission)
    }

    @ScriptObject
    fun setCallback(callback: Callback?) {
        mCallback = callback
    }

    @ScriptObject
    fun requestPermissionsCompat(requestCode: Int, permissions: Array<String>) {
        mPendingRequestCode = requestCode
        mPendingPermissions = permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mLauncher.launch(permissions)
        } else {
            val grantResults = IntArray(permissions.size)
            val packageManager = packageManager
            val pkgName = packageName
            for (i in permissions.indices) {
                grantResults[i] = packageManager.checkPermission(permissions[i], pkgName)
            }
            mCallback?.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
        }
    }

    @ScriptObject
    interface Callback {
        fun onRequestPermissionsResult(
            activity: PermissionRequestActivity,
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray,
        )

        fun onEndPermissionRequest(activity: PermissionRequestActivity)
    }
}
