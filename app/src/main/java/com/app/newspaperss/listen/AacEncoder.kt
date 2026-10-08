package com.app.newspaperss.listen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

/** Where encoded audio goes, a block of samples at a time. */
interface AudioSink : Closeable {
    fun write(samples: FloatArray)

    /** After [close], what went into the file, for the podcast log; null if there's nothing to say. */
    val report: String? get() = null
}

/** Opens an [AudioSink] writing mono audio at [sampleRate] to [file]. */
fun interface AudioEncoder {
    fun open(file: File, sampleRate: Int): AudioSink
}

/**
 * Speech as AAC in an MP4 file, through Android's own encoder: every phone has one from API 16,
 * where Opus encoding needs API 29. At 32 kbit/s mono, half an hour is about 7 MB.
 */
object AacEncoder : AudioEncoder {
    private const val BITRATE = 32_000
    private const val TIMEOUT_US = 10_000L

    /** Waits of [TIMEOUT_US] in a row before giving up on a stuck encoder: 5 seconds. */
    private const val WAITS = 500

    override fun open(file: File, sampleRate: Int): AudioSink {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        val muxer = try {
            MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        return Sink(codec, muxer, sampleRate)
    }

    private class Sink(private val codec: MediaCodec, private val muxer: MediaMuxer, private val rate: Int) : AudioSink {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var fed = 0L
        private var closed = false

        // What reached the file, to tell audio lost in encoding from silence Kokoro made.
        private var frames = 0
        private var lost = 0
        private var lastUs = -1L
        private var longestStepUs = 0L
        private var backwards = 0

        override fun write(samples: FloatArray) {
            var at = 0
            var waits = 0
            while (at < samples.size) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) {
                    check(++waits <= WAITS) { "The encoder took no more" }
                    drain(false)
                    continue
                }
                waits = 0
                // PCM is in the phone's byte order; a ByteBuffer starts big-endian.
                val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
                buffer.clear()
                val n = minOf(samples.size - at, buffer.remaining() / 2)
                for (i in at until at + n) {
                    buffer.putShort((samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                }
                codec.queueInputBuffer(index, 0, n * 2, fed * 1_000_000 / rate, 0)
                fed += n
                at += n
                drain(false)
            }
        }

        /** Moves what's encoded into the file; at the end, until the encoder says it's done. */
        private fun drain(end: Boolean) {
            var idle = 0
            while (true) {
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    // An encoder that never finishes mustn't hold the work forever.
                    index == MediaCodec.INFO_TRY_AGAIN_LATER && end && ++idle > WAITS -> throw IllegalStateException("The encoder didn't finish")
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val data = codec.getOutputBuffer(index)!!
                        // The codec's own setup data is in the track's format already.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            if (track >= 0) {
                                muxer.writeSampleData(track, data, info)
                                frames++
                                if (lastUs >= 0) {
                                    if (info.presentationTimeUs <= lastUs) backwards++
                                    longestStepUs = maxOf(longestStepUs, info.presentationTimeUs - lastUs)
                                }
                                lastUs = info.presentationTimeUs
                            } else {
                                lost++
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    !end -> return
                }
            }
        }

        /** An AAC frame holds 1024 samples, so a step between frames much longer than that is audio lost. */
        override val report: String
            get() = "encoder: ${fed} samples in, $frames frames out of ${(fed + 1023) / 1024}, " +
                "longest step ${longestStepUs / 1000} ms (a frame is ${1024_000 / rate} ms)" +
                (if (lost > 0) ", $lost frames before the file was ready" else "") +
                (if (backwards > 0) ", $backwards frames out of order" else "")

        override fun close() {
            if (closed) return
            closed = true
            try {
                var index = -1
                for (i in 0 until WAITS) {
                    index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) break
                    drain(false)
                }
                check(index >= 0) { "The encoder took no more" }
                codec.queueInputBuffer(index, 0, 0, fed * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
                if (track >= 0) muxer.stop()
            } finally {
                codec.release()
                muxer.release()
            }
        }
    }
}
