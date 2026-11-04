package com.rg406v.sampler.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Tous les réglages d'un pad. Chaque pad est un sampler, un nuage granulaire ou un synthé FM. */
class PadParams(val index: Int) {
    @Volatile
    var sample: Sample? = null
    var sampleName by mutableStateOf("vide")

    /** Incrémenté à chaque édition du sample, pour redessiner la forme d'onde. */
    var sampleVersion by mutableIntStateOf(0)

    class Snapshot(val sample: Sample?, val start: Float, val end: Float)

    private val history = ArrayDeque<Snapshot>()
    var canUndo by mutableStateOf(false)

    @Synchronized
    fun pushHistory(s: Sample?, st: Float, en: Float) {
        history.addLast(Snapshot(s, st, en))
        while (history.size > 3) history.removeFirst()
        canUndo = true
    }

    @Synchronized
    fun popHistory(): Snapshot? {
        val r = history.removeLastOrNull()
        canUndo = history.isNotEmpty()
        return r
    }

    @Synchronized
    fun clearHistory() {
        history.clear()
        canUndo = false
    }

    /** Nom affiché quand le pad est en mode FM. */
    var fmName by mutableStateOf("FM")

    /** 0 = sampler, 1 = granulaire, 2 = FM. */
    val mode = Param("Mode", 0f, 3f, 0f, stepped = true, labels = listOf("Sampler", "Granulaire", "FM", "Wavetable"))

    // ---- communs
    val volume = Param("Volume", 0f, 1f, 0.9f)
    val pan = Param("Pan", -1f, 1f, 0f)
    val pitch = Param("Pitch", -24f, 24f, 0f, unit = " st", decimals = 1)
    val start = Param("Début", 0f, 1f, 0f)
    val end = Param("Fin", 0f, 1f, 1f)
    val attack = Param("Attack", 0.001f, 2f, 0.002f, log = true, unit = " s", decimals = 3)
    val decay = Param("Decay", 0.005f, 2f, 0.2f, log = true, unit = " s", decimals = 3)
    val sustain = Param("Sustain", 0f, 1f, 1f)
    val release = Param("Release", 0.005f, 3f, 0.05f, log = true, unit = " s", decimals = 3)

    // ---- granulaire (lit la zone début/fin du sample)
    val gPos = Param("Position", 0f, 1f, 0.5f)
    val gSize = Param("Grain", 5f, 500f, 80f, log = true, unit = " ms", decimals = 0)
    val gDens = Param("Densité", 1f, 120f, 25f, log = true, unit = " /s", decimals = 0)
    val gSpray = Param("Spray", 0f, 1f, 0.1f)
    val gDetune = Param("Détune", 0f, 12f, 0f, unit = " st", decimals = 1)
    val gScan = Param("Scan", -1f, 1f, 0f)

    // ---- FM (2 opérateurs + feedback ; fréquence de base 220 Hz + Pitch)
    val fmRatio = Param("Ratio", 0.25f, 16f, 2f, log = true, unit = "x", decimals = 2)
    val fmIndex = Param("Index", 0f, 12f, 3f, unit = "", decimals = 1)
    val fmFeedback = Param("Feedback", 0f, 1f, 0f)
    val fmModDecay = Param("Mod decay", 0.02f, 5f, 0.4f, log = true, unit = " s", decimals = 2)

    // ---- wavetable (32 tables, morphing, unison, sync, mouvement automatique de la position)
    val wtTable = Param("Table", 0f, (Wavetables.COUNT - 1).toFloat(), 0f, stepped = true, decimals = 0, labels = Wavetables.NAMES)
    val wtPos = Param("Position", 0f, 1f, 0f)
    val wtWarp = Param("Sync", 0f, 1f, 0f)
    val wtUni = Param("Unison", 1f, 7f, 1f, stepped = true, decimals = 0)
    val wtDetune = Param("Détune", 0f, 100f, 18f, unit = " ct", decimals = 0)
    val wtSpread = Param("Stéréo", 0f, 1f, 0.6f)
    val wtSub = Param("Sub", 0f, 1f, 0f)
    val wtMotion = Param("Mouv. vit.", 0.05f, 12f, 0.5f, log = true, unit = " Hz", decimals = 2)
    val wtDepth = Param("Mouv. prof.", 0f, 1f, 0f)

    /** Nom affiché sur le pad : sample, patch FM ou table de wavetable. */
    val displayName: String
        get() = when (mode.value.toInt()) {
            2 -> fmName
            3 -> "WT " + Wavetables.NAMES.getOrElse(wtTable.value.toInt()) { "" }
            else -> sampleName
        }

    /** Boucle entre début et fin (impose le mode gate). */
    var loop by mutableStateOf(false)

    /** Gate : le son s'arrête (release) quand on relâche le pad. */
    var gate by mutableStateOf(false)

    private val serial: List<Param> by lazy {
        listOf(
            mode, volume, pan, pitch, start, end, attack, decay, sustain, release,
            gPos, gSize, gDens, gSpray, gDetune, gScan, fmRatio, fmIndex, fmFeedback, fmModDecay,
            wtTable, wtPos, wtWarp, wtUni, wtDetune, wtSpread, wtSub, wtMotion, wtDepth,
        )
    }

    /** Remet tous les réglages du pad à leurs valeurs par défaut (le sample n'est pas touché). */
    fun reset() {
        for (p in serial) p.value = p.defaultValue
        loop = false
        gate = false
        fmName = "FM"
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", sampleName)
        o.put("fm", fmName)
        o.put("loop", loop)
        o.put("gate", gate)
        val a = JSONArray()
        for (p in serial) a.put(p.value.toDouble())
        o.put("p", a)
        return o
    }

    fun applyJson(o: JSONObject) {
        sampleName = o.optString("name", sampleName)
        fmName = o.optString("fm", fmName)
        loop = o.optBoolean("loop", loop)
        gate = o.optBoolean("gate", gate)
        val a = o.optJSONArray("p") ?: return
        for (k in serial.indices) {
            if (k < a.length()) serial[k].value = a.getDouble(k).toFloat().coerceIn(serial[k].min, serial[k].max)
        }
        sampleVersion++
    }

    /** Le pad tient la note tant qu'il est enfoncé ? (le granulaire est toujours tenu) */
    fun holds(): Boolean = gate || loop || mode.value.toInt() == 1
}
