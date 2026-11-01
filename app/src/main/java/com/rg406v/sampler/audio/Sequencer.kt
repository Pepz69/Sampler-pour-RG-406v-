package com.rg406v.sampler.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Un pattern : 16 pistes (une par pad) de 64 pas maximum, avec p-locks par pas. */
class Pattern {
    val len = IntArray(16) { 16 }
    val on = Array(16) { BooleanArray(64) }
    val prob = Array(16) { IntArray(64) { 100 } }
    val micro = Array(16) { FloatArray(64) }
    val retrig = Array(16) { IntArray(64) { 1 } }
    val slen = Array(16) { FloatArray(64) { 1f } }
    val pitch = Array(16) { FloatArray(64) { Float.NaN } }
    val vol = Array(16) { FloatArray(64) { Float.NaN } }
    val pan = Array(16) { FloatArray(64) { Float.NaN } }
    val start = Array(16) { FloatArray(64) { Float.NaN } }
    val cond = Array(16) { IntArray(64) }

    fun resetStep(t: Int, s: Int) {
        on[t][s] = false
        prob[t][s] = 100
        micro[t][s] = 0f
        retrig[t][s] = 1
        slen[t][s] = 1f
        pitch[t][s] = Float.NaN
        vol[t][s] = Float.NaN
        pan[t][s] = Float.NaN
        start[t][s] = Float.NaN
        cond[t][s] = 0
    }

    fun clearTrack(t: Int) { for (s in 0 until 64) resetStep(t, s) }

    fun clearAll() { for (t in 0 until 16) { clearTrack(t); len[t] = 16 } }

    fun copyFrom(o: Pattern) {
        for (t in 0 until 16) {
            len[t] = o.len[t]
            for (s in 0 until 64) {
                on[t][s] = o.on[t][s]; prob[t][s] = o.prob[t][s]; micro[t][s] = o.micro[t][s]
                retrig[t][s] = o.retrig[t][s]; slen[t][s] = o.slen[t][s]
                pitch[t][s] = o.pitch[t][s]; vol[t][s] = o.vol[t][s]
                pan[t][s] = o.pan[t][s]; start[t][s] = o.start[t][s]; cond[t][s] = o.cond[t][s]
            }
        }
    }

    fun hasLock(t: Int, s: Int) =
        !pitch[t][s].isNaN() || !vol[t][s].isNaN() || !pan[t][s].isNaN() || !start[t][s].isNaN() ||
            prob[t][s] < 100 || retrig[t][s] > 1 || cond[t][s] > 0 || micro[t][s] != 0f
}

/**
 * Séquenceur à la Elektron : pas à pas, p-locks (hauteur, volume, pan, début), probabilité, conditions
 * de trig, micro-timing, retrig, swing, longueurs de piste indépendantes. Les événements sont calculés
 * dans le thread audio, à l'échantillon près.
 */
class Sequencer(private val sampleRate: Int) {
    companion object {
        const val MAXE = 1024
        val COND_LABELS = listOf("—", "1:2", "2:2", "1:3", "2:3", "3:3", "1:4", "2:4", "3:4", "4:4")
        private val COND_A = intArrayOf(0, 1, 2, 1, 2, 3, 1, 2, 3, 4)
        private val COND_B = intArrayOf(1, 2, 2, 3, 3, 3, 4, 4, 4, 4)
        val RETRIGS = intArrayOf(1, 2, 3, 4, 6, 8)
    }

    val patterns = Array(8) { Pattern() }
    var current by mutableIntStateOf(0)
    val bpm = Param("BPM", 40f, 300f, 120f, decimals = 0)
    val swing = Param("Swing", 50f, 75f, 50f, unit = "%", decimals = 0)
    val mute = BooleanArray(16)

    var playing by mutableStateOf(false)
    var recArmed by mutableStateOf(false)

    /** Incrémenté à chaque modification faite par l'UI (redessin). */
    var version by mutableIntStateOf(0)

    /** Incrémenté à chaque pas joué (redessin du curseur de lecture). */
    var tick by mutableIntStateOf(0)

    @Volatile var gstep = -1L
    @Volatile private var needReset = false
    @Volatile private var lastStepNano = 0L

    private var now = 0.0
    private var nextStepTime = 0.0
    private var seed = 987654321

