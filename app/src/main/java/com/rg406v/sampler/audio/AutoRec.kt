package com.rg406v.sampler.audio

/** Une courbe d'automation : SLOTS valeurs réparties sur la boucle. */
class Lane {
    val raw = FloatArray(AutoRec.SLOTS) { Float.NaN }

    fun valueAt(frac: Float): Float {
        val n = AutoRec.SLOTS
        val x = frac * n
        val i0 = x.toInt()
        val i = ((i0 % n) + n) % n
        val a = raw[i]
        if (a.isNaN()) return Float.NaN
        val b = raw[(i + 1) % n]
        if (b.isNaN()) return a
        return a + (b - a) * (x - i0)
    }

    /** Remplit les trous en gardant la dernière valeur. Faux si la courbe est vide. */
    fun finish(): Boolean {
        val n = raw.size
        var first = -1
        for (i in 0 until n) if (!raw[i].isNaN()) { first = i; break }
        if (first < 0) return false
        var last = raw[first]
        for (k in 0 until n) {
            val idx = (first + k) % n
            if (raw[idx].isNaN()) raw[idx] = last else last = raw[idx]
        }
        return true
    }

    fun complete(): Boolean {
        for (v in raw) if (v.isNaN()) return false
        return true
    }
}

/** État partagé de l'enregistrement de mouvements (écrit par l'UI, lu par le moteur). */
object AutoRec {
    const val SLOTS = 256

    @Volatile var armed = false

    /** Position dans la boucle, 0..1 (mise à jour par le moteur audio). */
    @Volatile var pos = 0f

    fun touch(p: Param, v: Float) {
        if (!armed || !p.autoable) return
        var lane = p.lane
        if (lane == null) {
            lane = Lane()
            p.lane = lane
            p.lastSlot = -1
        }
        val n = SLOTS
        val slot = (pos * n).toInt().coerceIn(0, n - 1)
        val last = p.lastSlot
        if (last in 0 until n && last != slot) {
            val d = (slot - last + n) % n
            if (d in 1..24) {
                val a = lane.raw[last]
                for (k in 1 until d) lane.raw[(last + k) % n] = if (a.isNaN()) v else a + (v - a) * k / d
            }
        }
        lane.raw[slot] = v
        p.lastSlot = slot
    }
}
