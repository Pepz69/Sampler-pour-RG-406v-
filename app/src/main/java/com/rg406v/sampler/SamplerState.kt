package com.rg406v.sampler

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.rg406v.sampler.audio.AudioEngine
import com.rg406v.sampler.audio.BitcrusherFx
import com.rg406v.sampler.audio.ChorusFx
import com.rg406v.sampler.audio.AutoRec
import com.rg406v.sampler.audio.FilterverseFx
import com.rg406v.sampler.audio.Lane
import com.rg406v.sampler.audio.ManipulatorFx
import com.rg406v.sampler.audio.CompressorFx
import com.rg406v.sampler.audio.DefaultSounds
import com.rg406v.sampler.audio.DelayFx
import com.rg406v.sampler.audio.DistortionFx
import com.rg406v.sampler.audio.EditResult
import com.rg406v.sampler.audio.Effect
import com.rg406v.sampler.audio.EqFx
import com.rg406v.sampler.audio.FilterFx
import com.rg406v.sampler.audio.PadParams
import com.rg406v.sampler.audio.Param
import com.rg406v.sampler.audio.Pattern
import com.rg406v.sampler.audio.Recorder
import com.rg406v.sampler.audio.ReverbFx
import com.rg406v.sampler.audio.Sample
import com.rg406v.sampler.audio.SampleEdit
import com.rg406v.sampler.audio.SampleLoader
import com.rg406v.sampler.audio.Sequencer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

const val TAB_FX = 0
const val TAB_PAD = 1
const val TAB_PADFX = 2
const val TAB_SEQ = 3
const val TAB_PIANO = 4
const val TAB_KITS = 5
const val TAB_PUNCH = 6
const val TAB_AUDIO = 7
const val TAB_PADS = 8
const val TAB_MIX = 9
const val NTABS = 10

/** Un mode = une entrée du rail de gauche, avec une ou plusieurs sous-pages (ce sont les anciens onglets). */
class ModeDef(val label: String, val tabs: List<Int>, val subs: List<String>)

val MODES = listOf(
    ModeDef("PADS", listOf(TAB_PADS), listOf("pads")),
    ModeDef("SEQ", listOf(TAB_SEQ, TAB_PIANO), listOf("pas", "piano")),
    ModeDef("SAMPLE", listOf(TAB_PAD), listOf("sample")),
    ModeDef("MIXER", listOf(TAB_MIX), listOf("mixer")),
    ModeDef("FX", listOf(TAB_FX, TAB_PADFX, TAB_PUNCH), listOf("master", "pad", "punch")),
    ModeDef("PROJET", listOf(TAB_KITS), listOf("projet")),
    ModeDef("SETUP", listOf(TAB_AUDIO), listOf("audio")),
)

/** Tout l'état de l'appli : pads, effets, séquenceur, kits, réglages audio. Partagé entre l'UI, la manette et le moteur. */
/** Un périphérique audio proposé dans le menu (info == null : automatique). */
class DevItem(val info: android.media.AudioDeviceInfo?, val label: String) {
    val id: Int get() = info?.id ?: 0
}

class SamplerState(private val ctx: Context) {

    val pads = List(16) { PadParams(it) }
    val engine = AudioEngine(ctx, pads)
    val seq: Sequencer = engine.seq
    val storage = Storage(ctx)

    private val ui = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ effets

    private fun makeChain(sr: Int, ownPad: Int): List<Effect> = listOf(
        FilterFx(sr), DistortionFx(sr), BitcrusherFx(), ChorusFx(sr), DelayFx(sr), ReverbFx(sr),
        EqFx(sr), CompressorFx(sr, engine, ownPad),
        ManipulatorFx(sr), FilterverseFx(sr),
    ).also { chain -> for (e in chain) for (q in e.persistParams) q.autoable = true }

    /** Chaîne master, dans l'ordre du signal (gauche → droite). */
    val fx = mutableStateListOf<Effect>()

    /** Effets individuels : une chaîne complète par pad. */
    val padFx: List<SnapshotStateList<Effect>> = List(16) { i ->
        mutableStateListOf<Effect>().apply { addAll(makeChain(engine.sampleRate, i)) }
    }
    val padActive = mutableStateListOf<Boolean>().apply { repeat(16) { add(false) } }
    val punchActive = mutableStateListOf<Boolean>().apply { repeat(12) { add(false) } }

    var selectedFx by mutableIntStateOf(0)
    var selectedPad by mutableIntStateOf(0)

    /** Onglet affiché (voir les constantes TAB_*). */
    var tab by mutableIntStateOf(TAB_PADS)

    /** Select maintenu = Shift (fonctions secondaires de la manette). */
    var shiftHeld by mutableStateOf(false)

    private val modeSub = IntArray(MODES.size)

    fun modeIndex(): Int = MODES.indexOfFirst { tab in it.tabs }.coerceAtLeast(0)

    fun goMode(m: Int) {
        val cur = modeIndex()
        modeSub[cur] = MODES[cur].tabs.indexOf(tab).coerceAtLeast(0)
        val n = m.mod(MODES.size)
        val md = MODES[n]
        tab = md.tabs[modeSub[n].coerceIn(0, md.tabs.size - 1)]
        selectedFx = 0
    }

    fun goTab(t: Int) {
        val m = MODES.indexOfFirst { t in it.tabs }
        if (m >= 0) {
            val cur = modeIndex()
            modeSub[cur] = MODES[cur].tabs.indexOf(tab).coerceAtLeast(0)
            modeSub[m] = MODES[m].tabs.indexOf(t)
        }
        tab = t
        selectedFx = 0
    }

    fun nextSub(d: Int) {
        val md = MODES[modeIndex()]
        if (md.tabs.size < 2) return
        goTab(md.tabs[(md.tabs.indexOf(tab) + d).mod(md.tabs.size)])
    }

    // ---- mixer : solo (coupe les autres pistes du séquenceur, puis restaure)
    var soloPad by mutableIntStateOf(-1)
    private val soloSaved = BooleanArray(16)

