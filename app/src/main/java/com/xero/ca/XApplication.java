package com.xero.ca;

import com.xero.ca.gen.Secret;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.util.Log;

import com.qq.e.comm.managers.GDTAdSdk;
import com.tendcloud.tenddata.TalkingDataSDK;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class XApplication extends Application {
    public static final String TAG = "CA";

    public static String getPackageVersion(Context ctx) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName;
            } else {
                return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            }
        } catch (PackageManager.NameNotFoundException e) {
            //do nothing
        }
        return null;
    }

    public static void reportError(Context ctx, Thread t, Throwable e) {
        TalkingDataSDK.onError(ctx, e);
        Log.e(TAG, t.toString(), e);
        StringWriter s = new StringWriter();
        s.append("Version ").append(getPackageVersion(ctx)).append("\n");
        e.printStackTrace(new PrintWriter(s));
        try {
            PrintWriter fs = new PrintWriter(new FileOutputStream(Environment.getExternalStorageDirectory().getAbsolutePath() + "/com.xero.ca.error.log", true));
            fs.println("* Error: " + SimpleDateFormat.getDateTimeInstance().format(new Date()));
            fs.println("FATAL:" + s);
            fs.close();
        } catch (Exception err) {
            Log.e(TAG, "I/O Error", err);
        }
        ctx.startActivity(BugReportActivity.createIntent(ctx, s.toString(), 0));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        TalkingDataSDK.initSDK(this.getApplicationContext(), Secret.getTDToken(), "default", "");
        // 优量汇（GDT）广告 SDK：AppID 从 4.x 起只能通过这里传入，必须在请求广告前初始化。
        // GDT_ENABLED 为 false 表示构建时没装配优量汇（缺 GDTActionSDK aar），直接跳过。
        String gdtAppId = Secret.getGDTAppID();
        if (BuildConfig.GDT_ENABLED && gdtAppId != null && !gdtAppId.isEmpty()) {
            GDTAdSdk.initWithoutStart(this, gdtAppId);
            GDTAdSdk.start(null);
        }
        Thread.setDefaultUncaughtExceptionHandler(new ErrorCatcher());
    }

    public class ErrorCatcher implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
            reportError(XApplication.this, t, e);
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }
    }
}
