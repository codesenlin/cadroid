package com.xero.ca

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.xero.ca.script.engine.JsContext
import com.xero.ca.script.engine.JsEngine
import com.xero.ca.script.engine.JsScope
import com.xero.ca.script.engine.JsEngines
import com.xero.ca.script.engine.JsScript
import com.xero.ca.script.engine.QuickJsEngine
import com.xero.ca.script.engine.RhinoEngine
import com.xero.ca.script.engine.ScriptHealth
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 脚本引擎的管理者：负责加载/编译脚本、在独立线程里跑事件循环。
 *
 * E0（见 docs/migration-kotlin-quickjs.md）：这里已经不再直接引用 Rhino，
 * 引擎相关的东西都收在 [JsEngine] / [RhinoEngine] 里；换 QuickJS 时只要换
 * [engine] 的实现。
 */
class ScriptManager private constructor() {

    private var engine: JsEngine = RhinoEngine()
    private var cx: JsContext? = null
    private var scope: JsScope? = null
    private var handler: Handler? = null
    private var bindContext: android.content.Context? = null
    @Volatile
    private var scriptInterface: ScriptInterface? = null
    private var debugFile: String? = null
    private var cacheDir: String? = null
    private var hotfix: Hotfix? = null

    @Volatile
    private var running = false
    private var sourceName: String? = null
    private var compiledScript: JsScript? = null

    @Synchronized
    fun prepareScript(ctx: android.content.Context, srcName: String, runAfterPrepared: Boolean) {
        if (running) return
        running = true
        bindContext = ctx
        sourceName = srcName
        // 双引擎：auto 模式下 QuickJS 可靠时优先（加速），状态不好则 Rhino 兜底。
        // 不可用设备（如 32 位 ARM、小众 ABI）JsEngines 内部自动回退 Rhino。
        engine = JsEngines.select(ctx)
        fallbackStarted.set(false)
        // 先创建 ScriptInterface：启动页 onSplashActivityCreate 会检查它是否存在，
        // 晚于启动页创建的话 SplashActivity 会直接 finish，广告回调也就不会触发 startScript。
        scriptInterface = initScriptInterface()
        startPrepareThread(ctx, srcName, runAfterPrepared)
    }

    private fun startPrepareThread(
        ctx: android.content.Context,
        srcName: String,
        runAfterPrepared: Boolean,
    ) {
        val th = Thread(
            Thread.currentThread().threadGroup,
            PrepareCommand(runAfterPrepared),
            "Script_Loader",
            262144,
        )
        th.start()
    }

    @Synchronized
    fun startScript() {
        val h = handler ?: return
        h.post(StartCommand())
    }

    @Synchronized
    fun endScript(callUnload: Boolean) {
        val h = handler ?: return
        if (!running) return
        running = false
        h.post(ExitCommand(callUnload))
    }

    fun endScript() {
        endScript(true)
    }

    fun isRunning(): Boolean = running

    @Synchronized
    fun callScriptHook(name: String, args: Array<Any?>) {
        handler ?: return
        val c = cx ?: return
        engine.callFunction(c, scope, name, args)
    }

    fun setHotfix(coreFile: String, signFile: String, verify: ByteArray, versionCode: Int) {
        hotfix = Hotfix(coreFile, signFile, verify, versionCode)
    }

    fun setCacheDir(cacheDir: String) {
        this.cacheDir = cacheDir
    }

    /** 当前使用的脚本引擎（E3 双引擎切换时从这里换）。 */
    fun getEngine(): JsEngine = engine

    fun getContext(): JsContext? = cx

    fun getScope(): JsScope? = scope

    fun getBindContext(): android.content.Context? = bindContext

    fun getScriptInterface(): ScriptInterface? = scriptInterface

    fun getHandler(): Handler? = handler

