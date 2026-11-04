package com.rg406v.sampler.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/**
 * 50 kits par genre (IDM, bass music, DnB, downtempo, techno, acid techno, dark psy), entièrement synthétisés
 * par le code : rien n'est échantillonné. Un kit = 16 sons (batterie, basses, synthés, textures, effets) et
 * parfois un pad en mode wavetable. Les sons sont fabriqués à la demande, quand on choisit le kit.
 */
object StyleKits {
    private const val SR = 44100
    private val TWO_PI = (2.0 * PI).toFloat()
    private val SINT = FloatArray(4096) { sin(2.0 * PI * it / 4096).toFloat() }

    /** Sinus à partir d'une phase en cycles. */
    private fun sn(cycles: Float): Float {
        val x = cycles - floor(cycles)
        return SINT[(x * 4096f).toInt() and 4095]
    }

    data class Spec(
        // grosse caisse
        val kF0: Float = 160f, val kF1: Float = 48f, val kPd: Float = 28f, val kDec: Float = 7f,
        val kDrv: Float = 1.6f, val kClk: Float = 0.25f, val kLen: Float = 0.45f,
        // caisse claire
        val sTone: Float = 190f, val sNoise: Float = 0.7f, val sDec: Float = 16f, val sBody: Float = 0.5f,
        // charleston
        val hDec: Float = 70f, val hoDec: Float = 9f, val hMetal: Float = 0.5f,
        // clap
        val cDec: Float = 16f, val cBursts: Int = 3, val cGap: Float = 0.011f,
        // percussions
        val p1F: Float = 180f, val p1Dec: Float = 12f, val p2F: Float = 420f, val p2Dec: Float = 20f, val pFm: Float = 0f,
        val rimF: Float = 1700f,
        // basse : 0 sinus, 1 scie, 2 carré, 3 fm, 4 acid, 5 reese, 6 wobble
        val bF: Float = 55f, val bWave: Int = 1, val bDrv: Float = 1.5f, val bRep: Int = 40, val bWob: Int = 3,
        val bCut: Float = 0.3f, val bRes: Float = 0.5f,
        // synthés : 0 pad, 1 drone sombre, 2 accord carré, 3 cloche fm, 4 cordes
        val syKind: Int = 0, val syRoot: Float = 110f, val syChord: IntArray = intArrayOf(0, 3, 7, 10),
        // stab : 0 scie, 1 carré, 2 fm, 3 pincé, 4 rave
        val stKind: Int = 1,
        // texture : 0 souffle, 1 craquements, 2 drone métallique, 3 vent
        val txKind: Int = 0,
        // fx : 0 impact, 1 zap descendant, 2 laser montant, 3 rafale de glitch, 4 caisse inversée
        val fxKind: Int = 0,
        // riser : 0 bruit + sinus, 1 sinus, 2 cymbale inversée, 3 pulsé
        val riseKind: Int = 0,
        // dernier pad : 0 blips, 1 voix, 2 crash inversé, 3 ticks métalliques
        val glKind: Int = 0,
        val grit: Float = 0f,
        // pad 12 en wavetable si wt >= 0
        val wt: Int = -1, val wtPos: Float = 0.3f, val wtUni: Int = 3, val wtDet: Float = 20f, val wtSub: Float = 0.3f,
        val seed: Int = 1,
    )

    class KitDef(val style: String, val name: String, val bpm: Int, val spec: Spec)

    // ------------------------------------------------------------ outils

    private fun secs(s: Float) = (s * SR).toInt()

    private inline fun gen(n: Int, f: (t: Float, i: Int) -> Float) = FloatArray(n) { f(it / SR.toFloat(), it) }

    private val METAL = floatArrayOf(205.3f, 304.4f, 369.6f, 522.7f, 540f, 800f)
    private fun metal(t: Float): Float {
        var a = 0f
        for (f in METAL) a += if (sn(f * t) >= 0f) 1f else -1f
        return a / 6f
    }

    private class Lp {
        var ic1 = 0f
        var ic2 = 0f
        fun run(x: Float, fc: Float, q: Float): Float {
            val f = fc.coerceIn(25f, 18000f)
            val g = tan(PI * f / SR).toFloat()
            val k = 1f / q
            val a1 = 1f / (1f + g * (g + k))
            val a2 = g * a1
            val a3 = g * a2
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            return v2
        }
    }

    private fun crush(d: FloatArray, amt: Float) {
        if (amt <= 0f) return
        val hold = 1 + (amt * 6f).toInt()
        val levels = 2f.pow(12f - amt * 6f)
        var held = 0f
        for (i in d.indices) {
            if (i % hold == 0) held = Math.round(d[i] * levels) / levels
            d[i] = held
        }
    }

    /** Fin de chaîne : grain éventuel, normalisation, petit fondu final. */
    private fun fin(d: FloatArray, sp: Spec, peak: Float = 0.9f, grit: Boolean = true, fade: Boolean = true): FloatArray {
        if (grit) crush(d, sp.grit)
        var m = 0f
        for (x in d) m = max(m, abs(x))
        val g = if (m > 1e-6f) peak / m else 0f
        for (i in d.indices) d[i] *= g
        if (fade) {
            val f = min(d.size / 4, secs(0.004f))
            for (i in 0 until f) d[d.size - 1 - i] *= i / f.toFloat()
        }
        return d
    }

