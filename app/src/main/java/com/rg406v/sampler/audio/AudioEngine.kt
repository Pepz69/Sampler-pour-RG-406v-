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

    /** Gamme : chaque pad joue une note différente (pitch ajouté à celui du pad). */
    val scale = Param(
        "Gamme", 0f, 4f, 0f, stepped = true,
        labels = listOf("Aucune", "Chromatique", "Majeur", "Mineur", "Pentatonique"),
    )
    private val scales = arrayOf(
        IntArray(16),
        IntArray(16) { it },
        intArrayOf(0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24, 26),
        intArrayOf(0, 2, 3, 5, 7, 8, 10, 12, 14, 15, 17, 19, 20, 22, 24, 26),
        intArrayOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24, 26, 28, 31, 33, 36),
    )

    /** Chaîne d'effets dans l'ordre du signal. Remplacée atomiquement par l'UI. */
    @Volatile
    var chain: Array<Effect> = emptyArray()

    /** Effets individuels de chaque pad (insérés avant le mixage vers la chaîne master). */
    @Volatile
    var padChains: Array<Array<Effect>> = Array(pads.size) { emptyArray() }

    fun setPadChain(i: Int, chain: Array<Effect>) {
        val c = padChains.copyOf()
        c[i] = chain
        padChains = c
    }

    private val voices = Array(pads.size) { Voice(pads[it]) }
    private val padL = FloatArray(BLOCK)
    private val padR = FloatArray(BLOCK)
    private val padQuiet = IntArray(pads.size)
    private val mixL = FloatArray(BLOCK)
    private val mixR = FloatArray(BLOCK)
    private val out = FloatArray(BLOCK * 2)

    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        val am = ctx.getSystemService(AudioManager::class.java)
        sampleRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    }

    fun noteOn(i: Int) {
        voices[i].keyOffset = scales[scale.value.toInt().coerceIn(0, 4)][i % 16].toFloat()
        voices[i].trig = true
    }

    fun noteOff(i: Int) {
        if (pads[i].holds()) voices[i].rel = true
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
                val chains = padChains
                for (i in voices.indices) {
                    val v = voices[i]
                    val pc = chains[i]
                    var anyFx = false
                    for (fx in pc) if (fx.enabled) { anyFx = true; break }
                    if (!anyFx) {
                        v.render(mixL, mixR, BLOCK, sampleRate)
                        padQuiet[i] = 0
                        continue
                    }
                    // Pad avec effets : on laisse vivre les queues (delay, reverb) jusqu'au silence.
                    if (!v.isActive() && padQuiet[i] >= 400) continue
                    java.util.Arrays.fill(padL, 0f)
                    java.util.Arrays.fill(padR, 0f)
                    v.render(padL, padR, BLOCK, sampleRate)
                    for (fx in pc) if (fx.enabled) fx.process(padL, padR, BLOCK)
                    var peak = 0f
                    for (k in 0 until BLOCK) {
                        peak = max(peak, max(abs(padL[k]), abs(padR[k])))
                        mixL[k] += padL[k]
                        mixR[k] += padR[k]
                    }
                    padQuiet[i] = if (v.isActive() || peak > 1e-5f) 0 else padQuiet[i] + 1
                }

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
