package com.xero.ca.script.engine

import android.content.Context
import android.util.Log
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSFunction
import com.quickjs.JSObject
import com.quickjs.JavaCallback
import com.quickjs.QuickJS
import com.xero.ca.script.engine.quickjs.QuickJsBridgeHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [JsEngine] 的 QuickJS 实现（E2 进行中）。
 *
 * 绑定：`io.github.taoweiji.quickjs:quickjs-android:1.4.6`
 * （seven332/quickjs-android 的维护分支；暴露 JSFunction.call / registerJavaMethod，
 *  能做 Rhino 那种「JS 对象实现 Java 接口」的适配器）
 *
 * 已完成：
 *  - E1：运行时创建/销毁、执行脚本、脚本内容缓存（QuickJS 这边缓存的是源码，不是字节码）
 *  - E2 第一步：String RPC 桥 + JS prelude（java / android / Packages、字段/方法/构造、
 *    数组、instanceof、ScriptInterface 注入、Java 调 JS 钩子）
 *
 * 还没做（E2.2）：JS 对象实现 Java 接口（`new java.lang.Runnable({...})`）——
 * 需要桥把 JS 函数/对象传回 Java，QuickJS 绑定这边已经具备条件（JavaCallback 的
 * JSArray 参数能拿到 JSFunction），下一步实现。
 *
 * 真实脚本仍需要 E2.2 才能跑起来；默认引擎还是 Rhino。
 */
class QuickJsEngine : JsEngine {

    override val name: String = "QuickJS"

    override val requiresMainThread: Boolean = true

    /** 慢回调计数（双引擎健康检查用：QuickJS 状态不佳时退回 Rhino）。 */
    @Volatile
    var slowCallbackCount: Int = 0
        private set

    /** 引擎已释放（切换到 Rhino 后，旧 QuickJS UI 的回调还可能被触发，必须挡住）。 */
    @Volatile
    var released: Boolean = false
        private set

    private val disposeLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 绑定里 JsScript 的载荷：QuickJS 没有暴露 bytecode 编译接口，缓存源码。 */
    private data class ScriptData(val source: String, val sourceName: String?)

    private var runtime: QuickJS? = null
    private var context: JSContext? = null
    private var bridge: QuickJsBridgeHost? = null
    private var jsThread: Thread? = null
    private var postToJsThread: ((Runnable) -> Unit)? = null

    override fun isAvailable(context: Context): Boolean {
        return try {
            val rt = QuickJS.createRuntime()
            val ctx = rt.createContext()
            ctx.close()
            rt.close()
            true
        } catch (t: Throwable) {
            Log.w("Script", "QuickJS 不可用（${android.os.Build.SUPPORTED_ABIS.joinToString("/")}）: $t")
            false
        }
    }

    override fun enterContext(): JsContext {
        val rt = QuickJS.createRuntime()
        runtime = rt
        val ctx = rt.createContext()
        context = ctx
        return JsContext(ctx)
    }

    override fun exitContext() {
        released = true
        try {
            context?.close()
        } catch (_: Throwable) {
        } finally {
            context = null
        }
        try {
            runtime?.close()
        } catch (_: Throwable) {
        } finally {
            runtime = null
        }
    }

    override fun initScope(context: JsContext, scriptInterface: Any): JsScope {
        val ctx = context.impl as JSContext
        val bridge = QuickJsBridgeHost(scriptInterface)
        this.bridge = bridge
        // JS 侧统一入口：__caCall(requestJson) → responseJson
        ctx.registerJavaMethod(
            JavaCallback { _, args -> bridge.call(args.getString(0)) },
            "__caCall",
        )
        // 用 JS 对象实现 Java 接口：__caAdapter(className, implObject) → 代理 handle
        ctx.registerJavaMethod(
            JavaCallback { _, args ->
                bridge.newAdapter(args.getString(0), args.getObject(1)).toString()
            },
            "__caAdapter",
        )
        // 带 JS 原始值（函数/对象）的调用：call / new
        ctx.registerJavaMethod(
            JavaCallback { _, args ->
                bridge.raw(args.getString(0), args.getObject(1) as? JSArray)
            },
            "__caRaw",
        )
        bridge.jsCall = { fn, argsJson -> callJsFunction(ctx, fn, argsJson) }
        bridge.jsContext = ctx
        val prelude = readPrelude()
        ctx.executeVoidScript(prelude, "ca-prelude.js")
        Log.i("Script", "QuickJS: prelude 已加载（java/android/Packages + ScriptInterface）")
        return JsScope(ctx)
    }

