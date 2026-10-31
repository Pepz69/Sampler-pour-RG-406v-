package com.rg406v.sampler.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/**
 * Moteur audio : un thread à priorité audio qui mélange les voix, applique la chaîne d'effets
 * puis écrit dans un AudioTrack float stéréo en mode faible latence.
 *
 * Si ça craque : augmente BLOCK (512). Si tu veux moins de latence : 128.
 */
class AudioEngine(ctx: Context, private val pads: List<PadParams>) {

    companion object {
        const val BLOCK = 256
    }

    val sampleRate: Int
    val master = Param("Master", 0f, 1f, 0.8f)

    /** Chaîne d'effets dans l'ordre du signal. Remplacée atomiquement par l'UI. */
    @Volatile
    var chain: Array<Effect> = emptyArray()

    private val voices = Array(pads.size) { Voice(pads[it]) }
    private val mixL = FloatArray(BLOCK)
    private val mixR = FloatArray(BLOCK)
    private val out = FloatArray(BLOCK * 2)

    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        val am = ctx.getSystemService(AudioManager::class.java)
        sampleRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    }

    fun noteOn(i: Int) { voices[i].trig = true }

    fun noteOff(i: Int) {
        val p = pads[i]
        if (p.gate || p.loop) voices[i].rel = true
    }

    fun stopAll() { for (v in voices) v.kill = true }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ audioLoop() }, "sampler-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a < 0.8f) return x
        val y = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
        return if (x < 0f) -y else y
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val mask = AudioFormat.CHANNEL_OUT_STEREO
        val enc = AudioFormat.ENCODING_PCM_FLOAT
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, enc)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(enc)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(max(minBytes, BLOCK * 2 * 4 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }

        try {
            val target = max(BLOCK * 2, minBytes / 8)
            if (track.bufferCapacityInFrames >= target) track.bufferSizeInFrames = target
            track.play()

            while (running) {
                java.util.Arrays.fill(mixL, 0f)
                java.util.Arrays.fill(mixR, 0f)
                for (v in voices) v.render(mixL, mixR, BLOCK, sampleRate)

                val ch = chain
                for (fx in ch) if (fx.enabled) fx.process(mixL, mixR, BLOCK)

                val g = master.value
                for (i in 0 until BLOCK) {
                    out[2 * i] = softClip(mixL[i] * g)
                    out[2 * i + 1] = softClip(mixR[i] * g)
                }
                val written = track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
            }
        } finally {
            try { track.stop() } catch (e: Exception) { }
            track.release()
        }
    }
}
