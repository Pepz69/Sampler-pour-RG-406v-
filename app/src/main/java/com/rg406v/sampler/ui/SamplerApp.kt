package com.rg406v.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rg406v.sampler.SamplerState
import com.rg406v.sampler.TAB_AUDIO
import com.rg406v.sampler.TAB_FX
import com.rg406v.sampler.TAB_KITS
import com.rg406v.sampler.TAB_PAD
import com.rg406v.sampler.TAB_PADFX
import com.rg406v.sampler.TAB_PIANO
import com.rg406v.sampler.TAB_PUNCH
import com.rg406v.sampler.TAB_SEQ
import com.rg406v.sampler.audio.PadParams
import com.rg406v.sampler.audio.Wavetables
import com.rg406v.sampler.audio.Param
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ------------------------------------------------------------------ style
// Fond presque noir, modules arrondis, filets fins, dégradés violet / cyan / rose.

internal val Bg = Color(0xFF0B0C10)
internal val Module = Color(0xFF14151C)
internal val ModuleHi = Color(0xFF1B1D27)
internal val Line = Color(0xFF23263A)
internal val TextHi = Color(0xFFE9EAF2)
internal val Dim = Color(0xFF7C8095)
internal val Violet = Color(0xFF8B6CFF)
internal val Cyan = Color(0xFF4FD1FF)
internal val Pink = Color(0xFFFF5C8A)
internal val Amber = Color(0xFFFFB45C)
internal val TrackCol = Color(0xFF262A3B)

internal class Accent(val a: Color, val b: Color) {
    fun brush(w: Float = 200f, h: Float = 200f) =
        Brush.linearGradient(listOf(a, b), start = Offset(0f, 0f), end = Offset(w, h))
}

internal val AccFx = Accent(Violet, Pink)
internal val AccSampler = Accent(Cyan, Violet)
internal val AccGrain = Accent(Amber, Pink)
internal val AccFm = Accent(Pink, Violet)

internal fun modeAccent(mode: Int) = when (mode) {
    1 -> AccGrain
    2 -> AccFm
    3 -> AccFx
    else -> AccSampler
}

@Composable
fun SamplerApp(state: SamplerState) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Violet)) {
        Surface(color = Bg, contentColor = TextHi, modifier = Modifier.fillMaxSize()) {
            AppShell(state)
        }
    }
}

// ------------------------------------------------------------------ pads

