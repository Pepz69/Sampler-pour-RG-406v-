package com.rg406v.sampler.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/** Effet stéréo traité en place, bloc par bloc, sur le thread audio. */
abstract class Effect(val name: String, val short: String) {
    var enabled by mutableStateOf(false)
    abstract val params: List<Param>

    /** Tous les paramètres à sauvegarder (par défaut : ceux affichés). */
    open val persistParams: List<Param> get() = params
    abstract fun process(l: FloatArray, r: FloatArray, n: Int)
}

// ---------------------------------------------------------------- Filtre (SVF)
class FilterFx(private val sr: Int) : Effect("Filtre", "Flt") {
    val cutoff = Param("Cutoff", 30f, 18000f, 1200f, log = true, unit = "Hz")
    val res = Param("Reso", 0f, 1f, 0.3f)
    val type = Param("Type", 0f, 2f, 0f, stepped = true, labels = listOf("LP", "HP", "BP"))
    override val params = listOf(cutoff, res, type)

    private val ic1 = FloatArray(2)
    private val ic2 = FloatArray(2)

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val fc = cutoff.value.coerceAtMost(sr * 0.45f)
        val g = tan(PI.toFloat() * fc / sr)
        val k = 2f - 1.95f * res.value
        val a1 = 1f / (1f + g * (g + k))
        val a2 = g * a1
        val a3 = g * a2
        val t = type.value.toInt()
        for (ch in 0..1) {
            val buf = if (ch == 0) l else r
            var s1 = ic1[ch]
            var s2 = ic2[ch]
            for (i in 0 until n) {
                val x = buf[i]
                val v3 = x - s2
                val v1 = a1 * s1 + a2 * v3
                val v2 = s2 + a2 * s1 + a3 * v3
                s1 = 2f * v1 - s1
                s2 = 2f * v2 - s2
                buf[i] = when (t) {
                    0 -> v2
                    1 -> x - k * v1 - v2
                    else -> k * v1
                }
            }
            ic1[ch] = s1
            ic2[ch] = s2
        }
    }
}

// ---------------------------------------------------------------- Distorsion
class DistortionFx(private val sr: Int) : Effect("Distorsion", "Dst") {
    val drive = Param("Drive", 0f, 1f, 0.5f)
    val tone = Param("Tone", 500f, 16000f, 6000f, log = true, unit = "Hz")
    val mix = Param("Mix", 0f, 1f, 1f)
    override val params = listOf(drive, tone, mix)

    private val lp = FloatArray(2)

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val gain = 10f.pow(drive.value * 2f)
        val a = exp(-2f * PI.toFloat() * tone.value / sr)
        val mx = mix.value
        for (ch in 0..1) {
            val buf = if (ch == 0) l else r
            var s = lp[ch]
            for (i in 0 until n) {
                val x = buf[i]
                val y = tanh(x * gain) * 0.6f
                s = (1f - a) * y + a * s
                buf[i] = x * (1f - mx) + s * mx
            }
            lp[ch] = s
        }
    }
}

// ---------------------------------------------------------------- Bitcrusher
class BitcrusherFx : Effect("Crusher", "Crush") {
    val bits = Param("Bits", 2f, 16f, 8f, stepped = true, unit = " bit", decimals = 0)
    val down = Param("Downsample", 1f, 40f, 4f, stepped = true, unit = "x", decimals = 0)
    val mix = Param("Mix", 0f, 1f, 1f)
    override val params = listOf(bits, down, mix)

    private val held = FloatArray(2)
    private var cnt = 0

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val levels = 2f.pow(bits.value - 1f)
        val ds = down.value.toInt().coerceAtLeast(1)
        val mx = mix.value
        for (i in 0 until n) {
            if (cnt == 0) {
                held[0] = round(l[i] * levels) / levels
                held[1] = round(r[i] * levels) / levels
            }
            cnt++
            if (cnt >= ds) cnt = 0
            l[i] = l[i] * (1f - mx) + held[0] * mx
            r[i] = r[i] * (1f - mx) + held[1] * mx
        }
    }
}

// ---------------------------------------------------------------- Chorus
class ChorusFx(private val sr: Int) : Effect("Chorus", "Cho") {
    val rate = Param("Rate", 0.05f, 8f, 0.8f, log = true, unit = " Hz")
    val depth = Param("Depth", 0f, 1f, 0.5f)
    val mix = Param("Mix", 0f, 1f, 0.5f)
    override val params = listOf(rate, depth, mix)

    private val size = 4096
    private val mask = size - 1
    private val bl = FloatArray(size)
    private val br = FloatArray(size)
    private var w = 0
    private var ph = 0f

