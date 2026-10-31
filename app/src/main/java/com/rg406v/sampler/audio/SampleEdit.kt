package com.rg406v.sampler.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Résultat d'une édition : nouvelles données + position de la sélection (fractions 0..1). */
class EditResult(val data: FloatArray, val start: Float, val end: Float)

/** Opérations d'édition hors-ligne sur un sample mono. Les indices a..b délimitent la sélection. */
object SampleEdit {
    private const val FADE = 48

    /** Garde uniquement la sélection. */
    fun crop(d: FloatArray, a: Int, b: Int) = EditResult(d.copyOfRange(a, b), 0f, 1f)

    /** Supprime la sélection et recolle les deux bords. */
    fun cutOut(d: FloatArray, a: Int, b: Int): EditResult? {
        val n = d.size - (b - a)
        if (n < 64) return null
        val out = FloatArray(n)
        System.arraycopy(d, 0, out, 0, a)
        System.arraycopy(d, b, out, a, d.size - b)
        for (k in 0 until FADE) {
            val g = k / FADE.toFloat()
            val ia = a - 1 - k
            if (ia >= 0) out[ia] *= g
            val ib = a + k
            if (ib < n) out[ib] *= g
        }
        return EditResult(out, 0f, 1f)
    }

    fun reverse(d: FloatArray, a: Int, b: Int): EditResult {
        val out = d.copyOf()
        var i = a
        var j = b - 1
        while (i < j) {
            val t = out[i]; out[i] = out[j]; out[j] = t
            i++; j--
        }
        return EditResult(out, a.toFloat() / d.size, b.toFloat() / d.size)
    }

    fun fade(d: FloatArray, a: Int, b: Int, fadeIn: Boolean): EditResult {
        val out = d.copyOf()
        val n = b - a
        for (k in 0 until n) {
            val g = k / n.toFloat()
            out[a + k] *= if (fadeIn) g else 1f - g
        }
        return EditResult(out, a.toFloat() / d.size, b.toFloat() / d.size)
    }

    fun normalize(d: FloatArray, a: Int, b: Int): EditResult? {
        var peak = 0f
        for (v in d) peak = max(peak, abs(v))
        if (peak < 1e-6f) return null
        val g = 0.95f / peak
        return EditResult(FloatArray(d.size) { d[it] * g }, a.toFloat() / d.size, b.toFloat() / d.size)
    }

    /** Enlève le silence au début et à la fin. */
    fun trimSilence(d: FloatArray): EditResult? {
        var peak = 0f
        for (v in d) peak = max(peak, abs(v))
        val thr = max(0.003f, peak * 0.02f)
        val first = d.indexOfFirst { abs(it) > thr }
        if (first < 0) return null
        val last = d.indexOfLast { abs(it) > thr }
        val a = max(0, first - 64)
        val b = min(d.size, last + 65)
        if (a == 0 && b == d.size) return null
        return crop(d, a, b)
    }

    /** Étire la sélection sans changer la hauteur (factor 2 = deux fois plus long). */
    fun stretchRegion(d: FloatArray, a: Int, b: Int, sr: Int, factor: Float): EditResult? {
        if (abs(factor - 1f) < 0.01f) return null
        return splice(d, a, b, stretch(d.copyOfRange(a, b), sr, factor))
    }

    /** Transpose la sélection en gardant sa durée. */
    fun pitchRegion(d: FloatArray, a: Int, b: Int, sr: Int, semitones: Float): EditResult? {
        if (abs(semitones) < 0.05f) return null
        val ratio = 2.0.pow(semitones / 12.0).toFloat()
        val seg = d.copyOfRange(a, b)
        val st = stretch(seg, sr, ratio)
        val res = FloatArray(seg.size) { i ->
            val p = i * ratio
            val i0 = p.toInt()
            when {
                i0 >= st.size -> 0f
                i0 + 1 >= st.size -> st[i0]
                else -> st[i0] + (st[i0 + 1] - st[i0]) * (p - i0)
            }
        }
        return splice(d, a, b, res)
    }

    /** Découpe la sélection en n parties égales. */
    fun slice(d: FloatArray, a: Int, b: Int, n: Int): List<FloatArray> {
        val len = b - a
        return List(n) { k ->
            val s = a + (len.toLong() * k / n).toInt()
            val e = a + (len.toLong() * (k + 1) / n).toInt()
            val part = d.copyOfRange(s, max(e, s + 1))
            val f = min(32, part.size / 2)
            for (j in 0 until f) {
                val g = j / f.toFloat()
                part[j] *= g
                part[part.size - 1 - j] *= g
            }
            part
        }
    }

    private fun splice(d: FloatArray, a: Int, b: Int, mid: FloatArray): EditResult {
        val out = FloatArray(a + mid.size + (d.size - b))
        System.arraycopy(d, 0, out, 0, a)
        System.arraycopy(mid, 0, out, a, mid.size)
        System.arraycopy(d, b, out, a + mid.size, d.size - b)
        val f = min(FADE, mid.size / 2)
        if (a > 0) for (k in 0 until f) out[a + k] *= k / f.toFloat()
        if (b < d.size) for (k in 0 until f) out[a + mid.size - 1 - k] *= k / f.toFloat()
        return EditResult(out, a.toFloat() / out.size, (a + mid.size).toFloat() / out.size)
    }

    /** Time-stretch WSOLA : recouvrement de fenêtres choisies par corrélation. */
    fun stretch(x: FloatArray, sr: Int, factor: Float): FloatArray {
        val f = factor.coerceIn(0.25f, 4f)
        val win = max(512, (sr * 0.046f).toInt() / 2 * 2)
        val hopS = win / 2
        val outLen = max(win, (x.size * f).toInt())
        val src = FloatArray(x.size + win * 3)
        System.arraycopy(x, 0, src, 0, x.size)
        val out = FloatArray(outLen + win)
        val norm = FloatArray(outLen + win)
        val window = FloatArray(win) { (0.5 * (1.0 - cos(2.0 * PI * it / win))).toFloat() }
        val hopA = hopS / f
        val tol = hopS / 2

        var prev = 0
        var outPos = 0
        var k = 0
        while (outPos < outLen) {
            var pos = 0
            if (k > 0) {
                val nominal = (k * hopA).toInt()
                val hi = min(x.size - 1, nominal + tol)
                val lo = min(max(0, nominal - tol), hi)
                val tgt = prev + hopS
                var best = lo
                var bestC = -Float.MAX_VALUE
                var cand = lo
                while (cand <= hi) {
                    var c = 0f
                    var e = 1e-9f
                    var j = 0
                    while (j < hopS) {
                        val v = src[cand + j]
                        c += src[tgt + j] * v
                        e += v * v
                        j += 4
                    }
                    val score = c / sqrt(e)
                    if (score > bestC) { bestC = score; best = cand }
                    cand += 4
                }
                pos = best
            }
            for (j in 0 until win) {
                out[outPos + j] += src[pos + j] * window[j]
                norm[outPos + j] += window[j]
            }
            prev = pos
            outPos += hopS
            k++
        }
        for (i in 0 until outLen) out[i] = if (norm[i] > 1e-3f) out[i] / norm[i] else 0f
        return out.copyOf(outLen)
    }
}