@Composable
internal fun PadGrid(state: SamplerState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until 4) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (col in 0 until 4) {
                    PadButton(state, row * 4 + col, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

internal val padHints = listOf("A", "B", "X", "Y", "↑", "→", "↓", "←")

@Composable
internal fun PadButton(state: SamplerState, idx: Int, modifier: Modifier) {
    val pad = state.pads[idx]
    val active = state.padActive[idx]
    val selected = state.selectedPad == idx
    val bankActive = (idx >= 8) == state.bankHeld
    val mode = pad.mode.value.toInt()
    val acc = modeAccent(mode)
    val shape = RoundedCornerShape(14.dp)
    val hint = (if (idx >= 8) "l1+" else "") + padHints[idx % 8].lowercase()
    val label = when (mode) {
        1 -> "grains\n" + pad.sampleName
        2 -> pad.fmName
        3 -> pad.displayName
        else -> pad.sampleName
    }
    val recTarget = state.recording && selected
    val hasFx = state.padFx[idx].any { it.enabled }

    Box(
        modifier
            .drawBehind {
                if (active) {
                    drawRoundRect(
                        Pink.copy(alpha = 0.22f),
                        topLeft = Offset(-5f, -5f),
                        size = Size(size.width + 10f, size.height + 10f),
                        cornerRadius = CornerRadius(26f, 26f),
                    )
                }
            }
            .clip(shape)
            .then(
                if (active) Modifier.background(Brush.linearGradient(listOf(Violet, Pink)), shape)
                else Modifier.background(if (bankActive) Module else Bg, shape)
            )
            .then(
                if (selected) Modifier.border(1.5.dp, acc.brush(), shape)
                else Modifier.border(1.dp, Line, shape)
            )
            .pointerInput(idx) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    state.padDown(idx)
                    waitForUpOrCancellation()
                    state.padUp(idx)
                }
            }
    ) {
        Text(
            hint,
            fontSize = 9.sp,
            color = if (active) Color.White.copy(alpha = 0.8f) else Dim,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) Color.White else TextHi,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
        )
        if (hasFx) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Color.White else Cyan)
            )
        }
        if (recTarget) {
            Text(
                "● rec",
                fontSize = 9.sp,
                color = Pink,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
        if (pad.loop && mode == 0) {
            Text(
                "loop",
                fontSize = 9.sp,
                color = if (active) Color.White.copy(alpha = 0.8f) else Cyan,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ effets

@Composable
internal fun FxPanel(state: SamplerState) {
    val chain = state.chain()
    val sel = chain.getOrNull(state.selectedFx) ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            chain.forEachIndexed { i, e ->
                val chosen = i == state.selectedFx
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(shape)
                        .background(if (chosen) ModuleHi else Bg, shape)
                        .then(
                            if (chosen) Modifier.border(1.5.dp, AccFx.brush(), shape)
                            else Modifier.border(1.dp, Line, shape)
                        )
                        .clickable { state.selectedFx = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        e.short.lowercase(),
                        fontSize = 10.sp,
                        color = if (chosen || e.enabled) TextHi else Dim,
                        maxLines = 1,
                    )
                    if (e.enabled) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(AccFx.brush(10f, 10f))
                        )
                    }
                }
            }
        }
        Text(
            (if (state.tab == 2) "effets du pad ${state.selectedPad + 1}" else "effets master") +
                " · signal de gauche à droite",
            fontSize = 10.sp,
            color = Dim,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                sel.name.lowercase(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.weight(1f),
            )
            Pill("‹", false, AccFx) { state.moveFx(-1) }
            Pill("›", false, AccFx) { state.moveFx(1) }
            Switch(
                checked = sel.enabled,
                onCheckedChange = { sel.enabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Violet,
                    checkedBorderColor = Violet,
                    uncheckedThumbColor = Dim,
                    uncheckedTrackColor = TrackCol,
                    uncheckedBorderColor = Line,
                ),
            )
        }
        FxBody(state, sel)
        Text("sticks : gauche = 1er et 2e réglage, droit = 3e", fontSize = 10.sp, color = Dim)
    }
}

// ------------------------------------------------------------------ pad

@Composable
internal fun PadPanel(state: SamplerState) {
    val pad = state.pads[state.selectedPad]
    val mode = pad.mode.value.toInt()
    val acc = modeAccent(mode)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (mode < 2) Pill("charger", false, acc) { state.requestPick(state.selectedPad) }
            Pill(
                if (state.recording) "■ %.1f s".format(state.recSeconds) else "● micro",
                state.recording,
                AccFm,
            ) { state.toggleRecord() }
            Spacer(Modifier.weight(1f))
            Pill("réglages", state.padView == 0, acc) { state.padView = 0 }
            Pill("édition", state.padView == 1, acc) { state.padView = 1 }
        }
        if (state.recording) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TrackCol)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(state.recLevel.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(AccFm.brush(300f, 10f))
                )
            }
            Text("touche un pad pour choisir où va l'enregistrement", fontSize = 10.sp, color = Dim)
        }

        if (state.padView == 1) {
            EditSection(state, pad, mode)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("sampler", "grain", "fm", "wavetable").forEachIndexed { k, t ->
                    Pill(t, mode == k, modeAccent(k)) {
                        pad.mode.value = k.toFloat()
                        if (k == 3) Wavetables.prefetch(pad.wtTable.value.toInt())
                    }
                }
            }
            if (mode < 2) Waveform(pad, mode == 1, acc)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mode == 0) Pill("loop", pad.loop, acc) { pad.loop = !pad.loop }
                if (mode != 1) Pill("gate", pad.gate || (mode == 0 && pad.loop), acc) { pad.gate = !pad.gate }
            }

            when (mode) {
                1 -> {
                    SectionTitle("grains")
                    KnobGrid(listOf(pad.gPos, pad.gSize, pad.gDens, pad.gSpray, pad.gDetune, pad.gScan), acc)
                    SectionTitle("son")
                    KnobGrid(listOf(pad.volume, pad.pan, pad.pitch, pad.start, pad.end), acc)
                }
                3 -> WavetableBody(pad, acc)
                2 -> {
                    SectionTitle("fm")
                    KnobGrid(listOf(pad.fmRatio, pad.fmIndex, pad.fmFeedback, pad.fmModDecay), acc)
                    SectionTitle("son")
                    KnobGrid(listOf(pad.volume, pad.pan, pad.pitch), acc)
                }
                else -> {
                    SectionTitle("son")
                    KnobGrid(listOf(pad.volume, pad.pan, pad.pitch, pad.start, pad.end), acc)
                }
            }
            SectionTitle("enveloppe")
            KnobGrid(listOf(pad.attack, pad.decay, pad.sustain, pad.release), acc)

            Text(
                when (mode) {
                    1 -> "sticks : gauche = position / taille, droit = densité / spray · R2 = micro"
                    2 -> "sticks : gauche = ratio / index, droit = feedback / mod decay · R2 = micro"
                    3 -> "sticks : gauche = position / sync, droit = unison / détune · R2 = micro"
                    else -> "sticks : gauche = pitch / volume, droit = début / fin · R2 = micro"
                },
                fontSize = 10.sp,
                color = Dim,
            )
        }
    }
}