    // ------------------------------------------------------------ batterie

    private fun kick(sp: Spec, rn: Random): FloatArray {
        val out = gen(secs(sp.kLen)) { t, _ ->
            val ph = sp.kF1 * t + (sp.kF0 - sp.kF1) * (1f - exp(-t * sp.kPd)) / sp.kPd
            var s = tanh(sp.kDrv * sin(TWO_PI * ph)) * exp(-t * sp.kDec)
            s += (rn.nextFloat() * 2f - 1f) * exp(-t * 600f) * sp.kClk
            s * min(1f, t * 1500f)
        }
        return fin(out, sp)
    }

    private fun snare(sp: Spec, rn: Random): FloatArray {
        var prev = 0f
        val out = gen(secs(0.4f)) { t, _ ->
            val x = rn.nextFloat() * 2f - 1f
            val y = x - prev * 0.6f
            prev = x
            y * exp(-t * sp.sDec) * sp.sNoise +
                sin(TWO_PI * (sp.sTone * t + 40f * (1f - exp(-t * 30f)) / 30f)) * exp(-t * sp.sDec * 1.6f) * sp.sBody
        }
        return fin(out, sp)
    }

    private fun hat(sp: Spec, rn: Random, dec: Float, sec: Float): FloatArray {
        var prev = 0f
        val out = gen(secs(sec)) { t, _ ->
            val x = rn.nextFloat() * 2f - 1f
            val y = x - prev
            prev = x
            (metal(t) * sp.hMetal + y * (1f - sp.hMetal)) * exp(-t * dec) * min(1f, t * 1000f)
        }
        return fin(out, sp, 0.7f)
    }

    private fun clap(sp: Spec, rn: Random): FloatArray {
        val out = gen(secs(0.35f)) { t, _ ->
            var e = exp(-t * sp.cDec) * 0.35f
            for (b in 0 until sp.cBursts) {
                val tb = t - b * sp.cGap
                if (tb >= 0f) e += exp(-tb * 80f)
            }
            (rn.nextFloat() * 2f - 1f) * e
        }
        return fin(out, sp, 0.8f)
    }

    private fun perc(sp: Spec, f: Float, dec: Float, fmRatio: Float, rn: Random): FloatArray {
        val out = if (sp.pFm > 0f) {
            gen(secs(0.5f)) { t, _ ->
                sin(TWO_PI * f * t + sp.pFm * exp(-t * dec) * sin(TWO_PI * f * fmRatio * t)) * exp(-t * dec)
            }
        } else {
            gen(secs(0.5f)) { t, _ ->
                sin(TWO_PI * (f * 0.7f * t + f * 0.5f * (1f - exp(-t * 20f)) / 20f)) * exp(-t * dec) +
                    (rn.nextFloat() * 2f - 1f) * exp(-t * 200f) * 0.15f
            }
        }
        return fin(out, sp, 0.85f)
    }

    private fun rim(sp: Spec): FloatArray {
        val out = gen(secs(0.12f)) { t, _ ->
            (sin(TWO_PI * sp.rimF * t) + sin(TWO_PI * sp.rimF * 0.46f * t) * 0.7f) * exp(-t * 95f)
        }
        return fin(out, sp, 0.7f)
    }

    // ------------------------------------------------------------ basses (boucles : cycles entiers)

    private fun bassLoop(sp: Spec): FloatArray {
        val p = max(24, (SR / sp.bF).roundToInt())
        val filtered = sp.bWave == 4 || sp.bWave == 6
        val reps = if (filtered || sp.bWave == 5) sp.bRep else max(1, (1.2f * sp.bF).toInt())
        val n = p * reps
        val hMax = max(2, min(20, (SR / 2f / sp.bF / 1.2f).toInt()))
        val raw = FloatArray(n)
        for (i in 0 until n) {
            val c = (i % p) / p.toFloat()
            raw[i] = when (sp.bWave) {
                0 -> sn(c)
                2 -> {
                    var s = 0f
                    var h = 1
                    while (h <= hMax) { s += sn(c * h) / h; h += 2 }
                    s * 0.9f
                }
                3 -> {
                    val lfo = 0.5f + 0.5f * sn(2f * i / n)
                    sn(c + (1f + 3f * lfo) * sn(2f * c) / TWO_PI)
                }
                5 -> {
                    var s = 0f
                    val ph = i.toFloat() / n
                    for (d in -1..1) {
                        val cc = ph * (reps + d)
                        for (h in 1..hMax) s += sn(cc * h) / h
                    }
                    s * 0.35f
                }
                else -> {
                    var s = 0f
                    for (h in 1..hMax) s += sn(c * h) / h
                    s * 0.6f
                }
            }
        }
        var out = raw
        if (filtered) {
            val lp = Lp()
            val q = 0.6f + sp.bRes * 9f
            out = FloatArray(n)
            for (pass in 0 until 2) {
                for (i in 0 until n) {
                    val ph = sp.bWob * i.toFloat() / n
                    val fc = if (sp.bWave == 4) {
                        val e = ph - floor(ph)
                        90f + 4200f * sp.bCut * exp(-4f * e)
                    } else {
                        val l = 0.5f + 0.5f * sn(ph)
                        80f + 5000f * sp.bCut * l * l
                    }
                    val y = lp.run(raw[i], fc, q)
                    if (pass == 1) out[i] = y
                }
            }
        }
        for (i in out.indices) out[i] = tanh(sp.bDrv * out[i])
        return fin(out, sp, 0.85f, grit = false, fade = false)
    }

