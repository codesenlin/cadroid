package com.xero.ca

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.xero.ca.script.engine.JsContext
import com.xero.ca.script.engine.JsEngine
import com.xero.ca.script.engine.JsScope
import com.xero.ca.script.engine.JsScript
import com.xero.ca.script.engine.RhinoEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.lang.ref.WeakReference

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
        // 主引擎只有 Rhino（反射/UI 密集逻辑最快）；纯 JS 计算走 QuickJsWorker 侧载。
        engine = RhinoEngine()
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
        handler = Handler()
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
                reportEngineError(lastInitError)
                return false
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
            return true
        }
    }

    /** 释放运行时（Rhino 在脚本线程 loop 退出后调用）。 */
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
            if (callUnload) callScriptHook("unload", arrayOf())
            scriptInterface?.clearBridge()
            handler?.looper?.quit()
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
