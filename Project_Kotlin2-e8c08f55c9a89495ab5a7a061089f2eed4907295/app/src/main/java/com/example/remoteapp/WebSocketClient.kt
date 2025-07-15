package com.example.remoteapp

import android.content.Context
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

// This WebSocket client connects to the Origin app to receive video frames and commands.
class RemoteWebSocketClient(
    serverUri: URI,
    private val context: Context, // Add context parameter
    private val onOpen: () -> Unit,
    private val onMessageString: (String) -> Unit, // For text messages
    private val onMessageBytes: (ByteArray) -> Unit, // For binary (image) messages
    private val onClose: (code: Int, reason: String?, remote: Boolean) -> Unit,
    private val onError: (Exception) -> Unit // onError expects a non-null Exception
) : WebSocketClient(serverUri) {

    companion object {
        private const val TAG = "RemoteWSC"
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.d(TAG, "Connected to server: ${uri.host}:${uri.port}")
        onOpen.invoke()
    }

    override fun onMessage(message: String?) {
        message?.let {
            Log.d(TAG, "Received text message: $it")
            onMessageString.invoke(it) // Invoke the specific text message callback
        }
    }

    override fun onMessage(bytes: ByteBuffer?) {
        bytes?.let {
            val byteArray = ByteArray(it.remaining())
            it.get(byteArray)
            Log.d(TAG, "Received binary message of size: ${byteArray.size} bytes")
            onMessageBytes.invoke(byteArray) // Invoke the specific binary message callback
        }
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Disconnected from server. Code: $code, Reason: $reason, Remote: $remote")
        onClose.invoke(code, reason, remote)
    }

    // Explicitly handle a potentially null 'ex' before passing it to onError
    override fun onError(ex: Exception?) {
        val actualException = ex ?: Exception("Unknown WebSocket error occurred.")
        Log.e(TAG, "WebSocket Error: ${actualException.message}", actualException)
        onError.invoke(actualException) // Pass a guaranteed non-null Exception
    }
}
