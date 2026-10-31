package com.xero.ca.script.engine

import android.util.Log
import com.quickjs.JSContext
import com.quickjs.QuickJS
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * QuickJS「侧载加速」worker：Rhino 主引擎之外，单独起一个持 QuickJS context 的线程，
 * 只做 **纯 JS 计算**（JSON 进 / JSON 出，不接 Java 反射桥）。
 *
 * 为什么这样做：
 *  - QuickJS 引擎本身快（编译/执行纯 JS 比 Rhino 快很多）
 *  - 但它的 Java 通信桥（JSON + 反射）每次调用 ms 级，界面构建这种
 *    "上千次 Java 调用" 的活儿反而更慢 —— 那部分继续用 Rhino
 *  - 所以把纯计算/算法型热路径 offload 到 worker，Rhino 线程同步等结果
 *    （脚本线程本来就在后台，阻塞它不会 ANR）
 *
 * 线程模型：worker 线程独占一个 QuickJS context，所有调用排队串行执行；
 * 调用方（Rhino 脚本线程）用 latch 等结果。单线程是 QuickJS 的硬约束，
 * 正好用"任务队列"把它变成"异步服务"。
 */
object QuickJsWorker {

    private const val TAG = "Script"

    private class Task(val code: String, val latch: CountDownLatch, val out: Array<Any?>)

    private val queue = LinkedBlockingQueue<Task>()

    @Volatile
    private var started = false

    @Volatile
    private var failed = false

    @Volatile
    private var context: JSContext? = null

    private fun ensureStarted() {
        if (started || failed) return
        synchronized(this) {
            if (started || failed) return
            started = true
            Thread({
                try {
                    val runtime = QuickJS.createRuntime()
                    val ctx = runtime.createContext()
                    context = ctx
                    ctx.executeVoidScript(
                        "var __quickOut = null;" +
                            "function __quickCall(name, argsJson) {" +
                            "  var f = globalThis[name];" +
                            "  if (typeof f !== 'function') throw new Error('worker 函数不存在: ' + name);" +
                            "  var args = JSON.parse(argsJson);" +
                            "  return JSON.stringify(f.apply(null, args));" +
                            "}",
                        "quick-worker-boot.js"
                    )
                    while (true) {
                        val task = queue.take()
                        try {
                            task.out[0] = ctx.executeStringScript(task.code, "quick-worker.js")
                        } catch (t: Throwable) {
                            task.out[0] = "{\"__error\":\"" + (t.message ?: "worker error")
                                .replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"
                        } finally {
                            task.latch.countDown()
                        }
                    }
                } catch (t: Throwable) {
                    failed = true
                    Log.w(TAG, "QuickJS worker 启动失败", t)
                    while (true) {
                        val task = queue.take()
                        task.out[0] = null
                        task.latch.countDown()
                    }
                }
            }, "QuickJS-Worker").apply { isDaemon = true }.start()
        }
    }

    /** worker 是否可用（QuickJS .so 存在）。 */
    fun isAvailable(): Boolean = try {
        Class.forName("com.quickjs.QuickJS")
        true
    } catch (t: Throwable) {
        false
    }

    /** 在 worker 里执行一段 JS，返回结果字符串（必须是字符串/可 JSON 化）。 */
    fun eval(code: String, timeoutMs: Long = 10_000): String? {
        if (failed || !isAvailable()) return null
        ensureStarted()
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<Any?>(1)
        queue.put(Task(code, latch, out))
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) out[0] as? String else null
    }

    /** 注册一个纯 JS 函数（source 形如 "function name(a,b){...}"）。 */
    fun register(name: String, source: String): Boolean {
        val js = escapeForDoubleQuote(source)
        return eval("(function(){ $source ; return typeof $name })()") != null
    }

    /** 调用已注册函数；args 为 JSON 数组，返回 JSON 字符串。 */
    fun call(name: String, argsJson: String, timeoutMs: Long = 10_000): String? {
        val n = escapeForDoubleQuote(name)
        val a = escapeForDoubleQuote(argsJson)
        return eval("__quickCall(\"$n\", \"$a\")", timeoutMs)
    }

    private fun escapeForDoubleQuote(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
