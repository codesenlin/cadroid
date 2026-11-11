package com.qq.e.ads.splash

import com.qq.e.comm.util.AdError

/**
 * 优量汇 SplashADListener 的空实现（桩），方法签名与官方 SDK 一致。
 * 只在本地缺 GDTActionSDK aar（即整套 GDT 都没装配）时参与编译。
 */
interface SplashADListener {
    fun onADDismissed()
    fun onNoAD(adError: AdError?)
    fun onADPresent()
    fun onADClicked()
    fun onADTick(millisUntilFinished: Long)
    fun onADExposure()
    fun onADLoaded(expireTimestamp: Long)
}
