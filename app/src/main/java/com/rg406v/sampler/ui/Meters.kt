package com.rg406v.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rg406v.sampler.SamplerState
import com.rg406v.sampler.audio.CompressorFx
import com.rg406v.sampler.audio.Effect
import com.rg406v.sampler.audio.EqFx
import com.rg406v.sampler.audio.FilterverseFx
import com.rg406v.sampler.audio.ManipulatorFx
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

internal val Hot = Color(0xFFFF3B5C)

/** Compteur qui s'incrémente toutes les `ms` : à lire dans un Canvas pour le redessiner en continu. */
@Composable
internal fun rememberTick(ms: Long = 50L): androidx.compose.runtime.MutableIntState {
    val t = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ms)
            t.intValue = t.intValue + 1
        }
    }
    return t
}

private fun dbText(pos: Float): String = if (pos <= 0.01f) "−∞" else "%.1f".format(pos * 60f - 60f)

/** Barre de niveau -60..0 dB : vert/cyan, ambre vers -15 dB, rouge vers -6 dB, repère de crête. */
@Composable
private fun LevelBar(level: Float, hold: Float, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(3.dp))) {
        val w = size.width
        val h = size.height
        drawRect(TrackCol, Offset(0f, 0f), Size(w, h))
        val v = level.coerceIn(0f, 1f)
        fun seg(a: Float, b: Float, c: Color) {
            val x0 = a * w
            val x1 = minOf(b, v) * w
            if (x1 > x0) drawRect(c, Offset(x0, 0f), Size(x1 - x0, h))
        }
        seg(0f, 0.75f, Cyan)
        seg(0.75f, 0.90f, Amber)
        seg(0.90f, 1f, Hot)
        if (hold > 0.01f) {
            val hx = (hold.coerceIn(0f, 1f) * w).coerceIn(1f, w - 1f)
            drawLine(Color.White, Offset(hx, 0f), Offset(hx, h), strokeWidth = 2f)
        }
    }
}

@Composable
private fun Led(on: Boolean) {
    Box(
        Modifier
            .size(9.dp)
            .clip(RoundedCornerShape(50))
            .background(if (on) Hot else TrackCol)
    )
}

/** Bandeau de niveaux : entrée (micro), sortie L/R avec crête en dB, voyants de saturation, charge CPU. */
@Composable
internal fun MeterStrip(state: SamplerState) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Bg, shape)
            .border(1.dp, if (state.outClip) Hot else Line, shape)
            .clickable { state.resetClip() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("in", fontSize = 9.sp, color = if (state.inMonitor || state.recording) TextHi else Dim)
                Led(state.inClip)
            }
            LevelBar(state.inLvl, 0f, Modifier.fillMaxWidth().height(7.dp))
        }
        Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("out", fontSize = 9.sp, color = TextHi)
                Led(state.outClip)
                Text(
                    "L " + dbText(state.holdL) + "  R " + dbText(state.holdR) + " dB",
                    fontSize = 9.sp,
                    color = if (state.outClip) Hot else Dim,
                    maxLines = 1,
                )
            }
            LevelBar(state.outL, state.holdL, Modifier.fillMaxWidth().height(5.dp))
            LevelBar(state.outR, state.holdR, Modifier.fillMaxWidth().height(5.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("cpu", fontSize = 9.sp, color = Dim)
            Text(
                "%.0f%%".format(state.cpu),
                fontSize = 11.sp,
                color = if (state.cpu > 80f) Hot else TextHi,
            )
        }
    }
}

// ------------------------------------------------------------------ corps des effets (EQ, compresseur, autres)

@Composable
internal fun FxBody(state: SamplerState, sel: Effect) {
    when (sel) {
        is EqFx -> EqPanel(state, sel)
        is CompressorFx -> {
            GrMeter(sel)
            KnobGrid(sel.params, AccFx)
            Text(
                "kick : à chaque note de ce pad (clavier ou séquenceur), le reste du son baisse puis remonte, " +
                    "comme le side-kick du KO II. duck = profondeur, duck rel = durée de remontée.",
                fontSize = 10.sp,
                color = Dim,
            )
        }
        is ManipulatorFx -> {
            KnobGrid(sel.params, AccFx)
            Text(
                "transpose le son de -24 à +24 demi-tons. fine = réglage en cents, spread = désaccord gauche/droite, " +
                    "retour = écho transposé qui monte ou descend, tilt = bascule du timbre vers le grave ou l'aigu.",
                fontSize = 10.sp,
                color = Dim,
            )
        }
        is FilterverseFx -> {
            KnobGrid(sel.params, AccFx)
            Text(
                "10 réglages : cutoff, reso, drive, type (LP12, LP24, HP, BP, notch, peigne, voyelle, phaser), " +
                    "LFO (vitesse, profondeur, forme), env = le volume du son module le filtre, stéréo = décalage du LFO gauche/droite.",
                fontSize = 10.sp,
                color = Dim,
            )
        }
        else -> KnobGrid(sel.params, AccFx)
    }
    AutoSection(state, sel)
}

