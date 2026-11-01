package com.rg406v.sampler.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/*
 * Deux effets créatifs, écrits de zéro, inspirés de l'esprit des plugins
 * Manipulator (pitch shifter) et Filterverse (filtre multi-modes très modulable)
 * de Polyverse Music. Ce n'est PAS leur code ni leur algorithme.
 */

// ------------------------------------------------------------ Manipulator-like
/**
 * Transpositeur : deux lectures croisées dans une ligne à retard (technique
 * "delay-line pitch shifter"), avec réglage fin, désaccord stéréo, retour,
 * taille de fenêtre et une bascule de timbre (tilt) qui simule grossièrement
 * un déplacement de formants.
 */
class ManipulatorFx(private val sr: Int) : Effect("Manipulator", "Man") {
    val pitch = Param("Pitch", -24f, 24f, 7f, stepped = true, unit = " st")
    val fine = Param("Fine", -100f, 100f, 0f, unit = " ct", decimals = 0)
    val mix = Param("Mix", 0f, 1f, 0.5f)
    val feedback = Param("Retour", 0f, 0.9f, 0f)
    val window = Param("Fenêtre", 20f, 200f, 70f, log = true, unit = " ms", decimals = 0)
    val spread = Param("Spread", 0f, 50f, 0f, unit = " ct", decimals = 0)
    val tilt = Param("Tilt", -1f, 1f, 0f)
    override val params = listOf(pitch, fine, mix, feedback, window, spread, tilt)

    private val size = 16384
    private val mask = size - 1
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var wp = 0
    private val phase = floatArrayOf(0f, 0.25f)
    private val fbL = FloatArray(2)
    private val lpState = FloatArray(2)

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val w = (window.value * 0.001f * sr).coerceIn(256f, (size - 4).toFloat() / 1.0f)
        val baseSt = pitch.value + fine.value / 100f
        val spreadSt = spread.value / 100f
        val ratioL = 2.0.pow(((baseSt + spreadSt) / 12f).toDouble()).toFloat()
        val ratioR = 2.0.pow(((baseSt - spreadSt) / 12f).toDouble()).toFloat()
        val incL = (1f - ratioL) / w
        val incR = (1f - ratioR) / w
        val m = mix.value
        val fb = feedback.value
        val t = tilt.value
        val lpA = 1f - exp(-2f * PI.toFloat() * 1200f / sr)
        val neutral = abs(baseSt) < 0.005f && spreadSt < 0.005f
        var pl = phase[0]
        var pr = phase[1]
        var lpl = lpState[0]
        var lpr = lpState[1]
        var fl = fbL[0]
        var fr = fbL[1]

        for (i in 0 until n) {
            val xl = l[i]
            val xr = r[i]
            bufL[wp] = xl + fl * fb
            bufR[wp] = xr + fr * fb

            var wl: Float
            var wr: Float
            if (neutral) {
                wl = xl
                wr = xr
            } else {
                wl = tap(bufL, pl, w)
                wr = tap(bufR, pr, w)
            }
            pl += incL
            if (pl >= 1f) pl -= 1f else if (pl < 0f) pl += 1f
            pr += incR
            if (pr >= 1f) pr -= 1f else if (pr < 0f) pr += 1f

            if (t != 0f) {
                lpl += lpA * (wl - lpl)
                lpr += lpA * (wr - lpr)
                wl = lpl * (1f - 0.6f * t) + (wl - lpl) * (1f + 0.6f * t)
                wr = lpr * (1f - 0.6f * t) + (wr - lpr) * (1f + 0.6f * t)
            }
            fl = tanh(wl)
            fr = tanh(wr)
            l[i] = xl + (wl - xl) * m
            r[i] = xr + (wr - xr) * m
            wp = (wp + 1) and mask
        }
        phase[0] = pl
        phase[1] = pr
        lpState[0] = lpl
        lpState[1] = lpr
        fbL[0] = fl
        fbL[1] = fr
    }

    /** Deux lectures décalées d'une demi-fenêtre, pondérées par une fenêtre de Hann. */
    private fun tap(buf: FloatArray, p: Float, w: Float): Float {
        val p2 = if (p >= 0.5f) p - 0.5f else p + 0.5f
        val w1 = 0.5f - 0.5f * cos(2f * PI.toFloat() * p)
        return read(buf, p * w + 1f) * w1 + read(buf, p2 * w + 1f) * (1f - w1)
    }

    private fun read(buf: FloatArray, delay: Float): Float {
        val pos = wp - delay
        var ip = pos.toInt()
        if (pos < 0f && pos != ip.toFloat()) ip -= 1
        val fr = pos - ip
        val a = buf[ip and mask]
        val b = buf[(ip + 1) and mask]
        return a + (b - a) * fr
    }
}