    // ------------------------------------------------------------ synthés

    private fun noteF(sp: Spec, iv: Int) = sp.syRoot * 2f.pow(iv / 12f)

    private fun synthLoop(sp: Spec): FloatArray {
        val n = secs(2f)
        val out = FloatArray(n)
        val notes = sp.syChord
        for (i in 0 until n) {
            val ph = i.toFloat() / n
            var s = 0f
            when (sp.syKind) {
                1 -> {
                    val rootC = max(1, (noteF(sp, 0) * 2f * 0.5f).roundToInt())
                    s += sn(ph * rootC * 0.5f) * 0.8f + sn(ph * rootC) * 0.3f
                    for (iv in notes) {
                        val c = max(1, (noteF(sp, iv) * 2f).roundToInt())
                        s += (sn(ph * c) + 0.4f * sn(ph * (c + 1)) + 0.25f * sn(ph * c * 2)) * 0.25f
                    }
                    s *= 1f + 0.3f * sn(2f * ph)
                }
                2 -> for (iv in notes) {
                    val c = max(1, (noteF(sp, iv) * 2f).roundToInt())
                    var h = 1
                    while (h <= 9) { s += sn(ph * c * h) / h; h += 2 }
                }
                3 -> for (iv in notes) {
                    val c = max(1, (noteF(sp, iv) * 2f).roundToInt())
                    val beta = 1f + 2.5f * (0.5f + 0.5f * sn(ph))
                    s += sn(ph * c + beta * sn(ph * c * 3f) / TWO_PI) * 0.6f
                }
                4 -> for (iv in notes) {
                    val c = max(1, (noteF(sp, iv) * 2f).roundToInt()).toFloat()
                    val vib = 0.004f * sn(6f * ph)
                    for (h in 1..8) s += sn(ph * c * h + vib * h) / (h * 1.3f)
                }
                else -> for (iv in notes) {
                    val c = max(1, (noteF(sp, iv) * 2f).roundToInt())
                    for (h in 1..10) {
                        val a = 1f / h.toFloat().pow(1.4f)
                        s += (sn(ph * c * h) + sn(ph * (c + 1) * h)) * a
                    }
                }
            }
            out[i] = s
        }
        return fin(out, sp, 0.7f, grit = false, fade = false)
    }

    private fun stab(sp: Spec, rn: Random): FloatArray {
        val len = if (sp.stKind == 3) 1.0f else 0.6f
        val three = sp.syChord.take(3)
        val out = when (sp.stKind) {
            2 -> gen(secs(len)) { t, _ ->
                var s = 0f
                for (iv in three) {
                    val f = noteF(sp, iv) * 2f
                    s += sin(TWO_PI * f * t + 3f * exp(-t * 9f) * sin(TWO_PI * f * 2f * t)) * 0.4f
                }
                s * exp(-t * 6f) * min(1f, t * 400f)
            }
            3 -> {
                val f = noteF(sp, 0) * 2f
                val buf = FloatArray(max(8, (SR / f).toInt())) { rn.nextFloat() * 2f - 1f }
                var idx = 0
                gen(secs(len)) { _, _ ->
                    val o = buf[idx]
                    val nx = buf[(idx + 1) % buf.size]
                    buf[idx] = (o + nx) * 0.5f * 0.997f
                    idx = (idx + 1) % buf.size
                    o
                }
            }
            else -> gen(secs(len)) { t, _ ->
                var s = 0f
                for (iv in three) {
                    val f = noteF(sp, iv) * 2f
                    val maxH = max(1, min(14, (14000f / f).toInt()))
                    for (h in 1..maxH) {
                        if (sp.stKind == 1 && h % 2 == 0) continue
                        val det = if (sp.stKind == 4) 1.004f else 1f
                        s += (sn(f * h * t) + (if (sp.stKind == 4) sn(f * det * h * t) else 0f)) *
                            exp(-t * (3f + h * 0.5f)) / h
                    }
                }
                s * min(1f, t * 500f)
            }
        }
        return fin(out, sp, 0.8f)
    }

    // ------------------------------------------------------------ textures, fx

