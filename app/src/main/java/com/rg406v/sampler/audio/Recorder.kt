package com.rg406v.sampler.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Enregistre le micro en mono 16 bits (30 s max). Le niveau et la durée sont remontés par callback. */
class Recorder(private val onProgress: (level: Float, seconds: Float) -> Unit) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val MAX_SECONDS = 30
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var data = ShortArray(0)
    private var count = 0

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return false
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, 4096)
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        data = ShortArray(SAMPLE_RATE * MAX_SECONDS)
        count = 0
        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(1024)
            try {
                rec.startRecording()
                while (running && count < data.size) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val k = minOf(n, data.size - count)
                    System.arraycopy(buf, 0, data, count, k)
                    count += k
                    var peak = 0
                    for (i in 0 until k) peak = max(peak, abs(buf[i].toInt()))
                    onProgress(sqrt(peak / 32768f), count / SAMPLE_RATE.toFloat())
                }
            } catch (e: Exception) {
                // on garde ce qui a été enregistré
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
            }
        }.also { it.start() }
        return true
    }

    /** Arrête et renvoie le sample en float (DC retiré, normalisé), ou null si trop court. */
    fun stop(): FloatArray? {
        running = false
        thread?.join(1000)
        thread = null
        val n = count
        if (n < SAMPLE_RATE / 20) return null

        var mean = 0.0
        for (i in 0 until n) mean += data[i]
        mean /= n

        val out = FloatArray(n)
        var peak = 0f
        for (i in 0 until n) {
            val v = ((data[i] - mean) / 32768.0).toFloat()
            out[i] = v
            if (abs(v) > peak) peak = abs(v)
        }
        if (peak > 0.001f) {
            val g = 0.9f / peak
            for (i in 0 until n) out[i] *= g
        }
        return out
    }
}