/** Outils d'édition du sample du pad sélectionné (agissent sur la zone entre les deux repères). */
@Composable
internal fun EditSection(state: SamplerState, pad: PadParams, mode: Int) {
    if (mode >= 2) {
        Text(
            "les modes fm et wavetable n'utilisent pas de sample. passe en sampler ou granulaire pour éditer.",
            fontSize = 11.sp,
            color = Dim,
        )
        return
    }
    val acc = modeAccent(mode)
    val version = pad.sampleVersion // relu pour rafraîchir l'info après chaque édition
    val s = pad.sample
    val info = if (s == null || version < 0) "aucun sample sur ce pad" else {
        val dur = s.data.size / s.sampleRate.toFloat()
        "sélection %.2f s sur %.2f s".format((pad.end.value - pad.start.value) * dur, dur)
    }

    Waveform(pad, false, acc)
    Text("$info · glisse sur la forme d'onde pour placer les repères", fontSize = 10.sp, color = Dim)

    SectionTitle("sélection")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        EditBtn("garder", Modifier.weight(1f)) { state.editCrop() }
        EditBtn("supprimer", Modifier.weight(1f)) { state.editCut() }
        EditBtn("silences", Modifier.weight(1f)) { state.editTrimSilence() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        EditBtn("inverser", Modifier.weight(1f)) { state.editReverse() }
        EditBtn("fondu in", Modifier.weight(1f)) { state.editFadeIn() }
        EditBtn("fondu out", Modifier.weight(1f)) { state.editFadeOut() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        EditBtn("normaliser", Modifier.weight(1f)) { state.editNormalize() }
        EditBtn("tout", Modifier.weight(1f)) { state.selectAll() }
        EditBtn("annuler", Modifier.weight(1f), enabled = pad.canUndo) { state.undo() }
    }

    SectionTitle("temps et hauteur")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(62.dp), contentAlignment = Alignment.Center) { Knob(state.stretch, acc) }
        EditBtn("étirer la sélection", Modifier.weight(1f)) { state.editStretch() }
    }
    Text("x2 = deux fois plus long, hauteur inchangée", fontSize = 10.sp, color = Dim)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(62.dp), contentAlignment = Alignment.Center) { Knob(state.shift, acc) }
        EditBtn("transposer, durée gardée", Modifier.weight(1f)) { state.editShift() }
    }
    SectionTitle("découpe")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(62.dp), contentAlignment = Alignment.Center) { Knob(state.slices, acc) }
        EditBtn("découper vers les pads suivants", Modifier.weight(1f)) { state.editSlice() }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, fontSize = 10.sp, color = Dim, letterSpacing = 1.sp, modifier = Modifier.padding(top = 2.dp))
}

