package com.xero.ca

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.io.File

/**
 * ITGSA 公平运行内存机制（Fair Memory）客户端适配。
 *
 * 系统通过 itgsa.intent.action.TRIM / KILL 广播通知应用释放内存或保存现场，
 * 应用需要在 3 秒内通过广播里的 Binder 回调返回处理结果。
 */
object FairMemoryAdapter {
    private const val TAG = "FairMemory"
    private const val ACTION_TRIM = "itgsa.intent.action.TRIM"
    private const val ACTION_KILL = "itgsa.intent.action.KILL"
    private const val TRANSACTION_EXCEPTION_REPLY = IBinder.FIRST_CALL_TRANSACTION
    private const val RESULT_OK = 0
    private const val RESULT_FAILED = 1

    private var initialized = false
    private var handler: Handler? = null
    private var receiver: BroadcastReceiver? = null
    private var remote: IBinder? = null

    fun initialize(context: Context) {
        if (initialized) return
        // XApplication 也会在 :gdt 等子进程创建；只在主进程注册，避免重复接收。
        if (currentProcessName(context) != context.packageName) return
        initialized = true

        val thread = HandlerThread(TAG).apply { start() }
        handler = Handler(thread.looper)
        val appContext = context.applicationContext

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                Log.d(TAG, "Fair memory broadcast: ${intent.action}")
                when (intent.action) {
                    ACTION_TRIM -> handleNotification(intent, kill = false)
                    ACTION_KILL -> handleNotification(intent, kill = true)
                }
            }
        }
        receiver = r

        val filter = IntentFilter().apply {
            addAction(ACTION_TRIM)
            addAction(ACTION_KILL)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(r, filter, null, handler, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(r, filter, null, handler)
            }
            Log.d(TAG, "Fair memory receiver registered")
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to register fair memory receiver", t)
        }
    }

    private fun currentProcessName(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                File("/proc/self/cmdline").readText().trim('\u0000')
            }
        } catch (t: Throwable) {
            context.packageName
        }
    }

    private fun handleNotification(intent: Intent, kill: Boolean) {
        try {
            val data = intent.extras ?: return
            val common = data.getBundle("common") ?: return
            val notifyType = common.getInt("notifyType")
            val notifyId = common.getInt("notifyId")
            val reason = common.getString("reason").orEmpty()
            val action = common.getString("action").orEmpty()
            val callback = common.getBinder("callback")
            val extra = data.getBundle("extra")

            val pss = extra?.getInt("pss", -1) ?: -1
            val pssLimit = extra?.getInt("pssLimit", -1) ?: -1
            val heapSize = extra?.getInt("heapSize", -1) ?: -1
            val heapCapacity = extra?.getInt("heapCapacity", -1) ?: -1
            Log.w(
                TAG,
                "notify type=$notifyType id=$notifyId action=$action reason=$reason " +
                    "pss=$pss/$pssLimit heap=$heapSize/$heapCapacity kill=$kill"
            )

            if (kill) {
                // 当前没有可持久化的页面现场；保留回调，确保系统收到成功结果。
                Log.w(TAG, "Fair memory kill notice received; no persisted scene to save")
            } else {
                // 预警：尽量回收本进程可立即释放的资源。
                releaseTransientMemory()
            }

            if (callback != null) {
                reply(callback, notifyType, notifyId, RESULT_OK, if (kill) "no scene to save" else "trimmed")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fair memory notification failed", t)
        }
    }

    private fun releaseTransientMemory() {
        try {
            // 触发一次显式 GC 只作为厂商内存预警下的最后手段；
            // CA 的图片/WebView 缓存由 CA 自己持有，这里不强行清空。
            System.gc()
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to release transient memory", t)
        }
    }

    private fun reply(binder: IBinder, notifyType: Int, notifyId: Int, result: Int, message: String) {
        synchronized(this) {
            try {
                if (remote == null) {
                    remote = binder
                    remote?.linkToDeath({
                        synchronized(this) { remote = null }
                    }, 0)
                }
            } catch (e: RemoteException) {
                remote = null
                Log.w(TAG, "Unable to link fair memory callback", e)
                return
            }
        }

        val target = remote ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInt(notifyType)
            data.writeInt(notifyId)
            data.writeInt(result)
            val extra = Bundle().apply { putString("reply", message) }
            data.writeBundle(extra)
            target.transact(TRANSACTION_EXCEPTION_REPLY, data, reply, IBinder.FLAG_ONEWAY)
            reply.readException()
            Log.d(TAG, "Fair memory reply type=$notifyType id=$notifyId result=$result")
        } catch (t: Throwable) {
            Log.w(TAG, "Fair memory reply failed", t)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
