package com.rg406v.sampler.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.os.Process
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/**
 * Moteur audio : un thread à priorité audio qui déclenche les voix (jeu live + séquenceur, découpé au
 * sample près), applique les effets des pads, la chaîne master, les effets punch-in, mesure les niveaux
 * et écrit dans un AudioTrack float stéréo. Bloc et tampon sont réglables en direct.
 */
class AudioEngine(ctx: Context, private val pads: List<PadParams>) {

    companion object {
        const val MAX_BLOCK = 2048
        const val CAPACITY_FRAMES = 8192
    }

    val sampleRate: Int
    val master = Param("Master", 0f, 1f, 0.8f)

    val scale = Param("Gamme", 0f, (Scales.size - 1).toFloat(), 0f, stepped = true, decimals = 0, labels = Scales.names)
    val scaleRoot = Param("Tonique", 0f, 11f, 0f, stepped = true, decimals = 0, labels = Scales.NOTE_NAMES)

    /** Hauteur (demi-tons) du pad i : degrés de la gamme choisie, en partant de la tonique. */
    fun scaleOffset(i: Int): Float {
        val t = scale.value.toInt().coerceIn(0, Scales.size - 1)
        if (t == 0) return 0f
        val st = Scales.steps[t]
        val d = i % 16
        return (12 * (d / st.size) + st[d % st.size] + scaleRoot.value.toInt()).toFloat()
    }

    // ---- réglages de latence / anti-craquement (appliqués en direct)
    @Volatile var blockSize = 256
    @Volatile var bufferBlocks = 2
    @Volatile var autoBuffer = true
    @Volatile var perfMode = 0 // 0 faible latence, 1 normal, 2 économie

    // ---- périphérique de sortie (null = choix automatique d'Android)
    @Volatile var outputDevice: AudioDeviceInfo? = null
    @Volatile var routedLabel = ""

    // ---- automation par mouvements : horloge de boucle et courbes
    @Volatile var autoBars = 4
    @Volatile var autoEnabled = true
    @Volatile var lanes: Array<Param> = emptyArray()
    private var autoBeat = 0f
    private var autoWasPlaying = false

    // ---- diagnostic
    @Volatile var underruns = 0
    @Volatile var loadPercent = 0f
    @Volatile var bufferFrames = 0

    // ---- niveaux de sortie (lus et remis à zéro par l'UI)
    @Volatile var outPeakL = 0f
    @Volatile var outPeakR = 0f
    @Volatile var clipHit = false

    // ---- sidechain déclenché par les notes
    val duckHit = IntArray(16) { -1 }
    @Volatile var keyCapturePad = -1
    val keyL = FloatArray(MAX_BLOCK)
    val keyR = FloatArray(MAX_BLOCK)
    private val uiHit = BooleanArray(16)

    @Volatile
    var chain: Array<Effect> = emptyArray()

    @Volatile
    var padChains: Array<Array<Effect>> = Array(pads.size) { emptyArray() }

    fun setPadChain(i: Int, chain: Array<Effect>) {
        val c = padChains.copyOf()
        c[i] = chain
        padChains = c
    }

    init {
        val am = ctx.getSystemService(AudioManager::class.java)
        sampleRate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    }

    val seq = Sequencer(sampleRate)
    val arp = Arp(seq, sampleRate)
    val punch = PunchFx(sampleRate) { seq.bpm.value }
    val spectrum = SpectrumAnalyzer(sampleRate)

    private val voices = Array(pads.size) { Voice(pads[it]) }
    private val padL = FloatArray(MAX_BLOCK)
    private val padR = FloatArray(MAX_BLOCK)
    private val padQuiet = IntArray(pads.size)
    private val mixL = FloatArray(MAX_BLOCK)
    private val mixR = FloatArray(MAX_BLOCK)
    private val out = FloatArray(MAX_BLOCK * 2)

    @Volatile private var running = false
    private var thread: Thread? = null

