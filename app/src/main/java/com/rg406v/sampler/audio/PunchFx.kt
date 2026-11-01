package com.rg406v.sampler.audio

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Effets "punch-in" globaux : on les maintient enfoncés pendant le jeu (beat repeat, stutter, reverse,
 * tape stop, octaves, unison, filtre). Placés en fin de chaîne master.
 */
class PunchFx(private val sr: Int, private val bpm: () -> Float) {
    companion object {
        val NAMES = listOf(
            "loop 1/4", "loop 1/8", "loop 1/16", "loop 1/32",
            "stutter", "reverse", "tape stop", "octave +",
            "octave −", "unison", "filtre", "loop 1/2",
        )
    }

    val mix = Param("Punch", 0f, 1f, 1f)

    private val size = sr * 4
    private val ringL = FloatArray(size)
    private val ringR = FloatArray(size)
    private val loopL = FloatArray(size)
    private val loopR = FloatArray(size)
    private var wp = 0

    @Volatile private var heldMask = 0
    private val stamp = IntArray(12)
    private var stampCounter = 0

    private var active = -1
    private var cur = -1
    private var wet = 0f

    private var loopLen = 1
    private var readPos = 0
    private var gatePh = 0
    private var gateG = 0f
    private var tapeStart = 0
    private var tapePos = 0.0
    private var tapeSpeed = 1f
    private var ph1 = 0f
    private var uniPh = 0f
    private var fT = 0
    private var fG = 0.1f
    private val fS1 = FloatArray(2)
    private val fS2 = FloatArray(2)

    private var outL = 0f
    private var outR = 0f

    fun press(k: Int) = synchronized(this) {
        stamp[k] = ++stampCounter
        heldMask = heldMask or (1 shl k)
    }

    fun release(k: Int) = synchronized(this) {
        heldMask = heldMask and (1 shl k).inv()
    }

    fun releaseAll() = synchronized(this) { heldMask = 0 }

    fun isHeld(k: Int) = (heldMask shr k) and 1 == 1

    private fun wrap(i: Int) = ((i % size) + size) % size

    private fun readRing(buf: FloatArray, pos: Float): Float {
        val fl = floor(pos)
        val i0 = fl.toInt()
        val f = pos - fl
        val a = buf[wrap(i0)]
        val b = buf[wrap(i0 + 1)]
        return a + (b - a) * f
    }

    private fun trigger(k: Int) {
        val beat = (sr * 60f / bpm().coerceIn(40f, 300f)).toInt()
        when (k) {
            0, 1, 2, 3, 5, 11 -> {
                loopLen = when (k) {
                    0, 5 -> beat
                    1 -> beat / 2
                    2 -> beat / 4
                    3 -> beat / 8
                    else -> beat * 2
                }.coerceIn(64, size - 1)
                val start = wrap(wp - loopLen)
                for (i in 0 until loopLen) {
                    loopL[i] = ringL[wrap(start + i)]
                    loopR[i] = ringR[wrap(start + i)]
                }
                readPos = 0
            }
            4 -> { gatePh = 0; gateG = 1f }
            6 -> { tapeStart = wp; tapePos = 0.0; tapeSpeed = 1f }
            7, 8 -> { ph1 = 0f }
            9 -> { uniPh = 0f }
            10 -> { fT = 0; fG = 0.1f; fS1.fill(0f); fS2.fill(0f) }
        }
    }