    private fun texture(sp: Spec, rn: Random): FloatArray {
        val n = secs(2f)
        val out = FloatArray(n)
        when (sp.txKind) {
            1 -> {
                var prev = 0f
                for (i in 0 until n) {
                    val x = rn.nextFloat() * 2f - 1f
                    var s = (x - prev) * 0.03f
                    prev = x
                    if (rn.nextFloat() < 0.0016f) s += (rn.nextFloat() * 2f - 1f) * (0.3f + rn.nextFloat() * 0.7f)
                    out[i] = s
                }
            }
            2 -> {
                val freqs = FloatArray(6) { (200f + rn.nextFloat() * 2200f) }
                val cyc = IntArray(6) { max(1, (freqs[it] * 2f).roundToInt()) }
                for (i in 0 until n) {
                    val ph = i.toFloat() / n
                    var s = 0f
                    for (k in 0 until 6) s += sn(ph * cyc[k]) * (0.5f + 0.5f * sn(ph * (k + 1) + k * 0.13f)) / (1f + k * 0.4f)
                    out[i] = s
                }
            }
            else -> {
                val lp = Lp()
                val q = if (sp.txKind == 3) 5f else 0.8f
                val raw = FloatArray(n) { rn.nextFloat() * 2f - 1f }
                for (pass in 0 until 2) {
                    for (i in 0 until n) {
                        val ph = i.toFloat() / n
                        val fc = if (sp.txKind == 3) 500f + 1800f * (0.5f + 0.5f * sn(ph)) else 300f + 2500f * (0.5f + 0.5f * sn(ph))
                        val y = lp.run(raw[i], fc, q)
                        if (pass == 1) out[i] = y
                    }
                }
            }
        }
        return fin(out, sp, 0.7f, grit = false, fade = false)
    }

    private fun fxHit(sp: Spec, rn: Random): FloatArray {
        val out = when (sp.fxKind) {
            1 -> gen(secs(0.5f)) { t, _ ->
                sin(TWO_PI * (80f * t + 3000f * (1f - exp(-t * 18f)) / 18f)) * exp(-t * 7f)
            }
            2 -> gen(secs(0.6f)) { t, _ ->
                sin(TWO_PI * (200f * t + 3800f * t * t / 1.2f)) * (1f - t / 0.6f) * min(1f, t * 200f)
            }
            3 -> {
                var hold = 0f
                var nxt = 0
                gen(secs(0.5f)) { t, i ->
                    if (i >= nxt) {
                        hold = 80f + rn.nextFloat() * 1800f
                        nxt = i + secs(0.01f + rn.nextFloat() * 0.03f)
                    }
                    (if (sn(hold * t) >= 0f) 1f else -1f) * exp(-t * 5f)
                }
            }
            4 -> gen(secs(0.7f)) { t, _ ->
                (rn.nextFloat() * 2f - 1f) * (t / 0.7f).pow(3f)
            }
            else -> {
                var lp = 0f
                gen(secs(1.6f)) { t, _ ->
                    val x = rn.nextFloat() * 2f - 1f
                    lp += (x - lp) * 0.08f
                    sin(TWO_PI * (30f * t + 80f * (1f - exp(-t * 6f)) / 6f)) * exp(-t * 2.2f) + lp * exp(-t * 3f) * 1.2f
                }
            }
        }
        return fin(out, sp)
    }

    private fun riser(sp: Spec, rn: Random): FloatArray {
        val len = 2f
        var prev = 0f
        val out = gen(secs(len)) { t, _ ->
            val k = t / len
            val x = rn.nextFloat() * 2f - 1f
            val y = x - prev * (1f - 0.7f * k)
            prev = x
            when (sp.riseKind) {
                1 -> sin(TWO_PI * (150f * t + 1500f * t * t / 2f)) * k
                2 -> (metal(t) * 0.5f + y * 0.5f) * k * k * k
                3 -> {
                    val gate = if (sn(2f * t + 3f * t * t) > 0f) 1f else 0.15f
                    y * k * gate
                }
                else -> y * k * k * 0.8f + sin(TWO_PI * (200f * t + 1200f * t * t / 2f)) * k * 0.3f
            }
        }
        return fin(out, sp, 0.8f)
    }

    private fun glitch(sp: Spec, rn: Random): FloatArray {
        val out = when (sp.glKind) {
            1 -> gen(secs(0.7f)) { t, _ ->
                val f0 = 140f
                var s = 0f
                for (h in 1..28) {
                    val f = h * f0
                    s += sn(f * t) / h * (exp(-0.5f * ((f - 750f) / 110f).let { it * it }) + 0.6f * exp(-0.5f * ((f - 1150f) / 160f).let { it * it }))
                }
                s * exp(-t * 4f) * min(1f, t * 100f)
            }
            2 -> {
                var prev = 0f
                gen(secs(1.2f)) { t, _ ->
                    val x = rn.nextFloat() * 2f - 1f
                    val y = x - prev
                    prev = x
                    y * (t / 1.2f).pow(3f)
                }
            }
            3 -> gen(secs(0.5f)) { t, _ ->
                var s = 0f
                for (k in 0 until 5) {
                    val tb = t - k * 0.07f
                    if (tb >= 0f) s += sin(TWO_PI * (2200f + k * 300f) * tb) * exp(-tb * 120f)
                }
                s
            }
            else -> {
                val freqs = FloatArray(8) { 150f * 2f.pow(rn.nextInt(0, 24) / 12f) }
                gen(secs(0.55f)) { t, _ ->
                    val k = min(7, (t / 0.07f).toInt())
                    val tb = t - k * 0.07f
                    (if (sn(freqs[k] * t) >= 0f) 1f else -1f) * exp(-tb * 30f) * 0.6f
                }
            }
        }
        return fin(out, sp, 0.8f)
    }

