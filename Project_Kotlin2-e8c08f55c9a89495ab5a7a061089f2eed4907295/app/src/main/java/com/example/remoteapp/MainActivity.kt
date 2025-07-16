// MainActivity.kt (Remote App)
package com.example.remoteapp

import android.content.Context
import android.graphics.PixelFormat
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.Enumeration

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback,
    WebSocketServerManager.WebSocketServerListener {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusTextView: TextView
    private lateinit var ipAddressTextView: TextView

    private var wsServer: WebSocketServerManager? = null
    private var mediaPlaybackManager: MediaPlaybackManager? = null
    private val WEBSOCKET_PORT = 8080 // Or configurable

    // These will be populated by CSD from Origin
    private var receivedVideoFormat: MediaFormat? = null
    private var receivedAudioFormat: MediaFormat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // You'll need to create this layout

        surfaceView = findViewById(R.id.videoSurfaceView)
        statusTextView = findViewById(R.id.statusTextView)
        ipAddressTextView = findViewById(R.id.statusTextView)

        surfaceView.holder.addCallback(this)
        surfaceView.holder.setFormat(PixelFormat.RGBA_8888) // Ensure surface is ready for video rendering

        startWebSocketServer()
        displayLocalIpAddress()
    }

    private fun startWebSocketServer() {
        wsServer = WebSocketServerManager(WEBSOCKET_PORT, this).apply {
            start() // This starts the server in a new thread
        }
    }

    private fun displayLocalIpAddress() {
        val ip = getLocalIpAddress()
        ipAddressTextView.text = "Remote IP: $ip:$WEBSOCKET_PORT"
    }

    // Helper to get local IP address
    private fun getLocalIpAddress(): String {
        try {
            val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf: NetworkInterface = interfaces.nextElement()
                val addresses: Enumeration<InetAddress> = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr: InetAddress = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: SocketException) {
            Log.e(TAG, "Error getting IP address: ${ex.message}", ex)
        }
        return "N/A"
    }

    override fun onDestroy() {
        super.onDestroy()
        wsServer?.stopServer()
        mediaPlaybackManager?.stopPlayback()
    }

    // --- SurfaceHolder.Callback methods ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created.")
        // Initialize MediaPlaybackManager here, but don't start playback yet
        mediaPlaybackManager = MediaPlaybackManager(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed.")
        mediaPlaybackManager?.stopPlayback() // Crucial for ephemeral data and resource release
    }

    // --- WebSocketServerManager.WebSocketServerListener methods ---
    override fun onServerStarted() {
        runOnUiThread { statusTextView.text = "Waiting for Origin..." }
    }

    override fun onClientConnected(ipAddress: String) {
        runOnUiThread { statusTextView.text = "Origin Connected: $ipAddress" }
        // Reset formats for new connection
        receivedVideoFormat = null
        receivedAudioFormat = null
    }

    override fun onClientDisconnected(ipAddress: String, code: Int, reason: String) {
        runOnUiThread {
            statusTextView.text = "Origin Disconnected. Reason: $reason"
            mediaPlaybackManager?.stopPlayback() // Crucial for ephemeral data
        }
    }

    override fun onMessageReceived(message: ByteBuffer) {
        // Parse the incoming binary message
        if (!message.hasRemaining()) {
            Log.w(TAG, "Received empty buffer.")
            return
        }

        val type = message.get() // Read 1-byte type
        val ptsUs = message.long // Read 8-byte PTS
        val data = message.slice() // Remaining data is the encoded media

        when (type) {
            MediaPlaybackManager.PACKET_TYPE_VIDEO_CSD -> {
                Log.d(TAG, "Received Video CSD, PTS: $ptsUs, Size: ${data.remaining()}")
                // This is simplified. For H.264, ptsUs 0 might be SPS, ptsUs 1 might be PPS.
                // You need to reconstruct the MediaFormat from these.
                if (receivedVideoFormat == null) {
                    // Placeholder values. Actual resolution/MIME should be inferred or sent.
                    // For H.264, createVideoFormat needs width/height, but CSD often doesn't contain it.
                    // A more robust solution might send resolution as part of initial handshake.
                    // For now, assume common 1080p, you'll need to adapt.
                    receivedVideoFormat = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
                }
                if (ptsUs == 0L) { // Assuming PTS 0 for SPS (csd-0)
                    receivedVideoFormat?.setByteBuffer("csd-0", data)
                } else if (ptsUs == 1L) { // Assuming PTS 1 for PPS (csd-1)
                    receivedVideoFormat?.setByteBuffer("csd-1", data)
                }

                // If both SPS and PPS are received, and audio format is also ready, then set media formats
                if (receivedVideoFormat?.getByteBuffer("csd-0") != null &&
                    receivedVideoFormat?.getByteBuffer("csd-1") != null &&
                    receivedAudioFormat != null && mediaPlaybackManager != null
                ) {
                    mediaPlaybackManager?.setMediaFormats(receivedVideoFormat, receivedAudioFormat)
                }
            }
            MediaPlaybackManager.PACKET_TYPE_AUDIO_CSD -> {
                Log.d(TAG, "Received Audio CSD, PTS: $ptsUs, Size: ${data.remaining()}")
                // For AAC, create the AudioFormat and add the CSD
                // Placeholder values. Actual sample rate/channels should be inferred or sent.
                receivedAudioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2)
                receivedAudioFormat?.setByteBuffer("csd-0", data) // AudioSpecificConfig for AAC

                // If video format is also ready, then set media formats
                if (receivedVideoFormat != null && receivedAudioFormat != null && mediaPlaybackManager != null) {
                    mediaPlaybackManager?.setMediaFormats(receivedVideoFormat, receivedAudioFormat)
                }
            }
            else -> {
                if (mediaPlaybackManager != null && mediaPlaybackManager?.isPlaying?.get() == true) {
                    mediaPlaybackManager?.addPacket(type, ptsUs, data)
                }
            }
        }
    }

    override fun onCommandReceived(command: String) {
        runOnUiThread {
            Log.d(TAG, "Received command: $command")
            when (command) {
                MediaPlaybackManager.COMMAND_START_STREAMING -> { // Corrected: Reference from MediaPlaybackManager
                    if (mediaPlaybackManager != null) {
                        mediaPlaybackManager?.startPlayback()
                        statusTextView.text = "Streaming..."
                    } else {
                        Log.e(TAG, "MediaPlaybackManager not initialized when START_STREAMING received.")
                    }
                }
                MediaPlaybackManager.COMMAND_STOP_STREAMING -> { // Corrected: Reference from MediaPlaybackManager
                    if (mediaPlaybackManager != null) {
                        mediaPlaybackManager?.stopPlayback()
                        statusTextView.text = "Stream Stopped by Origin."
                    }
                }
            }
        }
    }

    override fun onServerError(ex: Exception) {
        runOnUiThread {
            statusTextView.text = "Server Error: ${ex.message}"
            Log.e(TAG, "WebSocket Server Error", ex)
        }
    }

    companion object {
        private const val TAG = "RemoteApp"
    }
}