    fun toggleSolo(i: Int) {
        if (soloPad == i) {
            for (t in 0 until 16) seq.mute[t] = soloSaved[t]
            soloPad = -1
        } else {
            if (soloPad < 0) for (t in 0 until 16) soloSaved[t] = seq.mute[t]
            soloPad = i
            for (t in 0 until 16) seq.mute[t] = t != i
        }
        seq.touch()
    }

    /** L1 maintenu : la manette joue les pads 9 à 16 (ou les punch-in 9 à 12). */
    var bankHeld by mutableStateOf(false)

    /** R1 maintenu : les boutons de la manette déclenchent les punch-in au lieu des pads. */
    var punchHeld by mutableStateOf(false)

    /** Sous-vue de l'onglet Pad : 0 = réglages, 1 = édition du sample. */
    var padView by mutableIntStateOf(0)

    // outils d'édition
    val stretch = Param("Étirement", 0.25f, 4f, 1f, log = true, unit = "x", decimals = 2)
    val shift = Param("Transposer", -12f, 12f, 0f, unit = " st", decimals = 1)
    val slices = Param("Parts", 2f, 16f, 8f, stepped = true, decimals = 0)
    private var editing = false

    var requestPick: (Int) -> Unit = {}
    var requestExport: (String) -> Unit = {}
    var requestImport: () -> Unit = {}
    var requestMic: () -> Unit = {}