// ------------------------------------------------------------ Filterverse-like
/** Filtre à variables d'état avec sorties LP / BP / HP simultanées. */
private class Svf {
    var ic1 = 0f
    var ic2 = 0f
    var a1 = 0f
    var a2 = 0f
    var a3 = 0f
    var k = 1f
    var lp = 0f
    var bp = 0f
    var hp = 0f

    fun set(g: Float, kk: Float) {
        k = kk
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun tick(x: Float) {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        lp = v2
        bp = v1
        hp = x - k * v1 - v2
    }
}

/**
 * Filtre multi-modes (passe-bas 12/24, passe-haut, passe-bande, notch, peigne,
 * voyelles, phaser) avec drive, un LFO (5 formes) et un suiveur d'enveloppe
 * pour moduler la fréquence, décalage stéréo du LFO.
 */
class FilterverseFx(private val sr: Int) : Effect("Filterverse", "Fvr") {
    val cutoff = Param("Cutoff", 30f, 18000f, 900f, log = true, unit = " Hz", decimals = 0)
    val res = Param("Reso", 0f, 1f, 0.45f)
    val drive = Param("Drive", 0f, 1f, 0.2f)
    val type = Param(
        "Type", 0f, 7f, 0f, stepped = true,
        labels = listOf("LP12", "LP24", "HP", "BP", "Notch", "Peigne", "Voyelle", "Phaser"),
    )
    val rate = Param("LFO rate", 0.05f, 20f, 0.8f, log = true, unit = " Hz")
    val depth = Param("LFO depth", 0f, 1f, 0.4f)
    val shape = Param("LFO forme", 0f, 4f, 0f, stepped = true, labels = listOf("sin", "tri", "saw", "carré", "s&h"))
    val envAmt = Param("Env", -1f, 1f, 0f)
    val stereo = Param("Stéréo", 0f, 1f, 0.3f)
    val mix = Param("Mix", 0f, 1f, 1f)
    override val params = listOf(cutoff, res, drive, type, rate, depth, shape, envAmt, stereo, mix)

    private val f1 = arrayOf(Svf(), Svf())
    private val f2 = arrayOf(Svf(), Svf())
    private val f3 = arrayOf(Svf(), Svf())
    private val apS = Array(2) { FloatArray(4) }
    private val apFb = FloatArray(2)
    private val combN = 4096
    private val combBuf = Array(2) { FloatArray(combN) }
    private var cw = 0
    private val combDelay = floatArrayOf(100f, 100f)
    private val lfoPhase = floatArrayOf(0f, 0f)
    private val lfoVal = floatArrayOf(0f, 0f)
    private val shVal = floatArrayOf(0f, 0f)
    private var env = 0f
    private var seed = 22222

    private fun rnd(): Float {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        return (seed and 0xFFFF) / 32768f - 1f
    }

    private fun lfo(ch: Int, ph: Float, kind: Int): Float = when (kind) {
        0 -> sin(2f * PI.toFloat() * ph)
        1 -> if (ph < 0.5f) 4f * ph - 1f else 3f - 4f * ph
        2 -> 2f * ph - 1f
        3 -> if (ph < 0.5f) 1f else -1f
        else -> shVal[ch]
    }

    // formants approximatifs (Hz) pour u, o, a, e, i
    private val vf1 = floatArrayOf(320f, 450f, 750f, 520f, 300f)
    private val vf2 = floatArrayOf(800f, 850f, 1150f, 1800f, 2300f)

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val t = type.value.toInt()
        val kind = shape.value.toInt()
        val baseOct = 0f
        val resV = res.value
        val drv = 1f + drive.value * 8f
        val comp = 1f / (1f + drive.value * 2.2f)
        val m = mix.value
        val dep = depth.value
        val envA = envAmt.value
        val rt = rate.value
        val st = stereo.value * 0.5f
        val maxFc = sr * 0.45f
        val chunk = 16
        val smoothC = 0.35f
        val envAtk = 1f - exp(-1f / (0.001f * sr))
        val envRel = 1f - exp(-1f / (0.08f * sr))

