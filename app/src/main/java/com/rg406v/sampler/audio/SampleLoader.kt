package com.rg406v.sampler.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.max

/** Décode WAV (16 bits), MP3, OGG, FLAC, AAC... en mono float via MediaExtractor / MediaCodec. */
object SampleLoader {

    private const val MAX_SECONDS = 30

    fun displayName(ctx: Context, uri: Uri): String {
        val raw = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null } ?: uri.lastPathSegment ?: "sample"
        return raw.substringBeforeLast('.')
    }

    fun load(ctx: Context, uri: Uri): Sample? = try {
        decode(ctx, uri)
    } catch (e: Exception) {
        null
    }

    private fun decode(ctx: Context, uri: Uri): Sample? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; fmt = f; break
                }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)

            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val maxSamples = rate * ch * MAX_SECONDS

            var pcm = ShortArray(rate * ch)
            var count = 0
            fun append(sb: ShortBuffer) {
                val k = sb.remaining()
                if (count + k > pcm.size) pcm = pcm.copyOf(max(pcm.size * 2, count + k))
                sb.get(pcm, count, k)
                count += k
            }

            if (mime == "audio/raw") {
                if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
                ) return null
                val bb = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
                while (count < maxSamples) {
                    bb.clear()
                    val n = ex.readSampleData(bb, 0)
                    if (n < 0) break
                    bb.position(0)
                    bb.limit(n)
                    append(bb.asShortBuffer())
                    ex.advance()
                }
            } else {
                val c = MediaCodec.createDecoderByType(mime)
                codec = c
                c.configure(fmt, null, null, 0)
                c.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone && count < maxSamples) {
                    if (!inputDone) {
                        val inIdx = c.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = c.getInputBuffer(inIdx)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val outIdx = c.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        val ob = c.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        append(ob.order(ByteOrder.nativeOrder()).asShortBuffer())
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = c.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            if (ch < 1) return null
            val frames = count / ch
            if (frames < 2) return null
            val out = FloatArray(frames)
            for (i in 0 until frames) {
                var s = 0f
                for (c in 0 until ch) s += pcm[i * ch + c]
                out[i] = s / (ch * 32768f)
            }
            return Sample(displayName(ctx, uri), out, rate)
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            ex.release()
        }
    }
}
