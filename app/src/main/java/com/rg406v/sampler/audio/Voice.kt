package com.rg406v.sampler.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val IDLE = 0
private const val ATTACK = 1
private const val DECAY = 2
private const val SUSTAIN = 3
private const val RELEASE = 4

private const val MAXG = 48
private val HANN = FloatArray(1024) { (0.5 * (1.0 - cos(2.0 * PI * it / 1023.0))).toFloat() }
private val TWO_PI = 2.0 * PI

/**
 * Une voix = un pad. Rendu (sampler / granulaire / FM) appelé depuis le thread audio.
 * render() écrit dans l[off until off+n] : le moteur découpe les blocs aux instants de déclenchement.
 */
class Voice(private val pad: PadParams) {
    @Volatile var trig = false
    @Volatile var rel = false
    @Volatile var kill = false

    /** Décalage de hauteur (demi-tons) imposé par la gamme choisie. */
    @Volatile var keyOffset = 0f

    // Overrides d'une note (p-locks du séquenceur, piano roll). NaN = valeur du pad.
    @Volatile private var ovPitch = 0f
    @Volatile private var ovVol = 1f
    @Volatile private var ovPan = Float.NaN
    @Volatile private var ovStart = Float.NaN

    fun setLocks(pitch: Float, vol: Float, pan: Float, start: Float) {
        ovPitch = if (pitch.isNaN()) 0f else pitch
        ovVol = if (vol.isNaN()) 1f else vol
        ovPan = pan
        ovStart = start
    }

    private var env = 0f
    private var stage = IDLE
    private var relStep = 0f
    private var relSamples = 1f
    private var pAtk = 0f
    private var pDec = 0f
    private var pSus = 1f
    private var pAuto = false

    private var pos = 0.0

    private val gPos = DoubleArray(MAXG)
    private val gInc = DoubleArray(MAXG)
    private val gAge = IntArray(MAXG)
    private val gLen = IntArray(MAXG)
    private val gL = FloatArray(MAXG)
    private val gR = FloatArray(MAXG)
    private var gCount = 0f
    private var scanPos = 0f
    private var seed = 22222 + pad.index * 7919

    private val wtPh = DoubleArray(7)
    private val wtInc = DoubleArray(7)
    private val wtGl = FloatArray(7)
    private val wtGr = FloatArray(7)
    private var wtSubPh = 0.0
    private var wtLfo = 0.0

    private var phC = 0.0
    private var phM = 0.0
    private var lastM = 0f
    private var modEnv = 1f

    /** Voix en cours de jeu (ou sur le point de démarrer). */
    fun isActive(): Boolean = trig || stage != IDLE

    private fun startFrac(): Float {
        val o = ovStart
        return if (o.isNaN()) pad.start.eff else o
    }

    private fun rnd(): Float {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        return (seed ushr 8) / 16777216f
    }

    private fun stepEnv(): Boolean {
        when (stage) {
            ATTACK -> { env += pAtk; if (env >= 1f) { env = 1f; stage = DECAY } }
            DECAY -> {
                env -= pDec
                if (env <= pSus) {
                    env = pSus
                    if (pAuto) { relStep = env / relSamples; stage = RELEASE } else stage = SUSTAIN
                }
            }
            RELEASE -> { env -= relStep; if (env <= 0f) { env = 0f; stage = IDLE } }
        }
        return stage != IDLE
    }

    fun render(l: FloatArray, r: FloatArray, off: Int, n: Int, sr: Int) {
        if (kill) {
            kill = false; trig = false; rel = false
            stage = IDLE; env = 0f
            return
        }
        val mode = pad.mode.eff.toInt()
        val s = pad.sample

        if (trig) {
            trig = false
            if (mode >= 2 || s != null) {
                when (mode) {
                    0 -> pos = (startFrac() * s!!.data.size).toDouble()
                    1 -> { java.util.Arrays.fill(gLen, 0); gCount = 0f; scanPos = 0f }
                    2 -> { phC = 0.0; phM = 0.0; lastM = 0f; modEnv = 1f }
                    else -> {
                        for (k in 0 until 7) wtPh[k] = rnd().toDouble()
                        wtSubPh = 0.0
                        wtLfo = 0.0
                    }
                }
                stage = ATTACK
            }
        }
        if (rel) {
            rel = false
            if (stage != IDLE && stage != RELEASE) {
                relStep = env / (max(pad.release.eff, 0.002f) * sr)
                stage = RELEASE
            }
        }
        if (stage == IDLE) return
        if (mode < 2 && s == null) return

        pAtk = 1f / (pad.attack.eff * sr)
        pSus = pad.sustain.eff
        pDec = (1f - pSus) / (pad.decay.eff * sr)
        relSamples = max(pad.release.eff, 0.002f) * sr
        pAuto = mode >= 2 && !pad.gate

        val vol = pad.volume.eff * ovVol
        val panV = if (ovPan.isNaN()) pad.pan.eff else ovPan
        val ang = (panV + 1f) * (PI.toFloat() / 4f)
        val gl = cos(ang) * vol
        val gr = sin(ang) * vol

        when (mode) {
            0 -> renderSampler(l, r, off, n, sr, s!!, gl, gr)
            1 -> renderGranular(l, r, off, n, sr, s!!, vol, panV)
            2 -> renderFm(l, r, off, n, sr, gl, gr)
            else -> renderWt(l, r, off, n, sr, vol, panV)
        }
    }

