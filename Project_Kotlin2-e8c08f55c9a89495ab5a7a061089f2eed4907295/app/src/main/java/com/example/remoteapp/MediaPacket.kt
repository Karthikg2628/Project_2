package com.example.remoteapp

import java.nio.ByteBuffer

// Data class to hold media packets (frames or CSD) with their type and presentation timestamp.
data class MediaPacket(
    val type: Byte,
    val presentationTimeUs: Long,
    val data: ByteBuffer
)
