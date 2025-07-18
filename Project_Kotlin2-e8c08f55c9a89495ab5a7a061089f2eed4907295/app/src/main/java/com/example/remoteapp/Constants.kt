// Constants.kt
package com.example.common // Or whatever package suits your structure

object Constants {
    const val WEBSOCKET_PORT = 8080
    const val COMMAND_START_STREAMING = "START_STREAMING"
    const val COMMAND_STOP_STREAMING = "STOP_STREAMING"
    const val COMMAND_STREAM_READY = "STREAM_READY" // New command for synchronization

    // Media format constants - crucial for matching encoder/decoder
    const val VIDEO_MIME_TYPE = "video/avc" // H.264
    const val VIDEO_WIDTH = 1280
    const val VIDEO_HEIGHT = 720
    const val VIDEO_BITRATE = 6_000_000 // 6 Mbps
    const val VIDEO_FRAME_RATE = 30
    const val VIDEO_I_FRAME_INTERVAL = 1 // seconds
}