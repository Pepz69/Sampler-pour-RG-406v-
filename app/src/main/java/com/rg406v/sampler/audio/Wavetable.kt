package com.rg406v.sampler.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 32 wavetables générées par le code (aucune n'est copiée d'un synthé du commerce).
 * Chaque table = 32 images (morphing) de 1024 points, en 3 versions de plus en plus pauvres en harmoniques
 * pour éviter le repliement quand la note monte.
 */
object Wavetables {
    const val FRAMES = 32
    const val SIZE = 1024
    private const val HMAX = 64
    private val LIMITS = intArrayOf(64, 16, 4)

    class Table(val mip: Array<FloatArray>)

    private class Def(val name: String, val time: ((Float, Float) -> Float)?, val harm: ((Int, Float) -> Float)?)

    private val TWO_PI = (2.0 * PI).toFloat()
    private fun frac(x: Float) = x - floor(x)
    private fun sine(p: Float) = sin(TWO_PI * p)
    private fun saw(p: Float) = 2f * frac(p) - 1f
    private fun sqr(p: Float) = if (frac(p) < 0.5f) 1f else -1f
    private fun tri(p: Float): Float {
        val q = frac(p)
        return if (q < 0.5f) 4f * q - 1f else 3f - 4f * q
    }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun fold(x: Float): Float {
        val y = ((x + 1f) % 4f + 4f) % 4f
        return if (y > 2f) 3f - y else y - 1f
    }
    private fun gauss(x: Float, c: Float, w: Float): Float {
        val d = (x - c) / w
        return exp(-0.5f * d * d)
    }

    private fun hashF(h: Int, seed: Int): Float {
        var x = h * 374761393 + seed * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f
    }

    // voyelles (F1, F2) : u o a e i
    private val VF1 = floatArrayOf(300f, 450f, 750f, 520f, 300f)
    private val VF2 = floatArrayOf(800f, 850f, 1150f, 1800f, 2300f)

    private fun vowel(h: Int, m: Float, f0: Float, order: IntArray): Float {
        val pos = m * (order.size - 1)
        val i = pos.toInt().coerceIn(0, order.size - 2)
        val t = pos - i
        val f1 = lerp(VF1[order[i]], VF1[order[i + 1]], t)
        val f2 = lerp(VF2[order[i]], VF2[order[i + 1]], t)
        val f = h * f0
        return (1f / h) * (gauss(f, f1, 110f) + 0.6f * gauss(f, f2, 160f)) * 3f
    }

    private val DEFS: List<Def> = listOf(
        Def("Basique", { p, m ->
            when {
                m < 0.34f -> lerp(sine(p), tri(p), m / 0.34f)
                m < 0.67f -> lerp(tri(p), saw(p), (m - 0.34f) / 0.33f)
                else -> lerp(saw(p), sqr(p), (m - 0.67f) / 0.33f)
            }
        }, null),
        Def("Scie-Carré", { p, m -> lerp(saw(p), sqr(p), m) }, null),
        Def("PWM", { p, m -> if (frac(p) < 0.5f - 0.45f * m) 1f else -1f }, null),
        Def("Sync dur", { p, m -> saw(frac(p) * (1f + m * 7f)) }, null),
        Def("FM 1:2", { p, m -> sin(TWO_PI * p + 6f * m * sin(TWO_PI * 2f * p)) }, null),
        Def("FM 1:3", { p, m -> sin(TWO_PI * p + 5f * m * sin(TWO_PI * 3f * p)) }, null),
        Def("Repli", { p, m -> fold(sine(p) * (1f + m * 6f)) }, null),
        Def("Voyelles", null, { h, m -> vowel(h, m, 110f, intArrayOf(0, 1, 2, 3, 4)) }),
        Def("Orgue", null, { h, m ->
            val a = floatArrayOf(1f, 0.8f, 0.6f, 0.5f, 0f, 0.4f, 0f, 0.3f)
            val b = floatArrayOf(1f, 0f, 0.9f, 0f, 0.7f, 0f, 0.5f, 0.8f)
            if (h <= 8) lerp(a[h - 1], b[h - 1], m) else 0f
        }),
        Def("Cloche", null, { h, m ->
            val k = 2 + (m * 5f).toInt()
            if (h % k == 1) 1f / sqrt1(h) else 0.04f / h
        }),
        Def("Basse sale", { p, m -> tanh((1f + m * 9f) * sine(p)) }, null),
        Def("Peigne", null, { h, m -> (1f / h) * (0.55f + 0.45f * kotlin.math.cos(h * (0.3f + m * 2.2f))) }),
        Def("Filtre ouvert", null, { h, m -> (1f / h) * exp(-h * (0.02f + (1f - m) * 0.4f)) }),
        Def("Impair-pair", null, { h, m -> (1f / h) * (if (h % 2 == 1) 1f - m * 0.9f else m) }),
        Def("Aléa A", null, { h, m -> lerp(hashF(h, 1), hashF(h, 2), m) / (1f + h * 0.15f) }),
        Def("Aléa B", null, { h, m -> lerp(hashF(h, 3), hashF(h, 4), m) / (1f + h * 0.12f) }),
        Def("Aléa C", null, { h, m -> lerp(hashF(h, 5), hashF(h, 6), m) * (if (h % 2 == 0) 0.4f else 1f) / (1f + h * 0.1f) }),
        Def("Pincé", null, { h, m -> (1f / h) * exp(-h * m * 0.25f) }),
        Def("Ensemble", null, { h, m -> (1f / (h.toFloat().pow(1.2f))) * (1f + 0.5f * sin(h * m * 3f)) }),
        Def("Chœur", null, { h, m -> vowel(h, m, 130f, intArrayOf(1, 2, 0)) }),
        Def("Acide", null, { h, m -> 1f / h + 3f * gauss(h.toFloat(), 2f + m * 14f, 1.2f) }),
        Def("Sombre", { p, m -> 0.6f * fold(sine(p) * (1.5f + m * 4f)) + 0.4f * m * sine(2f * p) }, null),
        Def("Métal", { p, m -> sin(TWO_PI * p + (1f + m * 7f) * sin(TWO_PI * 7f * p)) }, null),
        Def("Verre", null, { h, m -> gauss(h.toFloat(), 5f + m * 14f, 2.2f) / (h.toFloat().pow(0.3f)) }),
        Def("Sub + harm.", null, { h, m ->
            when (h) {
                1 -> 1f
                2 -> 0.3f * m
                3 -> 0.8f * m
                5 -> 0.5f * m
                else -> 0f
            }
        }),
        Def("Pile de scies", { p, m -> (saw(p) + saw(2f * p) * m * 0.7f + saw(3f * p) * m * 0.5f) / (1f + m) }, null),
        Def("Triangle replié", { p, m -> fold(tri(p) * (1f + m * 4f)) }, null),
        Def("Déformation", { p, m -> sin(TWO_PI * frac(p).pow(1f + 3f * m)) }, null),
        Def("Phase", { p, m -> sin(TWO_PI * p + m * 4f * sin(TWO_PI * p)) }, null),
        Def("Formant", null, { h, m -> (1f / h) * gauss(h.toFloat(), 1f + m * 16f, 2.5f) + 0.1f / h }),
        Def("Anneau", { p, m -> sine(p) * sine(p * (2 + (m * 6f).toInt())) }, null),
        Def("Carrés empilés", { p, m -> (sqr(p) + m * 0.6f * sqr(p * 3f)) / (1f + 0.6f * m) }, null),
    )

