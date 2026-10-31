package com.xero.ca

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.KeyEvent

import com.xero.ca.gen.Secret
import com.xero.ca.script.AnalyticsPlatform
import com.xero.ca.script.RhinoFrameLayout
import com.xero.ca.script.RhinoWebSocketClientHelper
import com.xero.ca.script.RhinoWebSocketServerHelper
import com.xero.ca.script.RhinoWebView
import com.xero.ca.script.ScriptObject

import java.io.File
import java.io.IOException
import java.net.URISyntaxException

/**
 * 暴露给核心脚本的 Java 接口对象。
 *
 * 注意：这个类里的公有方法、字段名都被 AndroidBridge.js 通过反射访问，
 * 迁移到 Kotlin 时必须逐个保持原名（不要改成 Kotlin 属性），
 * 静态成员用 @JvmStatic、常量用 const val。
 */
@ScriptObject
class ScriptInterface(manager: ScriptManager) {
    private val mManager: ScriptManager = manager
    private val mContext = manager.getBindContext()!!
    private var mBindActivity: SplashActivity? = null
    private var mBindService: ScriptService? = null
    private val mUiThread: Thread
    private val mUiHandler: Handler
    private val mPreference: Preference
    private var mBridge: Bridge? = null
    private val mCallbackProxy: CallbackProxy
    private var mOnlineMode = false
    private var mOfflineReason: String? = null

    init {
        if (mContext is ScriptService) {
            mBindService = mContext
        }
        mUiHandler = Handler(mContext.mainLooper)
        mUiThread = mContext.mainLooper.thread
        mPreference = Preference.getInstance(mContext)
        mCallbackProxy = CallbackProxy()
    }

    fun reportError(techInfo: String?) {
        mContext.applicationContext.startActivity(
            BugReportActivity.createIntent(mContext, techInfo ?: "", android.os.Process.myPid())
        )
    }

    fun setLoadingTitle(title: String) {
        mBindActivity?.setLoadingTitle(title)
    }

    fun runOnUiThread(action: Runnable) {
        if (Thread.currentThread() != mUiThread) {
            mUiHandler.post(action)
        } else {
            action.run()
        }
    }