        var start = 0
        while (start < n) {
            val len = minOf(chunk, n - start)
            // enveloppe (mono) mesurée sur le début du bloc
            var peak = 0f
            for (i in start until start + len) {
                val a = 0.5f * (abs(l[i]) + abs(r[i]))
                if (a > peak) peak = a
            }
            env += (peak - env) * (if (peak > env) envAtk else envRel) * len
            if (env > 1.5f) env = 1.5f

            for (ch in 0..1) {
                var ph = lfoPhase[ch] + rt * len / sr
                if (ph >= 1f) {
                    ph -= 1f
                    if (kind == 4) shVal[ch] = rnd()
                }
                lfoPhase[ch] = ph
                val shifted = if (ch == 1) (ph + st).let { if (it >= 1f) it - 1f else it } else ph
                val target = lfo(ch, shifted, kind)
                lfoVal[ch] += (target - lfoVal[ch]) * smoothC
            }
            // coefficients par canal
            for (ch in 0..1) {
                val oct = baseOct + lfoVal[ch] * dep * 3f + env * envA * 4f
                val fc = (cutoff.value * 2f.pow(oct)).coerceIn(20f, maxFc)
                val g = tan(PI.toFloat() * fc / sr)
                val k = 2f - 1.95f * resV
                when (t) {
                    0, 1, 2, 3, 4 -> {
                        f1[ch].set(g, k)
                        f2[ch].set(g, k)
                    }
                    6 -> {
                        // la fréquence fait glisser la voyelle u→o→a→e→i
                        val pos = (ln(fc / 30f) / ln(18000f / 30f) * 4f).coerceIn(0f, 3.999f)
                        val ix = pos.toInt()
                        val fr = pos - ix
                        val a = vf1[ix] + (vf1[ix + 1] - vf1[ix]) * fr
                        val b = vf2[ix] + (vf2[ix + 1] - vf2[ix]) * fr
                        val kv = 2f - 1.85f * resV
                        f1[ch].set(tan(PI.toFloat() * a.coerceAtMost(maxFc) / sr), kv)
                        f2[ch].set(tan(PI.toFloat() * b.coerceAtMost(maxFc) / sr), kv)
                    }
                    5 -> combDelay[ch] += ((sr / fc).coerceIn(2f, combN - 4f) - combDelay[ch]) * 0.3f
                    else -> {
                        val tg = tan(PI.toFloat() * fc / sr)
                        f3[ch].a1 = (tg - 1f) / (tg + 1f)
                    }
                }
            }

            for (ch in 0..1) {
                val buf = if (ch == 0) l else r
                val a = f1[ch]
                val b = f2[ch]
                var cwi = cw
                for (i in start until start + len) {
                    val x = buf[i]
                    val xd = tanh(x * drv)
                    var y: Float
                    when (t) {
                        0 -> { a.tick(xd); y = a.lp }
                        1 -> { a.tick(xd); b.tick(a.lp); y = b.lp }
                        2 -> { a.tick(xd); y = a.hp }
                        3 -> { a.tick(xd); y = a.k * a.bp }
                        4 -> { a.tick(xd); y = xd - a.k * a.bp }
                        5 -> {
                            val d = combDelay[ch]
                            val pos = cwi - d
                            val ip = pos.toInt() - (if (pos < 0f) 1 else 0)
                            val fr = pos - ip
                            val cb = combBuf[ch]
                            val q = cb[ip and (combN - 1)]
                            val q2 = cb[(ip + 1) and (combN - 1)]
                            val dl = q + (q2 - q) * fr
                            y = xd + dl * (resV * 0.95f)
                            cb[cwi] = tanh(y)
                            y *= (1f - resV * 0.45f)
                        }
                        6 -> {
                            a.tick(xd)
                            b.tick(xd)
                            y = (a.k * a.bp + 0.7f * b.k * b.bp) * (1.4f + 1.5f * resV)
                        }
                        else -> {
                            val c = f3[ch].a1
                            val s = apS[ch]
                            var z = xd + apFb[ch] * (resV * 0.8f)
                            for (j in 0 until 4) {
                                val o = c * z + s[j]
                                s[j] = z - c * o
                                z = o
                            }
                            apFb[ch] = tanh(z)
                            y = 0.5f * (xd + z)
                        }
                    }
                    y *= comp
                    val ay = abs(y)
                    if (ay > 0.9f) y = (if (y > 0) 1f else -1f) * (0.9f + 0.1f * tanh((ay - 0.9f) / 0.1f))
                    buf[i] = x + (y - x) * m
                    cwi = (cwi + 1) and (combN - 1)
                }
                if (ch == 1) cw = cwi
            }
            start += len
        }
    }
}
