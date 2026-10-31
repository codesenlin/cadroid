package com.xero.ca.script.engine

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * QuickJS「侧载加速」worker：Rhino 主引擎之外，单独起一个持 QuickJS context 的线程，
 * 只做 **纯 JS 计算**（JSON 进 / JSON 出，不接 Java 反射桥）。
 *
 * 为什么这样做：
 *  - QuickJS 引擎本身快（编译/执行纯 JS 比 Rhino 快很多，实测 ~40x）
 *  - 但它的 Java 通信桥（JSON + 反射）每次调用 ms 级，界面构建这种
 *    "上千次 Java 调用" 的活儿反而更慢 —— 那部分继续用 Rhino
 *  - 所以把纯计算/算法型热路径 offload 到 worker，Rhino 线程同步等结果
 *    （脚本线程本来就在后台，阻塞它不会 ANR）
 *
 * 引擎绑定：io.github.dokar3:quickjs-kt-android:1.0.15（Apache-2.0，
 * .so 已 16KB page 对齐，替代此前自编译的极端版；worker 只需要 evaluate）。
 *
 * 线程模型：worker 线程独占一个 QuickJs 实例，所有调用排队串行执行；
 * 调用方（Rhino 脚本线程）用 latch 等结果。
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
    private var quickJs: QuickJs? = null

    private val bootScript =
        "function __quickCall(name, argsJson) {" +
            "  var f = globalThis[name];" +
            "  if (typeof f !== 'function') throw new Error('worker 函数不存在: ' + name);" +
            "  var args = JSON.parse(argsJson);" +
            "  return JSON.stringify(f.apply(null, args));" +
            "}"

    /** worker 是否可用（库存在 + Android 6.0+，quickjs-kt 的 AAR minSdk 是 23）。 */
    fun isAvailable(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 23) return false
        return try {
            Class.forName("com.dokar.quickjs.QuickJs")
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun ensureStarted() {
        if (started || failed) return
        synchronized(this) {
            if (started || failed) return
            started = true
            Thread({
                val js = try {
                    QuickJs.create(Dispatchers.Default).apply {
                        memoryLimit = 128L * 1024 * 1024
                        evaluationTimeoutMillis = 10_000
                    }
                } catch (t: Throwable) {
                    failed = true
                    Log.w(TAG, "QuickJS worker 启动失败", t)
                    drain()
                    return@Thread
                }
                try {
                    runBlocking { js.evaluate<Any?>(bootScript, "worker-boot.js") }
                } catch (t: Throwable) {
                    failed = true
                    Log.w(TAG, "QuickJS worker boot 失败", t)
                    drain()
                    return@Thread
                }
                quickJs = js
                while (true) {
                    val task = queue.take()
                    try {
                        // quickjs-kt 会把 JS 布尔/数字转成 Kotlin 类型，worker 协议统一按字符串回传
                        val r = runBlocking { js.evaluate<Any?>(task.code, "worker.js") }
                        task.out[0] = r?.toString()
                    } catch (t: Throwable) {
                        val msg = if (t is QuickJsException) t.message ?: "worker error" else t.toString()
                        task.out[0] = "{\"__error\":\"" + msg
                            .replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"
                    } finally {
                        task.latch.countDown()
                    }
                }
            }, "QuickJS-Worker").apply { isDaemon = true }.start()
        }
    }

    private fun drain() {
        while (true) {
            val task = try { queue.take() } catch (t: InterruptedException) { return }
            task.out[0] = null
            task.latch.countDown()
        }
    }

    /** 在 worker 里执行一段 JS，返回结果字符串（必须是字符串/可 JSON 化）。 */
    fun eval(code: String, timeoutMs: Long = 10_000): String? {
        if (failed || !isAvailable()) return null
        ensureStarted()
        if (failed) return null
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<Any?>(1)
        queue.put(Task(code, latch, out))
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) out[0] as? String else null
    }

    /** 注册一个纯 JS 函数（source 形如 "function name(a,b){...}"）。 */
    fun register(name: String, source: String): Boolean {
        val escaped = source.replace("\\", "\\\\").replace("\"", "\\\"")
        return eval("(function(){ $source ; return typeof $name })()") != null
    }

    /** 调用已注册函数；args 为 JSON 数组，返回 JSON 字符串。 */
    fun call(name: String, argsJson: String, timeoutMs: Long = 10_000): String? {
        val n = name.replace("\\", "\\\\").replace("\"", "\\\"")
        val a = argsJson.replace("\\", "\\\\").replace("\"", "\\\"")
        return eval("__quickCall(\"$n\", \"$a\")", timeoutMs)
    }
}
