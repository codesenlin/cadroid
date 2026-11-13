package com.xero.ca.script

import mcpews.MCSocketServer
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake

import java.net.InetSocketAddress

/**
 * PEWS-API 的低层服务器适配。
 *
 * CA 的 WSServer 仍负责 Minecraft 消息协议；这里只把传输层的
 * 握手 / WSEncrypt / Java-WebSocket 生命周期交给 PEWS 的
 * MCSocketServer，替换掉原先自己写的 WebSocketServer 包装。
 */
@ScriptObject
class PewsWebSocketServerHelper(port: Int, delegate: DelegateInterface?) :
    MCSocketServer(InetSocketAddress(port)) {

    private val delegate: DelegateInterface =
        requireNotNull(delegate) { "Delegate can't be null" }

    override fun onStart() {
        delegate.onStart()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        delegate.onOpen(conn, handshake)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        delegate.onClose(conn, code, reason, remote)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        delegate.onMessage(conn, message)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        delegate.onError(conn, ex)
    }

    @ScriptObject
    interface DelegateInterface {
        fun onOpen(conn: WebSocket, handshake: ClientHandshake)
        fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean)
        fun onMessage(conn: WebSocket, message: String)
        fun onError(conn: WebSocket?, ex: Exception)
        fun onStart()
    }
}
