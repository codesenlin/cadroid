package com.qq.e.comm.managers

import android.content.Context

/**
 * 优量汇 GDTAdSdk 的空实现（桩），只在本地缺 GDTActionSDK aar 时参与编译。
 * 此时 BuildConfig.GDT_ENABLED 为 false，XApplication 不会调用它。
 */
object GDTAdSdk {
    interface OnStartListener {
        fun onStartSuccess()
        fun onStartFailed(e: Exception?)
    }

    @JvmStatic
    fun initWithoutStart(context: Context, appId: String) {}

    @JvmStatic
    fun start(listener: OnStartListener?) {}
}