    private fun fx(k: Int, inL: Float, inR: Float) {
        when (k) {
            0, 1, 2, 3, 11 -> {
                val a = min(readPos, 32)
                val b = min(loopLen - 1 - readPos, 32)
                val g = min(a, b) / 32f
                outL = loopL[readPos] * g
                outR = loopR[readPos] * g
                if (++readPos >= loopLen) readPos = 0
            }
            5 -> {
                val a = min(readPos, 32)
                val b = min(loopLen - 1 - readPos, 32)
                val g = min(a, b) / 32f
                val idx = loopLen - 1 - readPos
                outL = loopL[idx] * g
                outR = loopR[idx] * g
                if (++readPos >= loopLen) readPos = 0
            }
            4 -> {
                val period = max(64, (sr * 60f / bpm().coerceIn(40f, 300f) / 4f).toInt())
                gatePh++
                if (gatePh >= period) gatePh = 0
                val target = if (gatePh < period / 2) 1f else 0f
                gateG += (target - gateG) * 0.02f
                outL = inL * gateG
                outR = inR * gateG
            }
            6 -> {
                tapeSpeed = max(0f, tapeSpeed - 1f / (sr * 0.8f))
                val pos = (tapeStart + tapePos).toFloat()
                val g = min(1f, tapeSpeed * 4f)
                outL = readRing(ringL, pos) * g
                outR = readRing(ringR, pos) * g
                tapePos += tapeSpeed
            }
            7, 8 -> {
                val w = (0.05f * sr)
                val up = k == 7
                val dph = (if (up) -1f else 0.5f) / w
                var p2 = ph1 + 0.5f
                if (p2 >= 1f) p2 -= 1f
                val d1 = ph1 * w + 2f
                val d2 = p2 * w + 2f
                val w1 = sin(PI.toFloat() * ph1)
                val w2 = sin(PI.toFloat() * p2)
                outL = readRing(ringL, wp - d1) * w1 + readRing(ringL, wp - d2) * w2
                outR = readRing(ringR, wp - d1) * w1 + readRing(ringR, wp - d2) * w2
                ph1 += dph
                if (ph1 >= 1f) ph1 -= 1f
                if (ph1 < 0f) ph1 += 1f
            }
            9 -> {
                uniPh += 0.6f / sr
                if (uniPh >= 1f) uniPh -= 1f
                val t = 2f * PI.toFloat() * uniPh
                val d1 = (0.014f + 0.003f * sin(t)) * sr
                val d2 = (0.019f + 0.003f * sin(t + 1.57f)) * sr
                outL = inL * 0.75f + readRing(ringL, wp - d1) * 0.6f
                outR = inR * 0.75f + readRing(ringR, wp - d2) * 0.6f
            }
            else -> {
                if ((fT and 31) == 0) {
                    val tsec = fT / sr.toFloat()
                    val fc = 16000f * (250f / 16000f).pow(min(tsec / 0.9f, 1f))
                    fG = tan(PI.toFloat() * min(fc, sr * 0.45f) / sr)
                }
                fT++
                val kk = 1.2f
                val a1 = 1f / (1f + fG * (fG + kk))
                val a2 = fG * a1
                val a3 = fG * a2
                for (ch in 0..1) {
                    val x = if (ch == 0) inL else inR
                    val v3 = x - fS2[ch]
                    val v1 = a1 * fS1[ch] + a2 * v3
                    val v2 = fS2[ch] + a2 * fS1[ch] + a3 * v3
                    fS1[ch] = 2f * v1 - fS1[ch]
                    fS2[ch] = 2f * v2 - fS2[ch]
                    if (ch == 0) outL = v2 else outR = v2
                }
            }
        }
    }

    fun process(l: FloatArray, r: FloatArray, n: Int) {
        val mask = heldMask
        var desired = -1
        var best = -1
        for (k in 0 until 12) {
            if ((mask shr k) and 1 == 1 && stamp[k] > best) { best = stamp[k]; desired = k }
        }
        if (desired != active) {
            active = desired
            if (desired >= 0) { cur = desired; trigger(desired) }
        }
        val wetTarget = if (active >= 0) 1f else 0f
        val m = mix.value

        for (i in 0 until n) {
            val inL = l[i]
            val inR = r[i]
            ringL[wp] = inL
            ringR[wp] = inR
            if (cur >= 0 && (active >= 0 || wet > 0.001f)) {
                fx(cur, inL, inR)
                l[i] = inL + (outL - inL) * wet * m
                r[i] = inR + (outR - inR) * wet * m
            }
            wet += (wetTarget - wet) * 0.006f
            wp++
            if (wp >= size) wp = 0
        }
    }
}
