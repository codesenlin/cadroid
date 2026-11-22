package com.xero.ca.script

/**
 * PEWS-API 未集成时的空实现。
 *
 * 只是为了让 ScriptInterface 在缺少 PEWS-API 依赖时仍能编译；
 * BuildConfig.PEWS_ENABLED 为 false，脚本层不会调用 start/sendCommand。
 */
class PewsWebSocketBridge(port: Int, delegate: Listener?) {
    interface Listener {
        fun onStart()
        fun onOpen(address: String)
        fun onClose()
        fun onEvent(json: String)
        fun onResponse(callbackId: Int, json: String)
        fun onError(message: String, bindFailure: Boolean)
    }

    private val listener: Listener? = delegate

    fun start() = Unit
    fun stop() = Unit
    fun setTcpNoDelay(value: Boolean) = Unit
    fun isConnected(): Boolean = false
    fun getRemoteAddress(): String = ""
    fun closeConnection() = Unit
    fun subscribeEvent(name: String): Boolean = false
    fun unsubscribeEvent(name: String): Boolean = false
    fun sendCommand(command: String, callbackId: Int): Boolean {
        listener?.onError("PEWS-API 未集成", false)
        return false
    }
}
