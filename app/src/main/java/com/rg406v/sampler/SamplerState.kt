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
import com.rg406v.sampler.audio.DefaultSounds
import com.rg406v.sampler.audio.DelayFx
import com.rg406v.sampler.audio.DistortionFx
import com.rg406v.sampler.audio.EditResult
import com.rg406v.sampler.audio.Effect
import com.rg406v.sampler.audio.FilterFx
import com.rg406v.sampler.audio.PadParams
import com.rg406v.sampler.audio.Recorder
import com.rg406v.sampler.audio.Sample
import com.rg406v.sampler.audio.Param
import com.rg406v.sampler.audio.ReverbFx
import com.rg406v.sampler.audio.SampleEdit
import com.rg406v.sampler.audio.SampleLoader
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/** Tout l'état de l'appli : pads, chaîne d'effets, sélection. Partagé entre l'UI, la manette et le moteur audio. */
class SamplerState(private val ctx: Context) {

    val pads = List(16) { PadParams(it) }
    val engine = AudioEngine(ctx, pads)

    /** Chaîne d'effets, dans l'ordre du signal (gauche → droite). */
    val fx = mutableStateListOf<Effect>()

    /** Effets individuels : une chaîne complète (6 effets) par pad. */
    val padFx: List<SnapshotStateList<Effect>> = List(16) {
        mutableStateListOf<Effect>().apply { addAll(makeChain(engine.sampleRate)) }
    }
    val padActive = mutableStateListOf<Boolean>().apply { repeat(16) { add(false) } }

    var selectedFx by mutableIntStateOf(0)
    var selectedPad by mutableIntStateOf(0)

    /** 0 = effets master, 1 = pad, 2 = effets du pad sélectionné. */
    var tab by mutableIntStateOf(0)

    /** L1 maintenu : la manette joue les pads 9 à 16. */
    var bankHeld by mutableStateOf(false)

    /** Sous-vue de l'onglet Pad : 0 = réglages, 1 = édition du sample. */
    var padView by mutableIntStateOf(0)

    // Réglages des outils d'édition
    val stretch = Param("Étirement", 0.25f, 4f, 1f, log = true, unit = "x", decimals = 2)
    val shift = Param("Transposer", -12f, 12f, 0f, unit = " st", decimals = 1)
    val slices = Param("Parts", 2f, 16f, 8f, stepped = true, decimals = 0)
    private var editing = false

    var requestPick: (Int) -> Unit = {}
    var requestMic: () -> Unit = {}

    // Enregistrement micro
    var recording by mutableStateOf(false)
    var recSeconds by mutableFloatStateOf(0f)
    var recLevel by mutableFloatStateOf(0f)
    private val recorder = Recorder { level, seconds ->
        recLevel = level
        recSeconds = seconds
        if (seconds >= Recorder.MAX_SECONDS - 0.1f) ui.post { stopRecording() }
    }

    private val prefs = ctx.getSharedPreferences("sampler", Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val latch = BooleanArray(4)

    init {
        val sr = engine.sampleRate
        fx.addAll(makeChain(sr))
        syncChain()
        for (i in 0 until 16) engine.setPadChain(i, padFx[i].toTypedArray())

        val defaults = DefaultSounds.build()
        defaults.forEachIndexed { i, s ->
            pads[i].sample = s
            pads[i].sampleName = s.name
        }
        for (i in 8..10) {
            pads[i].loop = true
            pads[i].gate = true
            pads[i].release.value = 0.15f
        }

        // Démos : deux pads FM et un nuage granulaire (le mode se change dans l'onglet Pad).
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
            sample = defaults[10]; sampleName = "Nuage"
            gPos.value = 0.5f; gSize.value = 120f; gDens.value = 30f; gSpray.value = 0.3f
            attack.value = 0.3f; release.value = 0.8f
        }

        // Recharge les samples choisis lors des lancements précédents.
        Thread {
            for (i in 0 until 16) {
                val s = prefs.getString("uri_$i", null) ?: continue
                loadSync(i, Uri.parse(s), notify = false)
            }
        }.start()
    }

    // ------------------------------------------------------------ pads

    fun padDown(i: Int) {
        selectedPad = i
        // Pendant l'enregistrement, toucher un pad choisit sa destination sans le jouer.
        if (recording) return
        padActive[i] = true
        engine.noteOn(i)
    }

    fun padUp(i: Int) {
        if (!padActive[i]) return
        padActive[i] = false
        engine.noteOff(i)
    }

    fun releaseAll() {
        for (i in 0 until 16) if (padActive[i]) padUp(i)
    }

    fun panic() {
        engine.stopAll()
        for (i in 0 until 16) padActive[i] = false
    }

    // ------------------------------------------------------------ chargement de samples

    private fun toast(msg: String) {
        ui.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun loadSync(i: Int, uri: Uri, notify: Boolean): Boolean {
        val loaded = SampleLoader.load(ctx, uri)
        if (loaded == null) {
            if (notify) toast("Format non supporté (WAV 16 bits, MP3, OGG, FLAC, AAC)")
            return false
        }
        // Au redémarrage, les samples édités ou enregistrés retrouvent leur nom.
        val savedName = if (notify) null else prefs.getString("name_$i", null)
        val s = if (savedName != null) Sample(savedName, loaded.data, loaded.sampleRate) else loaded
        val p = pads[i]
        p.clearHistory()
        p.sampleVersion++
        p.start.value = 0f
        p.end.value = 1f
        p.sample = s
        p.sampleName = s.name
        if (p.mode.value.toInt() == 2) p.mode.value = 0f
        val ed = prefs.edit()
        ed.putString("uri_$i", uri.toString())
        if (notify) ed.remove("name_$i")
        ed.apply()
        return true
    }

    fun loadFromUri(i: Int, uri: Uri) {
        Thread {
            if (loadSync(i, uri, notify = true)) toast("Pad ${i + 1} : ${pads[i].sampleName}")
        }.start()
    }

    // ------------------------------------------------------------ micro

    fun toggleRecord() {
        if (recording) { stopRecording(); return }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            requestMic()
        }
    }