    private fun toast(msg: String) {
        ui.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------ micro, niveaux

    var recording by mutableStateOf(false)
    var recSeconds by mutableFloatStateOf(0f)
    var recLevel by mutableFloatStateOf(0f)
    var inMonitor by mutableStateOf(false)
    private var pendingMonitor = false
    val inGain = Param("Entrée", 0.25f, 8f, 1f, log = true, unit = "x", decimals = 2)
    @Volatile private var inPeakRaw = 0f
    @Volatile private var inClipRaw = false

    private val recorder = Recorder { level, seconds, clip ->
        recLevel = level
        recSeconds = seconds
        val raw = level * level
        if (raw > inPeakRaw) inPeakRaw = raw
        if (clip) inClipRaw = true
        if (seconds >= Recorder.MAX_SECONDS - 0.1f) ui.post { stopRecording() }
    }

    // niveaux affichés (0..1 sur une échelle -60 dB .. 0 dB)
    var outL by mutableFloatStateOf(0f)
    var outR by mutableFloatStateOf(0f)
    var holdL by mutableFloatStateOf(0f)
    var holdR by mutableFloatStateOf(0f)
    var inLvl by mutableFloatStateOf(0f)
    var outClip by mutableStateOf(false)
    var inClip by mutableStateOf(false)
    var cpu by mutableFloatStateOf(0f)
    var underruns by mutableIntStateOf(0)
    var bufMs by mutableFloatStateOf(0f)
    private var holdAgeL = 0
    private var holdAgeR = 0

    /** Positions des indicateurs : pour la dB, voir meterDb(). */
    private fun meterPos(p: Float): Float {
        if (p <= 1e-6f) return 0f
        return ((20f * log10(p) + 60f) / 60f).coerceIn(0f, 1f)
    }

    private val meterTick = object : Runnable {
        override fun run() {
            val pl = engine.outPeakL
            val pr = engine.outPeakR
            engine.outPeakL = 0f
            engine.outPeakR = 0f
            run {
                val now = System.nanoTime()
                for (q in engine.lanes) {
                    val o = q.ovr
                    if (!o.isNaN() && now - q.touchNs > 450_000_000L && q.value != o) q.setSilently(o)
                }
            }
            val nl = meterPos(pl)
            val nr = meterPos(pr)
            outL = if (nl > outL) nl else max(0f, outL - 0.035f)
            outR = if (nr > outR) nr else max(0f, outR - 0.035f)
            if (nl >= holdL) { holdL = nl; holdAgeL = 0 } else if (++holdAgeL > 40) holdL = max(0f, holdL - 0.02f)
            if (nr >= holdR) { holdR = nr; holdAgeR = 0 } else if (++holdAgeR > 40) holdR = max(0f, holdR - 0.02f)
            if (engine.clipHit) { outClip = true; engine.clipHit = false }

            val ip = inPeakRaw
            inPeakRaw = 0f
            val ni = meterPos(ip)
            inLvl = if (ni > inLvl) ni else max(0f, inLvl - 0.035f)
            if (inClipRaw) { inClip = true; inClipRaw = false }

            cpu = engine.loadPercent
            underruns = engine.underruns
            bufMs = engine.bufferFrames * 1000f / engine.sampleRate
            recorder.gain = inGain.value
            ui.postDelayed(this, 40)
        }
    }

    fun resetClip() {
        outClip = false
        inClip = false
        engine.clipHit = false
        holdL = 0f
        holdR = 0f
    }

    fun hasMic() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun toggleRecord() {
        if (recording) { stopRecording(); return }
        if (hasMic()) startRecording() else { pendingMonitor = false; requestMic() }
    }

    fun startRecording() {
        if (recording) return
        recSeconds = 0f
        recLevel = 0f
        if (recorder.beginStore()) recording = true else toast("Micro indisponible")
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        recLevel = 0f
        val data = recorder.endStore()
        if (data == null) { toast("Enregistrement trop court"); return }
        val i = selectedPad // le sample va sur le pad sélectionné au moment de l'arrêt
        val p = pads[i]
        val s = Sample("Rec ${i + 1}", data, Recorder.SAMPLE_RATE)
        p.start.value = 0f
        p.end.value = 1f
        p.sample = s
        p.sampleName = s.name
        if (p.mode.value.toInt() == 2) p.mode.value = 0f
        p.clearHistory()
        p.sampleVersion++
        toast("Pad ${i + 1} : enregistrement prêt")
    }

    fun toggleMonitor() {
        if (inMonitor) { recorder.stopMonitor(); inMonitor = false; return }
        if (hasMic()) startMonitor() else { pendingMonitor = true; requestMic() }
    }

    private fun startMonitor() {
        if (recorder.startMonitor()) inMonitor = true else toast("Micro indisponible")
    }

    fun onMicResult(ok: Boolean) {
        if (!ok) {
            pendingMonitor = false
            toast("Autorise le micro pour enregistrer")
            return
        }
        if (pendingMonitor) { pendingMonitor = false; startMonitor() } else startRecording()
    }

    // ------------------------------------------------------------ pads

    fun padDown(i: Int) {
        selectedPad = i
        // Pendant l'enregistrement micro, toucher un pad choisit sa destination sans le jouer.
        if (recording) return
        padActive[i] = true
        engine.noteOn(i)
        if (seq.recArmed && seq.playing) seq.recordHit(i)
    }

    fun padUp(i: Int) {
        if (!padActive[i]) return
        padActive[i] = false
        engine.noteOff(i)
    }

    /** Piano roll : joue le pad sélectionné à une hauteur donnée (demi-tons). */
    fun noteDown(semis: Int) {
        val i = selectedPad
        padActive[i] = true
        engine.noteOn(i, semis.toFloat())
        if (seq.recArmed && seq.playing) seq.recordHit(i, if (semis == 0) Float.NaN else semis.toFloat())
    }

    fun noteUp() = padUp(selectedPad)

    fun releaseAll() {
        for (i in 0 until 16) if (padActive[i]) padUp(i)
        for (k in 0 until 12) if (punchActive[k]) punchUp(k)
    }

    fun panic() {
        engine.stopAll()
        engine.punch.releaseAll()
        for (i in 0 until 16) padActive[i] = false
        for (k in 0 until 12) punchActive[k] = false
        seq.stop()
    }

    fun punchDown(k: Int) {
        if (k !in 0 until 12) return
        punchActive[k] = true
        engine.punch.press(k)
    }

    fun punchUp(k: Int) {
        if (k !in 0 until 12) return
        punchActive[k] = false
        engine.punch.release(k)
    }

    // ------------------------------------------------------------ chargement de samples

    fun loadFromUri(i: Int, uri: Uri) {
        Thread {
            val loaded = SampleLoader.load(ctx, uri)
            if (loaded == null) {
                toast("Format non supporté (WAV 16 bits, MP3, OGG, FLAC, AAC)")
            } else {
                ui.post {
                    val p = pads[i]
                    p.start.value = 0f
                    p.end.value = 1f
                    p.sample = loaded
                    p.sampleName = loaded.name
                    if (p.mode.value.toInt() == 2) p.mode.value = 0f
                    p.clearHistory()
                    p.sampleVersion++
                    toast("Pad ${i + 1} : ${loaded.name}")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ effets : sélection / ordre

    /** Chaîne actuellement éditée : celle du pad (onglet FX pad) ou la chaîne master. */
    fun chain(): SnapshotStateList<Effect> = if (tab == TAB_PADFX) padFx[selectedPad] else fx

    private fun syncChain() {
        if (tab == TAB_PADFX) engine.setPadChain(selectedPad, padFx[selectedPad].toTypedArray())
        else engine.chain = fx.toTypedArray()
    }

    fun selectFx(dir: Int) {
        val c = chain()
        if (c.isEmpty()) return
        selectedFx = (selectedFx + dir).mod(c.size)
    }

    fun moveFx(dir: Int) {
        val c = chain()
        val a = selectedFx
        val b = a + dir
        if (b !in c.indices) return
        val t = c[a]
        c[a] = c[b]
        c[b] = t
        selectedFx = b
        syncChain()
    }

    // ------------------------------------------------------------ manette

    fun toggleTab() {
        goMode(modeIndex() + 1)
    }

    /** Start : active/coupe l'effet (onglets FX), charge un sample (onglet Pad), sinon play/stop du séquenceur. */
    fun startPressed() {
        when (tab) {
            TAB_FX, TAB_PADFX -> chain().getOrNull(selectedFx)?.let { it.enabled = !it.enabled }
            TAB_PAD -> requestPick(selectedPad)
            else -> seqToggle()
        }
    }

    private val latch = BooleanArray(4)

    /** Sticks : réglage relatif des paramètres de l'écran courant. */
    fun stickAdjust(lx: Float, ly: Float, rx: Float, ry: Float, dt: Float) {
        val axes = floatArrayOf(lx, ly, rx, ry)
        val targets: List<Param?> = when (tab) {
            TAB_FX, TAB_PADFX -> {
                val ps = chain().getOrNull(selectedFx)?.params ?: emptyList()
                List(4) { ps.getOrNull(it) }
            }
            TAB_PAD -> {
                val p = pads[selectedPad]
                when (p.mode.value.toInt()) {
                    1 -> listOf(p.gPos, p.gSize, p.gDens, p.gSpray)
                    2 -> listOf(p.fmRatio, p.fmIndex, p.fmFeedback, p.fmModDecay)
                    else -> listOf(p.pitch, p.volume, p.start, p.end)
                }
            }
            TAB_SEQ, TAB_PIANO -> listOf(seq.bpm, seq.swing, null, null)
            TAB_PUNCH -> listOf(engine.punch.mix, null, null, null)
            TAB_MIX, TAB_PADS -> {
                val p = pads[selectedPad]
                listOf(p.volume, if (tab == TAB_MIX) p.pan else p.pitch, engine.master, if (tab == TAB_PADS) seq.bpm else null)
            }
            else -> listOf(engine.master, null, null, null)
        }
        for (i in 0 until 4) {
            val a = axes[i]
            val p = targets[i] ?: continue
            if (p.stepped) {
                if (abs(a) > 0.6f && !latch[i]) {
                    latch[i] = true
                    p.nudge(if (a > 0f) 1 else -1)
                } else if (abs(a) < 0.3f) {
                    latch[i] = false
                }
            } else if (a != 0f) {
                p.norm = p.norm + a * dt * 0.6f
            }
        }
    }

    // ------------------------------------------------------------ séquenceur

    var lockStep by mutableIntStateOf(-1)
    var seqPage by mutableIntStateOf(0)
    var seqLockMode by mutableStateOf(false)
    var pianoOct by mutableIntStateOf(0)

    val lkPitch = Param("Hauteur", -24f, 24f, 0f, unit = " st", decimals = 0)
    val lkVol = Param("Volume", 0f, 1f, 1f)
    val lkPan = Param("Pan", -1f, 1f, 0f)
    val lkStart = Param("Début", 0f, 1f, 0f)
    val lkProb = Param("Proba", 0f, 100f, 100f, stepped = true, unit = "%", decimals = 0)
    val lkMicro = Param("Micro", -0.5f, 0.5f, 0f, decimals = 2)
    val lkRetrig = Param("Retrig", 0f, 5f, 0f, stepped = true, labels = Sequencer.RETRIGS.map { "${it}x" })
    val lkCond = Param("Cond", 0f, 9f, 0f, stepped = true, labels = Sequencer.COND_LABELS)
    val lkLen = Param("Durée", 0.25f, 4f, 1f, log = true, unit = "x", decimals = 2)
    private var lockLoading = false
    private val patClip = Pattern()
    private val trackLens = intArrayOf(1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64)

    fun seqToggle() {
        if (seq.playing) { seq.stop(); engine.releaseHolds() } else seq.start()
    }

    fun toggleStep(tr: Int, s: Int) {
        val p = seq.pattern()
        if (p.on[tr][s]) p.resetStep(tr, s) else p.on[tr][s] = true
        seq.touch()
    }

    fun stepTap(s: Int) {
        val tr = selectedPad
        if (!seqLockMode) { toggleStep(tr, s); return }
        if (lockStep == s) { lockStep = -1; return }
        val p = seq.pattern()
        if (!p.on[tr][s]) { p.on[tr][s] = true; seq.touch() }
        lockStep = s
        loadLock()
    }

    fun loadLock() {
        val s = lockStep
        if (s < 0) return
        val tr = selectedPad
        val p = seq.pattern()
        lockLoading = true
        lkPitch.value = p.pitch[tr][s].let { if (it.isNaN()) 0f else it }
        lkVol.value = p.vol[tr][s].let { if (it.isNaN()) 1f else it }
        lkPan.value = p.pan[tr][s].let { if (it.isNaN()) 0f else it }
        lkStart.value = p.start[tr][s].let { if (it.isNaN()) 0f else it }
        lkProb.value = p.prob[tr][s].toFloat()
        lkMicro.value = p.micro[tr][s]
        lkRetrig.value = Sequencer.RETRIGS.indexOf(p.retrig[tr][s]).coerceAtLeast(0).toFloat()
        lkCond.value = p.cond[tr][s].toFloat()
        lkLen.value = p.slen[tr][s]
        lockLoading = false
    }

    /** Écrit les réglages de la fenêtre p-lock dans le pas sélectionné (valeur par défaut = pas de lock). */
    fun applyLock() {
        if (lockLoading) return
        val s = lockStep
        if (s < 0) return
        val tr = selectedPad
        val p = seq.pattern()
        p.pitch[tr][s] = if (lkPitch.value == 0f) Float.NaN else lkPitch.value
        p.vol[tr][s] = if (lkVol.value >= 0.999f) Float.NaN else lkVol.value
        p.pan[tr][s] = if (abs(lkPan.value) < 0.01f) Float.NaN else lkPan.value
        p.start[tr][s] = if (lkStart.value <= 0.001f) Float.NaN else lkStart.value
        p.prob[tr][s] = lkProb.value.toInt()
        p.micro[tr][s] = lkMicro.value
        p.retrig[tr][s] = Sequencer.RETRIGS[lkRetrig.value.toInt().coerceIn(0, 5)]
        p.cond[tr][s] = lkCond.value.toInt()
        p.slen[tr][s] = lkLen.value
        seq.touch()
    }

    fun setPattern(i: Int) {
        seq.current = i.coerceIn(0, 7)
        lockStep = -1
        seq.touch()
    }

    fun nextPattern(dir: Int) = setPattern((seq.current + dir).mod(8))

    fun clearTrackSteps() { seq.pattern().clearTrack(selectedPad); lockStep = -1; seq.touch() }

    fun newSequence() {
        for (p in seq.patterns) p.clearAll()
        seq.current = 0
        lockStep = -1
        seq.touch()
    }

    fun clearPattern() { seq.pattern().clearAll(); lockStep = -1; seq.touch() }

    fun copyPattern() { patClip.copyFrom(seq.pattern()); toast("Pattern ${seq.current + 1} copié") }

    fun pastePattern() { seq.pattern().copyFrom(patClip); seq.touch(); toast("Collé dans le pattern ${seq.current + 1}") }

    fun toggleMute(tr: Int) { seq.mute[tr] = !seq.mute[tr]; seq.touch() }

    fun changeTrackLen(dir: Int) {
        val p = seq.pattern()
        val tr = selectedPad
        val idx = trackLens.indexOfLast { it <= p.len[tr] }.coerceAtLeast(0)
        val n = (idx + dir).coerceIn(0, trackLens.size - 1)
        p.len[tr] = trackLens[n]
        if (seqPage * 16 >= p.len[tr]) seqPage = 0
        seq.touch()
    }

    /** Piano roll : pose, change ou retire la note du pas s à la hauteur semis. */
    fun pianoTap(s: Int, semis: Int) {
        val p = seq.pattern()
        val tr = selectedPad
        val cur = p.pitch[tr][s].let { if (it.isNaN()) 0 else it.toInt() }
        if (p.on[tr][s] && cur == semis) {
            p.resetStep(tr, s)
        } else {
            p.on[tr][s] = true
            p.pitch[tr][s] = if (semis == 0) Float.NaN else semis.toFloat()
        }
        seq.touch()
    }

    // ------------------------------------------------------------ kits et séquences

    val kitList = mutableStateListOf<String>()
    val seqList = mutableStateListOf<String>()
    val projList = mutableStateListOf<String>()
    var currentProject by mutableStateOf("")
    var currentKit by mutableStateOf("classic")
    var libReady by mutableStateOf(false)
    var libKit by mutableIntStateOf(0)
    var kitsView by mutableIntStateOf(0)
    val builtinNames = listOf("classic", "808", "lo-fi", "synth")

    fun refreshLists() {
        val k = storage.listKits()
        val s = storage.listSeq()
        val pr = storage.listProjects()
        ui.post {
            projList.clear(); projList.addAll(pr)
            kitList.clear(); kitList.addAll(k)
            seqList.clear(); seqList.addAll(s)
        }
    }

    private fun applyKitDefaults(k: Int, sounds: List<Sample>) {
        when (k) {
            0 -> {
                for (i in 8..10) { pads[i].loop = true; pads[i].gate = true; pads[i].release.value = 0.15f }
                pads[12].apply {
                    mode.value = 2f; gate = true; fmName = "FM basse"
                    fmRatio.value = 1f; fmIndex.value = 3f; fmFeedback.value = 0.3f; fmModDecay.value = 0.3f
                    pitch.value = -12f; sustain.value = 0.8f; release.value = 0.12f
                }
                pads[13].apply {
                    mode.value = 2f; gate = false; fmName = "FM cloche"
                    fmRatio.value = 3.5f; fmIndex.value = 6f; fmModDecay.value = 0.8f
                    pitch.value = 7f; attack.value = 0.001f; decay.value = 1.5f; sustain.value = 0f; release.value = 0.3f
                }
                pads[15].apply {
                    mode.value = 1f
                    sample = sounds[10]; sampleName = "Nuage"
                    gPos.value = 0.5f; gSize.value = 120f; gDens.value = 30f; gSpray.value = 0.3f
                    attack.value = 0.3f; release.value = 0.8f
                }
            }
            1 -> pads[15].apply { loop = true; gate = true; release.value = 0.15f }
            3 -> for (i in intArrayOf(0, 1, 9, 10, 11)) { pads[i].gate = true; pads[i].release.value = 0.2f }
        }
    }

    private fun applyBuiltinNow(k: Int, sounds: List<Sample>) {
        engine.stopAll()
        for (i in 0 until 16) {
            val p = pads[i]
            p.reset()
            val s = sounds[i]
            p.sample = s
            p.sampleName = s.name
            p.start.value = 0f
            p.end.value = 1f
            p.clearHistory()
            p.sampleVersion++
        }
        applyKitDefaults(k, sounds)
        currentKit = builtinNames[k]
    }

    fun applyBuiltin(k: Int) {
        Thread {
            val kit = DefaultSounds.kits.getOrNull(k) ?: return@Thread
            ui.post {
                applyBuiltinNow(k, kit.sounds)
                toast("Banque ${kit.name} chargée")
            }
        }.start()
    }

    fun saveKitAs(name: String) {
        Thread {
            try {
                storage.saveKit(name, pads)
                ui.post { currentKit = name }
                refreshLists()
                toast("Kit « $name » enregistré")
            } catch (e: Exception) {
                toast("Échec de l'enregistrement")
            }
        }.start()
    }

    fun loadKitNamed(name: String) {
        Thread {
            engine.stopAll()
            val ok = storage.loadKit(name, pads)
            if (ok) { ui.post { currentKit = name }; toast("Kit « $name » chargé") } else toast("Kit introuvable")
        }.start()
    }

    fun deleteKit(name: String) {
        Thread { storage.deleteKit(name); refreshLists() }.start()
    }

    fun saveSeqAs(name: String) {
        Thread {
            try {
                storage.saveSeq(name, seq.toJson())
                refreshLists()
                toast("Séquence « $name » enregistrée")
            } catch (e: Exception) {
                toast("Échec de l'enregistrement")
            }
        }.start()
    }

    fun loadSeqNamed(name: String) {
        Thread {
            val j = storage.loadSeq(name)
            if (j != null && seq.fromJson(j)) {
                ui.post { lockStep = -1 }
                toast("Séquence « $name » chargée")
            } else toast("Séquence introuvable")
        }.start()
    }

    fun deleteSeq(name: String) {
        Thread { storage.deleteSeq(name); refreshLists() }.start()
    }

    /** Met un son de la bibliothèque sur un pad et le joue pour l'écoute. */
    fun assignSound(i: Int, s: Sample) {
        val p = pads[i]
        p.pushHistory(p.sample, p.start.value, p.end.value)
        p.sample = s
        p.sampleName = s.name
        p.start.value = 0f
        p.end.value = 1f
        if (p.mode.value.toInt() == 2) p.mode.value = 0f
        p.sampleVersion++
        engine.noteOn(i)
        ui.postDelayed({ engine.noteOff(i) }, 500)
    }

    fun clearPad(i: Int) {
        val p = pads[i]
        p.sample = null
        p.sampleName = "vide"
        p.clearHistory()
        p.sampleVersion++
    }

    /** Échange le son et tous les réglages de deux pads. */
    fun swapPads(a: Int, b: Int) {
        if (a == b) return
        val pa = pads[a]
        val pb = pads[b]
        val ja = pa.toJson()
        val jb = pb.toJson()
        val sa = pa.sample
        val sb = pb.sample
        engine.stopAll()
        pa.applyJson(jb); pa.sample = sb
        pb.applyJson(ja); pb.sample = sa
        pa.clearHistory(); pb.clearHistory()
        pa.sampleVersion++; pb.sampleVersion++
        toast("Pads ${a + 1} et ${b + 1} échangés")
    }

    // ------------------------------------------------------------ réglages audio

    fun setBlock(n: Int) { engine.blockSize = n }
    fun setBufferBlocks(n: Int) { engine.bufferBlocks = n.coerceIn(1, 8) }

    fun applyPreset(block: Int, nbuf: Int) {
        engine.blockSize = block
        engine.bufferBlocks = nbuf
    }

    fun setPerf(m: Int) {
        engine.perfMode = m
        Thread { engine.restart() }.start()
    }

    fun resetUnderruns() { engine.underruns = 0; underruns = 0 }

    // ------------------------------------------------------------ édition de sample

    private fun regionOf(p: PadParams): IntArray? {
        val s = p.sample ?: return null
        val len = s.data.size
        if (len < 2) return null
        val a = (p.start.value * len).toInt().coerceIn(0, len - 2)
        val b = (p.end.value * len).toInt().coerceIn(a + 1, len)
        return intArrayOf(a, b)
    }

    private fun runEdit(work: (FloatArray, Int, Int, Int) -> EditResult?) {
        val i = selectedPad
        val p = pads[i]
        val s = p.sample
        val reg = regionOf(p)
        if (s == null || reg == null) { toast("Aucun sample sur ce pad"); return }
        if (editing) { toast("Traitement en cours..."); return }
        editing = true
        Thread {
            try {
                val r = work(s.data, reg[0], reg[1], s.sampleRate)
                ui.post {
                    if (r != null) commitEdit(i, s, r) else toast("Rien à modifier")
                    editing = false
                }
            } catch (e: Throwable) {
                ui.post { editing = false; toast("Échec de l'édition") }
            }
        }.start()
    }

    private fun commitEdit(i: Int, old: Sample, r: EditResult) {
        val p = pads[i]
        p.pushHistory(old, p.start.value, p.end.value)
        p.sample = Sample(old.name, r.data, old.sampleRate)
        p.start.value = r.start
        p.end.value = r.end
        p.sampleVersion++
    }

    fun editCrop() = runEdit { d, a, b, _ -> SampleEdit.crop(d, a, b) }
    fun editCut() = runEdit { d, a, b, _ -> SampleEdit.cutOut(d, a, b) }
    fun editTrimSilence() = runEdit { d, _, _, _ -> SampleEdit.trimSilence(d) }
    fun editReverse() = runEdit { d, a, b, _ -> SampleEdit.reverse(d, a, b) }
    fun editFadeIn() = runEdit { d, a, b, _ -> SampleEdit.fade(d, a, b, true) }
    fun editFadeOut() = runEdit { d, a, b, _ -> SampleEdit.fade(d, a, b, false) }
    fun editNormalize() = runEdit { d, a, b, _ -> SampleEdit.normalize(d, a, b) }

    fun editStretch() {
        val f = stretch.value
        runEdit { d, a, b, sr -> SampleEdit.stretchRegion(d, a, b, sr, f) }
    }

    fun editShift() {
        val st = shift.value
        runEdit { d, a, b, sr -> SampleEdit.pitchRegion(d, a, b, sr, st) }
    }

    fun selectAll() {
        val p = pads[selectedPad]
        p.start.value = 0f
        p.end.value = 1f
    }

    fun undo() {
        val p = pads[selectedPad]
        val snap = p.popHistory() ?: return
        p.sample = snap.sample
        snap.sample?.let { p.sampleName = it.name }
        p.start.value = snap.start
        p.end.value = snap.end
        p.sampleVersion++
    }

    /** Découpe la sélection en parties égales réparties sur les pads suivants (à partir du pad courant). */
    fun editSlice() {
        val i0 = selectedPad
        val p = pads[i0]
        val s = p.sample
        val reg = regionOf(p)
        if (s == null || reg == null) { toast("Aucun sample sur ce pad"); return }
        val n = min(slices.value.toInt(), 16 - i0)
        if (n < 2) { toast("Choisis un pad plus bas : il faut au moins 2 pads libres à droite"); return }
        if (editing) { toast("Traitement en cours..."); return }
        editing = true
        Thread {
            try {
                val parts = SampleEdit.slice(s.data, reg[0], reg[1], n)
                ui.post {
                    parts.forEachIndexed { k, d ->
                        val t = pads[i0 + k]
                        t.pushHistory(t.sample, t.start.value, t.end.value)
                        val ns = Sample("${s.name} ${k + 1}", d, s.sampleRate)
                        t.sample = ns
                        t.sampleName = ns.name
                        t.start.value = 0f
                        t.end.value = 1f
                        t.sampleVersion++
                        if (t.mode.value.toInt() == 2) t.mode.value = 0f
                    }
                    editing = false
                    toast("Découpé sur les pads ${i0 + 1} à ${i0 + parts.size}")
                }
            } catch (e: Throwable) {
                ui.post { editing = false; toast("Échec de la découpe") }
            }
        }.start()
    }

    // ------------------------------------------------------------ sauvegarde automatique

    private val saveLock = Any()
    private val autoCache = arrayOfNulls<Sample>(16)
    @Volatile private var ready = false

    private fun effectsToJson(list: List<Effect>): JSONArray {
        val a = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("id", e.short)
            o.put("en", e.enabled)
            val ps = JSONArray()
            for (p in e.persistParams) ps.put(p.value.toDouble())
            o.put("p", ps)
            val au = JSONArray()
            for (q in e.autoParams) au.put(q.value.toDouble())
            o.put("a", au)
            val ln = JSONArray()
            e.persistParams.forEachIndexed { k, q ->
                val l = q.lane
                if (l != null && l.complete()) {
                    val lo = JSONObject()
                    lo.put("k", k)
                    val va = JSONArray()
                    for (v in l.raw) va.put(Math.round(v * 1000.0) / 1000.0)
                    lo.put("v", va)
                    ln.put(lo)
                }
            }
            o.put("lanes", ln)
            a.put(o)
        }
        return a
    }

    /** Reconstruit une chaîne (ordre, activation, réglages) depuis le JSON ; applique sur le thread UI. */
    private fun restoreEffects(target: SnapshotStateList<Effect>, arr: JSONArray?, ownPad: Int, onDone: () -> Unit) {
        if (arr == null) return
        val fresh = makeChain(engine.sampleRate, ownPad).toMutableList()
        val ordered = ArrayList<Effect>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val e = fresh.firstOrNull { it.short == o.optString("id") } ?: continue
            fresh.remove(e)
            e.enabled = o.optBoolean("en", false)
            val ps = o.optJSONArray("p")
            val params = e.persistParams
            if (ps != null) {
                for (k in params.indices) {
                    if (k < ps.length()) params[k].value = ps.getDouble(k).toFloat().coerceIn(params[k].min, params[k].max)
                }
            }
            val au = o.optJSONArray("a")
            if (au != null) {
                val ap = e.autoParams
                for (k in ap.indices) {
                    if (k < au.length()) ap[k].value = au.getDouble(k).toFloat().coerceIn(ap[k].min, ap[k].max)
                }
            }
            val lj = o.optJSONArray("lanes")
            if (lj != null) {
                val pp = e.persistParams
                for (m in 0 until lj.length()) {
                    val lo = lj.getJSONObject(m)
                    val k = lo.optInt("k", -1)
                    val va = lo.optJSONArray("v") ?: continue
                    if (k !in pp.indices || va.length() != AutoRec.SLOTS) continue
                    val lane = Lane()
                    for (i in 0 until AutoRec.SLOTS) lane.raw[i] = va.getDouble(i).toFloat()
                    pp[k].lane = lane
                }
            }
            ordered.add(e)
        }
        ordered.addAll(fresh)
        ui.post {
            target.clear()
            target.addAll(ordered)
            onDone()
            rebuildLanes()
        }
    }

    private fun buildStateJson(): String {
        val o = JSONObject()
        o.put("master", engine.master.value.toDouble())
        o.put("scale", engine.scale.value.toDouble())
        o.put("punch", engine.punch.mix.value.toDouble())
        o.put("ingain", inGain.value.toDouble())
        o.put("block", engine.blockSize)
        o.put("bufblocks", engine.bufferBlocks)
        o.put("auto", engine.autoBuffer)
        o.put("perf", engine.perfMode)
        o.put("kit", currentKit)
        o.put("fx", effectsToJson(fx))
        val pf = JSONArray()
        for (i in 0 until 16) pf.put(effectsToJson(padFx[i]))
        o.put("padfx", pf)
        o.put("seq", JSONObject(seq.toJson()))
        val mute = JSONArray()
        for (i in 0 until 16) mute.put(seq.mute[i])
        o.put("mute", mute)
        return o.toString()
    }

    fun autosave() {
        if (!ready) return
        synchronized(saveLock) {
            try {
                storage.saveKit("_auto", pads, autoCache)
                storage.writeState(buildStateJson())
            } catch (e: Exception) {
                // pas grave : on réessaiera à la prochaine sauvegarde
            }
        }
    }

    private fun restoreState() {
        try {
            if (storage.hasKit("_auto")) {
                storage.loadKit("_auto", pads)
                for (i in 0 until 16) autoCache[i] = pads[i].sample
            }
            val txt = storage.readState() ?: return
            applyState(JSONObject(txt))
        } catch (e: Exception) {
            // état illisible : on garde les valeurs par défaut
        }
    }

    /** Applique un état complet (sauvegarde auto ou projet). */
    private fun applyState(o: JSONObject) {
        try {
            ui.post {
                engine.master.value = o.optDouble("master", 0.8).toFloat()
                engine.scale.value = o.optDouble("scale", 0.0).toFloat()
                engine.punch.mix.value = o.optDouble("punch", 1.0).toFloat()
                inGain.value = o.optDouble("ingain", 1.0).toFloat().coerceIn(inGain.min, inGain.max)
                engine.blockSize = o.optInt("block", 256)
                engine.bufferBlocks = o.optInt("bufblocks", 2)
                engine.autoBuffer = o.optBoolean("auto", true)
                val perf = o.optInt("perf", 0)
                if (perf != engine.perfMode) setPerf(perf)
                currentKit = o.optString("kit", currentKit)
            }
            restoreEffects(fx, o.optJSONArray("fx"), -1) { engine.chain = fx.toTypedArray() }
            val pf = o.optJSONArray("padfx")
            if (pf != null) {
                for (i in 0 until min(16, pf.length())) {
                    restoreEffects(padFx[i], pf.optJSONArray(i), i) { engine.setPadChain(i, padFx[i].toTypedArray()) }
                }
            }
            o.optJSONObject("seq")?.let { seq.fromJson(it.toString()) }
            val mute = o.optJSONArray("mute")
            if (mute != null) for (i in 0 until min(16, mute.length())) seq.mute[i] = mute.optBoolean(i, false)
        } catch (e: Exception) {
            // état illisible : on garde les valeurs par défaut
        }
    }

    private val autoTick = object : Runnable {
        override fun run() {
            Thread { autosave() }.start()
            ui.postDelayed(this, 15000)
        }
    }

    // ------------------------------------------------------------ projets (tout en un)
    fun saveProjectAs(name: String) {
        if (!ready) return
        Thread {
            try {
                synchronized(saveLock) {
                    storage.saveKit(name, pads, null, storage.projectsRoot)
                    storage.writeProjectState(name, buildStateJson())
                }
                ui.post { currentProject = name }
                refreshLists()
                toast("Projet « $name » enregistré")
            } catch (e: Exception) {
                toast("Échec de l'enregistrement du projet")
            }
        }.start()
    }

    fun loadProjectNamed(name: String) {
        if (autoRec) toggleAutoRec()
        seq.stop()
        releaseAll()
        engine.stopAll()
        Thread {
            try {
                val txt = storage.readProjectState(name)
                if (txt == null || !storage.loadKit(name, pads, storage.projectsRoot)) {
                    toast("Projet introuvable")
                    return@Thread
                }
                applyState(JSONObject(txt))
                ui.post { currentProject = name }
                toast("Projet « $name » chargé")
            } catch (e: Exception) {
                toast("Projet illisible")
            }
        }.start()
    }

    fun exportProjectTo(name: String, uri: android.net.Uri) {
        Thread {
            try {
                val ok = synchronized(saveLock) {
                    ctx.contentResolver.openOutputStream(uri)?.use { storage.exportProjectZip(name, it) } ?: false
                }
                toast(if (ok) "Projet « $name » exporté" else "Export impossible")
            } catch (e: Exception) {
                toast("Export impossible")
            }
        }.start()
    }

    fun importProjectFrom(uri: android.net.Uri) {
        Thread {
            try {
                val n = ctx.contentResolver.openInputStream(uri)?.use { storage.importProjectZip(it) }
                if (n == null) toast("Ce fichier n'est pas un projet") else {
                    refreshLists()
                    toast("Projet « $n » importé")
                }
            } catch (e: Exception) {
                toast("Import impossible")
            }
        }.start()
    }

    fun deleteProject(name: String) {
        Thread { storage.deleteProject(name); refreshLists() }.start()
    }

    // ------------------------------------------------------------ automation : enregistrement de mouvements
    var autoRec by mutableStateOf(false)
    var laneVersion by mutableIntStateOf(0)

    private fun allEffects(): List<Effect> = fx.toList() + padFx.flatMap { it.toList() }

    fun rebuildLanes() {
        engine.lanes = allEffects().flatMap { it.persistParams }.filter { it.lane != null }.toTypedArray()
        laneVersion++
    }

    fun toggleAutoRec() {
        if (!autoRec) {
            for (e in allEffects()) for (q in e.persistParams) q.lastSlot = -1
            AutoRec.armed = true
            autoRec = true
            toast("Enregistrement des mouvements : bouge un bouton d'effet")
        } else {
            AutoRec.armed = false
            autoRec = false
            for (e in allEffects()) for (q in e.persistParams) {
                val l = q.lane ?: continue
                if (!l.finish()) { q.lane = null; q.ovr = Float.NaN }
            }
            rebuildLanes()
        }
    }

    fun clearLane(q: Param) {
        q.lane = null
        q.ovr = Float.NaN
        rebuildLanes()
    }

    fun clearLanes(e: Effect) {
        for (q in e.persistParams) { q.lane = null; q.ovr = Float.NaN }
        rebuildLanes()
    }

    fun setAutoBars(n: Int) { engine.autoBars = n }

    // ------------------------------------------------------------ périphériques audio (USB, casque...)
    private val audioManager = ctx.getSystemService(android.media.AudioManager::class.java)
    val outDevices = mutableStateListOf<DevItem>(DevItem(null, "automatique"))
    val inDevices = mutableStateListOf<DevItem>(DevItem(null, "micro par défaut"))
    var outSelId by mutableIntStateOf(0)
    var inSelId by mutableIntStateOf(0)
    val routedOut: String get() = engine.routedLabel
    val routedIn: String get() = recorder.routedLabel

    private fun devLabel(d: android.media.AudioDeviceInfo): String {
        val kind = when (d.type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "haut-parleur"
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "micro interne"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "casque"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB acc."
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
            android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG, android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL -> "ligne"
            else -> "autre"
        }
        val name = d.productName?.toString().orEmpty()
        return if (name.isBlank()) kind else "$kind · $name"
    }

    fun refreshDevices() {
        val am = audioManager ?: return
        val outs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != android.media.AudioDeviceInfo.TYPE_TELEPHONY }
        val ins = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            .filter {
                it.type != android.media.AudioDeviceInfo.TYPE_TELEPHONY &&
                    it.type != android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX
            }
        outDevices.clear()
        outDevices.add(DevItem(null, "automatique"))
        outs.forEach { outDevices.add(DevItem(it, devLabel(it))) }
        inDevices.clear()
        inDevices.add(DevItem(null, "micro par défaut"))
        ins.forEach { inDevices.add(DevItem(it, devLabel(it))) }
        // un périphérique débranché : retour au choix automatique
        if (outSelId != 0 && outDevices.none { it.id == outSelId }) selectOutput(0)
        if (inSelId != 0 && inDevices.none { it.id == inSelId }) selectInput(0)
    }

    fun selectOutput(id: Int) {
        outSelId = id
        engine.outputDevice = outDevices.firstOrNull { it.id == id }?.info
    }

    fun selectInput(id: Int) {
        if (recording) { toast("Termine d'abord l'enregistrement"); return }
        inSelId = id
        recorder.preferredDevice = inDevices.firstOrNull { it.id == id }?.info
        if (inMonitor) {
            recorder.stopMonitor()
            inMonitor = false
            startMonitor()
        }
    }

    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { refreshDevices() }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { refreshDevices() }
    }

    // ------------------------------------------------------------ cycle de vie

    fun resume() {
        engine.start()
        ui.removeCallbacks(meterTick)
        ui.post(meterTick)
        ui.removeCallbacks(autoTick)
        ui.postDelayed(autoTick, 15000)
    }

    fun pause() {
        ui.removeCallbacks(meterTick)
        ui.removeCallbacks(autoTick)
        stopRecording()
        if (inMonitor) { recorder.stopMonitor(); inMonitor = false }
        releaseAll()
        seq.stop()
        engine.stop()
        Thread { autosave() }.start()
    }

    init {
        audioManager?.registerAudioDeviceCallback(deviceCallback, ui)
        refreshDevices()
        val sr = engine.sampleRate
        fx.addAll(makeChain(sr, -1))
        engine.chain = fx.toTypedArray()
        for (i in 0 until 16) engine.setPadChain(i, padFx[i].toTypedArray())

        // Banque "classic" disponible tout de suite, puis restauration de la dernière session.
        applyBuiltinNow(0, DefaultSounds.build())
        Thread {
            DefaultSounds.kits // prépare les autres banques
            restoreState()
            ready = true
            ui.post { libReady = true }
            refreshLists()
        }.start()
    }
}
