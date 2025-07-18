package com.example.remoteapp

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class MediaPlaybackManager(private val surface: Surface) {

    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private var videoTrackFormat: MediaFormat? = null
    private var audioTrackFormat: MediaFormat? = null

    // Queues to hold incoming media packets
    private val videoPacketQueue = ConcurrentLinkedQueue<MediaPacket>()
    private val audioPacketQueue = ConcurrentLinkedQueue<MediaPacket>()

    // Flags to control playback and indicate if codecs are configured
    private val isPlaying = AtomicBoolean(false)
    private val videoCodecConfigured = AtomicBoolean(false)
    private val audioCodecConfigured = AtomicBoolean(false)

    // Handlers and threads for decoding
    private var videoDecoderThread: HandlerThread? = null
    private var videoDecoderHandler: Handler? = null

    private var audioDecoderThread: HandlerThread? = null
    private var audioDecoderHandler: Handler? = null

    // Presentation timestamps for A/V sync
    // These store the system time (in microseconds) when the first frame with PTS 0 was presented.
    // This allows us to calculate how much time has passed relative to the stream's timeline.
    private var videoStartTimeUs: Long = -1
    private var audioStartTimeUs: Long = -1

    // Threshold for A/V sync (video waits for audio if it's too far ahead)
    private val AV_SYNC_THRESHOLD_US = 100_000L // 100ms

    fun startPlayback() {
        if (isPlaying.getAndSet(true)) {
            Log.w(TAG, "Playback already started.")
            return
        }
        Log.d(TAG, "Starting media playback.")

        // Reset sync timestamps when starting new playback
        resetSyncTimestamps()

        videoDecoderThread = HandlerThread("VideoDecoderThread").apply { start() }
        videoDecoderHandler = Handler(videoDecoderThread!!.looper)

        audioDecoderThread = HandlerThread("AudioDecoderThread").apply { start() }
        audioDecoderHandler = Handler(audioDecoderThread!!.looper)

        // Initial check and start of decoding loops (will wait for codecs to be configured)
        videoDecoderHandler?.post(videoDecodingRunnable)
        audioDecoderHandler?.post(audioDecodingRunnable)
    }

    fun stopPlayback() {
        if (!isPlaying.getAndSet(false)) {
            Log.w(TAG, "Playback not active, nothing to stop.")
            return
        }
        Log.d(TAG, "Stopping media playback.")

        // Clear queues immediately to stop processing old frames
        videoPacketQueue.clear()
        audioPacketQueue.clear()

        // Stop handlers and threads
        videoDecoderHandler?.removeCallbacksAndMessages(null)
        videoDecoderThread?.quitSafely()
        videoDecoderThread?.join(1000) // Wait for thread to finish
        videoDecoderThread = null
        videoDecoderHandler = null

        audioDecoderHandler?.removeCallbacksAndMessages(null)
        audioDecoderThread?.quitSafely()
        audioDecoderThread?.join(1000) // Wait for thread to finish
        audioDecoderThread = null
        audioDecoderHandler = null

        releaseCodecs()
        resetSyncTimestamps() // Reset timestamps again after stopping

        Log.d(TAG, "Media playback stopped and resources released.")
    }

    private fun resetSyncTimestamps() {
        videoStartTimeUs = -1
        audioStartTimeUs = -1
        Log.d(TAG, "Sync timestamps reset.")
    }

    // Handles incoming video format data (PACKET_TYPE_VIDEO_FORMAT_DATA)
    fun setVideoFormat(mime: String, width: Int, height: Int, frameRate: Int) {
        if (videoCodecConfigured.get()) {
            Log.w(TAG, "Video format already set. Releasing current codec and reconfiguring.")
            releaseVideoCodec()
        }

        try {
            videoTrackFormat = MediaFormat.createVideoFormat(mime, width, height)
            videoTrackFormat?.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            Log.d(TAG, "Video format received: $videoTrackFormat")

            Log.d(TAG, "Video format ready, waiting for CSD to configure decoder.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create video MediaFormat: ${e.message}", e)
        }
    }

    // Handles incoming audio format data (PACKET_TYPE_AUDIO_FORMAT_DATA)
    fun setAudioFormat(mime: String, sampleRate: Int, channelCount: Int) {
        if (audioCodecConfigured.get()) {
            Log.w(TAG, "Audio format already set. Releasing current codec and reconfiguring.")
            releaseAudioCodec()
        }

        try {
            audioTrackFormat = MediaFormat.createAudioFormat(mime, sampleRate, channelCount)
            Log.d(TAG, "Audio format received: $audioTrackFormat")

            configureAudioCodec()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create audio MediaFormat: ${e.message}", e)
        }
    }

    // Handles incoming video CSD (PACKET_TYPE_VIDEO_CSD)
    fun setVideoCSD(csdData: ByteBuffer) {
        videoTrackFormat?.let { format ->
            if (!format.containsKey("csd-0")) {
                format.setByteBuffer("csd-0", csdData)
                Log.d(TAG, "Received video CSD-0, size: ${csdData.remaining()}")
            } else if (!format.containsKey("csd-1")) {
                format.setByteBuffer("csd-1", csdData)
                Log.d(TAG, "Received video CSD-1, size: ${csdData.remaining()}")
                configureVideoCodec()
            } else {
                Log.w(TAG, "Received more video CSD than expected (csd-0 and csd-1 already set). Ignoring.")
            }
        } ?: Log.w(TAG, "Video format not set, cannot apply CSD data.")
    }

    // Handles incoming audio CSD (PACKET_TYPE_AUDIO_CSD)
    fun setAudioCSD(csdData: ByteBuffer) {
        audioTrackFormat?.let { format ->
            if (!format.containsKey("csd-0")) {
                format.setByteBuffer("csd-0", csdData)
                Log.d(TAG, "Received audio CSD-0, size: ${csdData.remaining()}")
                configureAudioCodec()
            } else {
                Log.w(TAG, "Received more audio CSD than expected (csd-0 already set). Ignoring.")
            }
        } ?: Log.w(TAG, "Audio format not set, cannot apply CSD data.")
    }

    // Adds a video packet to the queue for decoding
    fun addVideoPacket(packet: MediaPacket) {
        videoPacketQueue.offer(packet)
    }

    // Adds an audio packet to the queue for decoding
    fun addAudioPacket(packet: MediaPacket) {
        audioPacketQueue.offer(packet)
    }

    private fun configureVideoCodec() {
        if (videoCodecConfigured.get()) {
            Log.d(TAG, "Video codec already configured.")
            return
        }
        videoTrackFormat?.let { format ->
            try {
                videoDecoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                videoDecoder?.configure(format, surface, null, 0)
                videoDecoder?.start()
                videoCodecConfigured.set(true)
                Log.d(TAG, "Video decoder configured and started.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure video decoder: ${e.message}", e)
                releaseVideoCodec()
            }
        } ?: Log.e(TAG, "Video format not set, cannot configure video decoder.")
    }

    private fun configureAudioCodec() {
        if (audioCodecConfigured.get()) {
            Log.d(TAG, "Audio codec already configured.")
            return
        }
        audioTrackFormat?.let { format ->
            try {
                audioDecoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                audioDecoder?.configure(format, null, null, 0)
                audioDecoder?.start()
                audioCodecConfigured.set(true)
                Log.d(TAG, "Audio decoder configured and started.")

                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelConfig = if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) {
                    AudioFormat.CHANNEL_OUT_MONO
                } else {
                    AudioFormat.CHANNEL_OUT_STEREO
                }
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                ).apply {
                    play()
                }
                Log.d(TAG, "AudioTrack configured and started.")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure audio decoder or AudioTrack: ${e.message}", e)
                releaseAudioCodec()
            }
        } ?: Log.e(TAG, "Audio format not set, cannot configure audio decoder.")
    }

    // Runnable for video decoding loop
    private val videoDecodingRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying.get()) {
                Log.d(TAG, "Video decoding stopped (isPlaying is false).")
                return
            }

            if (!videoCodecConfigured.get()) {
                Log.d(TAG, "Video codec not yet configured. Retrying in a bit...")
                videoDecoderHandler?.postDelayed(this, 100)
                return
            }

            val decoder = videoDecoder ?: run {
                Log.e(TAG, "Video decoder is null during decoding loop.")
                return
            }

            try {
                val inputBufferId = decoder.dequeueInputBuffer(0)
                if (inputBufferId >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferId)
                    val packet = videoPacketQueue.poll()

                    if (packet != null) {
                        inputBuffer?.put(packet.data)
                        decoder.queueInputBuffer(
                            inputBufferId,
                            0,
                            packet.data.remaining(),
                            packet.presentationTimeUs,
                            0
                        )

                        if (videoStartTimeUs == -1L) {
                            videoStartTimeUs = System.nanoTime() / 1000 - packet.presentationTimeUs
                            Log.d(TAG, "Video playback started. Initial video PTS: ${packet.presentationTimeUs}")
                        }

                        if (audioCodecConfigured.get() && audioStartTimeUs != -1L) {
                            val videoPlaybackTimeUs = (System.nanoTime() / 1000) - videoStartTimeUs + packet.presentationTimeUs

                            val audioPlaybackTimeUs = audioTrack?.let { track ->
                                val effectiveSampleRate = track.sampleRate.toLong().takeIf { it > 0 } ?: 1L
                                val audioTrackCurrentTimeUs = track.playbackHeadPosition.toLong() * 1_000_000L / effectiveSampleRate
                                (System.nanoTime() / 1000) - audioStartTimeUs + audioTrackCurrentTimeUs
                            } ?: run {
                                Log.w(TAG, "AudioTrack unexpectedly null during video A/V sync. Using video time.")
                                videoPlaybackTimeUs
                            }

                            val diff = videoPlaybackTimeUs - audioPlaybackTimeUs

                            if (diff > AV_SYNC_THRESHOLD_US) {
                                val sleepTimeMs = (diff - AV_SYNC_THRESHOLD_US) / 1000
                                Log.d(TAG, "Video too far ahead ($diff us). Pausing video for ${sleepTimeMs}ms.")
                                Thread.sleep(sleepTimeMs)
                            } else if (diff < -AV_SYNC_THRESHOLD_US) {
                                Log.v(TAG, "Video too far behind (${diff} us). Trying to catch up.")
                            }
                        }

                    } else {
                        // No video packet available, retry later
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 0)

                while (outputBufferId >= 0) {
                    decoder.releaseOutputBuffer(outputBufferId, true)
                    outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in video decoding loop: ${e.message}", e)
                isPlaying.set(false)
            } finally {
                if (isPlaying.get()) {
                    videoDecoderHandler?.post(this)
                }
            }
        }
    }

    // Runnable for audio decoding loop
    private val audioDecodingRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying.get()) {
                Log.d(TAG, "Audio decoding stopped (isPlaying is false).")
                return
            }

            if (!audioCodecConfigured.get()) {
                Log.d(TAG, "Audio codec not yet configured. Retrying in a bit...")
                audioDecoderHandler?.postDelayed(this, 100)
                return
            }

            val decoder = audioDecoder ?: run {
                Log.e(TAG, "Audio decoder is null during decoding loop.")
                return
            }
            val track = audioTrack ?: run {
                Log.e(TAG, "AudioTrack is null during decoding loop.")
                return
            }

            try {
                val inputBufferId = decoder.dequeueInputBuffer(0)
                if (inputBufferId >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferId)
                    val packet = audioPacketQueue.poll()

                    if (packet != null) {
                        inputBuffer?.put(packet.data)
                        decoder.queueInputBuffer(
                            inputBufferId,
                            0,
                            packet.data.remaining(),
                            packet.presentationTimeUs,
                            0
                        )

                        if (audioStartTimeUs == -1L) {
                            audioStartTimeUs = System.nanoTime() / 1000 - packet.presentationTimeUs
                            Log.d(TAG, "Audio playback started. Initial audio PTS: ${packet.presentationTimeUs}")
                        }

                    } else {
                        // No audio packet available, retry later
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 0)

                while (outputBufferId >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputBufferId)
                    outputBuffer?.let {
                        it.position(bufferInfo.offset)
                        it.limit(bufferInfo.offset + bufferInfo.size)
                        track.write(it, bufferInfo.size, AudioTrack.WRITE_BLOCKING)
                    }
                    decoder.releaseOutputBuffer(outputBufferId, false)
                    outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in audio decoding loop: ${e.message}", e)
                isPlaying.set(false)
            } finally {
                if (isPlaying.get()) {
                    audioDecoderHandler?.post(this)
                }
            }
        }
    }

    private fun releaseCodecs() {
        releaseVideoCodec()
        releaseAudioCodec()
    }

    private fun releaseVideoCodec() {
        if (videoDecoder != null) {
            try {
                videoDecoder?.stop()
                videoDecoder?.release()
                Log.d(TAG, "Video decoder released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing video decoder: ${e.message}", e)
            } finally {
                videoDecoder = null
                videoCodecConfigured.set(false)
                videoTrackFormat = null
            }
        }
    }

    private fun releaseAudioCodec() {
        if (audioDecoder != null) {
            try {
                audioDecoder?.stop()
                audioDecoder?.release()
                Log.d(TAG, "Audio decoder released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing audio decoder: ${e.message}", e)
            } finally {
                audioDecoder = null
                audioCodecConfigured.set(false)
                audioTrackFormat = null
            }
        }
        if (audioTrack != null) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
                Log.d(TAG, "AudioTrack released.")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack: ${e.message}", e)
            } finally {
                audioTrack = null
            }
        }
    }

    companion object {
        private const val TAG = "MediaPlaybackManager"
    }
}