package com.xero.ca.script.engine

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 引擎选择 / 回退链。
 *
 * 规则：按 [candidates] 的顺序挑第一个 [JsEngine.isAvailable] 的。
 *
 * QuickJS 是 native 库（`.so`），不同绑定能提供的 ABI 有限：
 * 常见只有 `arm64-v8a` / `x86_64`，**32 位 ARM（armeabi-v7a）和小众架构
 * （riscv64、mips 等）往往没有可用产物**。这些设备上 [QuickJsEngine.isAvailable]
 * 会返回 false，自动落到 Rhino 上——脚本行为完全一致，只是没有 QuickJS 的性能收益。
 *
 * 所以：
 *  - JS 侧不要依赖"当前是哪个引擎"
 *  - 需要排查时用 `ScriptInterface.getScriptEngine()` 看引擎名
 */
object JsEngines {

    private const val TAG = "Script"

    /**
     * 引擎优先级（越前越优先）。
     *
     * E1 接 QuickJS 时把 `QuickJsEngine()` 加到这个列表最前面即可，
     * 回退逻辑不用改。
     */
    private fun candidates(): List<JsEngine> = listOf(
        // QuickJsEngine(),   // ← E1：接好绑定后打开
        RhinoEngine(),
    )

    /** 选一个当前设备可用的引擎；全都不可用时退回 Rhino（理论上不会发生）。 */
    fun select(context: Context): JsEngine {
        val abis = Build.SUPPORTED_ABIS.joinToString("/")
        for (engine in candidates()) {
            if (engine.isAvailable(context)) {
                Log.i(TAG, "脚本引擎: ${engine.name}（ABI: $abis）")
                return engine
            }
            Log.i(TAG, "脚本引擎 ${engine.name} 在当前设备不可用（ABI: $abis），回退下一个")
        }
        Log.w(TAG, "没有可用脚本引擎，回退 Rhino（ABI: $abis）")
        return RhinoEngine()
    }
}