    private var evCount = 0
    private val evTime = DoubleArray(MAXE)
    private val evPad = IntArray(MAXE)
    private val evType = IntArray(MAXE)
    private val evPitch = FloatArray(MAXE)
    private val evVol = FloatArray(MAXE)
    private val evPan = FloatArray(MAXE)
    private val evStart = FloatArray(MAXE)

    /** Événements dus dans le bloc courant (triés par instant). */
    val dOff = IntArray(MAXE)
    val dPad = IntArray(MAXE)
    val dType = IntArray(MAXE)
    val dPitch = FloatArray(MAXE)
    val dVol = FloatArray(MAXE)
    val dPan = FloatArray(MAXE)
    val dStart = FloatArray(MAXE)

    fun pattern() = patterns[current]
    fun touch() { version++ }

    fun start() {
        needReset = true
        playing = true
    }

    fun stop() {
        playing = false
    }

    fun playStep(track: Int): Int {
        if (!playing || gstep < 0) return -1
        return (gstep % pattern().len[track].coerceIn(1, 64)).toInt()
    }

    private fun rnd(): Float {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        return (seed ushr 8) / 16777216f
    }

    private fun addEv(t: Double, pad: Int, type: Int, pitch: Float, vol: Float, pan: Float, start: Float) {
        if (evCount >= MAXE) return
        val k = evCount++
        evTime[k] = t; evPad[k] = pad; evType[k] = type
        evPitch[k] = pitch; evVol[k] = vol; evPan[k] = pan; evStart[k] = start
    }

    private fun condOk(c: Int, cycle: Long): Boolean {
        if (c <= 0 || c >= COND_A.size) return true
        return (cycle % COND_B[c]).toInt() + 1 == COND_A[c]
    }