    // ------------------------------------------------------------ assemblage d'un kit

    private val BASS_NAMES = listOf("basse sinus", "basse scie", "basse carrée", "basse fm", "basse acid", "basse reese", "basse wobble")
    private val SYN_NAMES = listOf("pad", "drone", "accord", "cloche fm", "cordes")
    private val ST_NAMES = listOf("stab scie", "stab carré", "stab fm", "pincé", "stab rave")
    private val TX_NAMES = listOf("souffle", "craquements", "drone métal", "vent")
    private val FX_NAMES = listOf("impact", "zap", "laser", "glitch", "caisse inv.")
    private val RISE_NAMES = listOf("riser", "sweep", "cymbale inv.", "riser pulsé")
    private val GL_NAMES = listOf("blips", "voix", "crash inv.", "ticks")

    /** Fabrique les 16 sons d'un kit. À appeler hors du thread principal. */
    fun build(def: KitDef): List<Sample> {
        val sp = def.spec
        val rn = Random(sp.seed * 7919 + def.name.hashCode())
        val sub = sp.copy(bWave = 0, bF = sp.bF / 2f, bDrv = 1.3f)
        return listOf(
            Sample("kick", kick(sp, rn), SR),
            Sample("snare", snare(sp, rn), SR),
            Sample("hat", hat(sp, rn, sp.hDec, 0.15f), SR),
            Sample("hat ouvert", hat(sp, rn, sp.hoDec, 0.7f), SR),
            Sample("clap", clap(sp, rn), SR),
            Sample("perc 1", perc(sp, sp.p1F, sp.p1Dec, 1.5f, rn), SR),
            Sample("perc 2", perc(sp, sp.p2F, sp.p2Dec, 2.3f, rn), SR),
            Sample("rim", rim(sp), SR),
            Sample(BASS_NAMES[sp.bWave.coerceIn(0, 6)], bassLoop(sp), SR),
            Sample("sub", bassLoop(sub), SR),
            Sample(SYN_NAMES[sp.syKind.coerceIn(0, 4)], synthLoop(sp), SR),
            Sample(ST_NAMES[sp.stKind.coerceIn(0, 4)], stab(sp, rn), SR),
            Sample(TX_NAMES[sp.txKind.coerceIn(0, 3)], texture(sp, rn), SR),
            Sample(FX_NAMES[sp.fxKind.coerceIn(0, 4)], fxHit(sp, rn), SR),
            Sample(RISE_NAMES[sp.riseKind.coerceIn(0, 3)], riser(sp, rn), SR),
            Sample(GL_NAMES[sp.glKind.coerceIn(0, 3)], glitch(sp, rn), SR),
        )
    }

    // ------------------------------------------------------------ les 50 kits

