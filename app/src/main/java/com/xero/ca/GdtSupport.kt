package com.xero.ca

import android.os.Build

/**
 * 优量汇（GDT）原生库的 ABI 判断。
 *
 * `libturingad.so` 只有 ARM 产物（armeabi / armeabi-v7a / arm64-v8a），
 * x86 / x86_64 / riscv64 等架构上没有对应的 so。这类设备上不尝试初始化
 * GDT（`GDTAdSdk.initWithoutStart` 内部会加载 so，即使 catch 住了也可能
 * 留下半初始化状态），直接关掉广告功能，应用其余部分照常运行。
 *
 * 这是"原生库必须可选"原则的第三层：
 *  1. 构建期：`GDT_ENABLED` / `-Pca.withGdt=false`
 *  2. 运行期 ABI 预检：这里
 *  3. 运行期加载兜底：XApplication 里的 `catch (Throwable)`（UnsatisfiedLinkError 是 Error）
 */
object GdtSupport {

    private val ARM_ABIS = setOf("armeabi", "armeabi-v7a", "arm64-v8a")

    /** 当前设备是否有 ARM ABI（GDT 的 so 只覆盖 ARM）。 */
    fun isAbiSupported(): Boolean = Build.SUPPORTED_ABIS.any { it in ARM_ABIS }

    fun describeAbis(): String = Build.SUPPORTED_ABIS.joinToString("/")
}
