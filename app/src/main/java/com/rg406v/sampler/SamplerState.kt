package com.rg406v.sampler

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rg406v.sampler.audio.AudioEngine
import com.rg406v.sampler.audio.BitcrusherFx
import com.rg406v.sampler.audio.ChorusFx
import com.rg406v.sampler.audio.DefaultSounds
import com.rg406v.sampler.audio.DelayFx
import com.rg406v.sampler.audio.DistortionFx
import com.rg406v.sampler.audio.Effect
import com.rg406v.sampler.audio.FilterFx
import com.rg406v.sampler.audio.PadParams
import com.rg406v.sampler.audio.Param
import com.rg406v.sampler.audio.ReverbFx
import com.rg406v.sampler.audio.SampleLoader
import kotlin.math.abs

/** Tout l'état de l'appli : pads, chaîne d'effets, sélection. Partagé entre l'UI, la manette et le moteur audio. */
class SamplerState(private val ctx: Context) {

    val pads = List(16) { PadParams(it) }
    val engine = AudioEngine(ctx, pads)

    /** Chaîne d'effets, dans l'ordre du signal (gauche → droite). */
    val fx = mutableStateListOf<Effect>()
    val padActive = mutableStateListOf<Boolean>().apply { repeat(16) { add(false) } }

    var selectedFx by mutableIntStateOf(0)
    var selectedPad by mutableIntStateOf(0)

    /** 0 = onglet effets, 1 = onglet pad. */
    var tab by mutableIntStateOf(0)

    /** L1 maintenu : la manette joue les pads 9 à 16. */
    var bankHeld by mutableStateOf(false)

    var requestPick: (Int) -> Unit = {}

    private val prefs = ctx.getSharedPreferences("sampler", Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val latch = BooleanArray(4)

    init {
        val sr = engine.sampleRate
        fx.addAll(listOf(FilterFx(sr), DistortionFx(sr), BitcrusherFx(), ChorusFx(sr), DelayFx(sr), ReverbFx(sr)))
        syncChain()

        DefaultSounds.build().forEachIndexed { i, s ->
            pads[i].sample = s
            pads[i].sampleName = s.name
        }
        for (i in 8..10) {
            pads[i].loop = true
            pads[i].gate = true
            pads[i].release.value = 0.15f
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
        padActive[i] = true
        engine.noteOn(i)
    }

    fun padUp(i: Int) {
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
        val s = SampleLoader.load(ctx, uri)
        if (s == null) {
            if (notify) toast("Format non supporté (WAV 16 bits, MP3, OGG, FLAC, AAC)")
            return false
        }
        val p = pads[i]
        p.start.value = 0f
        p.end.value = 1f
        p.sample = s
        p.sampleName = s.name
        prefs.edit().putString("uri_$i", uri.toString()).apply()
        return true
    }

    fun loadFromUri(i: Int, uri: Uri) {
        Thread {
            if (loadSync(i, uri, notify = true)) toast("Pad ${i + 1} : ${pads[i].sampleName}")
        }.start()
    }

    // ------------------------------------------------------------ effets

    private fun syncChain() {
        engine.chain = fx.toTypedArray()
    }

    fun selectFx(dir: Int) {
        if (fx.isEmpty()) return
        selectedFx = (selectedFx + dir).mod(fx.size)
    }

    fun moveFx(dir: Int) {
        val a = selectedFx
        val b = a + dir
        if (b !in fx.indices) return
        val t = fx[a]
        fx[a] = fx[b]
        fx[b] = t
        selectedFx = b
        syncChain()
    }

    // ------------------------------------------------------------ manette

    fun toggleTab() {
        tab = if (tab == 0) 1 else 0
    }

    /** Start : active/désactive l'effet (onglet FX) ou ouvre le sélecteur de fichier (onglet pad). */
    fun startPressed() {
        if (tab == 0) {
            fx.getOrNull(selectedFx)?.let { it.enabled = !it.enabled }
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
        val targets: List<Param?> = if (tab == 0) {
            val ps = fx.getOrNull(selectedFx)?.params ?: emptyList()
            List(4) { ps.getOrNull(it) }
        } else {
            val p = pads[selectedPad]
            listOf(p.pitch, p.volume, p.start, p.end)
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