@Composable
internal fun AutoSection(state: SamplerState, e: Effect) {
    MotionSection(state, e)
    val on = e.autoOn.value >= 0.5f
    SectionTitle("automation : LFO")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(if (on) "auto : on" else "auto : off", on, AccFm) { e.autoOn.value = if (on) 0f else 1f }
        Text(
            "un LFO fait bouger un réglage autour de la valeur du bouton, au rythme du tempo.",
            fontSize = 10.sp,
            color = Dim,
            modifier = Modifier.weight(1f),
        )
    }
    if (on) {
        val sc = rememberScrollState()
        Text("réglage à automatiser", fontSize = 10.sp, color = Dim)
        Row(Modifier.horizontalScroll(sc), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            e.params.forEachIndexed { i, p ->
                Pill(p.name, e.autoTarget.value.toInt() == i, AccFx, hPad = 8) { e.autoTarget.value = i.toFloat() }
            }
        }
        Text("forme", fontSize = 10.sp, color = Dim)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            e.autoShape.labels?.forEachIndexed { i, t ->
                Pill(t, e.autoShape.value.toInt() == i, AccGrain, Modifier.weight(1f), hPad = 0) { e.autoShape.value = i.toFloat() }
            }
        }
        Text("vitesse (durée d'un cycle)", fontSize = 10.sp, color = Dim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            e.autoRate.labels?.forEachIndexed { i, t ->
                Pill(t, e.autoRate.value.toInt() == i, AccGrain, hPad = 8) { e.autoRate.value = i.toFloat() }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Knob(e.autoDepth, AccFx, knobSize = 40.dp)
            if (e.autoRate.value.toInt() >= 7) Knob(e.autoHz, AccFx, knobSize = 40.dp)
        }
    }
}


@Composable
private fun GrMeter(c: CompressorFx) {
    val tick = rememberTick(40)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("réduction", fontSize = 10.sp, color = Dim)
        Canvas(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            tick.intValue
            drawRect(TrackCol, Offset(0f, 0f), size)
            val g = (c.reduction / 24f).coerceIn(0f, 1f)
            drawRect(AccFx.brush(size.width, 10f), Offset(0f, 0f), Size(size.width * g, size.height))
        }
        Text("%.1f dB".format(c.reduction), fontSize = 10.sp, color = TextHi, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
    }
}

private fun fToX(f: Float, w: Float): Float = (ln(f / 20f) / ln(1000f)) * w
private fun xToF(x: Float, w: Float): Float = 20f * 1000f.pow((x / w).coerceIn(0f, 1f))

