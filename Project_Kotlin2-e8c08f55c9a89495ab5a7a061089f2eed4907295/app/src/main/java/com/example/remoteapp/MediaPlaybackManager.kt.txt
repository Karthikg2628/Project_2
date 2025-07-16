// MediaPlaybackManager.kt (Remote App)
package com.example.remoteapp

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MediaPlaybackManager(private val videoSurface: Surface) {

    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private val videoQueue = ArrayBlockingQueue<MediaPacket>(100) // Buffer for video frames
    private val audioQueue = ArrayBlockingQueue<MediaPacket>(100) // Buffer for audio packets

    private var _videoFormat: MediaFormat? = null
    private var _audioFormat: MediaFormat? = null

    val isPlaying = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(true)

    private var videoDecodingThread: Thread? = null
    private var audioDecodingThread: Thread? = null

    // Call this when CSD is received from Origin
    fun setMediaFormats(videoFormat: MediaFormat?, audioFormat: MediaFormat?) {
        if (videoFormat != null) {
            _videoFormat = videoFormat
            Log.d(TAG, "Received video format: ${_videoFormat?.getString(MediaFormat.KEY_MIME)}")
        }
        if (audioFormat != null) {
            _audioFormat = audioFormat
            Log.d(TAG, "Received audio format: ${_audioFormat?.getString(MediaFormat.KEY_MIME)}")
        }
    }

    fun startPlayback() {
        if (isPlaying.get()) {
            Log.w(TAG, "Playback already started.")
            return
        }
        if (_videoFormat == null || _audioFormat == null) {
            Log.e(TAG, "Media formats not set. Cannot start playback.")
            return
        }

        isPlaying.set(true)
        isStopped.set(false)
        initializeDecodersAndAudioTrack()

        videoDecodingThread = Thread(this::videoDecodingLoop, "VideoDecodingThread").apply { start() }
        audioDecodingThread = Thread(this::audioDecodingLoop, "AudioDecodingThread").apply { start() }

        Log.d(TAG, "Playback started.")
    }

    fun stopPlayback() {
        if (!isPlaying.get()) {
            Log.w(TAG, "Playback already stopped.")
            return
        }
        isPlaying.set(false)
        isStopped.set(true) // Signal threads to stop

        // Clear queues immediately (ephemeral data handling)
        videoQueue.clear()
        audioQueue.clear()

        // Interrupt threads and wait for them to finish (with timeout)
        videoDecodingThread?.interrupt()
        videoDecodingThread?.join(1000) // Wait for 1 second

        audioDecodingThread?.interrupt()
        audioDecodingThread?.join(1000)

        releaseResources()
        Log.d(TAG, "Playback stopped and resources released.")
    }

    fun addPacket(type: Byte, ptsUs: Long, data: ByteBuffer) {
        val packet = MediaPacket(type, ptsUs, data.duplicate()) // Use duplicate to avoid modifying original buffer
        try {
            when (type) {
                PACKET_TYPE_VIDEO -> videoQueue.offer(packet, 100, TimeUnit.MILLISECONDS)
                PACKET_TYPE_AUDIO -> audioQueue.offer(packet, 100, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Failed to add packet to queue: ${e.message}")
            Thread.currentThread().interrupt() // Restore interrupt status
        }
    }

    private fun initializeDecodersAndAudioTrack() {
        runCatching {
            // Video Decoder
            _videoFormat?.let { format ->
                videoDecoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                videoDecoder?.configure(format, videoSurface, null, 0)
                videoDecoder?.start()
                Log.d(TAG, "Video decoder initialized.")
            }

            // Audio Decoder
            _audioFormat?.let { format ->
                audioDecoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                audioDecoder?.configure(format, null, null, 0)
                audioDecoder?.start()
                Log.d(TAG, "Audio decoder initialized.")

                // Audio Track
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelConfig = if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                    AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val audioFormatEncoding = AudioFormat.ENCODING_PCM_16BIT // Assuming PCM 16-bit

                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormatEncoding)

                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormatEncoding,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                ).apply { play() }
                Log.d(TAG, "Audio track initialized.")
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize media components", e)
            stopPlayback() // Stop if initialization fails
        }
    }

    private fun videoDecodingLoop() {
        val info = MediaCodec.BufferInfo()
        while (isPlaying.get() && !Thread.interrupted()) {
            // --- MODIFIED SECTION ---
            val packet = videoQueue.poll(100, TimeUnit.MILLISECONDS) // Moved poll outside runCatching
            if (packet == null) {
                continue // Now 'continue' applies to the while loop, no experimental feature needed
            }
            // --- END MODIFIED SECTION ---

            runCatching { // The runCatching now wraps the actual decoding logic
                videoDecoder?.let { decoder ->
                    var inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        decoder.getInputBuffer(inputBufferIndex)?.apply {
                            clear()
                            put(packet.data)
                        }?.also {
                            decoder.queueInputBuffer(inputBufferIndex, 0, packet.data.remaining(), packet.ptsUs, 0)
                        }
                    }

                    var outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    while (outputBufferIndex >= 0) {
                        val presentationTimeNs = info.presentationTimeUs * 1000L
                        val currentAudioTimeNs = audioTrack?.let { track ->
                            track.playbackHeadPosition.toLong() * 1000L * 1000L / track.sampleRate
                        } ?: 0L

                        val render = presentationTimeNs <= currentAudioTimeNs

                        decoder.releaseOutputBuffer(outputBufferIndex, render)
                        outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    }
                }
            }.onFailure { e ->
                if (e is InterruptedException) {
                    Log.d(TAG, "Video decoding thread interrupted.")
                    Thread.currentThread().interrupt() // Restore interrupt status
                } else {
                    Log.e(TAG, "Error in video decoding loop", e)
                }
                break // Exit loop on error
            }
        }
        Log.d(TAG, "Video decoding loop stopped.")
    }

    private fun audioDecodingLoop() {
        val info = MediaCodec.BufferInfo()
        while (isPlaying.get() && !Thread.interrupted()) {
            // --- MODIFIED SECTION ---
            val packet = audioQueue.poll(100, TimeUnit.MILLISECONDS) // Moved poll outside runCatching
            if (packet == null) {
                continue // Now 'continue' applies to the while loop, no experimental feature needed
            }
            // --- END MODIFIED SECTION ---

            runCatching { // The runCatching now wraps the actual decoding logic
                audioDecoder?.let { decoder ->
                    var inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        decoder.getInputBuffer(inputBufferIndex)?.apply {
                            clear()
                            put(packet.data)
                        }?.also {
                            decoder.queueInputBuffer(inputBufferIndex, 0, packet.data.remaining(), packet.ptsUs, 0)
                        }
                    }

                    var outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    while (outputBufferIndex >= 0) {
                        decoder.getOutputBuffer(outputBufferIndex)?.let { outputBuffer ->
                            audioTrack?.write(outputBuffer, info.size, AudioTrack.WRITE_BLOCKING)
                        }
                        decoder.releaseOutputBuffer(outputBufferIndex, false) // Don't render to surface
                        outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    }
                }
            }.onFailure { e ->
                if (e is InterruptedException) {
                    Log.d(TAG, "Audio decoding thread interrupted.")
                    Thread.currentThread().interrupt()
                } else {
                    Log.e(TAG, "Error in audio decoding loop", e)
                }
                break // Exit loop on error
            }
        }
        Log.d(TAG, "Audio decoding loop stopped.")
    }

    private fun releaseResources() {
        videoDecoder?.stop()
        videoDecoder?.release()
        videoDecoder = null

        audioDecoder?.stop()
        audioDecoder?.release()
        audioDecoder = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        videoQueue.clear()
        audioQueue.clear()

        _videoFormat = null
        _audioFormat = null
    }

    // Helper data class for media packets
    data class MediaPacket(
        val type: Byte,
        val ptsUs: Long,
        val data: ByteBuffer
    )

    companion object {
        private const val TAG = "MediaPlaybackManager"
        private const val TIMEOUT_US = 10000L // 10ms timeout for buffer operations

        // Packet types (must match Origin)
        const val PACKET_TYPE_VIDEO: Byte = 0
        const val PACKET_TYPE_AUDIO: Byte = 1
        const val PACKET_TYPE_VIDEO_CSD: Byte = 2 // Codec Specific Data
        const val PACKET_TYPE_AUDIO_CSD: Byte = 3
        const val COMMAND_START_STREAMING = "START_STREAM"
        const val COMMAND_STOP_STREAMING = "STOP_STREAM"
    }
}