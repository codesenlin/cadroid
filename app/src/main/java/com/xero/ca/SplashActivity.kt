package com.xero.ca

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

@SuppressLint("CustomSplashScreen")
class SplashActivity : Activity() {
    private lateinit var mLoadingTitleView: TextView
    private lateinit var mAdContainer: FrameLayout
    private lateinit var mAdSkipView: TextView
    private lateinit var mAdSecondsView: TextView
    private lateinit var mProvider: AdProvider
    private var mBackCallback: OnBackInvokedCallback? = null
    private var mSplashDismissed = false
    private var mSplashScreen: SplashScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        mSplashScreen = splash
        splash.setKeepOnScreenCondition { !mSplashDismissed }
        setContentView(R.layout.main)
        mLoadingTitleView = findViewById(R.id.loadingTitle)
        mAdContainer = findViewById(R.id.adContainer)
        mAdSkipView = findViewById(R.id.skipView)
        mAdSecondsView = findViewById(R.id.secondsLeft)
        super.onCreate(savedInstanceState)
        if (!ScriptInterface.onSplashActivityCreate(this)) {
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback {
                if (mProvider.isCompleted()) finish()
            }
            mBackCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
        val provider = AdProvider.getInstance()
        mProvider = provider
        provider.prepare(this, mAdContainer, mAdSkipView, AdListener())
        // 没有广告/广告失败时 prepare 会直接完成；确保系统启动图能收起。
        provider.runAfterComplete(object : AdProvider.OnCompleteListener {
            override fun onComplete(provider: AdProvider, success: Boolean, message: String?) {
                mSplashDismissed = true
                mSplashScreen?.setKeepOnScreenCondition { false }
            }
        })
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mBackCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        ScriptInterface.onSplashActivityDestroy(this)
        if (::mProvider.isInitialized) mProvider.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return !mProvider.isCompleted() &&
                (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) || super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        mProvider.pause()
        super.onPause()
    }

    override fun onResume() {
        mProvider.resume()
        super.onResume()
    }

    fun setLoadingTitle(title: String) {
        runOnUiThread(SetLoadingTitleTask(title))
    }

    internal inner class SetLoadingTitleTask(private val title: String) : Runnable {
        override fun run() {
            mLoadingTitleView.text = title
        }
    }

    internal inner class AdListener : AdProvider.Listener {
        override fun onPresent() {
            mSplashDismissed = true
            mSplashScreen?.setKeepOnScreenCondition { false }
            mAdSecondsView.visibility = View.VISIBLE
            // 新版优量汇 SDK 会在 adContainer 内自行绘制带倒计时的“跳过”按钮，
            // 布局里的 skipView 不再交给 SDK，保持隐藏即可
        }

        override fun onTick(secondsLeft: Int) {
            mAdSecondsView.text = resources.getQuantityString(
                R.plurals.ad_seconds_left, secondsLeft, secondsLeft
            )
        }

        override fun onComplete() {
            mSplashDismissed = true
            mSplashScreen?.setKeepOnScreenCondition { false }
            mAdContainer.visibility = View.GONE
            mAdSecondsView.visibility = View.GONE
            mAdSkipView.visibility = View.GONE
        }
    }
}
