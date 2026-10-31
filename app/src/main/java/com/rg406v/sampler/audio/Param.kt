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
    var value by mutableFloatStateOf(default)

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