    private val IDM = Spec(
        kF0 = 200f, kF1 = 60f, kPd = 40f, kDec = 11f, kDrv = 1.2f, kClk = 0.5f, kLen = 0.3f,
        sTone = 240f, sNoise = 0.9f, sDec = 26f, sBody = 0.3f, hDec = 110f, hoDec = 16f, hMetal = 0.8f,
        cDec = 24f, cBursts = 2, p1F = 300f, p1Dec = 18f, pFm = 3f, p2F = 900f, p2Dec = 30f,
        bF = 49f, bWave = 3, bDrv = 1.4f, syKind = 3, syRoot = 146.8f, stKind = 2, txKind = 2, fxKind = 3,
        riseKind = 3, glKind = 0, grit = 0.25f,
    )
    private val BASSM = Spec(
        kF0 = 120f, kF1 = 38f, kPd = 18f, kDec = 3.8f, kDrv = 2.2f, kClk = 0.15f, kLen = 0.8f,
        sTone = 170f, sNoise = 0.75f, sDec = 11f, sBody = 0.7f, hDec = 60f, hMetal = 0.4f, cDec = 10f, cBursts = 4,
        p1F = 140f, bF = 41.2f, bWave = 6, bRep = 40, bWob = 4, bCut = 0.5f, bRes = 0.35f, bDrv = 2.2f,
        syKind = 1, syRoot = 82.4f, syChord = intArrayOf(0, 7, 12), stKind = 1, txKind = 3, fxKind = 0, riseKind = 0, glKind = 2,
    )
    private val DNB = Spec(
        kF0 = 170f, kF1 = 55f, kPd = 34f, kDec = 10f, kDrv = 1.8f, kClk = 0.35f, kLen = 0.3f,
        sTone = 230f, sNoise = 0.9f, sDec = 22f, sBody = 0.45f, hDec = 85f, hMetal = 0.35f, cBursts = 3,
        p1F = 240f, bF = 55f, bWave = 5, bRep = 44, bDrv = 2f, syKind = 0, syRoot = 110f,
        syChord = intArrayOf(0, 3, 7, 10, 14), stKind = 0, txKind = 0, fxKind = 2, riseKind = 0, glKind = 1,
    )
    private val DOWN = Spec(
        kF0 = 130f, kF1 = 50f, kPd = 24f, kDec = 6.5f, kDrv = 1.1f, kClk = 0.1f, kLen = 0.5f,
        sTone = 180f, sNoise = 0.55f, sDec = 13f, sBody = 0.7f, hDec = 55f, hMetal = 0.2f, cDec = 12f, cBursts = 2,
        p1F = 170f, bF = 49f, bWave = 0, bDrv = 1.3f, syKind = 4, syRoot = 98f,
        syChord = intArrayOf(0, 3, 7, 10, 14), stKind = 3, txKind = 1, fxKind = 0, riseKind = 2, glKind = 1, grit = 0.35f,
    )
    private val TECHNO = Spec(
        kF0 = 150f, kF1 = 47f, kPd = 26f, kDec = 6f, kDrv = 2f, kClk = 0.25f, kLen = 0.5f,
        sTone = 200f, sNoise = 0.7f, sDec = 16f, hMetal = 0.55f, bF = 49f, bWave = 2, bDrv = 2f,
        syKind = 2, syRoot = 110f, syChord = intArrayOf(0, 3, 7), stKind = 1, txKind = 0, fxKind = 0, riseKind = 0, glKind = 3,
    )
    private val ACID = Spec(
        kF0 = 160f, kF1 = 46f, kPd = 28f, kDec = 7f, kDrv = 2.6f, kClk = 0.3f, kLen = 0.4f,
        sTone = 205f, sNoise = 0.75f, sDec = 17f, hMetal = 0.5f, bF = 55f, bWave = 4, bRep = 40, bWob = 4,
        bCut = 0.5f, bRes = 0.75f, bDrv = 2f, syKind = 2, syRoot = 110f, syChord = intArrayOf(0, 3, 7, 10), stKind = 4,
        txKind = 0, fxKind = 1, riseKind = 1, glKind = 0, grit = 0.1f,
    )
    private val PSY = Spec(
        kF0 = 130f, kF1 = 45f, kPd = 22f, kDec = 5.5f, kDrv = 2.4f, kClk = 0.35f, kLen = 0.35f,
        sTone = 210f, sNoise = 0.6f, sDec = 18f, hDec = 90f, hMetal = 0.2f, bF = 51.9f, bWave = 1, bDrv = 2.2f,
        syKind = 1, syRoot = 98f, syChord = intArrayOf(0, 1, 7), stKind = 2, txKind = 3, fxKind = 3, riseKind = 3, glKind = 0,
        wt = 20, wtPos = 0.35f, wtUni = 5, wtDet = 28f, wtSub = 0.4f,
    )

