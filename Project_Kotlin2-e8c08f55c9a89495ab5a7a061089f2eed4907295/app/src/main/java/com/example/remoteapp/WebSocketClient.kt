import okhttp3.*
import okio.ByteString
import android.util.Log
import android.widget.VideoView
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.view.SurfaceHolder
import android.content.Context
import android.app.Activity
import com.example.remoteapp.R
import java.util.concurrent.TimeUnit

class VideoWebSocketClient(
    private val context: Context,
    private val serverIp: String,
    private val port: Int = 8080

) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS) // Increase timeout
        .retryOnConnectionFailure(true)
        .build()
    private lateinit var videoView: VideoView
    private lateinit var webSocket: WebSocket

    private val request: Request = Request.Builder()
        .url("ws://$serverIp:$port/video")
        .build()

    fun initialize() {
        // Get VideoView from the activity
        if (context is Activity) {
            videoView = context.findViewById(R.id.videoView)
                ?: throw IllegalStateException("VideoView with id 'videoView' not found in layout")
        } else {
            throw IllegalArgumentException("Context must be an Activity")
        }

        // Set up VideoView for custom rendering
        videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d("VideoWebSocketClient", "Surface created")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d("VideoWebSocketClient", "Surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d("VideoWebSocketClient", "Surface destroyed")
            }
        })
    }

    fun start() {
        if (!::videoView.isInitialized) {
            throw IllegalStateException("Must call initialize() before start()")
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("VideoWebSocketClient", "Connected to server at ws://$serverIp:$port/video")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d("VideoWebSocketClient", "Received frame size: ${bytes.size} bytes")
                handleVideoFrame(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("VideoWebSocketClient", "Connection failed: ${t.message}")
                // Optionally implement reconnection logic here
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("VideoWebSocketClient", "Connection closed: $reason")
            }
        })
    }

    private fun handleVideoFrame(frameData: ByteArray) {
        try {
            // Directly render the frame data (assuming it's already in correct format)
            renderFrame(frameData)
        } catch (e: Exception) {
            Log.e("VideoWebSocketClient", "Frame handling error", e)
        }
    }

    private fun renderFrame(frame: ByteArray) {
        // Check if frame is empty
        if (frame.isEmpty()) {
            Log.e("VideoWebSocketClient", "Empty frame received")
            return
        }

        // Ensure we're on the main thread for UI operations
        (context as Activity).runOnUiThread {
            var bitmap: Bitmap? = null
            var scaledBitmap: Bitmap? = null
            var canvas: Canvas? = null
            var holder: SurfaceHolder? = null

            try {
                // Check if surface is valid
                holder = videoView.holder
                if (!holder.surface.isValid) {
                    Log.e("VideoWebSocketClient", "Surface is not valid")
                    return@runOnUiThread
                }

                // Decode bitmap from frame data
                bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                if (bitmap == null) {
                    Log.e("VideoWebSocketClient", "Failed to decode bitmap from frame data")
                    return@runOnUiThread
                }

                // Lock canvas for drawing
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    // Clear canvas with black background
                    canvas.drawColor(Color.BLACK)

                    // Scale bitmap to fit canvas if needed
                    val canvasWidth = canvas.width
                    val canvasHeight = canvas.height
                    val bitmapWidth = bitmap.width
                    val bitmapHeight = bitmap.height

                    if (bitmapWidth != canvasWidth || bitmapHeight != canvasHeight) {
                        // Calculate scaling to maintain aspect ratio
                        val scaleX = canvasWidth.toFloat() / bitmapWidth
                        val scaleY = canvasHeight.toFloat() / bitmapHeight
                        val scale = minOf(scaleX, scaleY)

                        val scaledWidth = (bitmapWidth * scale).toInt()
                        val scaledHeight = (bitmapHeight * scale).toInt()

                        scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

                        // Center the scaled bitmap
                        val x = (canvasWidth - scaledWidth) / 2f
                        val y = (canvasHeight - scaledHeight) / 2f

                        canvas.drawBitmap(scaledBitmap, x, y, null)
                    } else {
                        // Draw bitmap directly if sizes match
                        canvas.drawBitmap(bitmap, 0f, 0f, null)
                    }

                    Log.v("VideoWebSocketClient", "Frame rendered successfully (${bitmapWidth}x${bitmapHeight})")
                } else {
                    Log.e("VideoWebSocketClient", "Could not lock canvas")
                }

            } catch (e: Exception) {
                Log.e("VideoWebSocketClient", "Error in renderFrame", e)
            } finally {
                // Always unlock canvas if lockCanvas was called, regardless of success
                if (canvas != null && holder != null) {
                    holder.unlockCanvasAndPost(canvas)
                }
                bitmap?.recycle()
                scaledBitmap?.recycle()
            }
        }
    }

    fun stop() {
        try {
            if (::webSocket.isInitialized) {
                webSocket.close(100000, "Client closed")
            }
        } catch (e: Exception) {
            Log.e("VideoWebSocketClient", "Error closing WebSocket", e)
        }

        try {
            client.dispatcher.executorService.shutdown()
        } catch (e: Exception) {
            Log.e("VideoWebSocketClient", "Error shutting down OkHttp client", e)
        }
    }

    // Optional: Method to check connection status
    fun isConnected(): Boolean {
        return ::webSocket.isInitialized
    }
}

