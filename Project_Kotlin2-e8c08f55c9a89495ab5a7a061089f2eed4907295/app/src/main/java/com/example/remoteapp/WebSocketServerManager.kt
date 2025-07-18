package com.example.remoteapp

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

// This class manages the WebSocket server.
// It uses the WebSocketServerListener interface defined in its own file.
class WebSocketServerManager(port: Int, private val listener: WebSocketServerListener) {

    private val webSocketServer: WebSocketServer

    init {
        webSocketServer = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onStart() {
                Log.d(TAG, "Server started on port $port")
                listener.onServerStarted()
            }

            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                val clientIp = conn.remoteSocketAddress.address.hostAddress
                Log.d(TAG, "Client connected: $clientIp")
                listener.onClientConnected(clientIp)
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                val clientIp = conn.remoteSocketAddress?.address?.hostAddress ?: "Unknown"
                Log.d(TAG, "Client disconnected: $clientIp, Code: $code, Reason: $reason")
                listener.onClientDisconnected(clientIp, code, reason)
            }

            override fun onMessage(conn: WebSocket, message: String) {
                // Handle text messages as commands
                Log.d(TAG, "Received text command from ${conn.remoteSocketAddress.address.hostAddress}: $message")
                listener.onCommandReceived(message)
            }

            override fun onMessage(conn: WebSocket, message: ByteBuffer) {
                // Handle binary messages as media packets
                // Duplicate the buffer to allow independent reading by multiple consumers if needed
                listener.onMessageReceived(message.duplicate())
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                val clientIp = conn?.remoteSocketAddress?.address?.hostAddress ?: "Unknown"
                Log.e(TAG, "WebSocket server error for client $clientIp", ex)
                listener.onServerError(ex)
            }
        }
    }

    fun start() {
        // Start the WebSocket server in a new thread to avoid blocking the UI thread.
        Thread {
            try {
                webSocketServer.run()
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket server failed to start or run", e)
                listener.onServerError(e)
            }
        }.start()
    }

    fun stopServer() {
        try {
            // Attempt to stop the server with a timeout.
            // This will close all open connections and stop the server thread.
            webSocketServer.stop(1000) // Stop with a 1-second timeout
            Log.d(TAG, "WebSocket server stopped.")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping WebSocket server: ${e.message ?: "Unknown interruption"}", e)
            Thread.currentThread().interrupt() // Restore interrupted status
        } catch (e: Exception) { // Catch general Exception for robustness during stop
            Log.e(TAG, "General error stopping WebSocket server: ${e.message ?: "Unknown error"}", e)
        }
    }

    companion object {
        private const val TAG = "WSServer"
        // Removed duplicated constants. These should be accessed from Constants.kt
        // For example, Constants.COMMAND_START_STREAMING
    }
}