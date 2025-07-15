package com.example.remoteapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URI

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var ipAddressEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var debugInfoTextView: TextView
    private lateinit var videoSurfaceView: SurfaceView

    private var remoteWebSocketClient: RemoteWebSocketClient? = null
    private var surfaceHolder: SurfaceHolder? = null

    companion object {
        private const val TAG = "RemoteMainActivity" // Changed TAG for clarity
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements by finding their IDs from activity_main.xml
        ipAddressEditText = findViewById(R.id.ipAddressEditText)
        connectButton = findViewById(R.id.connectButton)
        statusTextView = findViewById(R.id.statusTextView)
        debugInfoTextView = findViewById(R.id.debugInfoTextView)
        videoSurfaceView = findViewById(R.id.videoSurfaceView)

        videoSurfaceView.holder.addCallback(this)

        connectButton.setOnClickListener {
            val ipAddress = ipAddressEditText.text.toString()
            if (ipAddress.isNotBlank()) {
                connectToOrigin(ipAddress)
            } else {
                updateStatus("Please enter an IP address.", Color.RED)
            }
        }

        updateStatus("Disconnected", Color.GRAY)
        updateDebugInfo("Waiting for connection...")
    }

    private fun connectToOrigin(ipAddress: String) {
        // Close any existing connection first
        remoteWebSocketClient?.close()

        val uri = URI("ws://$ipAddress:8080")
        remoteWebSocketClient = RemoteWebSocketClient(uri,
            context = applicationContext, // Pass context here
            onOpen = {
                updateStatus("Connected to Origin", Color.GREEN)
                updateDebugInfo("Streaming started.")
            },
            onMessageString = { message -> // Corrected parameter name for text messages
                // Handle text messages (e.g., playback commands)
                handleTextMessage(message)
            },
            onMessageBytes = { bytes -> // Corrected parameter name for binary messages
                // Handle binary messages (video frames)
                displayFrame(bytes)
            },
            onClose = { code, reason, remote ->
                updateStatus("Disconnected: $reason (Code: $code)", Color.RED)
                updateDebugInfo("Connection closed. Code: $code, Reason: $reason")
            },
            onError = { ex ->
                // Ensure UI updates are on the main thread
                // Use safe call or Elvis operator for nullable Exception
                runOnUiThread {
                    updateStatus("Connection Error: ${ex.message ?: "Unknown error"}", Color.RED)
                    updateDebugInfo("WebSocket Error: ${ex.message ?: "Unknown error"}")
                }
            }
        )
        try {
            remoteWebSocketClient?.connect()
            updateStatus("Connecting...", Color.YELLOW)
            updateDebugInfo("Attempting to connect to ws://$ipAddress:8080")
        } catch (e: Exception) {
            // Use safe call or Elvis operator for nullable Exception
            updateStatus("Connection failed: ${e.message ?: "Unknown error"}", Color.RED)
            updateDebugInfo("Failed to initiate connection: ${e.message ?: "Unknown error"}")
        }
    }

    private fun handleTextMessage(message: String) {
        // Example: handle playback commands from origin
        if (message.startsWith("PLAY")) {
            updateDebugInfo("Origin sent PLAY command.")
            // You could implement playback control logic here if needed
        } else if (message == "PAUSE") {
            updateDebugInfo("Origin sent PAUSE command.")
        } else if (message == "STOP") {
            updateDebugInfo("Origin sent STOP command.")
            remoteWebSocketClient?.close() // Close connection if origin stops
        }
    }

    private fun displayFrame(jpegBytes: ByteArray) {
        lifecycleScope.launch(Dispatchers.Default) { // Decode bitmap on a background thread
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (bitmap != null) {
                // Draw to canvas on the main thread
                runOnUiThread {
                    surfaceHolder?.let { holder ->
                        var canvas: android.graphics.Canvas? = null
                        try {
                            canvas = holder.lockCanvas()
                            if (canvas != null) {
                                canvas.drawColor(Color.BLACK) // Clear canvas
                                // Scale bitmap to fit surface
                                val scaleX = canvas.width.toFloat() / bitmap.width
                                val scaleY = canvas.height.toFloat() / bitmap.height
                                val scale = Math.min(scaleX, scaleY)
                                val scaledWidth = bitmap.width * scale
                                val scaledHeight = bitmap.height * scale
                                val left = (canvas.width - scaledWidth) / 2
                                val top = (canvas.height - scaledHeight) / 2
                                val destRect =
                                    android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight)
                                canvas.drawBitmap(bitmap, null, destRect, null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error drawing bitmap to canvas: ${e.message}", e)
                            updateDebugInfo("Display Error: ${e.message ?: "Unknown drawing error"}")
                        } finally {
                            if (canvas != null) {
                                holder.unlockCanvasAndPost(canvas)
                            }
                            bitmap.recycle() // Recycle bitmap after drawing
                        }
                    }
                }
            } else {
                Log.e(TAG, "Failed to decode JPEG bytes into Bitmap.")
                updateDebugInfo("Frame Error: Failed to decode image.")
            }
        }
    }


    private fun updateStatus(message: String, color: Int) {
        // Ensure UI updates are on the main thread
        runOnUiThread {
            statusTextView.text = message
            statusTextView.setTextColor(color)
        }
    }

    private fun updateDebugInfo(message: String) {
        // Ensure UI updates are on the main thread
        runOnUiThread {
            debugInfoTextView.text = message
            Log.d(TAG, "Debug Info: $message")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        Log.d(TAG, "Surface created in Remote App.")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceHolder = holder
        Log.d(TAG, "Surface changed: width=$width, height=$height in Remote App.")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceHolder = null
        Log.d(TAG, "Surface destroyed in Remote App.")
        remoteWebSocketClient?.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        remoteWebSocketClient?.close()
    }
}
