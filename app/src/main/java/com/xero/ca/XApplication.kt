package com.xero.ca

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log

import com.qq.e.comm.managers.GDTAdSdk
import com.tendcloud.tenddata.TalkingDataSDK
import com.xero.ca.gen.Secret

import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.system.exitProcess

class XApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // QuickJS 实验路径：脚本整个生命周期在主线程（QuickJS 单线程 + Android UI 限制），
        // 但脚本里有同步网络请求，主线程默认会被 StrictMode 拦成 NetworkOnMainThreadException。
        // 实验阶段先放宽（正式执行引擎如果定 QuickJS，需要把这些网络调用改成异步）。
        if (!BuildConfig.JS_ENGINE.equals("rhino", ignoreCase = true)) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().permitNetwork().build()
            )
        }
        TalkingDataSDK.initSDK(applicationContext, Secret.getTDToken(), "default", "")
        // 优量汇（GDT）广告 SDK：AppID 从 4.x 起只能通过这里传入，必须在请求广告前初始化。
        // GDT_ENABLED 为 false 表示构建时没装配优量汇（缺 GDTActionSDK aar），直接跳过。
        val gdtAppId = Secret.getGDTAppID()
        if (BuildConfig.GDT_ENABLED && !gdtAppId.isNullOrEmpty()) {
            if (!GdtSupport.isAbiSupported()) {
                // GDT 的 libturingad.so 只覆盖 ARM；x86/x86_64/riscv64 等设备上
                // 连初始化都不做，避免 SDK 内部尝试加载 so 后留下半初始化状态。
                GdtState.reason =
                    "当前设备 ABI（" + GdtSupport.describeAbis() + "）不支持优量汇原生库（仅 ARM），广告关闭"
                Log.i("CA", GdtState.reason!!)
            } else {
                try {
                    GDTAdSdk.initWithoutStart(this, gdtAppId)
                    GDTAdSdk.start(null)
                    GdtState.ready = true
                } catch (t: Throwable) {
                    // 缺 libturingad.so 时抛的是 UnsatisfiedLinkError（Error，不是 Exception），
                    // 只装 base 包（不带 ABI 分体）的设备会走到这里。广告关掉，应用照常跑。
                    GdtState.reason = "优量汇初始化失败: " + t.message
                    Log.w("CA", "优量汇初始化失败，广告功能关闭: " + t.message)
                }
            }
        }
        // 记下系统原来的处理器，崩溃时先自己上报，再交还给它
        Thread.setDefaultUncaughtExceptionHandler(
            ErrorCatcher(Thread.getDefaultUncaughtExceptionHandler())
        )
    }

    inner class ErrorCatcher(
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            reportError(this@XApplication, t, e)
            // 交还给系统处理器：它才会写 tombstone、走正常的崩溃流程
            // （之前是 killProcess + exitProcess(0)，等于把崩溃伪装成正常退出，
            //   系统来不及记录，有的机型也不弹崩溃提示）
            if (previous != null) {
                previous.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    companion object {
        const val TAG = "CA"

        @JvmStatic
        fun getPackageVersion(ctx: Context): String? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.packageManager.getPackageInfo(
                        ctx.packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    ).versionName
                } else {
                    @Suppress("DEPRECATION")
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                }
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

        @JvmStatic
        fun reportError(ctx: Context, t: Thread, e: Throwable) {
            TalkingDataSDK.onError(ctx, e)
            Log.e(TAG, t.toString(), e)
            val s = StringWriter()
            s.append("Version ").append(getPackageVersion(ctx)).append("\n")
            e.printStackTrace(PrintWriter(s))
            try {
                @Suppress("DEPRECATION")
                PrintWriter(
                    FileOutputStream(
                        Environment.getExternalStorageDirectory().absolutePath + "/com.xero.ca.error.log",
                        true
                    )
                ).use { fs ->
                    fs.println("* Error: " + SimpleDateFormat.getDateTimeInstance().format(Date()))
                    fs.println("FATAL:$s")
                }
            } catch (err: Exception) {
                Log.e(TAG, "I/O Error", err)
            }
            ctx.startActivity(BugReportActivity.createIntent(ctx, s.toString(), 0))
        }
    }
}
