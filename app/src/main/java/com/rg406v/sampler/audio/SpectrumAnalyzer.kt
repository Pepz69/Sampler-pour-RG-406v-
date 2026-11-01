package com.rg406v.sampler.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Analyse le mix master : 96 bandes en échelle log (20 Hz - 20 kHz), valeurs normalisées 0..1. */
class SpectrumAnalyzer(private val sr: Int) {
    companion object {
        const val N = 1024
        const val BANDS = 96
    }

    val bins = FloatArray(BANDS)

    private val ring = FloatArray(N)
    private var wp = 0
    private var since = 0
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val hann = FloatArray(N) { (0.5 * (1.0 - cos(2.0 * PI * it / N))).toFloat() }
    private val lo = IntArray(BANDS)
    private val hi = IntArray(BANDS)

    init {
        for (j in 0 until BANDS) {
            val f0 = 20.0 * Math.pow(1000.0, j / BANDS.toDouble())
            val f1 = 20.0 * Math.pow(1000.0, (j + 1) / BANDS.toDouble())
            val b0 = (f0 * N / sr).toInt().coerceIn(1, N / 2 - 1)
            val b1 = (f1 * N / sr).toInt().coerceIn(b0, N / 2 - 1)
            lo[j] = b0
            hi[j] = b1
        }
    }

    fun feed(l: FloatArray, r: FloatArray, n: Int) {
        for (i in 0 until n) {
            ring[wp] = (l[i] + r[i]) * 0.5f
            wp = (wp + 1) and (N - 1)
            if (++since >= N / 2) { since = 0; analyze() }
        }
    }

    private fun analyze() {
        for (k in 0 until N) {
            re[k] = ring[(wp + k) and (N - 1)] * hann[k]
            im[k] = 0f
        }
        fft()
        for (j in 0 until BANDS) {
            var m = 0f
            for (b in lo[j]..hi[j]) m = max(m, sqrt(re[b] * re[b] + im[b] * im[b]))
            val db = 20f * log10(m / (N / 4f) + 1e-9f)
            val v = ((db + 80f) / 80f).coerceIn(0f, 1f)
            bins[j] = if (v > bins[j]) v else bins[j] * 0.88f + v * 0.12f
        }
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= N) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
