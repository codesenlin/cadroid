package com.xero.ca

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils

import com.xero.ca.gen.Secret

import java.io.File
import java.lang.ref.WeakReference

class ScriptService : Service() {
    private var mManager: ScriptManager? = null
    private var mLastIntent: Intent? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastNightMode: Boolean? = null
    private val uiModePoller = object : Runnable {
        override fun run() {
            val night = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (lastNightMode != null && lastNightMode != night) {
                mManager?.getScriptInterface()?.onSystemUiModeChanged()
            }
            lastNightMode = night
            uiHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lastNightMode = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        uiHandler.postDelayed(uiModePoller, 1500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (sInstance != null) {
            return super.onStartCommand(intent, flags, startId)
        }
        sInstance = WeakReference(this)
        if (intent == null) {
            stopSelf()
            return super.onStartCommand(null, flags, startId)
        }

        @Suppress("DEPRECATION")
        val realIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT) as Intent?
        if (realIntent == null) {
            stopSelf()
            return super.onStartCommand(intent, flags, startId)
        }
        mLastIntent = realIntent
        val sourceName = getString(R.string.app_name)
        val src = Preference.getInstance(this).getDebugSource()
        mManager = if (ScriptInterface.ACTION_DEBUG_EXEC == realIntent.action && !TextUtils.isEmpty(src)) {
            ScriptManager.createDebuggable(src)
        } else {
            ScriptManager.getInstance()
        }
        val mgr = mManager
        if (mgr == null || mgr.isRunning()) {
            stopSelf()
        } else {
            mgr.setCacheDir(cacheDir.path)
            checkHotfix()
            if (ACTION_PREPARE == intent.action) {
                mgr.prepareScript(this, sourceName, false)
            } else if (ACTION_RUN == intent.action) {
                mgr.prepareScript(this, sourceName, true)
            }
            startForeground(1, createNotification())
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiModePoller)
        mManager?.endScript()
        sInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun startActivity(intent: Intent) {
        if (ScriptInterface.applyIntent(intent)) {
            super.startActivity(intent)
        }
    }

    fun getLastIntent(): Intent? = mLastIntent

    fun createNotification(): Notification {
        var intentFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intentFlags = intentFlags or PendingIntent.FLAG_IMMUTABLE
        }
        return createNotificationBuilder()
            .setContentTitle("命令助手")
            .setContentText("正在运行中...")
            .setSmallIcon(R.mipmap.icon_small)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.icon_small))
            .setContentIntent(
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, ScriptActionService::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    intentFlags
                )
            )
            .build()
    }

    @Suppress("DEPRECATION")
    private fun createNotificationBuilder(): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(DEFAULT_CHANNEL, "保持活跃", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "快速管理命令助手"
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
            Notification.Builder(this, DEFAULT_CHANNEL)
        } else {
            Notification.Builder(this)
        }
    }

    private fun checkHotfix() {
        val base = getDir("rhino", MODE_PRIVATE)
        val core = File(base, "core.js")
        if (!core.isFile) return
        val sign = File(base, "core.sign")
        // 双 key 兼容：优先我们自己的热更新公钥，同时接受作者原版公钥签的官方热更新
        val verifyKeys = listOf(Secret.getVerifyKey(), Secret.getLegacyVerifyKey())
            .filter { it.isNotEmpty() }
        if (verifyKeys.isEmpty()) return
        mManager?.setHotfix(core.path, sign.path, verifyKeys, BuildConfig.VERSION_CODE)
    }

    companion object {
        private var sInstance: WeakReference<ScriptService>? = null
        private const val DEFAULT_CHANNEL = "default"

        const val ACTION_PREPARE = "com.xero.ca.script.ACTION_PREPARE"
        const val ACTION_RUN = "com.xero.ca.script.ACTION_RUN"
    }
}