    private fun processStep(t0: Double, stepLen: Double) {
        gstep++
        lastStepNano = System.nanoTime()
        tick++
        val g = gstep
        val pat = patterns[current]
        val swingDelay = if ((g and 1L) == 1L) (2.0 * swing.value / 100.0 - 1.0) * stepLen else 0.0
        for (tr in 0 until 16) {
            if (mute[tr]) continue
            val len = pat.len[tr].coerceIn(1, 64)
            val s = (g % len).toInt()
            if (!pat.on[tr][s]) continue
            if (!condOk(pat.cond[tr][s], g / len)) continue
            val pr = pat.prob[tr][s]
            if (pr < 100 && rnd() * 100f >= pr) continue
            val base = t0 + swingDelay + pat.micro[tr][s] * stepLen
            val rt = pat.retrig[tr][s].coerceIn(1, 8)
            val ls = pat.slen[tr][s].toDouble()
            for (r in 0 until rt) {
                val tt = base + r * stepLen / rt
                addEv(tt, tr, 0, pat.pitch[tr][s], pat.vol[tr][s], pat.pan[tr][s], pat.start[tr][s])
                addEv(tt + ls * stepLen / rt * 0.98, tr, 1, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            }
        }
    }

    private fun swapD(i: Int, j: Int) {
        var ti = dOff[i]; dOff[i] = dOff[j]; dOff[j] = ti
        ti = dPad[i]; dPad[i] = dPad[j]; dPad[j] = ti
        ti = dType[i]; dType[i] = dType[j]; dType[j] = ti
        var tf = dPitch[i]; dPitch[i] = dPitch[j]; dPitch[j] = tf
        tf = dVol[i]; dVol[i] = dVol[j]; dVol[j] = tf
        tf = dPan[i]; dPan[i] = dPan[j]; dPan[j] = tf
        tf = dStart[i]; dStart[i] = dStart[j]; dStart[j] = tf
    }

    private fun key(i: Int) = dOff[i] * 2 + (if (dType[i] == 1) 0 else 1)

    /** Appelé par le thread audio une fois par bloc de n échantillons. Renvoie le nombre d'événements dus. */
    fun advance(n: Int): Int {
        if (needReset) {
            needReset = false
            gstep = -1
            nextStepTime = now
            evCount = 0
        }
        if (playing) {
            val stepLen = sampleRate * 15.0 / bpm.value
            while (nextStepTime < now + n) {
                processStep(nextStepTime, stepLen)
                nextStepTime += stepLen
            }
        }
        var d = 0
        var w = 0
        for (k in 0 until evCount) {
            if (evTime[k] < now + n) {
                if (d < MAXE) {
                    dOff[d] = (evTime[k] - now).toInt().coerceIn(0, n - 1)
                    dPad[d] = evPad[k]; dType[d] = evType[k]
                    dPitch[d] = evPitch[k]; dVol[d] = evVol[k]; dPan[d] = evPan[k]; dStart[d] = evStart[k]
                    d++
                }
            } else {
                if (w != k) {
                    evTime[w] = evTime[k]; evPad[w] = evPad[k]; evType[w] = evType[k]
                    evPitch[w] = evPitch[k]; evVol[w] = evVol[k]; evPan[w] = evPan[k]; evStart[w] = evStart[k]
                }
                w++
            }
        }
        evCount = w
        for (i in 1 until d) {
            var j = i
            while (j > 0 && key(j - 1) > key(j)) { swapD(j - 1, j); j-- }
        }
        now += n
        return d
    }

    /** Enregistrement live : pose un trig sur le pas le plus proche de l'instant du jeu. */
    fun recordHit(tr: Int, pitch: Float = Float.NaN) {
        if (!playing || gstep < 0) return
        val stepLen = sampleRate * 15.0 / bpm.value
        val elapsed = (System.nanoTime() - lastStepNano) / 1e9 * sampleRate
        val g = if (elapsed > stepLen * 0.5) gstep + 1 else gstep
        val pat = pattern()
        val s = (g % pat.len[tr].coerceIn(1, 64)).toInt()
        pat.on[tr][s] = true
        if (!pitch.isNaN()) pat.pitch[tr][s] = pitch
        touch()
    }

    // ------------------------------------------------------------ sauvegarde

    private fun enc(f: Float): Double = if (f.isNaN()) -999.0 else f.toDouble()
    private fun dec(d: Double): Float = if (d <= -998.0) Float.NaN else d.toFloat()

    fun toJson(): String {
        val root = JSONObject()
        root.put("bpm", bpm.value.toDouble())
        root.put("swing", swing.value.toDouble())
        root.put("cur", current)
        val pats = JSONArray()
        for (p in patterns) {
            val tracks = JSONArray()
            for (t in 0 until 16) {
                val o = JSONObject()
                o.put("len", p.len[t])
                val st = JSONArray()
                for (s in 0 until 64) {
                    if (!p.on[t][s]) continue
                    val a = JSONArray()
                    a.put(s); a.put(p.prob[t][s]); a.put(p.micro[t][s].toDouble()); a.put(p.retrig[t][s])
                    a.put(p.slen[t][s].toDouble()); a.put(enc(p.pitch[t][s])); a.put(enc(p.vol[t][s]))
                    a.put(enc(p.pan[t][s])); a.put(enc(p.start[t][s])); a.put(p.cond[t][s])
                    st.put(a)
                }
                o.put("steps", st)
                tracks.put(o)
            }
            pats.put(tracks)
        }
        root.put("patterns", pats)
        return root.toString()
    }

    fun fromJson(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            bpm.value = root.optDouble("bpm", 120.0).toFloat().coerceIn(bpm.min, bpm.max)
            swing.value = root.optDouble("swing", 50.0).toFloat().coerceIn(swing.min, swing.max)
            val pats = root.getJSONArray("patterns")
            for (pi in 0 until minOf(8, pats.length())) {
                val p = patterns[pi]
                p.clearAll()
                val tracks = pats.getJSONArray(pi)
                for (t in 0 until minOf(16, tracks.length())) {
                    val o = tracks.getJSONObject(t)
                    p.len[t] = o.optInt("len", 16).coerceIn(1, 64)
                    val st = o.getJSONArray("steps")
                    for (i in 0 until st.length()) {
                        val a = st.getJSONArray(i)
                        val s = a.getInt(0).coerceIn(0, 63)
                        p.on[t][s] = true
                        p.prob[t][s] = a.getInt(1).coerceIn(0, 100)
                        p.micro[t][s] = a.getDouble(2).toFloat()
                        p.retrig[t][s] = a.getInt(3).coerceIn(1, 8)
                        p.slen[t][s] = a.getDouble(4).toFloat()
                        p.pitch[t][s] = dec(a.getDouble(5))
                        p.vol[t][s] = dec(a.getDouble(6))
                        p.pan[t][s] = dec(a.getDouble(7))
                        p.start[t][s] = dec(a.getDouble(8))
                        p.cond[t][s] = a.getInt(9).coerceIn(0, COND_LABELS.size - 1)
                    }
                }
            }
            current = root.optInt("cur", 0).coerceIn(0, 7)
            version++
            true
        } catch (e: Exception) {
            false
        }
    }
}
