package com.rg406v.sampler.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Arpégiateur : joue en boucle les notes tenues sur les touches du piano roll, sur le pad sélectionné.
 * Les notes sont injectées dans la file d'événements du séquenceur, donc à l'échantillon près et
 * avec le même tempo.
 */
class Arp(private val seq: Sequencer, private val sampleRate: Int) {
    companion object {
        val RATE_STEPS = doubleArrayOf(4.0, 2.0, 1.0, 0.5, 4.0 / 3.0, 2.0 / 3.0)
    }

    var on by mutableStateOf(false)
    var latch by mutableStateOf(false)
    val mode = Param(
        "Mode", 0f, 5f, 0f, stepped = true,
        labels = listOf("montant", "descendant", "aller-retour", "retour-aller", "aléatoire", "ordre joué"),
    )
    val rate = Param("Vitesse", 0f, 5f, 2f, stepped = true, labels = listOf("1/4", "1/8", "1/16", "1/32", "1/8 triolet", "1/16 triolet"))
    val octaves = Param("Octaves", 1f, 4f, 1f, stepped = true, decimals = 0)
    val gate = Param("Gate", 0.1f, 1f, 0.6f)

    private val lock = Any()
    private var held = IntArray(32)
    private var count = 0
    private var pad = 0
    private var running = false
    private var next = 0.0
    private var idx = 0
    private var seed = 4242

    private var down = 0

    fun press(semis: Int, forPad: Int) {
        synchronized(lock) {
            // en mode latch, une nouvelle prise (aucune touche tenue) remplace l'ancien accord
            if (latch && down == 0) count = 0
            down++
            pad = forPad
            for (k in 0 until count) if (held[k] == semis) return
            if (count < held.size) held[count++] = semis
        }
    }

    fun release(semis: Int) {
        synchronized(lock) {
            if (down > 0) down--
            if (latch) return
            for (k in 0 until count) {
                if (held[k] == semis) {
                    for (j in k until count - 1) held[j] = held[j + 1]
                    count--
                    break
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            count = 0
            down = 0
        }
    }

    private fun rnd(): Int {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        return seed and 0x7FFFFFFF
    }

    /** Liste jouée : les notes tenues, étendues sur les octaves choisies. */
    private fun sequence(): IntArray {
        val base = held.copyOf(count)
        if (mode.value.toInt() != 5) base.sort()
        val oct = octaves.value.toInt().coerceIn(1, 4)
        val out = IntArray(base.size * oct)
        var k = 0
        for (o in 0 until oct) for (b in base) out[k++] = b + 12 * o
        return out
    }

    private fun pick(): Int {
        val s = sequence()
        val n = s.size
        if (n == 1) return s[0]
        val m = mode.value.toInt()
        val i = idx++
        return when (m) {
            0, 5 -> s[i % n]
            1 -> s[n - 1 - i % n]
            2, 3 -> {
                val per = 2 * (n - 1)
                val p = i % per
                val j = if (p < n) p else per - p
                if (m == 2) s[j] else s[n - 1 - j]
            }
            else -> s[rnd() % n]
        }
    }

    /** Appelé par le thread audio avant seq.advance() : pose les notes dues pendant ce bloc. */
    fun tick(n: Int, bpm: Float) {
        val now = seq.clock()
        synchronized(lock) {
            if (!on || count == 0) {
                running = false
                return
            }
            val stepLen = sampleRate * 15.0 / bpm
            val period = stepLen * RATE_STEPS[rate.value.toInt().coerceIn(0, RATE_STEPS.size - 1)]
            if (!running) {
                running = true
                idx = 0
                next = if (seq.playing) maxOf(seq.gridTime(), now) else now
            }
            val g = gate.value.toDouble()
            while (next < now + n) {
                val note = pick()
                seq.addArpEvent(next, pad, 0, note.toFloat())
                seq.addArpEvent(next + period * g, pad, 1, Float.NaN)
                next += period
            }
        }
    }
}
