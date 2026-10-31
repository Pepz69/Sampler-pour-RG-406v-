package com.rg406v.sampler.audio

import kotlin.math.abs

/** Sample mono en float [-1, 1]. */
class Sample(val name: String, val data: FloatArray, val sampleRate: Int) {

    /** 256 pics pour dessiner la forme d'onde. */
    val peaks: FloatArray by lazy {
        val n = 256
        val out = FloatArray(n)
        if (data.isNotEmpty()) {
            for (i in 0 until n) {
                val a = (i.toLong() * data.size / n).toInt()
                val b = ((i + 1).toLong() * data.size / n).toInt().coerceAtLeast(a + 1).coerceAtMost(data.size)
                var m = 0f
                for (j in a until b) {
                    val v = abs(data[j])
                    if (v > m) m = v
                }
                out[i] = m
            }
        }
        out
    }
}