    /** Jeu live. pitch = demi-tons ajoutés (piano roll). */
    fun noteOn(i: Int, pitch: Float = 0f) {
        val v = voices[i]
        v.keyOffset = scaleOffset(i)
        v.setLocks(pitch, 1f, Float.NaN, Float.NaN)
        v.rel = false
        v.trig = true
        uiHit[i] = true
    }

    fun noteOff(i: Int) {
        if (pads[i].holds()) voices[i].rel = true
    }

    fun releaseHolds() {
        for (i in voices.indices) if (pads[i].holds()) voices[i].rel = true
    }

    fun stopAll() { for (v in voices) v.kill = true }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ audioLoop() }, "sampler-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
    }

    /** À appeler quand le mode de performance change : la piste audio doit être recréée. */
    fun restart() {
        val was = running
        stop()
        if (was) start()
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
        val perf = when (perfMode) {
            1 -> AudioTrack.PERFORMANCE_MODE_NONE
            2 -> AudioTrack.PERFORMANCE_MODE_POWER_SAVING
            else -> AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        }
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
                .setBufferSizeInBytes(max(minBytes, CAPACITY_FRAMES * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(perf)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }

        try {
            track.play()
            val startNs = System.nanoTime()
            var lastUnder = 0
            var lastBlk = -1
            var lastBuf = -1
            var lastDev: AudioDeviceInfo? = null
            var devCheck = 0

            while (running) {
                val od = outputDevice
                if (od !== lastDev) {
                    try { track.setPreferredDevice(od) } catch (e: Exception) { }
                    lastDev = od
                    devCheck = 0
                }
                if (devCheck-- <= 0) {
                    devCheck = 40
                    routedLabel = try {
                        track.routedDevice?.let { it.productName.toString() + " (" + it.type + ")" } ?: ""
                    } catch (e: Exception) { "" }
                }
                val blk = blockSize.coerceIn(32, MAX_BLOCK)
                val nbuf = bufferBlocks.coerceIn(1, 8)
                if (blk != lastBlk || nbuf != lastBuf) {
                    val target = (blk * nbuf).coerceIn(blk, track.bufferCapacityInFrames)
                    track.bufferSizeInFrames = target
                    bufferFrames = track.bufferSizeInFrames
                    lastBlk = blk
                    lastBuf = nbuf
                }

                val t0 = System.nanoTime()
                run {
                    val playing = seq.playing
                    if (playing && !autoWasPlaying) autoBeat = 0f
                    autoWasPlaying = playing
                    val loopBeats = 4f * autoBars.coerceIn(1, 8)
                    autoBeat += blk * seq.bpm.value / 60f / sampleRate
                    if (autoBeat >= loopBeats) autoBeat -= loopBeats * (autoBeat / loopBeats).toInt()
                    val frac = autoBeat / loopBeats
                    AutoRec.pos = frac
                    val ls = lanes
                    val en = autoEnabled
                    for (q in ls) {
                        q.ovr = if (!en || t0 - q.touchNs < 450_000_000L) Float.NaN else (q.lane?.valueAt(frac) ?: Float.NaN)
                    }
                }
                java.util.Arrays.fill(mixL, 0, blk, 0f)
                java.util.Arrays.fill(mixR, 0, blk, 0f)
                java.util.Arrays.fill(keyL, 0, blk, 0f)
                java.util.Arrays.fill(keyR, 0, blk, 0f)
                for (k in 0 until 16) {
                    duckHit[k] = if (uiHit[k]) { uiHit[k] = false; 0 } else -1
                }

                arp.tick(blk, seq.bpm.value)
                val nEv = seq.advance(blk)
                var evMask = 0
                for (e in 0 until nEv) {
                    val p = seq.dPad[e]
                    evMask = evMask or (1 shl p)
                    if (seq.dType[e] == 0) duckHit[p] = seq.dOff[e]
                }

                val kp = keyCapturePad
                val chains = padChains
                for (i in voices.indices) {
                    val v = voices[i]
                    val pc = chains[i]
                    var anyFx = false
                    for (fx in pc) if (fx.enabled) { anyFx = true; break }
                    val hasEv = (evMask shr i) and 1 == 1
                    val isKey = i == kp

                    if (anyFx && !v.isActive() && !hasEv && padQuiet[i] >= 400) continue

                    val tl: FloatArray
                    val tr: FloatArray
                    if (anyFx) {
                        java.util.Arrays.fill(padL, 0, blk, 0f)
                        java.util.Arrays.fill(padR, 0, blk, 0f)
                        tl = padL; tr = padR
                    } else if (isKey) {
                        tl = keyL; tr = keyR
                    } else {
                        tl = mixL; tr = mixR
                    }

                    var pos = 0
                    if (hasEv) {
                        for (e in 0 until nEv) {
                            if (seq.dPad[e] != i) continue
                            val o = seq.dOff[e]
                            if (o > pos) { v.render(tl, tr, pos, o - pos, sampleRate); pos = o }
                            if (seq.dType[e] == 0) {
                                v.keyOffset = scaleOffset(i)
                                v.setLocks(seq.dPitch[e], seq.dVol[e], seq.dPan[e], seq.dStart[e])
                                v.rel = false
                                v.trig = true
                            } else if (pads[i].holds()) {
                                v.rel = true
                            }
                            v.render(tl, tr, pos, 0, sampleRate)
                        }
                    }
                    v.render(tl, tr, pos, blk - pos, sampleRate)

                    if (anyFx) {
                        if (isKey) {
                            for (k in 0 until blk) { keyL[k] = padL[k]; keyR[k] = padR[k] }
                        }
                        for (fx in pc) if (fx.enabled) { fx.runAuto(blk, seq.bpm.value, sampleRate); fx.process(padL, padR, blk) }
                        var peak = 0f
                        for (k in 0 until blk) {
                            peak = max(peak, max(abs(padL[k]), abs(padR[k])))
                            mixL[k] += padL[k]
                            mixR[k] += padR[k]
                        }
                        padQuiet[i] = if (v.isActive() || peak > 1e-5f) 0 else padQuiet[i] + 1
                    } else {
                        padQuiet[i] = 0
                        if (isKey) {
                            for (k in 0 until blk) { mixL[k] += keyL[k]; mixR[k] += keyR[k] }
                        }
                    }
                }

                val ch = chain
                for (fx in ch) if (fx.enabled) { fx.runAuto(blk, seq.bpm.value, sampleRate); fx.process(mixL, mixR, blk) }
                punch.process(mixL, mixR, blk)
                spectrum.feed(mixL, mixR, blk)

                val g = master.value
                var pkL = 0f
                var pkR = 0f
                for (i in 0 until blk) {
                    val a = mixL[i] * g
                    val b = mixR[i] * g
                    val aa = abs(a)
                    val ab = abs(b)
                    if (aa > pkL) pkL = aa
                    if (ab > pkR) pkR = ab
                    out[2 * i] = softClip(a)
                    out[2 * i + 1] = softClip(b)
                }
                if (pkL > outPeakL) outPeakL = pkL
                if (pkR > outPeakR) outPeakR = pkR
                if (pkL >= 1f || pkR >= 1f) clipHit = true
                val t1 = System.nanoTime()

                val written = track.write(out, 0, blk * 2, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break

                val blockNs = blk * 1e9 / sampleRate
                loadPercent = loadPercent * 0.9f + 0.1f * ((t1 - t0) / blockNs * 100.0).toFloat()

                val u = track.underrunCount
                if (u != lastUnder) {
                    underruns += (u - lastUnder)
                    lastUnder = u
                    if (autoBuffer && bufferBlocks < 8 && System.nanoTime() - startNs > 1_000_000_000L) {
                        bufferBlocks = bufferBlocks + 1
                    }
                }
            }
        } finally {
            try { track.stop() } catch (e: Exception) { }
            track.release()
        }
    }
}
