// WebSocketServerManager.kt (Remote App)
package com.example.remoteapp

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

class WebSocketServerManager(
    port: Int,
    private val listener: WebSocketServerListener?
) : WebSocketServer(InetSocketAddress(port)) {

    private val connectedClient = AtomicReference<WebSocket?>()

    interface WebSocketServerListener {
        fun onServerStarted()
        fun onClientConnected(ipAddress: String)
        fun onClientDisconnected(ipAddress: String, code: Int, reason: String)
        fun onMessageReceived(message: ByteBuffer)
        fun onCommandReceived(command: String)
        fun onServerError(ex: Exception)
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        conn?.let {
            Log.d(TAG, "New connection from ${it.remoteSocketAddress?.address?.hostAddress}")
            connectedClient.set(it)
            listener?.onClientConnected(it.remoteSocketAddress?.address?.hostAddress ?: "Unknown")
        }
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        conn?.let {
            Log.d(TAG, "Closed ${it.remoteSocketAddress?.address?.hostAddress} with exit code $code additional info: $reason")
            if (connectedClient.compareAndSet(it, null)) { // Only clear if it's the current client
                listener?.onClientDisconnected(it.remoteSocketAddress?.address?.hostAddress ?: "Unknown", code, reason ?: "No reason")
            }
        }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        conn?.let {
            message?.let { msg ->
                Log.d(TAG, "Received text from ${it.remoteSocketAddress?.address?.hostAddress}: $msg")
                listener?.onCommandReceived(msg) // Assuming text messages are commands
            }
        }
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        conn?.let {
            message?.let { bytes ->
                Log.d(TAG, "Received binary from ${it.remoteSocketAddress?.address?.hostAddress}: ${bytes.remaining()} bytes")
                listener?.onMessageReceived(bytes)
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "An error occurred on connection ${conn?.remoteSocketAddress ?: "null"}: ${ex?.message}", ex)
        ex?.let { listener?.onServerError(it) }
    }

    override fun onStart() {
        Log.d(TAG, "Server started on port $port")
        connectionLostTimeout = 100 // Set a small timeout for more active monitoring
        listener?.onServerStarted()
    }

    fun stopServer() {
        runCatching {
            stop()
            Log.d(TAG, "WebSocket server stopped.")
        }.onFailure { e ->
            Log.e(TAG, "Error stopping WebSocket server", e)
        }
    }

    companion object {
        private const val TAG = "WSServer"
    }
}