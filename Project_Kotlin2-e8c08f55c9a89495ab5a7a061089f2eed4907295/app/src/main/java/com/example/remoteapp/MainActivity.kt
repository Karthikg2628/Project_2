package com.example.remoteapp

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope // Import for lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executors // Still used for UI updates if you prefer, or replace with coroutines

class MainActivity : AppCompatActivity(), WebSocketServerListener, SurfaceHolder.Callback {

    private lateinit var ipAddressTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var videoSurfaceView: SurfaceView
    private lateinit var wsServer: WebSocketServerManager
    private var mediaPlaybackManager: MediaPlaybackManager? = null

    // Ensure only one thread for UI updates to avoid conflicts.
    // While coroutines could also handle this, keeping the existing executor
    // if you prefer explicit separation, but a simple launch(Dispatchers.Main) would suffice.
    private val uiUpdateExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Force landscape orientation for video playback
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        ipAddressTextView = findViewById(R.id.ipAddressTextView)
        statusTextView = findViewById(R.id.statusTextView)
        videoSurfaceView = findViewById(R.id.videoSurfaceView)
        val localIpEditText: TextView = findViewById(R.id.ipAddressEditText)

        videoSurfaceView.holder.addCallback(this)

        // Display local IP address to connect to. This can be run on IO dispatcher
        // if Utils.getLocalIpAddress is potentially blocking (e.g., involves network calls).
        // For simple local IP lookup, it's usually fast enough, but moving it is safer.
        lifecycleScope.launch(Dispatchers.IO) {
            val localIp = Utils.getLocalIpAddress(this@MainActivity)
            withContext(Dispatchers.Main) {
                localIpEditText.text = localIp
                Log.d(TAG, "Local IP Address: $localIp")
            }
        }

        // Initialize WebSocket server. Start it in a background coroutine
        // to prevent blocking the main thread during initialization.
        wsServer = WebSocketServerManager(8080, this)
        updateStatus("Server initializing...", Color.GRAY)

