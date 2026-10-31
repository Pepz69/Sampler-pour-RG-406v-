package com.rg406v.sampler.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

private const val IDLE = 0
private const val ATTACK = 1
private const val DECAY = 2
private const val SUSTAIN = 3
private const val RELEASE = 4

/** Une voix = un pad (monophonique par pad, re-déclenchable). Rendu appelé depuis le thread audio. */
class Voice(private val pad: PadParams) {
    @Volatile var trig = false
    @Volatile var rel = false
    @Volatile var kill = false

    private var pos = 0.0
    private var env = 0f
    private var stage = IDLE
    private var relStep = 0f

    fun render(l: FloatArray, r: FloatArray, n: Int, sr: Int) {
        if (kill) {
            kill = false; trig = false; rel = false
            stage = IDLE; env = 0f
            return
        }
        val s = pad.sample
        if (trig) {
            trig = false
            if (s != null) {
                pos = (pad.start.value * s.data.size).toDouble()
                stage = ATTACK
            }
        }
        if (rel) {
            rel = false
            if (stage != IDLE && stage != RELEASE) {
                relStep = env / (max(pad.release.value, 0.002f) * sr)
                stage = RELEASE
            }
        }
        if (stage == IDLE || s == null) return

        val data = s.data
        val len = data.size
        if (len < 2) { stage = IDLE; env = 0f; return }

        val startPos = (pad.start.value * len).toInt().coerceIn(0, len - 2)
        val endPos = (pad.end.value * len).toInt().coerceIn(startPos + 1, len)
        val loop = pad.loop
        val rate = 2.0.pow(pad.pitch.value / 12.0) * s.sampleRate / sr
        val atkInc = 1f / (pad.attack.value * sr)
        val sus = pad.sustain.value
        val decInc = (1f - sus) / (pad.decay.value * sr)
        val vol = pad.volume.value
        val ang = (pad.pan.value + 1f) * (PI.toFloat() / 4f)
        val gl = cos(ang) * vol
        val gr = sin(ang) * vol

        var p = pos.coerceAtLeast(startPos.toDouble())
        if (p >= endPos) {
            if (loop) p = startPos.toDouble() else { stage = IDLE; env = 0f; return }
        }
        var e = env
        var st = stage

        for (i in 0 until n) {
            when (st) {
                ATTACK -> { e += atkInc; if (e >= 1f) { e = 1f; st = DECAY } }
                DECAY -> { e -= decInc; if (e <= sus) { e = sus; st = SUSTAIN } }
                RELEASE -> { e -= relStep; if (e <= 0f) { e = 0f; st = IDLE } }
            }
            if (st == IDLE) break

            val i0 = p.toInt()
            val frac = (p - i0).toFloat()
            val i1 = if (i0 + 1 < endPos) i0 + 1 else if (loop) startPos else i0
            val x = (data[i0] + (data[i1] - data[i0]) * frac) * e
            l[i] += x * gl
            r[i] += x * gr

            p += rate
            if (p >= endPos) {
                if (loop) {
                    p = startPos + (p - startPos) % (endPos - startPos)
                } else {
                    st = IDLE; e = 0f
                    break
                }
            }
        }
        pos = p
        env = e
        stage = st
    }
}