    private fun renderSampler(l: FloatArray, r: FloatArray, off: Int, n: Int, sr: Int, s: Sample, gl: Float, gr: Float) {
        val data = s.data
        val len = data.size
        if (len < 2) { stage = IDLE; env = 0f; return }

        val startPos = (startFrac() * len).toInt().coerceIn(0, len - 2)
        val endPos = (pad.end.eff * len).toInt().coerceIn(startPos + 1, len)
        val loop = pad.loop
        val rate = 2.0.pow((pad.pitch.eff + keyOffset + ovPitch) / 12.0) * s.sampleRate / sr

        var p = pos.coerceAtLeast(startPos.toDouble())
        if (p >= endPos) {
            if (loop) p = startPos.toDouble() else { stage = IDLE; env = 0f; return }
        }

        for (i in 0 until n) {
            if (!stepEnv()) break
            val i0 = p.toInt()
            val frac = (p - i0).toFloat()
            val i1 = if (i0 + 1 < endPos) i0 + 1 else if (loop) startPos else i0
            val x = (data[i0] + (data[i1] - data[i0]) * frac) * env
            l[off + i] += x * gl
            r[off + i] += x * gr

            p += rate
            if (p >= endPos) {
                if (loop) {
                    p = startPos + (p - startPos) % (endPos - startPos)
                } else {
                    stage = IDLE; env = 0f
                    break
                }
            }
        }
        pos = p
    }

    private fun renderGranular(l: FloatArray, r: FloatArray, off: Int, n: Int, sr: Int, s: Sample, vol: Float, panBase: Float) {
        val data = s.data
        val len = data.size
        if (len < 2) { stage = IDLE; env = 0f; return }

        val startPos = (startFrac() * len).toInt().coerceIn(0, len - 2)
        val endPos = (pad.end.eff * len).toInt().coerceIn(startPos + 1, len)
        val region = (endPos - startPos).toFloat()

        val sizeSamp = max(32, (pad.gSize.eff * 0.001f * sr).toInt())
        val dens = pad.gDens.eff
        val interval = sr / dens
        val spray = pad.gSpray.eff
        val detune = pad.gDetune.eff
        val pos0 = pad.gPos.eff
        val baseRate = 2.0.pow((pad.pitch.eff + keyOffset + ovPitch) / 12.0) * s.sampleRate / sr
        val gain = vol / sqrt(max(1f, dens * sizeSamp / sr))

        scanPos += pad.gScan.eff * 0.2f * n / sr
        scanPos -= floor(scanPos)

        for (i in 0 until n) {
            if (!stepEnv()) break

            gCount -= 1f
            if (gCount <= 0f) {
                gCount += interval * (0.7f + 0.6f * rnd())
                var slot = -1
                for (g in 0 until MAXG) if (gLen[g] == 0) { slot = g; break }
                if (slot >= 0) {
                    var c = pos0 + scanPos + (rnd() * 2f - 1f) * spray * 0.5f
                    c -= floor(c)
                    gPos[slot] = startPos + c.toDouble() * region
                    gInc[slot] = baseRate * 2.0.pow(((rnd() * 2f - 1f) * detune / 12f).toDouble())
                    gAge[slot] = 0
                    gLen[slot] = sizeSamp
                    val pn = (panBase + (rnd() * 2f - 1f) * 0.6f).coerceIn(-1f, 1f)
                    val a = (pn + 1f) * (PI.toFloat() / 4f)
                    gL[slot] = cos(a)
                    gR[slot] = sin(a)
                }
            }

            var sumL = 0f
            var sumR = 0f
            for (g in 0 until MAXG) {
                val glen = gLen[g]
                if (glen == 0) continue
                val p = gPos[g]
                val i0 = p.toInt()
                if (i0 >= endPos - 1) { gLen[g] = 0; continue }
                val frac = (p - i0).toFloat()
                val x = (data[i0] + (data[i0 + 1] - data[i0]) * frac) * HANN[gAge[g] * 1023 / glen]
                sumL += x * gL[g]
                sumR += x * gR[g]
                gPos[g] = p + gInc[g]
                gAge[g]++
                if (gAge[g] >= glen) gLen[g] = 0
            }
            l[off + i] += sumL * gain * env
            r[off + i] += sumR * gain * env
        }
    }

