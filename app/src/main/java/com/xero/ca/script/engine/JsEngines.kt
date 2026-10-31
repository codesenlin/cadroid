package com.xero.ca.script.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.xero.ca.BuildConfig

/**
 * 引擎选择 / 回退链（双引擎：QuickJS 加速，Rhino 兜底）。
 *
 * 选择策略（[BuildConfig.JS_ENGINE] 或用户偏好）：
 *  - `auto`：优先 QuickJS，但【上次 QuickJS 被标记为不可靠】时直接用 Rhino
 *  - `quickjs`：强制先试 QuickJS（不可用/初始化失败仍会退回 Rhino）
 *  - `rhino`：只用 Rhino
 *
 * QuickJS 是 native 库（`.so`），不同 ABI 产物有限（常见只有 arm64-v8a/x86_64），
 * 不可用时自动落到 Rhino——脚本行为一致，只是没有 QuickJS 的加速。
 *
 * QuickJS 的可靠性状态由 [ScriptHealth] 持久化：
 * 启动卡顿 / 回调异常 / 初始化失败时会被记为"不可靠"，auto 模式下次启动
 * 直接走 Rhino；一段时间（或版本更新）后再重新尝试 QuickJS。
 */
object JsEngines {

    private const val TAG = "Script"

    /** 引擎优先级（越前越优先），按模式 + 健康状态决定。 */
    private fun candidates(mode: String, quickJsTrusted: Boolean): List<JsEngine> = buildList {
        when (mode) {
            "rhino" -> Unit
            "quickjs" -> add(QuickJsEngine())
            else -> {
                // auto：QuickJS 可靠时才首选它
                if (quickJsTrusted) add(QuickJsEngine())
            }
        }
        add(RhinoEngine())
    }

    /** 选一个当前设备可用的引擎；全都不可用时退回 Rhino（理论上不会发生）。 */
    fun select(context: Context): JsEngine {
        val abis = Build.SUPPORTED_ABIS.joinToString("/")
        val mode = ScriptHealth.engineMode(context)
        val trusted = ScriptHealth.isQuickJsTrusted(context)
        Log.i(TAG, "脚本引擎模式: $mode（QuickJS 信任状态: $trusted，ABI: $abis）")
        for (engine in candidates(mode, trusted)) {
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
