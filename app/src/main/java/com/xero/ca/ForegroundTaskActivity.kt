package com.xero.ca

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

import com.xero.ca.script.ScriptObject

/**
 * 供核心脚本临时借用的前台 Activity：脚本通过 setDelegee 挂上回调，
 * 这个 Activity 会把生命周期、权限结果、startActivityForResult 结果转发回去。
 */
class ForegroundTaskActivity : Activity() {
    private var mDelegee: Delegee? = null
    private var mModal = false
    private var mBackCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ScriptInterface.onBeginForegroundTask(this, intent)
        if (mDelegee == null) {
            Handler().post { finish() }
        }
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback {
                if (!mModal) finish()
            }
            mBackCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        mDelegee?.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        mDelegee?.onActivityResult(this, requestCode, resultCode, data)
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        mDelegee?.onConfigurationChanged(this, newConfig)
        super.onConfigurationChanged(newConfig)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return mModal && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) || super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        mDelegee?.onPause(this)
        super.onPause()
    }

    override fun onResume() {
        mDelegee?.onResume(this)
        super.onResume()
    }

    override fun onStop() {
        mDelegee?.onStop(this)
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mBackCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        mDelegee?.onDestroy(this)
        super.onDestroy()
    }

    @ScriptObject
    fun setDelegee(delegee: Delegee?) {
        mDelegee = delegee
    }

    @ScriptObject
    fun isModal(): Boolean = mModal

    @ScriptObject
    fun setModal(modal: Boolean) {
        mModal = modal
    }

    @ScriptObject
    interface Delegee {
        fun onPause(activity: ForegroundTaskActivity)
        fun onResume(activity: ForegroundTaskActivity)
        fun onStop(activity: ForegroundTaskActivity)
        fun onDestroy(activity: ForegroundTaskActivity)
        fun onConfigurationChanged(activity: ForegroundTaskActivity, newConfig: Configuration)
        fun onActivityResult(activity: ForegroundTaskActivity, requestCode: Int, resultCode: Int, data: Intent?)
        fun onRequestPermissionsResult(
            activity: ForegroundTaskActivity,
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray,
        )
    }
}