        // Launch server startup in a coroutine on the IO dispatcher
        // This is the key change for "Skipped frames!"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                wsServer.start()
                withContext(Dispatchers.Main) {
                    updateStatus("Server started. Waiting for Origin connection...", Color.BLUE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebSocket server", e)
                withContext(Dispatchers.Main) {
                    updateStatus("Server failed to start: ${e.message}", Color.RED)
                    Toast.makeText(this@MainActivity, "Server error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called. Stopping server and media playback.")
        // It's good practice to stop these on a background thread if they might block
        // during shutdown, though for simple stop/release, it's often fine on main.
        lifecycleScope.launch(Dispatchers.IO) {
            wsServer.stopServer() // Ensure this properly closes all sockets
            Log.d(TAG, "WebSocket server stopped.")
            mediaPlaybackManager?.stopPlayback()
            Log.d(TAG, "Media playback stopped.")
        }
        uiUpdateExecutor.shutdownNow() // Shut down the executor for UI updates
    }

    // --- WebSocketServerListener Implementation ---

    // onServerStarted will now be called by the WS server implementation itself
    // after it successfully starts. The updateStatus call is already done in onCreate.
    // No change needed here, as the initial status update is handled above.
    override fun onServerStarted() {
        // This callback is triggered by WebSocketServerManager when it's internally ready.
        // The UI update for "Server started" is handled above in the onCreate coroutine.
    }

    override fun onClientConnected(ipAddress: String) {
        // All UI updates are already correctly delegated to runOnUiThread/uiUpdateExecutor.
        updateStatus("Client Connected: $ipAddress. Waiting for stream...", Color.CYAN)
        // No need for runOnUiThread here as updateStatus already does it.
        // It's crucial that mediaPlaybackManager is not null here.
        mediaPlaybackManager?.startPlayback()
    }

    override fun onClientDisconnected(ipAddress: String, code: Int, reason: String) {
        updateStatus("Client Disconnected: $ipAddress ($reason)", Color.RED)
        // No need for runOnUiThread here as updateStatus already does it.
        lifecycleScope.launch(Dispatchers.Main) { // Ensure UI update happens on Main thread
            ipAddressTextView.text = "Origin IP: N/A"
        }
        mediaPlaybackManager?.stopPlayback() // Stop playback when client disconnects
    }

    override fun onMessageReceived(message: ByteBuffer) {
        // Data processing for ByteBuffer needs to be efficient.
        // These operations are usually fast enough not to block, as they just read from buffer.
        // The actual media decoding happens on MediaPlaybackManager's internal threads.

        // Ensure the buffer is reset for reading each time
        message.rewind()
        if (message.remaining() < 1) {
            Log.e(TAG, "Received empty or too short binary message.")
            return
        }

        val packetType = message.get() // Read the first byte as packet type

        when (packetType) {
            Constants.PACKET_TYPE_VIDEO -> {
                if (message.remaining() < 8) { // For presentationTimeUs (long)
                    Log.e(TAG, "Video packet too short for PTS.")
                    return
                }
                val presentationTimeUs = message.getLong()
                val videoData = message.slice() // Data from current position to limit
                mediaPlaybackManager?.addVideoPacket(MediaPacket(packetType, presentationTimeUs, videoData))
            }
            Constants.PACKET_TYPE_AUDIO -> {
                if (message.remaining() < 8) { // For presentationTimeUs (long)
                    Log.e(TAG, "Audio packet too short for PTS.")
                    return
                }
                val presentationTimeUs = message.getLong()
                val audioData = message.slice() // Data from current position to limit
                mediaPlaybackManager?.addAudioPacket(MediaPacket(packetType, presentationTimeUs, audioData))
            }
            Constants.PACKET_TYPE_VIDEO_CSD -> {
                if (message.remaining() < 4) { // For csdDataLength (int)
                    Log.e(TAG, "Video CSD packet too short for length.")
                    return
                }
                val csdDataLength = message.getInt()
                if (message.remaining() < csdDataLength) {
                    Log.e(TAG, "Video CSD packet data too short, expected $csdDataLength, got ${message.remaining()}")
                    return
                }
                val csdData = message.slice() // Data from current position to limit
                mediaPlaybackManager?.setVideoCSD(csdData)
            }
            Constants.PACKET_TYPE_AUDIO_CSD -> {
                if (message.remaining() < 4) { // For csdDataLength (int)
                    Log.e(TAG, "Audio CSD packet too short for length.")
                    return
                }
                val csdDataLength = message.getInt()
                if (message.remaining() < csdDataLength) {
                    Log.e(TAG, "Audio CSD packet data too short, expected $csdDataLength, got ${message.remaining()}")
                    return
                }
                val csdData = message.slice() // Data from current position to limit
                mediaPlaybackManager?.setAudioCSD(csdData)
            }
            Constants.PACKET_TYPE_VIDEO_FORMAT_DATA -> {
                if (message.remaining() < 4) { // For mimeLength (int)
                    Log.e(TAG, "Video format packet too short for mime length.")
                    return
                }
                val mimeLength = message.getInt()
                // Check if enough bytes remain for mime, width, height, and frameRate
                if (message.remaining() < mimeLength + 4 + 4 + 4) {
                    Log.e(TAG, "Video format packet data too short after mime length, expected at least ${mimeLength + 12}, got ${message.remaining()}")
                    return
                }
                val mimeBytes = ByteArray(mimeLength)
                message.get(mimeBytes)
                val mime = String(mimeBytes, Charsets.UTF_8)
                val width = message.getInt()
                val height = message.getInt()
                val frameRate = message.getInt()
                mediaPlaybackManager?.setVideoFormat(mime, width, height, frameRate)
            }
            Constants.PACKET_TYPE_AUDIO_FORMAT_DATA -> {
                if (message.remaining() < 4) { // For mimeLength (int)
                    Log.e(TAG, "Audio format packet too short for mime length.")
                    return
                }
                val mimeLength = message.getInt()
                // Check if enough bytes remain for mime, sampleRate, and channelCount
                if (message.remaining() < mimeLength + 4 + 4) {
                    Log.e(TAG, "Audio format packet data too short after mime length, expected at least ${mimeLength + 8}, got ${message.remaining()}")
                    return
                }
                val mimeBytes = ByteArray(mimeLength)
                message.get(mimeBytes)
                val mime = String(mimeBytes, Charsets.UTF_8)
                val sampleRate = message.getInt()
                val channelCount = message.getInt()
                mediaPlaybackManager?.setAudioFormat(mime, sampleRate, channelCount)
            }
            else -> {
                Log.w(TAG, "Received unknown packet type: $packetType")
            }
        }
    }

    override fun onCommandReceived(command: String) {
        Log.d(TAG, "Received command: $command")
        when (command) {
            Constants.COMMAND_START_STREAMING -> {
                updateStatus("Streaming Started!", Color.GREEN)
                mediaPlaybackManager?.startPlayback() // Ensure playback starts/resumes
            }
            Constants.COMMAND_STOP_STREAMING -> {
                updateStatus("Streaming Stopped.", Color.BLACK)
                mediaPlaybackManager?.stopPlayback() // Stop playback
            }
            else -> {
                Log.w(TAG, "Unknown command received: $command")
                lifecycleScope.launch(Dispatchers.Main) { // Show Toast on Main thread
                    Toast.makeText(this@MainActivity, "Unknown command: $command", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onServerError(ex: Exception) {
        updateStatus("Server Error: ${ex.message}", Color.RED)
        Log.e(TAG, "WebSocket Server Error", ex)
    }

    // --- SurfaceHolder.Callback Implementation ---

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created. Initializing MediaPlaybackManager.")
        // Initialize MediaPlaybackManager here as Surface is ready
        mediaPlaybackManager = MediaPlaybackManager(holder.surface)
        // Playback will be started by onClientConnected or onCommandReceived.
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: $width x $height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed. Stopping MediaPlaybackManager.")
        mediaPlaybackManager?.stopPlayback() // Stop playback cleanly
        mediaPlaybackManager = null // Clear reference
    }

    // --- UI Update Helper ---

    private fun updateStatus(message: String, color: Int) {
        // Use the single-thread executor to enqueue UI updates.
        // Alternatively, you could directly use lifecycleScope.launch(Dispatchers.Main)
        // for all UI updates, potentially simplifying the threading.
        uiUpdateExecutor.execute {
            runOnUiThread {
                statusTextView.text = "Status: $message"
                statusTextView.setTextColor(color)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivityRemote"
    }
}