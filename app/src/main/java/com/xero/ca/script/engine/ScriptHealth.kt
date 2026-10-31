package com.xero.ca.script.engine

import android.content.Context
import android.util.Log
import com.xero.ca.BuildConfig

/**
 * 双引擎的"健康状态"记忆（QuickJS 加速，Rhino 兜底）。
 *
 * QuickJS 在真机上偶尔会因为主线程阻塞/桥回调异常导致卡顿甚至 ANR。
 * 我们不想因此把用户卡死——所以：
 *  - 一旦检测到 QuickJS 状态不好，就持久化标记为"不可靠"
 *  - auto 模式下下次启动直接走 Rhino
 *  - 冷却期（[BROKEN_COOLDOWN_MS]）过后再重新尝试 QuickJS
 *  - 脚本里可以手动切换：ScriptInterface.setScriptEngineMode("rhino"/"quickjs"/"auto")
 */
object ScriptHealth {

    private const val TAG = "Script"
    private const val PREFS = "ca_script_engine"
    private const val KEY_MODE = "mode"
    private const val KEY_BROKEN_UNTIL = "quickjs_broken_until"
    private const val KEY_BROKEN_REASON = "quickjs_broken_reason"

    /** QuickJS 被判"不可靠"后的冷却时间：24 小时（避免每次启动都白试一遍 QuickJS）。 */
    private const val BROKEN_COOLDOWN_MS = 24L * 60 * 60 * 1000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** auto / rhino / quickjs；默认取构建配置。 */
    fun engineMode(context: Context): String {
        val stored = prefs(context).getString(KEY_MODE, null)
        if (stored != null) return stored
        return when (BuildConfig.JS_ENGINE.lowercase()) {
            "quickjs" -> "quickjs"
            "auto" -> "auto"
            else -> "rhino"
        }
    }

    fun setEngineMode(context: Context, mode: String) {
        if (mode != "auto" && mode != "rhino" && mode != "quickjs") return
        prefs(context).edit().putString(KEY_MODE, mode).apply()
        Log.i(TAG, "脚本引擎模式已设置为 $mode（重启 App 生效）")
    }

    /** auto 模式下 QuickJS 是否还值得尝试。 */
    fun isQuickJsTrusted(context: Context): Boolean {
        val until = prefs(context).getLong(KEY_BROKEN_UNTIL, 0L)
        return System.currentTimeMillis() >= until
    }

    fun markQuickJsBroken(context: Context?, reason: String) {
        val ctx = context ?: return
        val until = System.currentTimeMillis() + BROKEN_COOLDOWN_MS
        prefs(ctx).edit()
            .putLong(KEY_BROKEN_UNTIL, until)
            .putString(KEY_BROKEN_REASON, reason)
            .apply()
        Log.w(TAG, "QuickJS 标记为不可靠（$reason），之后优先 Rhino，${BROKEN_COOLDOWN_MS / 3600_000}h 后重试")
    }

    fun markQuickJsOk(context: Context?) {
        val ctx = context ?: return
        prefs(ctx).edit()
            .remove(KEY_BROKEN_UNTIL)
            .remove(KEY_BROKEN_REASON)
            .apply()
    }

    fun describe(context: Context): String {
        val mode = engineMode(context)
        val trusted = isQuickJsTrusted(context)
        val reason = prefs(context).getString(KEY_BROKEN_REASON, null)
        return "mode=$mode trusted=$trusted" + if (reason != null) " brokenReason=$reason" else ""
    }
}
