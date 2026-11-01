package com.rg406v.sampler.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Entrée micro mono 16 bits. Peut tourner en simple moniteur (niveau d'entrée affiché, rien n'est gardé)
 * ou enregistrer (30 s max). Un gain d'entrée numérique est appliqué avant la mesure et l'enregistrement.
 */
class Recorder(private val onProgress: (level: Float, seconds: Float, clip: Boolean) -> Unit) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val MAX_SECONDS = 30
    }

    @Volatile var gain = 1f
    @Volatile var monitorWanted = false
    @Volatile private var running = false
    @Volatile private var storing = false
    private var thread: Thread? = null
    private var data = ShortArray(0)
    private var count = 0

    @SuppressLint("MissingPermission")
    private fun startThread(): Boolean {
        if (running) return true
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
        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(1024)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val g = gain
                    var peak = 0
                    var clip = false
                    for (i in 0 until n) {
                        var v = (buf[i] * g).toInt()
                        if (v > 32767) { v = 32767; clip = true } else if (v < -32768) { v = -32768; clip = true }
                        buf[i] = v.toShort()
                        peak = max(peak, abs(v))
                    }
                    if (storing && count + n <= data.size) {
                        System.arraycopy(buf, 0, data, count, n)
                        count += n
                    }
                    onProgress(sqrt(peak / 32768f), count / SAMPLE_RATE.toFloat(), clip || peak >= 32700)
                }
            } catch (e: Exception) {
                // on garde ce qui a été enregistré
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
                running = false
            }
        }.also { it.start() }
        return true
    }

    private fun stopThread() {
        running = false
        thread?.join(1000)
        thread = null
    }

    fun startMonitor(): Boolean {
        monitorWanted = true
        return startThread()
    }

    fun stopMonitor() {
        monitorWanted = false
        if (!storing) stopThread()
    }

    fun beginStore(): Boolean {
        if (!startThread()) return false
        data = ShortArray(SAMPLE_RATE * MAX_SECONDS)
        count = 0
        storing = true
        return true
    }

    /** Arrête l'enregistrement et renvoie le sample (DC retiré, normalisé), ou null si trop court. */
    fun endStore(): FloatArray? {
        storing = false
        if (!monitorWanted) stopThread()
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
