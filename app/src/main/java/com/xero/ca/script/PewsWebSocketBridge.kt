package com.xero.ca.script

import mcpews.MCClient
import mcpews.MCListener
import mcpews.MCSocketServer
import mcpews.command.CommandParser
import mcpews.event.EventType
import mcpews.message.MCMessage
import mcpews.message.MessagePurposeType

import java.net.BindException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 PEWS 的高层 MCSocketServer / MCClient API 适配成 CA 的 WSServer 可消费的形式。
 *
 * CA 侧仍然负责 UI、事件分发和控制台；协议解析、订阅、命令请求和响应匹配
 * 交给 PEWS。
 */
@ScriptObject
class PewsWebSocketBridge(port: Int, delegate: Listener?) :
    MCSocketServer(InetSocketAddress(port)) {

    @ScriptObject
    interface Listener {
        fun onStart()
        fun onOpen(address: String)
        fun onClose()
        fun onEvent(json: String)
        fun onResponse(callbackId: Int, json: String)
        fun onError(message: String, bindFailure: Boolean)
    }

    private val listener: Listener =
        requireNotNull(delegate) { "Listener can't be null" }

    private val parser = CommandParser(this)
    private val callbacks = ConcurrentHashMap<UUID, Int>()

    @Volatile
    private var active: MCClient? = null

    private val pewsListener = object : MCListener {
        override fun onConnected(client: MCClient) {
            active = client
            listener.onOpen(client.socket.remoteSocketAddress?.toString() ?: "")
        }

        override fun onDisconnected(client: MCClient) {
            if (active === client) active = null
            listener.onClose()
        }

        override fun onEvent(client: MCClient, eventMessage: MCMessage) {
            listener.onEvent(eventMessage.messageText)
        }

        override fun onResponse(client: MCClient, responseMessage: MCMessage, requestMessage: MCMessage) {
            emitResponse(requestMessage, responseMessage.messageText)
        }

        override fun onError(client: MCClient, errorMessage: MCMessage, requestMessage: MCMessage) {
            emitResponse(requestMessage, errorMessage.messageText)
        }
    }

    init {
        addListener(pewsListener)
    }

    override fun onStart() {
        listener.onStart()
    }

    override fun onError(conn: org.java_websocket.WebSocket?, ex: Exception) {
        listener.onError(ex.message ?: ex.javaClass.simpleName, ex is BindException)
    }

    fun isConnected(): Boolean = active?.socket?.isOpen == true

    fun getRemoteAddress(): String = active?.socket?.remoteSocketAddress?.toString() ?: ""

    fun closeConnection() {
        active?.close()
    }

    fun subscribeEvent(name: String): Boolean {
        val client = active ?: return false
        val type = EventType.fromString(name) ?: return false
        client.subscribeToEvent(type)
        return true
    }

    fun unsubscribeEvent(name: String): Boolean {
        val client = active ?: return false
        val type = EventType.fromString(name) ?: return false
        client.unsubscribeFromEvent(type)
        return true
    }

    /**
     * 发送一条 Minecraft 命令，callbackId 由 CA 侧分配。
     * 返回 false 表示命令无法被 PEWS 解析，或当前没有连接。
     */
    fun sendCommand(command: String, callbackId: Int): Boolean {
        val client = active ?: return false
        if (command == "closewebsocket" || command == "/closewebsocket") {
            client.close()
            return true
        }
        val parsed = parser.parseCommand(if (command.startsWith("/")) command else "/$command") ?: return false
        val message = MCMessage(parsed)
        callbacks[message.header.requestId] = callbackId
        client.send(pewsListener, message)
        return true
    }

    private fun emitResponse(request: MCMessage, json: String) {
        val id = callbacks.remove(request.header.requestId) ?: return
        listener.onResponse(id, json)
    }

    @Suppress("unused")
    private fun isCommandPurpose(message: MCMessage): Boolean =
        message.purpose == MessagePurposeType.COMMAND_REQUEST
}
