package com.tendcloud.tenddata

import android.content.Context

/**
 * TalkingData SDK 的空实现（桩）。
 *
 * 只在本地缺少 `app/libs/SaaS_TalkingDataSDK_Android_V5.0.40.jar` 时参与编译，
 * clone 下来没有本地依赖也能直接构建，统计功能等于关闭。
 */
object TalkingDataSDK {
    @JvmStatic
    fun initSDK(context: Context, appId: String, channel: String, partner: String) {}

    @JvmStatic
    fun onError(context: Context, throwable: Throwable?) {}

    @JvmStatic
    fun onPageBegin(context: Context, pageName: String) {}

    @JvmStatic
    fun onPageEnd(context: Context, pageName: String) {}

    @JvmStatic
    fun onEvent(context: Context, eventId: String, eventValue: Double, kv: Map<String, Any?>?) {}

    @JvmStatic
    fun onEvent(context: Context, eventId: String, kv: Map<String, Any?>?) {}

    @JvmStatic
    fun setGlobalKV(key: String, value: Any?) {}

    @JvmStatic
    fun removeGlobalKV(key: String) {}
}
