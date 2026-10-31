package com.rg406v.sampler.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Tous les réglages d'un pad. */
class PadParams(val index: Int) {
    @Volatile
    var sample: Sample? = null
    var sampleName by mutableStateOf("vide")

    val volume = Param("Volume", 0f, 1f, 0.9f)
    val pan = Param("Pan", -1f, 1f, 0f)
    val pitch = Param("Pitch", -24f, 24f, 0f, unit = " st", decimals = 1)
    val start = Param("Début", 0f, 1f, 0f)
    val end = Param("Fin", 0f, 1f, 1f)
    val attack = Param("Attack", 0.001f, 2f, 0.002f, log = true, unit = " s", decimals = 3)
    val decay = Param("Decay", 0.005f, 2f, 0.2f, log = true, unit = " s", decimals = 3)
    val sustain = Param("Sustain", 0f, 1f, 1f)
    val release = Param("Release", 0.005f, 3f, 0.05f, log = true, unit = " s", decimals = 3)

    /** Boucle entre début et fin (impose le mode gate). */
    var loop by mutableStateOf(false)

    /** Gate : le son s'arrête (release) quand on relâche le pad. Sinon one-shot. */
    var gate by mutableStateOf(false)
}
