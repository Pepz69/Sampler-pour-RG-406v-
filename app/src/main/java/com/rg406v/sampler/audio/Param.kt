package com.rg406v.sampler.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Paramètre réglable, lu par le thread audio et modifié par l'UI / les sticks.
 * `value` est un état Compose : l'UI se met à jour toute seule.
 */
class Param(
    val name: String,
    val min: Float,
    val max: Float,
    default: Float,
    val log: Boolean = false,
    val stepped: Boolean = false,
    val labels: List<String>? = null,
    val unit: String = "",
    val decimals: Int = 2,
) {
    val defaultValue: Float = default

    /** Modulation d'automation (en position normalisée), écrite par le thread audio. */
    @Volatile var modDelta = 0f
    @Volatile private var effCache = default

    /** Valeur lue par le DSP : la valeur réglée + l'automation éventuelle. */
    val eff: Float
        get() {
            if (modDelta != 0f) return effCache
            val o = ovr
            return if (o.isNaN()) raw else o
        }

    fun setMod(delta: Float) {
        modDelta = delta
        if (delta == 0f) return
        val b0 = if (ovr.isNaN()) raw else ovr
        val base = if (log) ln(b0 / min) / ln(max / min) else (b0 - min) / (max - min)
        val c = (base + delta).coerceIn(0f, 1f)
        var v = if (log) min * (max / min).pow(c) else min + c * (max - min)
        if (stepped) v = v.roundToInt().toFloat()
        effCache = v
    }
    private val st = mutableFloatStateOf(default)

    /** Copie de `value` lisible sans passer par le système d'états de Compose (thread audio). */
    @Volatile private var raw = default

    /** Valeur réglée (UI, sticks). Toute écriture est vue par l'enregistreur de mouvements. */
    var value: Float
        get() = st.floatValue
        set(v) {
            st.floatValue = v
            raw = v
            touchNs = System.nanoTime()
            AutoRec.touch(this, v)
        }

    /** Écriture sans déclencher l'enregistrement (lecture d'une automation affichée sur le bouton). */
    fun setSilently(v: Float) { st.floatValue = v; raw = v }

    // ---- automation par enregistrement de mouvements
    @Volatile var ovr = Float.NaN
    @Volatile var lane: Lane? = null
    @Volatile var touchNs = 0L
    var lastSlot = -1
    var autoable = false

    /** Position normalisée 0..1 (échelle log si demandé). */
    var norm: Float
        get() = if (log) (ln(value / min) / ln(max / min)).coerceIn(0f, 1f)
        else ((value - min) / (max - min)).coerceIn(0f, 1f)
        set(n) {
            val c = n.coerceIn(0f, 1f)
            var v = if (log) min * (max / min).pow(c) else min + c * (max - min)
            if (stepped) v = v.roundToInt().toFloat()
            value = v
        }

    fun nudge(steps: Int) {
        value = (value + steps).coerceIn(min, max)
    }

    fun display(): String {
        val v = value
        labels?.let { return it.getOrElse(v.roundToInt()) { "" } }
        return if (unit == "Hz") {
            if (v >= 1000f) "%.1f kHz".format(v / 1000f) else "%.0f Hz".format(v)
        } else "%.${decimals}f%s".format(v, unit)
    }
}