    private fun sqrt1(h: Int) = kotlin.math.sqrt(h.toFloat())

    val NAMES: List<String> = DEFS.map { it.name }
    val COUNT: Int get() = DEFS.size

    private val SIN = FloatArray(SIZE) { sin(2.0 * PI * it / SIZE).toFloat() }
    private val cache = ConcurrentHashMap<Int, Table>()
    private val building = ConcurrentHashMap.newKeySet<Int>()

    /** Renvoie la table si elle est prête ; sinon lance sa fabrication (quelques ms) et renvoie null. */
    fun get(i: Int): Table? {
        val k = i.coerceIn(0, COUNT - 1)
        cache[k]?.let { return it }
        prefetch(k)
        return null
    }

    fun prefetch(i: Int) {
        val k = i.coerceIn(0, COUNT - 1)
        if (cache.containsKey(k)) return
        if (building.add(k)) {
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    cache[k] = build(k)
                } finally {
                    building.remove(k)
                }
            }.start()
        }
    }

    private fun build(idx: Int): Table {
        val def = DEFS[idx]
        val mips = Array(LIMITS.size) { FloatArray(FRAMES * SIZE) }
        val a = FloatArray(HMAX + 1)
        val b = FloatArray(HMAX + 1)
        val frame = FloatArray(SIZE)
        for (f in 0 until FRAMES) {
            val m = f / (FRAMES - 1f)
            java.util.Arrays.fill(a, 0f)
            java.util.Arrays.fill(b, 0f)
            val harm = def.harm
            if (harm != null) {
                for (h in 1..HMAX) a[h] = harm(h, m)
            } else {
                val fn = def.time!!
                for (i in 0 until SIZE) frame[i] = fn(i / SIZE.toFloat(), m)
                for (h in 1..HMAX) {
                    var sa = 0f
                    var sb = 0f
                    for (i in 0 until SIZE) {
                        val idxS = (h * i) and (SIZE - 1)
                        sa += frame[i] * SIN[idxS]
                        sb += frame[i] * SIN[(idxS + SIZE / 4) and (SIZE - 1)]
                    }
                    a[h] = sa * 2f / SIZE
                    b[h] = sb * 2f / SIZE
                }
            }
            // normalisation : on règle le gain sur la version complète
            var peak = 0f
            for (i in 0 until SIZE) {
                var s = 0f
                for (h in 1..HMAX) {
                    val j = (h * i) and (SIZE - 1)
                    s += a[h] * SIN[j] + b[h] * SIN[(j + SIZE / 4) and (SIZE - 1)]
                }
                peak = max(peak, abs(s))
            }
            val g = if (peak > 1e-6f) 0.9f / peak else 0f
            for (mi in LIMITS.indices) {
                val lim = LIMITS[mi]
                val out = mips[mi]
                for (i in 0 until SIZE) {
                    var s = 0f
                    for (h in 1..lim) {
                        val j = (h * i) and (SIZE - 1)
                        s += a[h] * SIN[j] + b[h] * SIN[(j + SIZE / 4) and (SIZE - 1)]
                    }
                    out[f * SIZE + i] = s * g
                }
            }
        }
        return Table(mips)
    }
}
