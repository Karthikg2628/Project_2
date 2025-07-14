package com.example.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.*
import kotlin.math.max
import java.util.concurrent.atomic.AtomicBoolean
import com.example.remoteapp.R
import android.view.SurfaceHolder
import android.widget.VideoView
import VideoWebSocketClient

class MainActivity : AppCompatActivity() {
    private lateinit var webSocket: WebSocket
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(3, TimeUnit.SECONDS)
        .pingInterval(1, TimeUnit.SECONDS)
        .build()

    // VideoWebSocketClient for video streaming
    private lateinit var videoWebSocketClient: VideoWebSocketClient

    private val isConnected = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var imageView: ImageView
    private lateinit var videoView: VideoView
    private val frameBuffer = FrameBuffer(3) // Small buffer
    private val frameRenderer = FrameRenderer()
    private var lastAckTime = System.currentTimeMillis()

    companion object {
        private const val WS_PORT = 8080
        private const val TARGET_FPS = 24 // Matches sender FPS
        private const val DEFAULT_SERVER_IP = "192.168.1.100"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        imageView = findViewById(R.id.imageView)
        videoView = findViewById(R.id.videoView)

        // Setup VideoView
        setupReceiverVideoView()

        // Initialize VideoWebSocketClient
        val serverIp = intent.getStringExtra("SERVER_IP") ?: DEFAULT_SERVER_IP
        videoWebSocketClient = VideoWebSocketClient(this, serverIp, WS_PORT)
        videoWebSocketClient.initialize()

        // Start video streaming
        videoWebSocketClient.start()

        // Initialize fallback connection for ImageView
        initializeConnection()
        frameRenderer.start()
    }

    private fun initializeConnection() {
        scope.launch {
            while (!isConnected.get()) {
                try {
                    val serverIp = intent.getStringExtra("SERVER_IP") ?: DEFAULT_SERVER_IP

                    val request = Request.Builder()
                        .url("ws://$serverIp:$WS_PORT/ws")
                        .build()

                    webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(ws: WebSocket, response: Response) {
                            isConnected.set(true)
                            Log.d("MainActivity", "WebSocket connection opened")
                            sendAck()
                        }

                        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                            Log.e("MainActivity", "WebSocket connection failed", t)
                            handleDisconnection()
                        }

                        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                            Log.d("MainActivity", "WebSocket connection closed: $reason")
                            handleDisconnection()
                        }

                        override fun onMessage(ws: WebSocket, bytes: ByteString) {
                            processIncomingFrame(bytes)
                        }
                    })
                    return@launch
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error initializing connection", e)
                    delay(5000)
                }
            }
        }
    }

    private fun handleDisconnection() {
        isConnected.set(false)
        scope.launch {
            delay(2000)
            initializeConnection()
        }
    }

    private fun processIncomingFrame(bytes: ByteString) {
        scope.launch(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size)
                if (bitmap != null) {
                    if (!frameBuffer.addFrame(bitmap)) {
                        bitmap.recycle()
                        sendLagNotification()
                    } else {
                        sendAck()
                    }
                } else {
                    Log.w("MainActivity", "Failed to decode bitmap from frame")
                }
            } catch (e: Exception) {
                Log.e("FrameProcess", "Error processing frame", e)
            }
        }
    }

    private fun sendAck() {
        if (isConnected.get() && System.currentTimeMillis() - lastAckTime > 100) {
            webSocket.send("ACK")
            lastAckTime = System.currentTimeMillis()
        }
    }

    private fun sendLagNotification() {
        if (isConnected.get()) {
            webSocket.send("LAG")
        }
    }

    inner class FrameBuffer(capacity: Int) {
        private val queue = LinkedBlockingQueue<Bitmap>(capacity)

        fun addFrame(bitmap: Bitmap): Boolean {
            return if (queue.remainingCapacity() > 0) {
                queue.put(bitmap)
                true
            } else {
                // Buffer is full, drop oldest frame
                queue.poll()?.recycle()
                queue.put(bitmap)
                false
            }
        }

        fun getFrame(): Bitmap? = queue.poll()

        fun clear() {
            queue.forEach { it.recycle() }
            queue.clear()
        }
    }

    inner class FrameRenderer : Runnable {
        private val executor = Executors.newSingleThreadExecutor()
        private var isRunning = false
        private var lastRenderTime = 0L

        fun start() {
            if (!isRunning) {
                isRunning = true
                executor.execute(this)
            }
        }

        fun stop() {
            isRunning = false
            executor.shutdown()
        }

        override fun run() {
            val targetFrameTime = 10000L / TARGET_FPS

            while (isRunning) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastFrame = currentTime - lastRenderTime

                    if (timeSinceLastFrame >= targetFrameTime) {
                        renderNextFrame()
                        lastRenderTime = currentTime
                    } else {
                        val remainingTime = targetFrameTime - timeSinceLastFrame
                        Thread.sleep(max(1, remainingTime))
                    }
                } catch (e: Exception) {
                    Log.e("FrameRenderer", "Error in render loop", e)
                    Thread.sleep(100) // Prevent tight loop on error
                }
            }
        }

        private fun renderNextFrame() {
            frameBuffer.getFrame()?.let { frame ->
                runOnUiThread {
                    try {
                        imageView.setImageBitmap(frame)
                    } catch (e: Exception) {
                        Log.e("FrameRenderer", "Error setting bitmap", e)
                    } finally {
                        frame.recycle()
                    }
                }
            }
        }
    }

    private fun setupReceiverVideoView() {
        videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d("ReceiverSurface", "Surface created and ready for playback")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d("ReceiverSurface", "Surface changed to ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d("ReceiverSurface", "Surface destroyed")
            }
        })

        videoView.setOnErrorListener { mediaPlayer, what, extra ->
            Log.e("ReceiverPlayer", "MediaPlayer error: what=$what, extra=$extra")
            true // Handle the error
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop video streaming
        videoWebSocketClient.stop()

        // Stop other components
        isConnected.set(false)
        scope.cancel()

        // Close WebSocket connections
        if (::webSocket.isInitialized) {
            webSocket.close(1000, "Activity destroyed")
        }

        // Clean up resources
        frameBuffer.clear()
        frameRenderer.stop()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    override fun onPause() {
        super.onPause()
        // Optionally pause video streaming to save resources
        videoWebSocketClient.stop()
    }

    override fun onResume() {
        super.onResume()
        // Resume video streaming
        if (!videoWebSocketClient.isConnected()) {
            videoWebSocketClient.start()
        }
    }
}