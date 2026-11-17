package com.xero.ca.script

import android.content.res.AssetManager
import android.util.Log
import yancey.chelper.core.CHelperCore
import yancey.chelper.core.Suggestion

/**
 * CA 脚本层访问 CHelper 原生内核的桥。
 *
 * 返回字符串而不是 JNI 对象，避免 Rhino 直接持有 Java 对象；
 * 具体查询格式与 CA_Reforged 的桥保持一致，方便脚本层逐段解析。
 */
object CHelperBridge {
    private const val TAG = "CHelperBridge"

    private var core: CHelperCore? = null
    private var packName: String? = null

    private fun ensureCore(assets: AssetManager, pack: String) {
        if (core != null && pack == packName) return
        try {
            core?.close()
        } catch (_: Throwable) {
        }
        core = null
        packName = null
        core = CHelperCore.fromAssets(assets, "cpack/$pack")
        packName = pack
    }

    fun isAvailable(assets: AssetManager): Boolean = try {
        val list = assets.list("cpack")
        list != null && list.isNotEmpty() && Class.forName("yancey.chelper.core.CHelperCore") != null
    } catch (t: Throwable) {
        Log.w(TAG, "CHelper unavailable", t)
        false
    }

    private fun fail(op: String, t: Throwable): RuntimeException {
        Log.e(TAG, "CHelper $op failed", t)
        return t as? RuntimeException ?: RuntimeException("CHelper $op: $t", t)
    }

    /**
     * 一次查询返回：
     * structure \u0001 hint \u0001 error \u0001 suggestions \u0001 syntaxTypes \u0001 errorRanges
     * suggestions 内部用 \u0002 分隔，errorRanges 用 \u0002 分隔 "start,end"。
     */
    @Synchronized
    fun query(assets: AssetManager, pack: String, command: String, cursor: Int): String {
        try {
            ensureCore(assets, pack)
            val context = core!!.createContext(command)
            try {
                val structure = context.structure ?: ""
                val hint = context.getParamHint(cursor) ?: ""
                val reasons = context.errorReasons
                val error = reasons?.firstOrNull()?.errorReason ?: ""
                val sb = StringBuilder()
                sb.append(structure).append('\u0001')
                sb.append(hint).append('\u0001')
                sb.append(error).append('\u0001')
                val size = context.getSuggestionsSize(cursor).coerceAtMost(200)
                var first = true
                for (i in 0 until size) {
                    val s: Suggestion = context.getSuggestion(cursor, i) ?: continue
                    val name = s.name ?: ""
                    if (!first) sb.append('\u0002')
                    first = false
                    val desc = s.description ?: ""
                    sb.append(if (desc.isEmpty()) name else "$name - $desc")
                }
                sb.append('\u0001')
                context.syntaxToken?.forEach { sb.append((if (it in 0..35) '0' + it else '0')) }
                sb.append('\u0001')
                reasons?.forEachIndexed { index, r ->
                    if (index > 0) sb.append('\u0002')
                    sb.append(r.start).append(',').append(r.end)
                }
                return sb.toString()
            } finally {
                context.close()
            }
        } catch (t: Throwable) {
            throw fail("query", t)
        }
    }

    @Synchronized
    fun probe(assets: AssetManager, pack: String, command: String): String {
        try {
            ensureCore(assets, pack)
            val context = core!!.createContext(command)
            return try {
                context.command ?: ""
            } finally {
                context.close()
            }
        } catch (t: Throwable) {
            throw fail("probe", t)
        }
    }

    @Synchronized
    fun structure(assets: AssetManager, pack: String, command: String): String {
        try {
            ensureCore(assets, pack)
            val context = core!!.createContext(command)
            return try {
                context.structure ?: ""
            } finally {
                context.close()
            }
        } catch (t: Throwable) {
            throw fail("structure", t)
        }
    }

    @Synchronized
    fun applySuggestion(
        assets: AssetManager,
        pack: String,
        command: String,
        cursor: Int,
        which: Int,
    ): Array<String> {
        try {
            ensureCore(assets, pack)
            val context = core!!.createContext(command)
            return try {
                val addSpace = context.getSuggestion(cursor, which)?.isAddSpace ?: false
                val result = context.applySuggestion(cursor, which)
                    ?: return arrayOf("", "0", if (addSpace) "1" else "0")
                arrayOf(result.text, result.selection.toString(), if (addSpace) "1" else "0")
            } finally {
                context.close()
            }
        } catch (t: Throwable) {
            throw fail("applySuggestion", t)
        }
    }
}
