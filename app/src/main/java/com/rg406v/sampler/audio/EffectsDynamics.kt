package com.rg406v.sampler.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ================================================================ EQ paramétrique (6 bandes)

class EqBand(val index: Int, defType: Int, defFreq: Float, defOn: Boolean) {
    val type = Param(
        "Type", 0f, 5f, defType.toFloat(), stepped = true,
        labels = listOf("bell", "low shelf", "high shelf", "low cut", "high cut", "notch"),
    )
    val freq = Param("Freq", 20f, 20000f, defFreq, log = true, unit = "Hz")
    val gain = Param("Gain", -18f, 18f, 0f, unit = " dB", decimals = 1)
    val q = Param("Q", 0.2f, 12f, 1f, log = true, decimals = 2)
    val onP = Param("On", 0f, 1f, if (defOn) 1f else 0f, stepped = true)
    var on: Boolean
        get() = onP.value >= 0.5f
        set(v) { onP.value = if (v) 1f else 0f }

    val z1 = DoubleArray(2)
    val z2 = DoubleArray(2)
    val c = DoubleArray(5)

    fun usesGain() = type.value.toInt() <= 2
}

class EqFx(private val sr: Int) : Effect("EQ paramétrique", "EQ") {
    companion object {
        val BAND_COLORS = longArrayOf(0xFF5CE1A1, 0xFF8B6CFF, 0xFF4FD1FF, 0xFFFFB45C, 0xFFFF5C8A, 0xFF6C8CFF)

        /** Coefficients biquad RBJ normalisés : b0, b1, b2, a1, a2. */
        fun coeffs(type: Int, f: Float, gainDb: Float, q: Float, sr: Int, out: DoubleArray) {
            val w0 = 2.0 * PI * f.coerceAtMost(sr * 0.49f) / sr
            val cw = cos(w0)
            val sw = sin(w0)
            val a = 10.0.pow(gainDb / 40.0)
            val alpha = sw / (2.0 * q)
            val sq = 2.0 * sqrt(a) * alpha
            val b0: Double; val b1: Double; val b2: Double
            val a0: Double; val a1: Double; val a2: Double
            when (type) {
                0 -> {
                    b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
                }
                1 -> {
                    b0 = a * ((a + 1) - (a - 1) * cw + sq)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - sq)
                    a0 = (a + 1) + (a - 1) * cw + sq
                    a1 = -2 * ((a - 1) + (a + 1) * cw)
                    a2 = (a + 1) + (a - 1) * cw - sq
                }
                2 -> {
                    b0 = a * ((a + 1) + (a - 1) * cw + sq)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - sq)
                    a0 = (a + 1) - (a - 1) * cw + sq
                    a1 = 2 * ((a - 1) - (a + 1) * cw)
                    a2 = (a + 1) - (a - 1) * cw - sq
                }
                3 -> {
                    b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                4 -> {
                    b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                else -> {
                    b0 = 1.0; b1 = -2 * cw; b2 = 1.0
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
            }
            out[0] = b0 / a0; out[1] = b1 / a0; out[2] = b2 / a0
            out[3] = a1 / a0; out[4] = a2 / a0
        }

        /** Réponse en dB d'un biquad à la pulsation w (radians/échantillon). */
        fun magDb(c: DoubleArray, w: Double): Double {
            val cw = cos(w); val sw = sin(w)
            val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = c[0] + c[1] * cw + c[2] * c2
            val ni = -(c[1] * sw + c[2] * s2)
            val dr = 1.0 + c[3] * cw + c[4] * c2
            val di = -(c[3] * sw + c[4] * s2)
            return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di) + 1e-20)
        }
    }

    val bands = listOf(
        EqBand(0, 3, 40f, false),
        EqBand(1, 1, 120f, true),
        EqBand(2, 0, 500f, true),
        EqBand(3, 0, 2000f, true),
        EqBand(4, 2, 8000f, true),
        EqBand(5, 4, 16000f, false),
    )
    var selBand by mutableIntStateOf(2)
    val outGain = Param("Output", -12f, 12f, 0f, unit = " dB", decimals = 1)

    override val persistParams: List<Param>
        get() = bands.flatMap { listOf(it.onP, it.type, it.freq, it.gain, it.q) } + outGain

    /** Les sticks règlent la bande sélectionnée. */
    override val params: List<Param>
        get() {
            val b = bands[selBand.coerceIn(0, bands.size - 1)]
            return listOf(b.freq, b.gain, b.q, outGain)
        }

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (b in bands) {
            if (!b.on) continue
            if (b.usesGain() && abs(b.gain.eff) < 0.01f) continue
            coeffs(b.type.eff.toInt(), b.freq.eff, b.gain.eff, b.q.eff, sr, b.c)
            val c = b.c
            for (ch in 0..1) {
                val buf = if (ch == 0) l else r
                var z1 = b.z1[ch]
                var z2 = b.z2[ch]
                for (i in 0 until n) {
                    val x = buf[i].toDouble()
                    val y = c[0] * x + z1
                    z1 = c[1] * x - c[3] * y + z2
                    z2 = c[2] * x - c[4] * y
                    buf[i] = y.toFloat()
                }
                b.z1[ch] = z1
                b.z2[ch] = z2
            }
        }
        val og = 10f.pow(outGain.eff / 20f)
        if (og != 1f) for (i in 0 until n) { l[i] *= og; r[i] *= og }
    }

    /** Courbe de réponse totale (dB), 20 Hz - 20 kHz en échelle log, pour l'affichage. */
    fun curve(out: FloatArray) {
        val tmp = DoubleArray(5)
        java.util.Arrays.fill(out, 0f)
        for (b in bands) {
            if (!b.on) continue
            coeffs(b.type.value.toInt(), b.freq.value, b.gain.value, b.q.value, sr, tmp)
            for (i in out.indices) {
                val f = 20.0 * 1000.0.pow(i / (out.size - 1.0))
                out[i] += magDb(tmp, 2.0 * PI * f / sr).toFloat()
            }
        }
        val og = outGain.value
        for (i in out.indices) out[i] += og
    }
}