    val defs: List<KitDef> = listOf(
        // ---- IDM
        KitDef("IDM", "Glitch Garden", 132, IDM.copy(seed = 11)),
        KitDef("IDM", "Fractal Lab", 150, IDM.copy(seed = 12, kF0 = 240f, hDec = 140f, p1F = 520f, syChord = intArrayOf(0, 2, 7, 9), bF = 55f)),
        KitDef("IDM", "Broken Clock", 96, IDM.copy(seed = 13, kLen = 0.4f, kDec = 8f, cBursts = 4, glKind = 3, grit = 0.4f)),
        KitDef("IDM", "Soft Circuit", 110, IDM.copy(seed = 14, grit = 0.1f, hMetal = 0.5f, syKind = 0, stKind = 3, txKind = 0, bWave = 0)),
        KitDef("IDM", "Bitrot", 140, IDM.copy(seed = 15, grit = 0.7f, kClk = 0.8f, fxKind = 3, glKind = 0, bWave = 2)),
        KitDef("IDM", "Granular Rain", 90, IDM.copy(seed = 16, txKind = 1, syKind = 3, hDec = 160f, p2F = 1400f, glKind = 3, riseKind = 2)),
        KitDef("IDM", "Phase Machine", 128, IDM.copy(seed = 17, bWave = 5, bRep = 30, syKind = 4, kF0 = 180f, stKind = 4, fxKind = 2)),
        // ---- Bass music
        KitDef("BASS MUSIC", "Half-Time Heavy", 70, BASSM.copy(seed = 21)),
        KitDef("BASS MUSIC", "Riddim Grid", 140, BASSM.copy(seed = 22, bWob = 2, bCut = 0.7f, bWave = 6, kLen = 0.5f, sDec = 14f)),
        KitDef("BASS MUSIC", "Trap Smash", 140, BASSM.copy(seed = 23, kF1 = 34f, kDec = 2.6f, kLen = 1.1f, hDec = 110f, cBursts = 3, bWave = 0, bF = 36.7f, bDrv = 2.8f)),
        KitDef("BASS MUSIC", "Wub Cavern", 140, BASSM.copy(seed = 24, bWob = 6, bRes = 0.6f, bF = 36.7f, syKind = 1, txKind = 3)),
        KitDef("BASS MUSIC", "Future Garage", 130, BASSM.copy(seed = 25, kF0 = 110f, kDec = 6f, kLen = 0.4f, sNoise = 0.6f, grit = 0.2f, bWave = 3, syKind = 4, syRoot = 110f, stKind = 3)),
        KitDef("BASS MUSIC", "Neuro Grind", 150, BASSM.copy(seed = 26, bWave = 5, bRep = 36, bDrv = 3f, kDrv = 2.6f, fxKind = 3, glKind = 0)),
        KitDef("BASS MUSIC", "Sub Temple", 70, BASSM.copy(seed = 27, bWave = 0, bF = 32.7f, bDrv = 1.8f, kF1 = 32f, kLen = 1.2f, syKind = 1, riseKind = 2, txKind = 0)),
        // ---- DnB
        KitDef("DNB", "Liquid Rollers", 172, DNB.copy(seed = 31, syKind = 0, grit = 0f, bWave = 0, bF = 55f, hMetal = 0.3f, stKind = 3)),
        KitDef("DNB", "Neuro Funk", 174, DNB.copy(seed = 32, bWave = 5, bRep = 36, bDrv = 3f, fxKind = 3, syKind = 1)),
        KitDef("DNB", "Jungle Cut", 168, DNB.copy(seed = 33, sTone = 250f, sNoise = 0.8f, hDec = 100f, bWave = 0, bF = 49f, grit = 0.3f, glKind = 3, stKind = 4)),
        KitDef("DNB", "Jump Up", 174, DNB.copy(seed = 34, bWave = 6, bWob = 4, bCut = 0.6f, bF = 49f, kDrv = 2.2f, glKind = 0)),
        KitDef("DNB", "Darkstep", 172, DNB.copy(seed = 35, bF = 41.2f, bWave = 5, kF1 = 50f, syKind = 1, syChord = intArrayOf(0, 1, 7), txKind = 3, riseKind = 3)),
        KitDef("DNB", "Minimal Roll", 174, DNB.copy(seed = 36, sNoise = 0.6f, hDec = 120f, hMetal = 0.6f, bWave = 0, bF = 46.2f, stKind = 1, syKind = 2, grit = 0.15f)),
        KitDef("DNB", "Techstep Steel", 175, DNB.copy(seed = 37, kDrv = 2.4f, sBody = 0.6f, bWave = 4, bRes = 0.6f, bWob = 3, bCut = 0.45f, fxKind = 1, riseKind = 1)),
        // ---- Downtempo
        KitDef("DOWNTEMPO", "Dusty Break", 88, DOWN.copy(seed = 41, grit = 0.45f, glKind = 1)),
        KitDef("DOWNTEMPO", "Night Drive", 100, DOWN.copy(seed = 42, syKind = 0, bWave = 5, bRep = 30, bF = 43.7f, txKind = 0, grit = 0.2f, syChord = intArrayOf(0, 4, 7, 11, 14))),
        KitDef("DOWNTEMPO", "Soft Chill", 82, DOWN.copy(seed = 43, kLen = 0.6f, kDec = 5f, sNoise = 0.4f, hMetal = 0.1f, syKind = 4, stKind = 2, grit = 0.15f)),
        KitDef("DOWNTEMPO", "Trip Hop Smoke", 84, DOWN.copy(seed = 44, kF0 = 110f, kDrv = 1.4f, sTone = 160f, sBody = 0.8f, syKind = 1, syRoot = 87.3f, txKind = 1, grit = 0.55f)),
        KitDef("DOWNTEMPO", "Slow Motion", 72, DOWN.copy(seed = 45, kLen = 0.7f, hDec = 40f, hoDec = 6f, bWave = 3, bF = 41.2f, syKind = 0, riseKind = 0, glKind = 2)),
        KitDef("DOWNTEMPO", "Warm Tape", 92, DOWN.copy(seed = 46, grit = 0.6f, syKind = 4, stKind = 3, bWave = 0, txKind = 0, fxKind = 1)),
        KitDef("DOWNTEMPO", "Lounge Drift", 96, DOWN.copy(seed = 47, syKind = 3, syRoot = 130.8f, syChord = intArrayOf(0, 4, 7, 11), stKind = 2, bWave = 0, grit = 0.2f, glKind = 3)),
        // ---- Techno
        KitDef("TECHNO", "Warehouse", 132, TECHNO.copy(seed = 51)),
        KitDef("TECHNO", "Berlin Steel", 135, TECHNO.copy(seed = 52, kDrv = 2.6f, kF1 = 44f, kLen = 0.4f, hMetal = 0.7f, bF = 46.2f, txKind = 2, glKind = 0)),
        KitDef("TECHNO", "Detroit Chrome", 128, TECHNO.copy(seed = 53, kDrv = 1.6f, kLen = 0.45f, syKind = 4, syChord = intArrayOf(0, 3, 7, 10), stKind = 0, bWave = 1, hMetal = 0.4f)),
        KitDef("TECHNO", "Rolling Tunnel", 130, TECHNO.copy(seed = 54, bWave = 1, bF = 49f, kPd = 22f, kDec = 7.5f, hDec = 90f, riseKind = 3, fxKind = 4)),
        KitDef("TECHNO", "Dub Techno Haze", 124, TECHNO.copy(seed = 55, kDrv = 1.3f, hMetal = 0.2f, hDec = 40f, syKind = 0, stKind = 2, txKind = 0, grit = 0.2f, bWave = 0, glKind = 1)),
        KitDef("TECHNO", "Peak Hour", 138, TECHNO.copy(seed = 56, kDrv = 2.8f, kF1 = 50f, kLen = 0.35f, sNoise = 0.85f, cBursts = 4, bF = 55f, riseKind = 0)),
        KitDef("TECHNO", "Industrial Floor", 140, TECHNO.copy(seed = 57, kDrv = 3f, grit = 0.4f, hMetal = 0.9f, bWave = 4, bRes = 0.5f, txKind = 2, fxKind = 3, stKind = 2, glKind = 3)),
        // ---- Acid techno
        KitDef("ACID TECHNO", "Acid Tracks", 132, ACID.copy(seed = 61)),
        KitDef("ACID TECHNO", "Resonant Squelch", 135, ACID.copy(seed = 62, bRes = 0.9f, bCut = 0.6f, bWob = 3, bF = 49f)),
        KitDef("ACID TECHNO", "Acid Rave", 140, ACID.copy(seed = 63, stKind = 4, syKind = 0, riseKind = 0, bF = 61.7f, hMetal = 0.6f, grit = 0.2f)),
        KitDef("ACID TECHNO", "Hardacid", 145, ACID.copy(seed = 64, kDrv = 3f, kLen = 0.35f, bDrv = 3f, bRes = 0.8f, sNoise = 0.9f, cBursts = 4, fxKind = 3)),
        KitDef("ACID TECHNO", "Minimal Acid", 126, ACID.copy(seed = 65, kDrv = 1.8f, bCut = 0.3f, bRes = 0.7f, bWob = 2, syKind = 1, stKind = 1, hMetal = 0.3f, grit = 0f)),
        KitDef("ACID TECHNO", "Liquid Acid", 128, ACID.copy(seed = 66, bWob = 6, bCut = 0.4f, bRes = 0.55f, syKind = 4, stKind = 3, txKind = 3, riseKind = 2)),
        KitDef("ACID TECHNO", "Basement Acid", 138, ACID.copy(seed = 67, grit = 0.45f, kF1 = 42f, bF = 43.7f, txKind = 2, glKind = 3, stKind = 2)),
        // ---- Dark psy
        KitDef("DARK PSY", "Forest Night", 148, PSY.copy(seed = 71)),
        KitDef("DARK PSY", "Twilight Zone", 150, PSY.copy(seed = 72, wt = 22, kLen = 0.3f, bF = 55f, syChord = intArrayOf(0, 3, 7), txKind = 2, glKind = 3)),
        KitDef("DARK PSY", "Dark Matter", 155, PSY.copy(seed = 73, kDrv = 3f, kF1 = 42f, bF = 46.2f, wt = 29, wtUni = 7, wtDet = 40f, fxKind = 0, riseKind = 0)),
        KitDef("DARK PSY", "Nocturnal Drone", 145, PSY.copy(seed = 74, syKind = 1, syRoot = 77.8f, wt = 7, wtPos = 0.5f, wtUni = 3, txKind = 3, glKind = 1)),
        KitDef("DARK PSY", "Psy Kick Rolling", 148, PSY.copy(seed = 75, kLen = 0.25f, kDec = 7f, kClk = 0.5f, bWave = 4, bRes = 0.6f, bWob = 4, bCut = 0.4f, wt = 3)),
        KitDef("DARK PSY", "Occult Ritual", 143, PSY.copy(seed = 76, syKind = 4, syRoot = 73.4f, syChord = intArrayOf(0, 1, 6), stKind = 3, txKind = 1, fxKind = 4, wt = 17, grit = 0.2f)),
        KitDef("DARK PSY", "Hallucination", 160, PSY.copy(seed = 77, hMetal = 0.5f, p1F = 400f, pFm = 4f, bWave = 3, wt = 4, wtUni = 6, wtPos = 0.7f, grit = 0.15f, glKind = 0)),
        KitDef("DARK PSY", "Mushroom Grid", 152, PSY.copy(seed = 78, bWave = 5, bRep = 38, wt = 14, wtUni = 4, riseKind = 1, fxKind = 2, stKind = 4)),
    )

    val styles: List<String> = defs.map { it.style }.distinct()
    fun kitsOf(style: String): List<KitDef> = defs.filter { it.style == style }

}
