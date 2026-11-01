package com.rg406v.sampler.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
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

    // ------------------------------------------------------------ banques (kits)

    class BuiltinKit(val name: String, val sounds: List<Sample>)

    /** classic, 808, lo-fi, synth : 16 sons chacune. */
    val kits: List<BuiltinKit> by lazy {
        val classic = build()
        listOf(
            BuiltinKit("classic", classic),
            BuiltinKit("808", build808()),
            BuiltinKit("lo-fi", lofi(classic)),
            BuiltinKit("synth", buildSynth()),
        )
    }

    private val METAL = floatArrayOf(205.3f, 304.4f, 369.6f, 522.7f, 540f, 800f)

    private fun metal(t: Float): Float {
        var acc = 0f
        for (f in METAL) acc += sq(TWO_PI * f * t)
        return acc / 6f
    }

    private fun metalHat(sec: Float, decay: Float): FloatArray =
        gen(secs(sec)) { t, _ -> metal(t) * exp(-t * decay) * 0.45f * min(1f, t * 800f) }

    private fun tom(f1: Float, f0: Float, decay: Float, sec: Float): FloatArray =
        gen(secs(sec)) { t, _ ->
            sin(TWO_PI * (f0 * t + (f1 - f0) * (1f - exp(-t * 18f)) / 18f)) * exp(-t * decay) * 0.9f
        }

    private fun build808(): List<Sample> {
        val kick = gen(secs(0.9f)) { t, _ ->
            val ph = TWO_PI * (42f * t + 120f * (1f - exp(-t * 30f)) / 30f)
            tanh(1.6f * sin(ph)) * exp(-t * 3.2f) * 0.9f
        }
        var prev = 0f
        val snare = gen(secs(0.35f)) { t, _ ->
            val x = nz(); val y = x - prev; prev = x
            y * exp(-t * 14f) * 0.55f + sin(TWO_PI * 185f * t) * exp(-t * 26f) * 0.6f
        }
        val clap = gen(secs(0.35f)) { t, _ ->
            var e = exp(-t * 14f) * 0.35f
            for (b in 0 until 3) {
                val tb = t - b * 0.011f
                if (tb >= 0f) e += exp(-tb * 70f)
            }
            nz() * e * 0.5f
        }
        val rim = gen(secs(0.1f)) { t, _ ->
            (sin(TWO_PI * 1650f * t) + sin(TWO_PI * 820f * t) * 0.7f) * exp(-t * 110f) * 0.5f
        }
        val cowbell = gen(secs(0.5f)) { t, _ ->
            (sq(TWO_PI * 560f * t) + sq(TWO_PI * 845f * t)) * exp(-t * 9f) * 0.22f
        }
        val congaH = gen(secs(0.3f)) { t, _ ->
            sin(TWO_PI * (330f * t + 30f * (1f - exp(-t * 40f)) / 40f)) * exp(-t * 16f) * 0.8f
        }
        val congaL = gen(secs(0.35f)) { t, _ ->
            sin(TWO_PI * (240f * t + 25f * (1f - exp(-t * 40f)) / 40f)) * exp(-t * 14f) * 0.8f
        }
        val clave = gen(secs(0.12f)) { t, _ -> sin(TWO_PI * 2500f * t) * exp(-t * 60f) * 0.6f }
        var prev2 = 0f
        val maracas = gen(secs(0.12f)) { t, _ ->
            val x = nz(); val y = x - prev2; prev2 = x
            y * (1f - exp(-t * 300f)) * exp(-t * 35f) * 0.5f
        }
        val bass = gen(800 * 40) { _, i -> tanh(1.8f * sin(TWO_PI * (i % 800) / 800f)) * 0.65f }
        return listOf(
            Sample("808 kick", kick, SR),
            Sample("808 snare", snare, SR),
            Sample("808 clap", clap, SR),
            Sample("808 hat", metalHat(0.09f, 60f), SR),
            Sample("808 open", metalHat(0.6f, 7f), SR),
            Sample("808 tom L", tom(110f, 78f, 5f, 0.6f), SR),
            Sample("808 tom M", tom(160f, 118f, 6f, 0.5f), SR),
            Sample("808 tom H", tom(230f, 175f, 7f, 0.45f), SR),
            Sample("808 rim", rim, SR),
            Sample("808 bell", cowbell, SR),
            Sample("conga H", congaH, SR),
            Sample("conga L", congaL, SR),
            Sample("clave", clave, SR),
            Sample("maracas", maracas, SR),
            Sample("cymbal", metalHat(1.6f, 2.2f), SR),
            Sample("808 bass", bass, SR),
        )
    }

    /** Version "cassette" des sons : 11 kHz, 7 bits, filtrés, un peu de bruit. */
    private fun lofi(src: List<Sample>): List<Sample> = src.map { s ->
        val d = s.data
        val out = FloatArray(d.size)
        var held = 0f
        var lp = 0f
        for (i in d.indices) {
            if (i % 4 == 0) held = Math.round(d[i] * 64f) / 64f
            lp += (held - lp) * 0.45f
            out[i] = lp + nz() * 0.004f
        }
        Sample("lo " + s.name, out, s.sampleRate)
    }

    private fun additive(freq: Float, amps: FloatArray, decay: Float, sec: Float, atk: Float = 0.004f): FloatArray =
        gen(secs(sec)) { t, _ ->
            var acc = 0f
            for (k in amps.indices) acc += amps[k] * sin(TWO_PI * freq * (k + 1) * t)
            acc * exp(-t * decay) * min(1f, t / atk) * 0.4f
        }

    private fun fmTone(freq: Float, ratio: Float, index: Float, decay: Float, sec: Float): FloatArray =
        gen(secs(sec)) { t, _ ->
            sin(TWO_PI * freq * t + index * exp(-t * decay * 1.5f) * sin(TWO_PI * freq * ratio * t)) *
                exp(-t * decay) * 0.6f
        }

    private fun ks(n: Int, sec: Float): FloatArray {
        val buf = FloatArray(n) { nz() }
        var idx = 0
        return gen(secs(sec)) { _, _ ->
            val o = buf[idx]
            val nxt = buf[(idx + 1) % n]
            buf[idx] = (o + nxt) * 0.5f * 0.996f
            idx = (idx + 1) % n
            o * 0.9f
        }
    }

    private fun saw(n: Int) = FloatArray(n) { 1f / (it + 1) }

    private fun buildSynth(): List<Sample> {
        val square = floatArrayOf(1f, 0f, 0.33f, 0f, 0.2f, 0f, 0.14f, 0f, 0.11f)
        val organ = floatArrayOf(1f, 0.8f, 0.6f, 0.4f, 0.3f, 0.2f)
        val pad = gen(secs(2.5f)) { t, _ ->
            var acc = 0f
            for (k in 1..6) acc += (sin(TWO_PI * 220f * k * t) + sin(TWO_PI * 221.6f * k * t)) / k
            acc * min(1f, t / 0.25f) * exp(-t * 0.7f) * 0.2f
        }
        val strings = gen(secs(2.2f)) { t, _ ->
            var acc = 0f
            for (k in 1..8) acc += (sin(TWO_PI * 165f * k * t) + sin(TWO_PI * 166.2f * k * t)) / (k * 1.3f)
            acc * min(1f, t / 0.3f) * exp(-t * 0.8f) * 0.2f
        }
        val stab = gen(secs(0.45f)) { t, _ ->
            var acc = 0f
            for (f in floatArrayOf(220f, 261.6f, 329.6f)) {
                for (k in 1..5) acc += sin(TWO_PI * f * k * t) / k
            }
            acc * exp(-t * 7f) * min(1f, t / 0.003f) * 0.18f
        }
        val zap = gen(secs(0.3f)) { t, _ ->
            sin(TWO_PI * (90f * t + 1400f * (1f - exp(-t * 22f)) / 22f)) * exp(-t * 11f) * 0.7f
        }
        val blip = gen(secs(0.16f)) { t, _ -> sq(TWO_PI * 880f * t) * exp(-t * 22f) * 0.25f }
        var prev = 0f
        val riser = gen(secs(1.6f)) { t, _ ->
            val x = nz(); val y = x - prev; prev = x
            y * (t / 1.6f) * 0.5f
        }
        return listOf(
            Sample("saw bass", additive(55f, saw(8), 3f, 0.8f), SR),
            Sample("sub", additive(41.2f, floatArrayOf(1f), 2.5f, 1.0f), SR),
            Sample("square", additive(220f, square, 2.5f, 0.9f), SR),
            Sample("saw lead", additive(330f, saw(8), 3f, 0.9f), SR),
            Sample("pluck A", ks(200, 1f), SR),
            Sample("pluck E", ks(134, 1f), SR),
            Sample("fm bell", fmTone(440f, 3.5f, 5f, 3f, 1.2f), SR),
            Sample("fm keys", fmTone(220f, 1f, 3f, 4f, 0.9f), SR),
            Sample("fm bass", fmTone(55f, 1f, 4f, 3f, 0.7f), SR),
            Sample("organ", additive(220f, organ, 1.2f, 1.2f, 0.01f), SR),
            Sample("pad", pad, SR),
            Sample("strings", strings, SR),
            Sample("stab", stab, SR),
            Sample("zap", zap, SR),
            Sample("blip", blip, SR),
            Sample("riser", riser, SR),
        )
    }
}