@Composable
private fun EqPanel(state: SamplerState, eq: EqFx) {
    val tick = rememberTick(40)
    val curve = remember { FloatArray(160) }
    val spectrum = state.engine.spectrum
    val shape = RoundedCornerShape(12.dp)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(shape)
            .background(Bg, shape)
            .border(1.dp, Line, shape)
            .pointerInput(eq) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    var best = -1
                    var bestD = 48.dp.toPx()
                    for (b in eq.bands) {
                        val bx = fToX(b.freq.value, w)
                        val by = if (b.usesGain()) h * (1f - (b.gain.value + 18f) / 36f) else h / 2f
                        val d = abs(bx - down.position.x) + abs(by - down.position.y)
                        if (d < bestD) { bestD = d; best = b.index }
                    }
                    if (best < 0) return@awaitEachGesture
                    eq.selBand = best
                    val b = eq.bands[best]
                    b.on = true
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        b.freq.value = xToF(c.position.x, w).coerceIn(b.freq.min, b.freq.max)
                        if (b.usesGain()) {
                            b.gain.value = ((1f - c.position.y / h) * 36f - 18f).coerceIn(-18f, 18f)
                        }
                        c.consume()
                    }
                }
            }
    ) {
        tick.intValue
        val w = size.width
        val h = size.height
        // grille : 100 Hz, 1 kHz, 10 kHz et 0 dB
        for (f in floatArrayOf(100f, 1000f, 10000f)) {
            val x = fToX(f, w)
            drawLine(Line, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        drawLine(Line, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)
        // spectre du mix en fond
        val bins = spectrum.bins
        val bw = w / bins.size
        for (i in bins.indices) {
            val bh = bins[i] * h * 0.85f
            drawRect(
                Violet.copy(alpha = 0.28f),
                Offset(i * bw, h - bh),
                Size(bw * 0.8f, bh),
            )
        }
        // courbe de réponse
        eq.curve(curve)
        val path = Path()
        for (i in curve.indices) {
            val x = w * i / (curve.size - 1f)
            val y = (h * (1f - (curve[i] + 18f) / 36f)).coerceIn(0f, h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        // points de bande
        for (b in eq.bands) {
            val col = Color(EqFx.BAND_COLORS[b.index])
            val bx = fToX(b.freq.value, w)
            val by = if (b.usesGain()) h * (1f - (b.gain.value + 18f) / 36f) else h / 2f
            val r = if (b.index == eq.selBand) 8.dp.toPx() else 6.dp.toPx()
            if (b.on) {
                drawCircle(col, r, Offset(bx, by))
            } else {
                drawCircle(col.copy(alpha = 0.5f), r, Offset(bx, by), style = Stroke(1.5.dp.toPx()))
            }
            if (b.index == eq.selBand) drawCircle(Color.White, r + 3.dp.toPx(), Offset(bx, by), style = Stroke(1.5.dp.toPx()))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in eq.bands) {
            val col = Color(EqFx.BAND_COLORS[b.index])
            val sel = b.index == eq.selBand
            val sh = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(sh)
                    .background(if (sel) ModuleHi else Bg, sh)
                    .border(if (sel) 1.5.dp else 1.dp, if (sel) col else Line, sh)
                    .clickable { eq.selBand = b.index },
                contentAlignment = Alignment.Center,
            ) {
                Text("${b.index + 1}", fontSize = 11.sp, color = if (b.on) col else Dim, fontWeight = if (b.on) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
    val b = eq.bands[eq.selBand.coerceIn(0, eq.bands.size - 1)]
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("bande " + (b.index + 1) + (if (b.on) " on" else " off"), b.on, AccFx) { b.on = !b.on }
        Pill(b.type.display(), false, AccFx) { b.type.value = if (b.type.value >= b.type.max) b.type.min else b.type.value + 1f }
    }
    KnobGrid(eq.params, AccFx)
    Text("glisse les points sur la courbe : horizontal = fréquence, vertical = gain. le spectre violet est le mix.", fontSize = 10.sp, color = Dim)
}

@Composable
private fun MotionSection(state: SamplerState, e: Effect) {
    state.laneVersion // relit la liste des courbes quand elle change
    SectionTitle("automation : mouvements")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(if (state.autoRec) "● rec : on" else "● rec : off", state.autoRec, AccFm) { state.toggleAutoRec() }
        Text(
            "rec on : bouge les boutons de n'importe quel effet pendant que la boucle tourne, ils sont enregistrés. " +
                "rec off pour valider. toucher un bouton reprend la main un instant.",
            fontSize = 10.sp,
            color = Dim,
            modifier = Modifier.weight(1f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("boucle", fontSize = 10.sp, color = Dim, modifier = Modifier.width(44.dp))
        for (n in intArrayOf(1, 2, 4, 8)) {
            Pill("$n mes", state.engine.autoBars == n, AccGrain, Modifier.weight(1f), hPad = 0) { state.setAutoBars(n) }
        }
        Pill(if (state.engine.autoEnabled) "lecture on" else "lecture off", state.engine.autoEnabled, AccFx, hPad = 8) {
            state.engine.autoEnabled = !state.engine.autoEnabled
            state.laneVersion++
        }
    }
    val withLane = e.persistParams.filter { it.lane != null }
    if (withLane.isNotEmpty()) {
        Text("courbes de cet effet (touche pour effacer)", fontSize = 10.sp, color = Dim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (q in withLane) Pill(q.name + " ✕", true, AccFm, hPad = 8) { state.clearLane(q) }
            Pill("tout effacer", false, AccFx, hPad = 8) { state.clearLanes(e) }
        }
    }
}
