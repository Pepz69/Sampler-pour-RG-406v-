package com.rg406v.sampler.audio

/** Gammes et modes proposés au piano roll et au clavier de pads. Les 5 premières gardent leur ordre d'origine. */
object Scales {
    private class S(val name: String, val steps: IntArray)

    private fun s(name: String, vararg st: Int) = S(name, st)

    private val all: List<S> = listOf(
        S("aucune", IntArray(0)),
        s("chromatique", 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
        s("majeur", 0, 2, 4, 5, 7, 9, 11),
        s("mineur", 0, 2, 3, 5, 7, 8, 10),
        s("pentatonique", 0, 2, 4, 7, 9),
        // modes de la gamme majeure
        s("dorien", 0, 2, 3, 5, 7, 9, 10),
        s("phrygien", 0, 1, 3, 5, 7, 8, 10),
        s("lydien", 0, 2, 4, 6, 7, 9, 11),
        s("mixolydien", 0, 2, 4, 5, 7, 9, 10),
        s("locrien", 0, 1, 3, 5, 6, 8, 10),
        // mineures et majeures dérivées
        s("mineur harmonique", 0, 2, 3, 5, 7, 8, 11),
        s("mineur mélodique", 0, 2, 3, 5, 7, 9, 11),
        s("majeur harmonique", 0, 2, 4, 5, 7, 8, 11),
        s("dorien b2", 0, 1, 3, 5, 7, 9, 10),
        s("lydien augmenté", 0, 2, 4, 6, 8, 9, 11),
        s("lydien dominant", 0, 2, 4, 6, 7, 9, 10),
        s("mixolydien b6", 0, 2, 4, 5, 7, 8, 10),
        s("locrien nat. 2", 0, 2, 3, 5, 6, 8, 10),
        s("altéré", 0, 1, 3, 4, 6, 8, 10),
        // couleurs sombres, orientales, de l'est
        s("phrygien dominant", 0, 1, 4, 5, 7, 8, 10),
        s("hongrois mineur", 0, 2, 3, 6, 7, 8, 11),
        s("hongrois majeur", 0, 3, 4, 6, 7, 9, 10),
        s("double harmonique", 0, 1, 4, 5, 7, 8, 11),
        s("napolitain mineur", 0, 1, 3, 5, 7, 8, 11),
        s("napolitain majeur", 0, 1, 3, 5, 7, 9, 11),
        s("roumain mineur", 0, 2, 3, 6, 7, 9, 10),
        s("persan", 0, 1, 4, 5, 6, 8, 11),
        s("tzigane", 0, 2, 3, 6, 7, 8, 10),
        s("espagnol (8 notes)", 0, 1, 3, 4, 5, 6, 8, 10),
        s("énigmatique", 0, 1, 4, 6, 8, 10, 11),
        s("prométhée", 0, 2, 4, 6, 9, 10),
        // symétriques
        s("tons entiers", 0, 2, 4, 6, 8, 10),
        s("diminuée ton-demi", 0, 2, 3, 5, 6, 8, 9, 11),
        s("diminuée demi-ton", 0, 1, 3, 4, 6, 7, 9, 10),
        s("augmentée", 0, 3, 4, 7, 8, 11),
        s("tritonique", 0, 1, 4, 6, 7, 10),
        // pentatoniques et blues
        s("pentatonique mineure", 0, 3, 5, 7, 10),
        s("blues mineur", 0, 3, 5, 6, 7, 10),
        s("blues majeur", 0, 2, 3, 4, 7, 9),
        s("hirajoshi", 0, 2, 3, 7, 8),
        s("in-sen", 0, 1, 5, 7, 10),
        s("iwato", 0, 1, 5, 6, 10),
        s("yo", 0, 2, 5, 7, 9),
        s("kumoi", 0, 2, 3, 7, 9),
        s("pelog (balinais)", 0, 1, 3, 7, 8),
        s("égyptien", 0, 2, 5, 7, 10),
        s("chinois", 0, 4, 6, 7, 11),
        // jazz
        s("bebop dominant", 0, 2, 4, 5, 7, 9, 10, 11),
        s("bebop majeur", 0, 2, 4, 5, 7, 8, 9, 11),
        s("bebop dorien", 0, 2, 3, 4, 5, 7, 9, 10),
        // modes à transpositions limitées (Messiaen)
        s("messiaen 3", 0, 2, 3, 4, 6, 7, 8, 10, 11),
        s("messiaen 4", 0, 1, 2, 5, 6, 7, 8, 11),
        s("messiaen 5", 0, 1, 5, 6, 7, 11),
        s("messiaen 6", 0, 2, 4, 5, 6, 8, 10, 11),
        s("messiaen 7", 0, 1, 2, 3, 5, 6, 7, 8, 9, 11),
        // hexatoniques
        s("hexatonique majeure", 0, 2, 4, 5, 7, 9),
        s("hexatonique mineure", 0, 2, 3, 5, 7, 10),
    )

    val names: List<String> = all.map { it.name }
    val steps: List<IntArray> = all.map { it.steps }
    val size: Int get() = all.size

    val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Notes de la gamme (0..11, relatives à la tonique). null pour « aucune » et « chromatique ». */
    fun mask(type: Int): BooleanArray? {
        if (type <= 1) return null
        val m = BooleanArray(12)
        for (x in steps[type.coerceIn(0, all.size - 1)]) m[x % 12] = true
        return m
    }

    /** Nom des notes de la gamme pour une tonique donnée, ex. « D E F G A Bb C ». */
    fun noteList(type: Int, root: Int): String {
        if (type <= 0) return ""
        return steps[type.coerceIn(0, all.size - 1)].joinToString(" ") { NOTE_NAMES[(it + root) % 12] }
    }
}
