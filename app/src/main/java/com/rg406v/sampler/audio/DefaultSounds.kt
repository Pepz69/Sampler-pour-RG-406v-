package com.rg406v.sampler.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** 16 sons synthétisés pour que l'appli soit jouable dès le premier lancement. */
object DefaultSounds {
    private const val SR = 44100
    private val TWO_PI = (2.0 * PI).toFloat()
    private val rnd = Random(42)

    private fun nz() = rnd.nextFloat() * 2f - 1f
    private fun gen(n: Int, f: (t: Float, i: Int) -> Float) = FloatArray(n) { f(it / SR.toFloat(), it) }
    private fun secs(s: Float) = (s * SR).toInt()
    private fun sq(x: Float) = if (sin(x) >= 0f) 1f else -1f

    private fun noiseHp(seconds: Float, decay: Float, gain: Float): FloatArray {
        var prev = 0f
        return gen(secs(seconds)) { t, _ ->
            val x = nz()
            val y = x - prev
            prev = x
            y * exp(-t * decay) * gain
        }
    }

    fun build(): List<Sample> {
        val kick = gen(secs(0.45f)) { t, _ ->
            val ph = TWO_PI * (45f * t + 140f * (1f - exp(-t * 28f)) / 28f)
            sin(ph) * exp(-t * 7f) * 0.95f
        }
        val snare = gen(secs(0.3f)) { t, _ ->
            nz() * exp(-t * 16f) * 0.7f + sin(TWO_PI * 190f * t) * exp(-t * 28f) * 0.5f
        }
        val clap = gen(secs(0.3f)) { t, _ ->
            nz() * exp(-t * 18f) * (0.6f + 0.4f * abs(sin(TWO_PI * 50f * t))) * 0.8f
        }
        val tomL = gen(secs(0.45f)) { t, _ ->
            sin(TWO_PI * (70f * t + 40f * (1f - exp(-t * 15f)) / 15f)) * exp(-t * 7f) * 0.9f
        }
        val tomH = gen(secs(0.35f)) { t, _ ->
            sin(TWO_PI * (130f * t + 60f * (1f - exp(-t * 15f)) / 15f)) * exp(-t * 9f) * 0.9f
        }
        val rim = gen(secs(0.12f)) { t, _ ->
            (sin(TWO_PI * 1750f * t) + sin(TWO_PI * 480f * t) * 0.6f) * exp(-t * 90f) * 0.5f
        }
        // Boucles : longueur multiple de la période pour que la boucle soit propre.
        val bass = gen(800 * 28) { _, i ->
            val ph = TWO_PI * (i % 800) / 800f
            tanh(2.5f * sin(ph)) * 0.6f
        }
        val lead = gen(44100) { _, i ->
            var s = 0f
            for (k in 1..6) s += sin(TWO_PI * k * (i % 100) / 100f) / k
            s * 0.45f
        }
        val chordPartials = intArrayOf(2, 3, 4, 5, 6, 8)
        val chordAmps = floatArrayOf(1f, 0.7f, 0.8f, 0.6f, 0.6f, 0.3f)
        val chord = gen(400 * 220) { _, i ->
            var s = 0f
            for (j in chordPartials.indices) s += sin(TWO_PI * chordPartials[j] * (i % 400) / 400f) * chordAmps[j]
            s * 0.15f
        }
        val ksBuf = FloatArray(100) { nz() }
        var ksIdx = 0
        val pluck = gen(44100) { _, _ ->
            val o = ksBuf[ksIdx]
            val nxt = ksBuf[(ksIdx + 1) % 100]
            ksBuf[ksIdx] = (o + nxt) * 0.5f * 0.996f
            ksIdx = (ksIdx + 1) % 100
            o * 0.9f
        }
        val zap = gen(secs(0.35f)) { t, _ ->
            sin(TWO_PI * (80f * t + 2000f * (1f - exp(-t * 25f)) / 25f)) * exp(-t * 10f) * 0.8f
        }
        val bell = gen(secs(0.4f)) { t, _ ->
            (sq(TWO_PI * 540f * t) + sq(TWO_PI * 800f * t)) * exp(-t * 14f) * 0.25f
        }
        val crash = noiseHp(1.6f, 2.5f, 0.5f)
        val riser = run {
            var prev = 0f
            gen(secs(1.5f)) { t, _ ->
                val x = nz(); val y = x - prev; prev = x
                y * (t / 1.5f) * 0.5f
            }
        }

        return listOf(
            Sample("Kick", kick, SR),
            Sample("Snare", snare, SR),
            Sample("Hat fermé", noiseHp(0.08f, 70f, 0.6f), SR),
            Sample("Hat ouvert", noiseHp(0.45f, 9f, 0.5f), SR),
            Sample("Clap", clap, SR),
            Sample("Tom grave", tomL, SR),
            Sample("Tom aigu", tomH, SR),
            Sample("Rim", rim, SR),
            Sample("Basse", bass, SR),
            Sample("Lead", lead, SR),
            Sample("Accord", chord, SR),
            Sample("Pluck", pluck, SR),
            Sample("Zap", zap, SR),
            Sample("Cloche", bell, SR),
            Sample("Crash", crash, SR),
            Sample("Riser", riser, SR),
        )
    }
}