@Composable
internal fun EditBtn(text: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(Bg, shape)
            .border(1.dp, Line, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (enabled) TextHi else Dim.copy(alpha = 0.5f),
        )
    }
}

/** Bouton pastille : plein dégradé translucide quand il est actif. */
@Composable
internal fun Pill(
    text: String,
    on: Boolean,
    acc: Accent,
    modifier: Modifier = Modifier,
    hPad: Int = 12,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(28.dp)
            .clip(shape)
            .then(
                if (on) Modifier
                    .background(
                        Brush.horizontalGradient(listOf(acc.a.copy(alpha = 0.28f), acc.b.copy(alpha = 0.28f))),
                        shape,
                    )
                    .border(1.dp, acc.a.copy(alpha = 0.85f), shape)
                else Modifier.background(Bg, shape).border(1.dp, Line, shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = hPad.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.sp, maxLines = 1, color = if (on) TextHi else Dim)
    }
}

// ------------------------------------------------------------------ forme d'onde

@Composable
internal fun Waveform(pad: PadParams, grain: Boolean, acc: Accent) {
    val name = pad.sampleName // lus pour redessiner quand le sample change ou est édité
    val version = pad.sampleVersion
    val peaks = pad.sample?.peaks
    val shape = RoundedCornerShape(12.dp)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(shape)
            .background(Bg, shape)
            .border(1.dp, Line, shape)
            .pointerInput(pad) {
                // Glisser : déplace le repère (début ou fin) le plus proche du doigt.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val nearStart =
                        abs(down.position.x / w - pad.start.value) <= abs(down.position.x / w - pad.end.value)
                    fun move(x: Float) {
                        val f = (x / w).coerceIn(0f, 1f)
                        if (nearStart) pad.start.value = min(f, pad.end.value - 0.002f).coerceAtLeast(0f)
                        else pad.end.value = max(f, pad.start.value + 0.002f).coerceAtMost(1f)
                    }
                    move(down.position.x)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull() ?: break
                        if (!c.pressed) break
                        move(c.position.x)
                        c.consume()
                    }
                }
            }
    ) {
        val w = size.width
        val h = size.height
        if (peaks != null && name.isNotEmpty() && version >= 0) {
            val step = w / peaks.size
            val brush = acc.brush(w, h)
            for (i in peaks.indices) {
                val x = i * step + step / 2f
                val a = (peaks[i] * h * 0.42f).coerceAtLeast(1.5f)
                drawLine(
                    brush,
                    Offset(x, h / 2f - a),
                    Offset(x, h / 2f + a),
                    strokeWidth = (step * 0.55f).coerceAtLeast(1f),
                    cap = StrokeCap.Round,
                )
            }
        }
        val sx = pad.start.value * w
        val ex = pad.end.value * w
        drawRect(Bg.copy(alpha = 0.7f), Offset(0f, 0f), Size(sx, h))
        drawRect(Bg.copy(alpha = 0.7f), Offset(ex, 0f), Size((w - ex).coerceAtLeast(0f), h))
        drawLine(acc.a, Offset(sx, 0f), Offset(sx, h), strokeWidth = 2f)
        drawLine(acc.a, Offset(ex, 0f), Offset(ex, h), strokeWidth = 2f)
        drawCircle(acc.a, 4.dp.toPx(), Offset(sx, 7.dp.toPx()))
        drawCircle(acc.a, 4.dp.toPx(), Offset(ex, h - 7.dp.toPx()))
        if (grain) {
            val gx = (pad.start.value + pad.gPos.value * (pad.end.value - pad.start.value)) * w
            drawLine(Color.White, Offset(gx, 0f), Offset(gx, h), strokeWidth = 2.5f)
        }
    }
}

// ------------------------------------------------------------------ boutons rotatifs

@Composable
internal fun KnobGrid(params: List<Param>, acc: Accent, perRow: Int = 4) {
    params.chunked(perRow).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { p ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Knob(p, acc) }
            }
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * Bouton rotatif : glisse verticalement pour régler, double-tap pour revenir à la valeur par défaut.
 * Les paramètres bipolaires (pan, pitch...) dessinent l'arc depuis le centre.
 */