// ================================================================ Compresseur + sidechain déclenché par les notes

/**
 * Compresseur stéréo (feed-forward) avec un "side-kick" : à chaque note du pad choisi, le reste du signal
 * baisse puis remonte (groove de kick / basse). Sur la chaîne master, le pad kick lui-même n'est pas baissé.
 */
class CompressorFx(
    private val sr: Int,
    private val engine: AudioEngine,
    private val ownPad: Int,
) : Effect("Compresseur", "Cmp") {
    val threshold = Param("Thresh", -48f, 0f, -18f, unit = " dB", decimals = 0)
    val ratio = Param("Ratio", 1f, 20f, 4f, log = true, unit = ":1", decimals = 1)
    val attack = Param("Attack", 0.1f, 100f, 10f, log = true, unit = " ms", decimals = 1)
    val release = Param("Release", 10f, 1000f, 120f, log = true, unit = " ms", decimals = 0)
    val makeup = Param("Makeup", 0f, 24f, 0f, unit = " dB", decimals = 1)
    val mix = Param("Mix", 0f, 1f, 1f)
    val kick = Param("Kick", 0f, 16f, 0f, stepped = true, labels = listOf("off") + (1..16).map { "pad $it" })
    val duck = Param("Duck", 0f, 1f, 0.8f)
    val duckRel = Param("Duck rel", 30f, 800f, 220f, log = true, unit = " ms", decimals = 0)
    override val params = listOf(threshold, ratio, attack, release, makeup, mix, kick, duck, duckRel)

    /** Réduction de gain actuelle en dB (pour le vumètre). */
    @Volatile var reduction = 0f

    private var grDb = 0f
    private var duckEnv = 0f

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val th = threshold.eff
        val rt = ratio.eff
        val ac = exp(-1f / (sr * attack.eff * 0.001f))
        val rc = exp(-1f / (sr * release.eff * 0.001f))
        val mk = 10f.pow(makeup.eff / 20f)
        val mx = mix.eff
        val kp = kick.eff.toInt() - 1
        val duckAmt = if (kp >= 0 && kp != ownPad) duck.eff else 0f
        val dc = exp(-1f / (sr * duckRel.eff * 0.001f))
        val hit = if (kp >= 0) engine.duckHit[kp] else -1
        if (ownPad < 0) engine.keyCapturePad = kp
        val subtract = ownPad < 0 && kp >= 0
        val keyL = engine.keyL
        val keyR = engine.keyR

        for (i in 0 until n) {
            var xl = l[i]
            var xr = r[i]
            if (duckAmt > 0f) {
                if (i == hit) duckEnv = 1f
                val gd = 1f - duckAmt * duckEnv
                duckEnv *= dc
                if (subtract) {
                    val kl = keyL[i]
                    val kr = keyR[i]
                    xl = (xl - kl) * gd + kl
                    xr = (xr - kr) * gd + kr
                } else {
                    xl *= gd
                    xr *= gd
                }
            }
            val level = max(abs(xl), abs(xr))
            val over = 20f * log10(level + 1e-9f) - th
            val target = if (over > 0f) over * (1f - 1f / rt) else 0f
            grDb = if (target > grDb) ac * grDb + (1f - ac) * target else rc * grDb + (1f - rc) * target
            val g = 10f.pow(-grDb / 20f) * mk
            l[i] = xl * (1f - mx) + xl * g * mx
            r[i] = xr * (1f - mx) + xr * g * mx
        }
        reduction = grDb
    }
}
