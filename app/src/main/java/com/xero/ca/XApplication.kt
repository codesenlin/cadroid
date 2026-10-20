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

class XApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TalkingDataSDK.initSDK(applicationContext, Secret.getTDToken(), "default", "")
        // 优量汇（GDT）广告 SDK：AppID 从 4.x 起只能通过这里传入，必须在请求广告前初始化。
        // GDT_ENABLED 为 false 表示构建时没装配优量汇（缺 GDTActionSDK aar），直接跳过。
        val gdtAppId = Secret.getGDTAppID()
        if (BuildConfig.GDT_ENABLED && !gdtAppId.isNullOrEmpty()) {
            GDTAdSdk.initWithoutStart(this, gdtAppId)
            GDTAdSdk.start(null)
        }
        Thread.setDefaultUncaughtExceptionHandler(ErrorCatcher())
    }

    inner class ErrorCatcher : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            reportError(this@XApplication, t, e)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(0)
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