    private fun read(b: FloatArray, pos: Float): Float {
        val fl = floor(pos)
        val i0 = fl.toInt()
        val f = pos - fl
        val a = b[i0 and mask]
        val c = b[(i0 + 1) and mask]
        return a + (c - a) * f
    }

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val inc = rate.value / sr
        val dep = depth.value * 8f
        val mx = mix.value
        val twoPi = 2f * PI.toFloat()
        for (i in 0 until n) {
            bl[w] = l[i]
            br[w] = r[i]
            val lfoL = 0.5f + 0.5f * sin(twoPi * ph)
            var ph2 = ph + 0.25f
            if (ph2 >= 1f) ph2 -= 1f
            val lfoR = 0.5f + 0.5f * sin(twoPi * ph2)
            val dL = (12f + dep * lfoL) * sr / 1000f
            val dR = (12f + dep * lfoR) * sr / 1000f
            val wl = read(bl, w - dL)
            val wr = read(br, w - dR)
            l[i] = l[i] * (1f - 0.5f * mx) + wl * mx * 0.7f
            r[i] = r[i] * (1f - 0.5f * mx) + wr * mx * 0.7f
            ph += inc
            if (ph >= 1f) ph -= 1f
            w = (w + 1) and mask
        }
    }
}

// ---------------------------------------------------------------- Delay (ping-pong)
class DelayFx(private val sr: Int) : Effect("Delay", "Dly") {
    val time = Param("Time", 20f, 1500f, 350f, log = true, unit = " ms", decimals = 0)
    val feedback = Param("Feedback", 0f, 0.95f, 0.4f)
    val mix = Param("Mix", 0f, 1f, 0.35f)
    override val params = listOf(time, feedback, mix)

    private val size = sr * 2
    private val bl = FloatArray(size)
    private val br = FloatArray(size)
    private var w = 0
    private var cur = 0f

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val tgt = (time.value * sr / 1000f).coerceIn(1f, (size - 4).toFloat())
        if (cur <= 0f) cur = tgt
        val fb = feedback.value
        val mx = mix.value
        for (i in 0 until n) {
            cur += (tgt - cur) * 0.0008f
            var rp = w - cur
            if (rp < 0f) rp += size
            val i0 = rp.toInt().coerceIn(0, size - 1)
            val f = rp - i0
            val i1 = if (i0 + 1 >= size) 0 else i0 + 1
            val dl = bl[i0] + (bl[i1] - bl[i0]) * f
            val dr = br[i0] + (br[i1] - br[i0]) * f
            val inp = (l[i] + r[i]) * 0.5f
            var nl = inp + dr * fb
            var nr = dl * fb
            if (abs(nl) < 1e-15f) nl = 0f
            if (abs(nr) < 1e-15f) nr = 0f
            bl[w] = nl
            br[w] = nr
            l[i] += dl * mx
            r[i] += dr * mx
            w++
            if (w >= size) w = 0
        }
    }
}

// ---------------------------------------------------------------- Reverb (Freeverb)
class ReverbFx(sr: Int) : Effect("Reverb", "Rev") {
    val size = Param("Size", 0f, 1f, 0.6f)
    val damp = Param("Damp", 0f, 1f, 0.4f)
    val mix = Param("Mix", 0f, 1f, 0.3f)
    override val params = listOf(size, damp, mix)

    private class Comb(len: Int) {
        val buf = FloatArray(len)
        var idx = 0
        var store = 0f
        fun process(input: Float, fb: Float, damp: Float): Float {
            val out = buf[idx]
            store = out * (1f - damp) + store * damp
            if (abs(store) < 1e-15f) store = 0f
            buf[idx] = input + store * fb
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private class Allpass(len: Int) {
        val buf = FloatArray(len)
        var idx = 0
        fun process(input: Float): Float {
            val b = buf[idx]
            val out = -input + b
            buf[idx] = input + b * 0.5f
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private val combTune = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTune = intArrayOf(556, 441, 341, 225)
    private val sc = sr / 44100f
    private val combL = Array(8) { Comb((combTune[it] * sc).toInt()) }
    private val combR = Array(8) { Comb(((combTune[it] + 23) * sc).toInt()) }
    private val apL = Array(4) { Allpass((apTune[it] * sc).toInt()) }
    private val apR = Array(4) { Allpass(((apTune[it] + 23) * sc).toInt()) }

    override fun process(l: FloatArray, r: FloatArray, n: Int) {
        val fb = 0.7f + 0.28f * size.value
        val d = damp.value * 0.4f
        val mx = mix.value
        for (i in 0 until n) {
            val input = (l[i] + r[i]) * 0.015f
            var oL = 0f
            var oR = 0f
            for (c in 0 until 8) {
                oL += combL[c].process(input, fb, d)
                oR += combR[c].process(input, fb, d)
            }
            for (a in 0 until 4) {
                oL = apL[a].process(oL)
                oR = apR[a].process(oR)
            }
            l[i] = l[i] * (1f - 0.5f * mx) + oL * mx * 3f
            r[i] = r[i] * (1f - 0.5f * mx) + oR * mx * 3f
        }
    }
}