    @Throws(IOException::class)
    fun open(path: String): InputStream? {
        val file = debugFile
        if (file != null) {
            val src = File(file)
            return FileInputStream(File(src.parent, path))
        } else if (bindContext != null) {
            return bindContext!!.assets.open(path)
        }
        return null
    }

    private fun initScriptInterface(): ScriptInterface = ScriptInterface(this)

    @Throws(IOException::class)
    private fun getScriptReader(): Reader {
        val file = debugFile
        if (file != null) {
            try {
                return FileReader(file)
            } catch (e: IOException) {
                Log.e("Script", "Loading debugFile failed", e)
            }
        }
        val hf = hotfix
        if (hf != null) {
            try {
                return InputStreamReader(hf.getInputStream())
            } catch (e: IOException) {
                Log.e("Script", "Loading hotfix failed", e)
            }
        }
        return InputStreamReader(ScriptFileStream.fromAsset(bindContext!!, "script.js"))
    }

    @Throws(IOException::class)
    private fun getCompiledScript(): JsScript {
        val source = getScriptReader().use { it.readText() }
        val cache = cacheDir
        val cacheFile = if (cache != null && debugFile != null) File(cache, "script.obj") else null
        return engine.compile(cx!!, source, sourceName, cacheFile)
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    fun executeScriptCache(cacheFile: String, hash: ByteArray?, scope: Any?): Any? {
        val script = engine.readScriptCache(File(cacheFile), hash) ?: throw IOException("无法读取脚本缓存: $cacheFile")
        return engine.execute(cx!!, script, scope)
    }

    @Throws(IOException::class)
    fun writeScriptCache(source: String, sourceName: String?, cacheFile: String, hash: ByteArray) {
        val script = engine.compile(cx!!, source, sourceName, null)
        engine.writeScriptCache(File(cacheFile), hash, script)
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    fun executeICode(iCode: ByteArray, hash: ByteArray?, scope: Any?): Any? {
        val script = engine.readICode(iCode, hash) ?: throw IOException("无法读取 iCode")
        return engine.execute(cx!!, script, scope)
    }

    @Throws(IOException::class)
    fun compileICode(source: String, sourceName: String?, hash: ByteArray): ByteArray {
        val script = engine.compile(cx!!, source, sourceName, null)
        return engine.writeICode(script, hash)
    }

    /** 把 Java 对象包装成脚本可直接调用的值（引擎相关，交给 [engine]）。 */
    fun wrapAsScriptable(obj: Any?, scope: Any?): Any? = engine.wrapAsScriptable(obj, scope)

    private var lastInitError: Throwable? = null

    /** 初始化引擎运行时；失败把异常记到 [lastInitError]，由调用方决定是否回退。 */
    private fun initScriptRuntime(): Boolean = try {
        cx = engine.enterContext()
        scope = engine.initScope(cx!!, scriptInterface!!)
        handler = if (engine.requiresMainThread) Handler(Looper.getMainLooper()) else Handler()
        // QuickJS 是单线程的：Java 回调需要 post 回这个 Looper 才能执行 JS
        val h = handler!!
        engine.setCallbackDispatcher(Thread.currentThread(), h::post)
        true
    } catch (t: Throwable) {
        lastInitError = t
        Log.e("Script", "脚本引擎 ${engine.name} 初始化失败", t)
        false
    }

    private fun reportEngineError(t: Throwable?) {
        val ctx = bindContext ?: return
        XApplication.reportError(
            ctx.applicationContext, Thread.currentThread(),
            IllegalStateException("脚本引擎初始化失败（${engine.name} 运行时不可用）", t)
        )
    }

    inner class PrepareCommand(private val runAfterPrepared: Boolean) : Runnable {
        override fun run() {
            if (engine.requiresMainThread) {
                // QuickJS：JS 是单线程的，而脚本要大量操作 Android UI（只能在主线程），
                // 所以整个 JS 生命周期都放到主线程；主线程的 Looper 由系统驱动，不自己 loop。
                Handler(Looper.getMainLooper()).post(Runnable {
                    if (prepareOnCurrentThread(runAfterPrepared)) {
                        Log.i("Script", "脚本引擎 ${engine.name} 在主线程上运行")
                    }
                })
                return
            }
            Looper.prepare()
            if (!prepareOnCurrentThread(runAfterPrepared)) return
            Looper.loop()
            shutdownRuntime()
        }

        private fun prepareOnCurrentThread(runAfterPrepared: Boolean): Boolean {
            // 引擎初始化放在 try 里：Rhino 1.9 用到的 jdk.dynalink / StackWalker 在
            // Android 上不存在，正常情况下（-1 纯解释）不会加载到它们，但万一触发，
            // 抛的是 ExceptionInInitializerError（Error 不是 Exception），这里必须接住，
            // 否则直接崩在启动阶段。
            if (scriptInterface == null) scriptInterface = initScriptInterface()
            if (!initScriptRuntime()) {
                // 运行时才发现不可用（例如 .so 存在但加载失败）：回退 Rhino 再试一次
                if (engine.name != "Rhino") {
                    Log.w("Script", "脚本引擎 ${engine.name} 初始化失败，回退 Rhino", lastInitError)
                    try {
                        engine.exitContext()
                    } catch (_: Throwable) {
                    }
                    ScriptHealth.markQuickJsBroken(bindContext, "引擎初始化失败")
                    engine = RhinoEngine()
                    if (!initScriptRuntime()) {
                        reportEngineError(lastInitError)
                        return false
                    }
                } else {
                    reportEngineError(lastInitError)
                    return false
                }
            }
            try {
                val time = SystemClock.uptimeMillis()
                compiledScript = getCompiledScript()
                Log.d("Script", "Script Compiled in " + (SystemClock.uptimeMillis() - time) + "ms")
            } catch (e: Exception) {
                val ctx = bindContext ?: return false
                XApplication.reportError(
                    ctx.applicationContext, Thread.currentThread(),
                    SecurityException("Fail to decode and execute the script.", e)
                )
                return false
            }
            if (runAfterPrepared) {
                startScript()
            } else {
                scriptInterface?.onScriptReady()
            }
            if (engine is QuickJsEngine) {
                startQuickJsHealthMonitor()
            }
            return true
        }
    }

    // ---------- QuickJS 健康监控（状态不好自动退回 Rhino） ----------

    private val fallbackStarted = AtomicBoolean(false)

    @Volatile
    private var healthMonitorRunning = false

    /**
     * QuickJS 健康监控：
     *  - 主线程看门狗：每 500ms 往主线程投一个空任务，超过 4s 没执行 → 认为卡死
     *  - 慢回调：QuickJS 上单个 JS 回调 > 2.5s 的累计 3 次 → 认为状态不佳
     *  - 启动 90s 内没有触发回退 → 标记 QuickJS 可靠（后续 auto 模式继续用）
     */
    private fun startQuickJsHealthMonitor() {
        if (healthMonitorRunning) return
        healthMonitorRunning = true
        val engineRef = engine as? QuickJsEngine ?: return
        Thread({
            val start = SystemClock.uptimeMillis()
            try {
                while (healthMonitorRunning) {
                    val h = handler ?: break
                    val latch = CountDownLatch(1)
                    val t0 = SystemClock.uptimeMillis()
                    h.post { latch.countDown() }
                    val done = latch.await(9, TimeUnit.SECONDS)
                    val blocked = SystemClock.uptimeMillis() - t0
                    val elapsed = SystemClock.uptimeMillis() - start
                    if (blocked > 1500) Log.w("Script", "主线程响应慢: " + blocked + "ms")
                    // 启动阶段（前 20s）脚本初始化本来就会长时间占主线程（没有用户输入，
                    // 不会触发输入 ANR），这段只记录不判定；稳定期再按 4s 判定
                    val settle = elapsed < 20_000
                    if (!settle && (!done || blocked > 4000)) {
                        fallbackToRhino("主线程阻塞 ${blocked}ms")
                        return@Thread
                    }
                    if (!settle && engineRef.slowCallbackCount >= 2) {
                        fallbackToRhino("JS 回调过慢（${engineRef.slowCallbackCount} 次）")
                        return@Thread
                    }
                    if (elapsed > 120_000) {
                        ScriptHealth.markQuickJsOk(bindContext)
                        Log.i("Script", "QuickJS 启动阶段状态良好，保持使用")
                        return@Thread
                    }
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Log.w("Script", "QuickJS 健康监控异常", t)
            }
        }, "CA-EngineHealth").apply { isDaemon = true }.start()
    }

    /** QuickJS 状态不佳：标记不可靠，并当场重启脚本走 Rhino（同一进程内）。 */
    private fun fallbackToRhino(reason: String) {
        if (!fallbackStarted.compareAndSet(false, true)) return
        healthMonitorRunning = false
        Log.w("Script", "QuickJS 状态不佳（$reason），切换 Rhino 兜底")
        ScriptHealth.markQuickJsBroken(bindContext, reason)
        val h = handler
        if (h == null) return
        h.post {
            try {
                callScriptHook("unload", arrayOf())
            } catch (_: Throwable) {
            }
            try {
                scriptInterface?.clearBridge()
            } catch (_: Throwable) {
            }
            shutdownRuntime()
            running = false
            engine = RhinoEngine()
            val ctx = bindContext
            val src = sourceName
            if (ctx != null && src != null) {
                running = true
                scriptInterface = initScriptInterface()
                Log.i("Script", "已切换 Rhino，重新准备脚本")
                startPrepareThread(ctx, src, true)
            }
        }
    }

    /** 释放运行时（Rhino 在 loop 退出后调用；QuickJS 在 ExitCommand 里调用）。 */
    private fun shutdownRuntime() {
        try {
            engine.exitContext()
        } catch (_: Throwable) {
        }
        cx = null
        scope = null
        handler = null
        scriptInterface = null
    }

    inner class StartCommand : Runnable {
        override fun run() {
            val script = compiledScript
            if (script != null) {
                val t0 = SystemClock.uptimeMillis()
                engine.execute(cx!!, script, scope)
                Log.i("Script", "脚本执行完毕，用了 " + (SystemClock.uptimeMillis() - t0) + "ms")
                compiledScript = null
            }
        }
    }

    inner class ExitCommand(private val callUnload: Boolean) : Runnable {
        override fun run() {
            healthMonitorRunning = false
            if (callUnload) callScriptHook("unload", arrayOf())
            scriptInterface?.clearBridge()
            if (engine.requiresMainThread) {
                // 主线程的 Looper 不能 quit，直接释放引擎
                shutdownRuntime()
            } else {
                handler?.looper?.quit()
            }
            debugFile = null
        }
    }

    inner class Hotfix(
        private val coreFile: String,
        private val signFile: String,
        private val verify: ByteArray,
        private val versionCode: Int,
    ) {
        @Throws(IOException::class)
        fun getInputStream(): InputStream =
            ScriptFileStream.fromFile(bindContext!!, coreFile, signFile, verify, versionCode)
    }

    companion object {
        private var instance: WeakReference<ScriptManager?> = WeakReference(null)

        @JvmStatic
        @Synchronized
        fun hasInstance(): Boolean = instance.get() != null

        @JvmStatic
        @Synchronized
        fun getInstance(): ScriptManager {
            var m = instance.get()
            if (m == null) {
                m = ScriptManager()
                instance = WeakReference(m)
            }
            return m
        }

        @JvmStatic
        fun createDebuggable(debugFile: String): ScriptManager? {
            val m = instance.get()
            return if (m == null || !m.isRunning()) {
                val created = ScriptManager()
                created.debugFile = debugFile
                instance = WeakReference(created)
                created
            } else {
                null
            }
        }
    }
}