    private fun callJsFunction(ctx: JSContext, fn: JSFunction, argsJson: String): Any? {
        if (released) return null
        val target = jsThread
        val post = postToJsThread
        if (target != null && post != null && Thread.currentThread() !== target) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val box = arrayOfNulls<Any?>(1)
            post(Runnable {
                try {
                    box[0] = doCallJsFunction(ctx, fn, argsJson)
                } catch (t: Throwable) {
                    Log.w("Script", "QuickJS: 适配器回调失败", t)
                } finally {
                    latch.countDown()
                }
            })
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            return box[0]
        }
        val t0 = android.os.SystemClock.uptimeMillis()
        val result = doCallJsFunction(ctx, fn, argsJson)
        val cost = android.os.SystemClock.uptimeMillis() - t0
        // 1.5s 以上视为"卡顿"（Rhino 时代正常回调都在几十 ms 内）
        if (cost > 1500) {
            slowCallbackCount++
            val fnName = try { fn.get("name") as? String } catch (t: Throwable) { null }
            Log.w("Script", "JS 回调耗时 " + cost + "ms fn=" + fnName)
        }
        return result
    }

    private fun doCallJsFunction(ctx: JSContext, fn: JSFunction, argsJson: String): Any? {
        if (released) return null
        return try {
            val helper = ctx.get("__caCallAdapter") as? JSFunction ?: return null
            val arr = JSArray(ctx)
            arr.push(fn)
            arr.push(argsJson)
            val result = helper.call(ctx, arr)
            fromJsValue(result)
        } catch (t: Throwable) {
            // 引擎已切换到 Rhino 时，旧 UI 的回调可能还会打到已经 dispose 的 context
            if (!released && disposeLogged.compareAndSet(false, true)) {
                Log.w("Script", "QuickJS 回调执行失败（引擎可能已释放）", t)
            }
            null
        }
    }

    /** JS 值 → Java 对象：原始值直接过，prelude 的 JavaObject 包装读 __handle。 */
    private fun fromJsValue(v: Any?): Any? = when (v) {
        null, is String, is Number, is Boolean -> v
        is JSObject -> {
            if (v is JSFunction) return null
            val h = try { v.get("__handle") } catch (t: Throwable) { null }
            if (h is Number) bridgeHandle(h.toInt()) else null
        }
        else -> null
    }

    private fun bridgeHandle(handle: Int): Any? = bridge?.resolve(handle)

    override fun setCallbackDispatcher(jsThread: Thread, post: (Runnable) -> Unit) {
        this.jsThread = jsThread
        this.postToJsThread = post
    }

    private fun readPrelude(): String {
        val stream = QuickJsEngine::class.java.getResourceAsStream("/quickjs-prelude.js")
            ?: throw IllegalStateException("找不到 quickjs-prelude.js")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun compile(
        context: JsContext,
        source: String,
        sourceName: String?,
        cacheFile: File?,
    ): JsScript {
        if (cacheFile != null) {
            try {
                cacheFile.writeText(source)
            } catch (e: Exception) {
                Log.e("Script", "Write QuickJS script cache failed", e)
            }
        }
        return JsScript(ScriptData(source, sourceName))
    }

    override fun readScriptCache(cacheFile: File, hash: ByteArray?): JsScript? = try {
        JsScript(ScriptData(cacheFile.readText(), null))
    } catch (e: Exception) {
        Log.e("Script", "Loading QuickJS script cache failed", e)
        null
    }

    override fun writeScriptCache(cacheFile: File, hash: ByteArray, script: JsScript) {
        cacheFile.writeText((script.impl as ScriptData).source)
    }

    override fun execute(context: JsContext, script: JsScript, scope: Any?): Any? {
        val ctx = context.impl as JSContext
        val data = script.impl as ScriptData
        return ctx.executeScript(data.source, data.sourceName ?: "script")
    }

    override fun callFunction(
        context: JsContext,
        scope: Any?,
        name: String,
        args: Array<Any?>,
    ): Any? {
        // 绑定没有直接暴露「按名字调全局函数」的桥（executeFunction 也行，但参数要 JSArray），
        // 这里统一走 prelude 的 __caCallHook，参数用 JSON 传（钩子参数都是简单值）。
        val ctx = context.impl as JSContext
        val argsJson = JSONArray(args.toList()).toString()
        val code = "__caCallHook(" + JSONObject.quote(name) + ", " + JSONObject.quote(argsJson) + ")"
        return try {
            ctx.executeScript(code, "ca-call-hook.js")
        } catch (t: Throwable) {
            Log.w("Script", "QuickJS: 调用 JS 钩子 $name 失败: $t")
            null
        }
    }

    override fun wrapAsScriptable(obj: Any?, scope: Any?): Any? {
        // 脚本里 DBSettings 用它把 target 包成「静态类型 Scriptable」的 Java 对象，
        // 语义和 RhinoEngine 一致：直接复用 Rhino 的 NativeJavaObject（Rhino 仍在依赖里）。
        val parentScope = (scope as? JsScope)?.impl ?: scope
        val scriptable = parentScope as? org.mozilla.javascript.Scriptable
        return org.mozilla.javascript.NativeJavaObject(
            scriptable,
            obj,
            org.mozilla.javascript.lc.type.TypeInfoFactory.GLOBAL.create(
                org.mozilla.javascript.Scriptable::class.java
            )
        )
    }

    override fun readICode(iCode: ByteArray, hash: ByteArray?): JsScript = JsScript(
        ScriptData(iCode.toString(Charsets.UTF_8), null)
    )

    override fun writeICode(script: JsScript, hash: ByteArray): ByteArray =
        (script.impl as ScriptData).source.toByteArray()
}
