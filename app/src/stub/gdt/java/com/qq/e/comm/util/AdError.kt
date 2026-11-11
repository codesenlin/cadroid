package com.qq.e.comm.util

/**
 * 优量汇 AdError 的空实现（桩），只在本地缺 GDTActionSDK aar 时参与编译。
 */
class AdError @JvmOverloads constructor(
    val errorCode: Int = 0,
    val errorMsg: String? = null,
)