    private fun renderFm(l: FloatArray, r: FloatArray, off: Int, n: Int, sr: Int, gl: Float, gr: Float) {
        val f = 220.0 * 2.0.pow((pad.pitch.eff + keyOffset + ovPitch) / 12.0)
        val incC = f / sr
        val incM = incC * pad.fmRatio.eff
        val idx = pad.fmIndex.eff
        val fb = pad.fmFeedback.eff * 3f
        val mdec = exp(-1.0 / (sr * pad.fmModDecay.eff)).toFloat()

        var pc = phC
        var pm = phM
        var lm = lastM
        var me = modEnv

        for (i in 0 until n) {
            if (!stepEnv()) break
            me *= mdec
            val m = sin(TWO_PI * pm + fb * lm).toFloat()
            lm = m
            val c = sin(TWO_PI * pc + idx * me * m).toFloat()
            pm += incM
            pm -= floor(pm)
            pc += incC
            pc -= floor(pc)
            val x = c * env * 0.5f
            l[off + i] += x * gl
            r[off + i] += x * gr
        }
        phC = pc
        phM = pm
        lastM = lm
        modEnv = me
    }

    private fun renderWt(l: FloatArray, r: FloatArray, off: Int, n: Int, sr: Int, vol: Float, panBase: Float) {
        val tbl = Wavetables.get(pad.wtTable.eff.toInt()) ?: return
        val f = 220.0 * 2.0.pow((pad.pitch.eff + keyOffset + ovPitch) / 12.0)
        val uni = pad.wtUni.eff.toInt().coerceIn(1, 7)
        val det = pad.wtDetune.eff
        val spread = pad.wtSpread.eff
        val warp = pad.wtWarp.eff
        val sub = pad.wtSub.eff
        val posBase = pad.wtPos.eff
        val depth = pad.wtDepth.eff
        val lfoInc = pad.wtMotion.eff.toDouble() / sr
        val data = tbl.mip[if (f < 300.0) 0 else if (f < 1200.0) 1 else 2]
        val norm = 0.5f / sqrt(uni.toFloat())

        for (k in 0 until uni) {
            val u = if (uni == 1) 0f else (k / (uni - 1f)) * 2f - 1f
            wtInc[k] = f * 2.0.pow((u * det / 1200f).toDouble()) / sr
            val pn = (panBase + u * spread).coerceIn(-1f, 1f)
            val a = (pn + 1f) * (PI.toFloat() / 4f)
            wtGl[k] = cos(a) * vol * norm
            wtGr[k] = sin(a) * vol * norm
        }
        val subInc = f / 2.0 / sr
        val size = Wavetables.SIZE
        val last = Wavetables.FRAMES - 1

        var fi = 0
        var ff = 0f
        var b0 = 0
        var b1 = 0
        for (i in 0 until n) {
            if (!stepEnv()) break
            wtLfo += lfoInc
            if (wtLfo >= 1.0) wtLfo -= 1.0
            if ((i and 15) == 0) {
                val pos = (posBase + depth * sin(TWO_PI * wtLfo).toFloat()).coerceIn(0f, 1f)
                val fp = pos * last
                fi = fp.toInt().coerceIn(0, last)
                ff = fp - fi
                b0 = fi * size
                b1 = (if (fi + 1 > last) last else fi + 1) * size
            }
            var sumL = 0f
            var sumR = 0f
            for (k in 0 until uni) {
                var ph = wtPh[k]
                var q = ph
                if (warp > 0f) {
                    q = ph * (1.0 + warp * 7.0)
                    q -= floor(q)
                }
                val x = q * size
                val i0 = x.toInt() and (size - 1)
                val fr = (x - floor(x)).toFloat()
                val i1 = (i0 + 1) and (size - 1)
                val a0 = data[b0 + i0]
                val a1 = data[b1 + i0]
                val s0 = a0 + (data[b0 + i1] - a0) * fr
                val s1 = a1 + (data[b1 + i1] - a1) * fr
                val s = s0 + (s1 - s0) * ff
                sumL += s * wtGl[k]
                sumR += s * wtGr[k]
                ph += wtInc[k]
                if (ph >= 1.0) ph -= 1.0
                wtPh[k] = ph
            }
            var sb = 0f
            if (sub > 0f) {
                sb = sin(TWO_PI * wtSubPh).toFloat() * sub * 0.4f * vol
                wtSubPh += subInc
                if (wtSubPh >= 1.0) wtSubPh -= 1.0
            }
            l[off + i] += (sumL + sb) * env
            r[off + i] += (sumR + sb) * env
        }
    }
}