    fun setBridge(bridge: Bridge?) {
        mBridge = bridge
        GameBridgeService.setCallback(mCallbackProxy)
        AccessibilitySvc.setLifeCycleListener(mCallbackProxy)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ScriptTileService.setTileListener(mCallbackProxy)
        }
    }

    fun clearBridge() {
        mBridge = null
        GameBridgeService.removeCallback()
        AccessibilitySvc.setLifeCycleListener(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ScriptTileService.setTileListener(null)
        }
    }

    fun getContext(): Context = mContext

    fun getBindActivity(): SplashActivity? = mBindActivity

    fun getBindService(): ScriptService? = mBindService

    fun isActivityRunning(): Boolean = mBindActivity != null

    fun isServiceRunning(): Boolean = mBindService != null

    fun getIntent(): Intent? = mBindService?.getLastIntent()

    fun quit() {
        mBindService?.stopSelf()
        mBindActivity?.finishAndRemoveTask()
    }

    fun checkSelfPermission(permission: String): Int =
        mContext.checkPermission(permission, android.os.Process.myPid(), android.os.Process.myUid())

    fun beginPermissionRequest() {
        mContext.startActivity(
            Intent(mContext, PermissionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun startActivityForResult(intent: Intent, requestCode: Int) {
        if (mBridge == null || mBridge!!.applyIntent(intent)) {
            mContext.startActivity(ReturnDataActivity.createIntent(mContext, intent, requestCode))
        }
    }

    fun getPreference(): Preference = mPreference

    fun getAccessibilitySvc(): AccessibilitySvc? = AccessibilitySvc.getInstance()

    fun goToAccessibilitySetting() {
        AccessibilitySvc.goToAccessibilitySetting(mContext)
    }

    fun getScriptManager(): ScriptManager = mManager

    fun createWebSocketHelper(port: Int, delegate: RhinoWebSocketServerHelper.DelegateInterface?): RhinoWebSocketServerHelper =
        RhinoWebSocketServerHelper(port, delegate)

    fun createWSClient(uri: String, delegate: RhinoWebSocketClientHelper.DelegateInterface?): RhinoWebSocketClientHelper =
        try {
            RhinoWebSocketClientHelper(uri, delegate)
        } catch (e: URISyntaxException) {
            throw RuntimeException("Cannot create RhinoWebSocketClientHelper:" + e.message, e)
        }

    fun fileToUri(file: File): Uri = UnsafeFileProvider.getUriForFile(file)

    fun createFrameLayout(callback: RhinoFrameLayout.Callback?): RhinoFrameLayout =
        RhinoFrameLayout(mContext, callback)

    /** 当前脚本引擎名（"Rhino" / "QuickJS"），排查回退问题时用。 */
    fun getScriptEngine(): String = mManager.getEngine().name

    /** 双引擎模式：auto / rhino / quickjs（脚本里可读写，重启生效）。 */
    fun getScriptEngineMode(): String = com.xero.ca.script.engine.ScriptHealth.engineMode(mContext)

    // ---------- QuickJS 侧载加速 worker（Rhino 主引擎调用，纯 JS 计算） ----------

    /** worker 是否可用。 */
    fun quickWorkerAvailable(): Boolean = com.xero.ca.script.engine.QuickJsWorker.isAvailable()

    /** 在 QuickJS worker 注册一个纯 JS 函数（source: "function name(args){...}"）。 */
    fun quickWorkerRegister(source: String): Boolean {
        // 从 "function name(" 里提取函数名
        val name = Regex("function\\s+([A-Za-z0-9_$]+)\\s*\\(").find(source)?.groupValues?.get(1) ?: return false
        val escaped = source.replace("\\", "\\\\").replace("\"", "\\\"")
        com.xero.ca.script.engine.QuickJsWorker.eval("eval(\"$escaped\"); true") ?: return false
        return com.xero.ca.script.engine.QuickJsWorker.eval("typeof $name === 'function'") == "true"
    }

    /** 调用 worker 里的函数，argsJson 是 JSON 数组，返回 JSON 字符串。 */
    fun quickWorkerCall(name: String, argsJson: String): String? =
        com.xero.ca.script.engine.QuickJsWorker.call(name, argsJson)

    /** 直接执行 JS（调试/基准用），返回结果字符串。 */
    fun quickWorkerEval(code: String): String? = com.xero.ca.script.engine.QuickJsWorker.eval(code)

    /**
     * 把一个 JSON 值缓存到 worker 的全局变量里（只传一次，后续调用复用）。
     * 用于大块数据（命令库等）避免每次调用都序列化。
     */
    fun quickWorkerSetVar(name: String, json: String): Boolean {
        val safe = name.filter { it.isLetterOrDigit() || it == '_' }
        if (safe.isEmpty()) return false
        val escaped = json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        return com.xero.ca.script.engine.QuickJsWorker.eval(
            "globalThis.$safe = JSON.parse(\"$escaped\"); true"
        ) == "true"
    }

    fun setScriptEngineMode(mode: String) {
        com.xero.ca.script.engine.ScriptHealth.setEngineMode(mContext, mode)
    }

    /** 给脚本/排查用：当前引擎健康状态描述。 */
    fun getScriptEngineHealth(): String =
        com.xero.ca.script.engine.ScriptHealth.describe(mContext) + " current=" + mManager.getEngine().name

    /** HTTP 回调（脚本侧用 JS 对象实现）。 */
    @androidx.annotation.Keep
    interface HttpCallback {
        fun onDone(error: String?, body: String?)
    }

    /**
     * 异步 GET：脚本在 QuickJS 下跑在主线程，同步网络会卡主线程（ANR）。
     * 这个接口在后台线程请求，完成后回调脚本（回调会 marshal 回 JS 线程）。
     */
    @androidx.annotation.Keep
    fun httpGetAsync(url: String, callback: HttpCallback?) {
        val cb: HttpCallback = callback ?: return
        Thread(HttpGetTask(url, cb), "CA-http").start()
    }

    /** HTTP GET 任务（独立类：R8 对匿名内部类的捕获字段处理有问题，实测回调字段会丢成 null）。 */
    private class HttpGetTask(
        private val url: String,
        private val callback: HttpCallback,
    ) : Runnable {
        override fun run() {
            var error: String? = null
            var body: String? = null
            try {
                val conn = java.net.URL(url).openConnection()
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                (conn as? java.net.HttpURLConnection)?.requestMethod = "GET"
                body = conn.getInputStream().bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                error = t.toString()
            }
            try {
                callback.onDone(error, body)
            } catch (t: Throwable) {
                android.util.Log.w("Script", "httpGetAsync 回调失败", t)
            }
        }
    }

    fun getShellVersion(): Int = BuildConfig.SHELL_VERSION

    fun getVersionCode(): Int = BuildConfig.VERSION_CODE

    fun getVersion(): String = BuildConfig.VERSION_NAME

    fun getVerifyKey(): ByteArray = Secret.getVerifyKey()

    fun getGiteeFeedbackToken(): String = Secret.getGiteeFeedbackToken()

    fun createWebView(delegee: RhinoWebView.Delegee?): RhinoWebView =
        RhinoWebView(mContext).setDelegee(delegee)

    fun getGiteeClientId(): String = Secret.getGiteeClientId()

    fun getGiteeClientSecret(): String = Secret.getGiteeClientSecret()

    fun getAnalyticsPlatform(): AnalyticsPlatform = AnalyticsPlatform.getInstance()

    fun isOnlineMode(): Boolean = mOnlineMode

    fun getOfflineReason(): String? = mOfflineReason

    fun onScriptReady() {
        AdProvider.getInstance().runAfterComplete(mCallbackProxy)
    }

    fun notifyTileUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ScriptTileService.getInstance()?.notifyUpdate()
        }
    }

    fun beginForegroundTask(intent: Intent) {
        mContext.startActivity(
            intent.setClass(mContext, ForegroundTaskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    fun executeScriptCache(cacheFile: String, hash: ByteArray?, scope: Any?): Any? =
        mManager.executeScriptCache(cacheFile, hash, scope)

    @Throws(IOException::class)
    fun writeScriptCache(source: String, sourceName: String?, cacheFile: String, hash: ByteArray) {
        mManager.writeScriptCache(source, sourceName, cacheFile, hash)
    }

    @Throws(IOException::class, ClassNotFoundException::class)
    fun executeICode(iCode: ByteArray, hash: ByteArray?, scope: Any?): Any? =
        mManager.executeICode(iCode, hash, scope)

    @Throws(IOException::class)
    fun compileICode(source: String, sourceName: String?, hash: ByteArray): ByteArray =
        mManager.compileICode(source, sourceName, hash)

    /**
     * 把 JS 对象包成"静态类型为 Scriptable"的包装对象，脚本用它调内部方法
     * （get/put/delete/has 等）来模拟 Proxy。
     *
     * Rhino 1.9 的 NativeJavaObject 第三个参数换成了 TypeInfo，而且脚本里
     * `Class.forName(...)` 在反射调用下会走引导类加载器、找不到应用类，
     * 所以具体包装放在引擎实现里做（见 RhinoEngine.wrapAsScriptable）。
     */
    fun wrapAsScriptable(obj: Any?, scope: Any?): Any? =
        mManager.wrapAsScriptable(obj, scope)

    fun getUserID(): String = mPreference.getUserId()

    @ScriptObject
    interface Bridge {
        fun applyIntent(intent: Intent): Boolean

        fun onAccessibilitySvcCreate()

        fun onAccessibilitySvcDestroy()

        fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)

        fun onBeginPermissionRequest(activity: PermissionRequestActivity)

        fun onNewIntent(intent: Intent)

        fun onKeyEvent(event: KeyEvent)

        fun onRemoteEnabled()

        fun onRemoteMessage(msg: Message)

        fun onRemoteDisabled()

        fun onTileReady(config: ScriptTileService.TileConfig)

        fun onTileClick(config: ScriptTileService.TileConfig)

        fun onBeginForegroundTask(activity: ForegroundTaskActivity, intent: Intent?)
    }

    inner class CallbackProxy : AccessibilitySvc.ServiceLifeCycleListener,
        GameBridgeService.Callback, AdProvider.OnCompleteListener, ScriptTileService.TileListener {

        override fun onCreate() {
            mBridge?.onAccessibilitySvcCreate()
        }

        override fun onDestroy() {
            mBridge?.onAccessibilitySvcDestroy()
        }

        override fun onRemoteEnabled() {
            mBridge?.onRemoteEnabled()
        }

        override fun onRemoteMessage(msg: Message) {
            mBridge?.onRemoteMessage(msg)
        }

        override fun onRemoteDisabled() {
            mBridge?.onRemoteDisabled()
        }

        override fun onComplete(provider: AdProvider, success: Boolean, message: String?) {
            if (mOnlineMode) return
            mOnlineMode = success
            mOfflineReason = message
            mManager.startScript()
        }

        override fun onReady(config: ScriptTileService.TileConfig) {
            mBridge?.onTileReady(config)
        }

        override fun onClick(config: ScriptTileService.TileConfig) {
            mBridge?.onTileClick(config)
        }
    }

    companion object {
        const val ACTION_ADD_LIBRARY = "com.xero.ca.ADD_LIBRARY"
        const val ACTION_EDIT_COMMAND = "com.xero.ca.EDIT_COMMAND"
        const val ACTION_START_ON_BOOT = "com.xero.ca.START_ON_BOOT"
        const val ACTION_START_FROM_BACKGROUND = "com.xero.ca.ACTION_START_FROM_BACKGROUND"
        const val ACTION_START_FROM_SHORTCUT = "com.xero.ca.ACTION_START_FROM_SHORTCUT"
        const val ACTION_START_FROM_QS_TILE = "com.xero.ca.ACTION_START_FROM_QS_TILE"
        const val ACTION_SCRIPT_ACTION = "com.xero.ca.ACTION_SCRIPT_ACTION"
        const val ACTION_URI_ACTION = "com.xero.ca.ACTION_URI_ACTION"
        const val ACTION_DEBUG_EXEC = "com.xero.ca.DEBUG_EXEC"

        @JvmStatic
        fun getInstance(): ScriptInterface? =
            if (ScriptManager.hasInstance()) ScriptManager.getInstance().getScriptInterface() else null

        @JvmStatic
        fun callIntent(ctx: Context, intent: Intent) {
            val instance = getInstance()
            if (instance != null) {
                Log.d("CA", "Pass intent to script: $intent")
                instance.mBridge?.onNewIntent(intent)
            } else {
                val showSplash = !Preference.getInstance(ctx).getHideSplash()
                val serviceIntent = Intent(ctx, ScriptService::class.java)
                serviceIntent.putExtra(Intent.EXTRA_INTENT, intent)
                Log.d("CA", "Launch service to process intent: $intent")
                Log.d("CA", "Source context: $ctx")
                if (ctx is Activity && showSplash) {
                    serviceIntent.action = ScriptService.ACTION_PREPARE
                    ctx.startActivity(
                        Intent(ctx, SplashActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    serviceIntent.action = ScriptService.ACTION_RUN
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(serviceIntent)
                } else {
                    ctx.startService(serviceIntent)
                }
            }
        }

        @JvmStatic
        fun onSplashActivityCreate(activity: SplashActivity): Boolean {
            val instance = getInstance() ?: return false
            instance.mBindActivity = activity
            return true
        }

        @JvmStatic
        fun onSplashActivityDestroy(activity: SplashActivity) {
            getInstance()?.mBindActivity = null
        }

        @JvmStatic
        fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            getInstance()?.mBridge?.onActivityResult(requestCode, resultCode, data)
        }

        @JvmStatic
        fun onBeginPermissionRequest(activity: PermissionRequestActivity) {
            getInstance()?.mBridge?.onBeginPermissionRequest(activity)
        }

        @JvmStatic
        fun onBeginForegroundTask(activity: ForegroundTaskActivity, intent: Intent?) {
            getInstance()?.mBridge?.onBeginForegroundTask(activity, intent)
        }

        @JvmStatic
        fun applyIntent(intent: Intent): Boolean {
            val instance = getInstance() ?: return true
            return instance.mBridge?.applyIntent(intent) ?: true
        }

        @JvmStatic
        fun notifyKeyEvent(event: KeyEvent) {
            getInstance()?.mBridge?.onKeyEvent(event)
        }
    }
}
