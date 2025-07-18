package com.example.remoteapp

import java.nio.ByteBuffer

// Listener interface for MainActivity to receive server events.
// This interface MUST be a standalone file in the same package.
interface WebSocketServerListener {
    fun onServerStarted()
    fun onClientConnected(ipAddress: String)
    fun onClientDisconnected(ipAddress: String, code: Int, reason: String)
    fun onMessageReceived(message: ByteBuffer) // For binary data (media frames, CSD, format data)
    fun onCommandReceived(command: String) // For text commands (START_STREAM, STOP_STREAM)
    fun onServerError(ex: Exception)
}