@Composable
internal fun Knob(
    p: Param,
    acc: Accent,
    modifier: Modifier = Modifier,
    knobSize: Dp = 44.dp,
    showValue: Boolean = true,
) {
    val drag = remember { floatArrayOf(0f) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(knobSize)
                .pointerInput(p) { detectTapGestures(onDoubleTap = { p.value = p.defaultValue }) }
                .pointerInput(p) {
                    detectVerticalDragGestures(
                        onDragStart = { drag[0] = p.norm },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            drag[0] = (drag[0] - dy / 220f).coerceIn(0f, 1f)
                            p.norm = drag[0]
                        },
                    )
                }
        ) {
            val stroke = 4.dp.toPx()
            val d = this.size.minDimension - stroke
            val tl = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(d, d)
            drawArc(
                color = TrackCol,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = tl,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val n = p.norm
            val bipolar = p.min < 0f && p.max > 0f
            val n0 = if (bipolar) (0f - p.min) / (p.max - p.min) else 0f
            val sweep = abs(n - n0)
            if (sweep > 0.002f) {
                drawArc(
                    brush = acc.brush(this.size.width, this.size.height),
                    startAngle = 135f + 270f * min(n0, n),
                    sweepAngle = 270f * sweep,
                    useCenter = false,
                    topLeft = tl,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            val ang = (135f + 270f * n) * (PI.toFloat() / 180f)
            val r = d / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            drawCircle(Color.White, 2.5.dp.toPx(), Offset(c.x + r * cos(ang), c.y + r * sin(ang)))
        }
        Text(
            p.name.lowercase(),
            fontSize = 9.sp,
            color = Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 56.dp),
        )
        if (showValue) {
            Text(
                p.display().lowercase(),
                fontSize = 10.sp,
                color = TextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 56.dp),
            )
        }
    }
}

/** Réglages du mode wavetable : choix de la table, morphing, unison, sync, mouvement. */
@Composable
internal fun WavetableBody(pad: PadParams, acc: Accent) {
    val open = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val t = pad.wtTable.value.toInt()
    androidx.compose.runtime.LaunchedEffect(t) { Wavetables.prefetch(t) }
    SectionTitle("table")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Pill("‹", false, acc, hPad = 10) { pad.wtTable.value = ((t - 1 + Wavetables.COUNT) % Wavetables.COUNT).toFloat() }
        Text(
            "${t + 1}. " + pad.wtTable.display(),
            fontSize = 12.sp,
            color = TextHi,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Pill("›", false, acc, hPad = 10) { pad.wtTable.value = ((t + 1) % Wavetables.COUNT).toFloat() }
        Pill("liste", open.value, acc, hPad = 8) { open.value = !open.value }
    }
    if (open.value) {
        Wavetables.NAMES.withIndex().chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((i, n) in row) {
                    val on = i == t
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(shape)
                            .background(if (on) Violet.copy(alpha = 0.45f) else Bg, shape)
                            .border(1.dp, if (on) Violet else Line, shape)
                            .clickable { pad.wtTable.value = i.toFloat() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(n, fontSize = 10.sp, color = if (on) TextHi else Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    SectionTitle("oscillateur")
    KnobGrid(listOf(pad.wtPos, pad.wtWarp, pad.wtUni, pad.wtDetune, pad.wtSpread, pad.wtSub), acc)
    SectionTitle("mouvement de la position")
    KnobGrid(listOf(pad.wtMotion, pad.wtDepth), acc)
    SectionTitle("son")
    KnobGrid(listOf(pad.volume, pad.pan, pad.pitch), acc)
    Text(
        "position = morphing entre les 32 images de la table. sync = synchronisation dure. unison = jusqu'à 7 voix désaccordées. " +
            "mouvement = un LFO fait balayer la position. note de base : la3 (220 Hz), pitch pour transposer.",
        fontSize = 10.sp,
        color = Dim,
    )
}