    fun startRecording() {
        if (recording) return
        recSeconds = 0f
        recLevel = 0f
        if (recorder.start()) recording = true else toast("Micro indisponible")
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        recLevel = 0f
        val data = recorder.stop()
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
        Thread { saveWav(i, data, Recorder.SAMPLE_RATE, s.name) }.start()
    }

    /** Sauvegarde en WAV (enregistrements et éditions) pour les retrouver au prochain lancement. */
    private fun saveWav(i: Int, data: FloatArray, sr: Int, name: String) {
        try {
            val bytes = data.size * 2
            val bb = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()); bb.putInt(36 + bytes)
            bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
            bb.putInt(16); bb.putShort(1); bb.putShort(1)
            bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
            bb.put("data".toByteArray()); bb.putInt(bytes)
            for (v in data) bb.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            val f = File(ctx.filesDir, "Pad_${i + 1}.wav")
            f.writeBytes(bb.array())
            prefs.edit()
                .putString("uri_$i", Uri.fromFile(f).toString())
                .putString("name_$i", name)
                .apply()
        } catch (e: Exception) {
            // pas grave : le sample reste utilisable pour cette session
        }
    }

    // ------------------------------------------------------------ édition du sample

    private fun regionOf(p: PadParams): IntArray? {
        val s = p.sample ?: return null
        val len = s.data.size
        if (len < 2) return null
        val a = (p.start.value * len).toInt().coerceIn(0, len - 2)
        val b = (p.end.value * len).toInt().coerceIn(a + 1, len)
        return intArrayOf(a, b)
    }

    /** Lance une édition sur la sélection du pad courant, hors du thread principal. */
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
        Thread { saveWav(i, r.data, old.sampleRate, old.name) }.start()
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
        val i = selectedPad
        val p = pads[i]
        val snap = p.popHistory() ?: return
        p.sample = snap.sample
        snap.sample?.let { p.sampleName = it.name }
        p.start.value = snap.start
        p.end.value = snap.end
        p.sampleVersion++
        snap.sample?.let { s -> Thread { saveWav(i, s.data, s.sampleRate, s.name) }.start() }
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
                        val idx = i0 + k
                        val t = pads[idx]
                        t.pushHistory(t.sample, t.start.value, t.end.value)
                        val ns = Sample("${s.name} ${k + 1}", d, s.sampleRate)
                        t.sample = ns
                        t.sampleName = ns.name
                        t.start.value = 0f
                        t.end.value = 1f
                        t.sampleVersion++
                        if (t.mode.value.toInt() == 2) t.mode.value = 0f
                        Thread { saveWav(idx, d, s.sampleRate, ns.name) }.start()
                    }
                    editing = false
                    toast("Découpé sur les pads ${i0 + 1} à ${i0 + parts.size}")
                }
            } catch (e: Throwable) {
                ui.post { editing = false; toast("Échec de la découpe") }
            }
        }.start()
    }

    // ------------------------------------------------------------ effets

    private fun makeChain(sr: Int): List<Effect> =
        listOf(FilterFx(sr), DistortionFx(sr), BitcrusherFx(), ChorusFx(sr), DelayFx(sr), ReverbFx(sr))

    /** Chaîne actuellement éditée : celle du pad (onglet 2) ou la chaîne master. */
    fun chain(): SnapshotStateList<Effect> = if (tab == 2) padFx[selectedPad] else fx

    private fun syncChain() {
        if (tab == 2) engine.setPadChain(selectedPad, padFx[selectedPad].toTypedArray())
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
        tab = (tab + 1) % 3
    }

    /** Start : active/désactive l'effet (onglet FX) ou ouvre le sélecteur de fichier (onglet pad). */
    fun startPressed() {
        if (tab != 1) {
            chain().getOrNull(selectedFx)?.let { it.enabled = !it.enabled }
        } else {
            requestPick(selectedPad)
        }
    }

    /**
     * Sticks : réglage relatif des paramètres de l'écran courant.
     * Onglet FX : stick gauche X/Y = param 1/2, stick droit X/Y = param 3/4.
     * Onglet pad : gauche X = pitch, gauche Y = volume, droit X = début, droit Y = fin.
     */
    fun stickAdjust(lx: Float, ly: Float, rx: Float, ry: Float, dt: Float) {
        val axes = floatArrayOf(lx, ly, rx, ry)
        val targets: List<Param?> = if (tab != 1) {
            val ps = chain().getOrNull(selectedFx)?.params ?: emptyList()
            List(4) { ps.getOrNull(it) }
        } else {
            val p = pads[selectedPad]
            when (p.mode.value.toInt()) {
                1 -> listOf(p.gPos, p.gSize, p.gDens, p.gSpray)
                2 -> listOf(p.fmRatio, p.fmIndex, p.fmFeedback, p.fmModDecay)
                else -> listOf(p.pitch, p.volume, p.start, p.end)
            }
        }
        for (i in 0 until 4) {
            val a = axes[i]
            val p = targets[i]
            if (p == null) continue
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
}
