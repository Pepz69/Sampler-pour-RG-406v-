package com.rg406v.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rg406v.sampler.SamplerState
import kotlin.math.floor

private val BLACK_KEYS = setOf(1, 3, 6, 8, 10)
private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

private fun pitchClass(semis: Int) = ((semis % 12) + 12) % 12

private fun noteLabel(semis: Int): String =
    if (semis == 0) "root" else NOTE_NAMES[pitchClass(semis)] + (if (semis > 0) "+" else "−") + (kotlin.math.abs(semis) / 12 * 12).let { if (it == 0) "" else "$it" }

@Composable
private fun TransportRow(state: SamplerState) {
    val seq = state.seq
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Pill(if (seq.playing) "■ stop" else "▶ play", seq.playing, AccSampler) { state.seqToggle() }
        Pill("● rec live", seq.recArmed, AccFm) { seq.recArmed = !seq.recArmed }
        Spacer(Modifier.weight(1f))
        Knob(seq.bpm, AccFx, knobSize = 36.dp)
        Knob(seq.swing, AccFx, knobSize = 36.dp)
    }
}

@Composable
private fun PatternRow(state: SamplerState) {
    val seq = state.seq
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until 8) {
            Pill("${i + 1}", seq.current == i, AccSampler, Modifier.weight(1f), hPad = 0) { state.setPattern(i) }
        }
    }
}

@Composable
private fun TrackHeader(state: SamplerState, showLenPages: Boolean = true) {
    val seq = state.seq
    val tr = state.selectedPad
    val pat = seq.pattern()
    seq.version
    val len = pat.len[tr]
    val pages = (len + 15) / 16
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            "pad ${tr + 1} · " + (if (state.pads[tr].mode.value.toInt() == 2) state.pads[tr].fmName else state.pads[tr].sampleName),
            fontSize = 11.sp,
            color = TextHi,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Pill(if (seq.mute[tr]) "muet" else "mute", seq.mute[tr], AccFm) { state.toggleMute(tr) }
        Pill("‹", false, AccFx, hPad = 8) { state.changeTrackLen(-1) }
        Text("$len", fontSize = 11.sp, color = TextHi, modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
        Pill("›", false, AccFx, hPad = 8) { state.changeTrackLen(1) }
    }
    if (showLenPages && pages > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("page", fontSize = 10.sp, color = Dim)
            for (p in 0 until pages) Pill("${p + 1}", state.seqPage.coerceIn(0, pages - 1) == p, AccSampler, hPad = 10) { state.seqPage = p }
        }
    }
}

// ------------------------------------------------------------------ séquenceur pas à pas

@Composable
internal fun SeqPanel(state: SamplerState) {
    val seq = state.seq
    val tr = state.selectedPad
    val pat = seq.pattern()
    seq.version
    val tk = seq.tick
    val len = pat.len[tr].coerceIn(1, 64)
    val pages = (len + 15) / 16
    val page = state.seqPage.coerceIn(0, pages - 1)
    val playStep = if (seq.playing && tk >= 0) seq.playStep(tr) else -1

    LaunchedEffect(tr, seq.current) { state.lockStep = -1 }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        TransportRow(state)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            EditBtn("copier", Modifier.weight(1f)) { state.copyPattern() }
            EditBtn("coller", Modifier.weight(1f)) { state.pastePattern() }
            EditBtn("vider piste", Modifier.weight(1.3f)) { state.clearTrackSteps() }
            EditBtn("vider tout", Modifier.weight(1.2f)) { state.clearPattern() }
        }
        TrackHeader(state)

        // 16 pas par page : 2 rangées de 8
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 8) {
                    val local = row * 8 + c
                    val s = page * 16 + local
                    val valid = s < len
                    val on = valid && pat.on[tr][s]
                    val sel = valid && state.lockStep == s
                    val head = valid && playStep == s
                    val locked = on && pat.hasLock(tr, s)
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.9f)
                            .clip(shape)
                            .then(
                                if (on) Modifier.background(Brush.linearGradient(listOf(Violet, Pink)), shape)
                                else Modifier.background(if ((local / 4) % 2 == 0) ModuleHi else Bg, shape)
                            )
                            .border(
                                if (head || sel) 2.dp else 1.dp,
                                if (head) Cyan else if (sel) Color.White else Line,
                                shape,
                            )
                            .then(if (valid) Modifier.clickable { state.stepTap(s) } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (valid) "${s + 1}" else "",
                            fontSize = 9.sp,
                            color = if (on) Color.White.copy(alpha = 0.85f) else Dim,
                        )
                        if (locked) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Amber)
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("trig", !state.seqLockMode, AccSampler) { state.seqLockMode = false; state.lockStep = -1 }
            Pill("p-lock", state.seqLockMode, AccGrain) { state.seqLockMode = true }
            Text(
                if (state.seqLockMode) "touche un pas pour régler ses paramètres" else "touche un pas pour le poser",
                fontSize = 10.sp,
                color = Dim,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.seqLockMode && state.lockStep >= 0 && state.lockStep < len) {
            LockPanel(state)
        }
        Text(
            "touche un pad à gauche pour choisir la piste. pastille ambre = pas avec réglages. " +
                "pour poser des notes de différentes hauteurs, utilise l'onglet piano.",
            fontSize = 10.sp,
            color = Dim,
        )
    }
}

@Composable
private fun LockPanel(state: SamplerState) {
    val sig = listOf(
        state.lkPitch.value, state.lkVol.value, state.lkPan.value, state.lkStart.value, state.lkProb.value,
        state.lkMicro.value, state.lkRetrig.value, state.lkCond.value, state.lkLen.value,
    )
    LaunchedEffect(sig) { state.applyLock() }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Bg, shape)
            .border(1.dp, Amber.copy(alpha = 0.6f), shape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("pas ${state.lockStep + 1} · réglages du pas", fontSize = 11.sp, color = Amber)
        KnobGrid(listOf(state.lkPitch, state.lkVol, state.lkPan, state.lkStart), AccGrain)
        KnobGrid(listOf(state.lkProb, state.lkMicro, state.lkRetrig, state.lkCond), AccGrain)
        KnobGrid(listOf(state.lkLen), AccGrain)
        Text(
            "valeur par défaut = pas de réglage. proba : chance que le pas joue. cond 1:2 = un cycle sur deux. " +
                "retrig : répétitions dans le pas.",
            fontSize = 10.sp,
            color = Dim,
        )
    }
}

// ------------------------------------------------------------------ piano roll

private const val ROWS = 13
private const val ROW_DP = 19

@Composable
internal fun PianoPanel(state: SamplerState) {
    val seq = state.seq
    val tr = state.selectedPad
    val pat = seq.pattern()
    seq.version
    val tk = seq.tick
    val len = pat.len[tr].coerceIn(1, 64)
    val pages = (len + 15) / 16
    val page = state.seqPage.coerceIn(0, pages - 1)
    val playStep = if (seq.playing && tk >= 0) seq.playStep(tr) else -1
    val lo = state.pianoOct * 12 // la fenêtre montre lo .. lo+12

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill(if (seq.playing) "■ stop" else "▶ play", seq.playing, AccSampler) { state.seqToggle() }
            Pill("● rec live", seq.recArmed, AccFm) { seq.recArmed = !seq.recArmed }
            Spacer(Modifier.weight(1f))
            Pill("oct −", false, AccFx) { if (state.pianoOct > -2) state.pianoOct -= 1 }
            Pill("oct +", false, AccFx) { if (state.pianoOct < 1) state.pianoOct += 1 }
        }
        TrackHeader(state)

        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, Line, shape),
        ) {
            // touches jouables : maintiens pour jouer le pad à cette hauteur
            Column(Modifier.width(46.dp)) {
                for (r in 0 until ROWS) {
                    val semis = lo + (ROWS - 1) - r
                    val black = pitchClass(semis) in BLACK_KEYS
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_DP.dp)
                            .background(
                                if (semis == 0) Violet.copy(alpha = 0.55f) else if (black) Bg else Color(0xFF2B2F42)
                            )
                            .border(0.5.dp, Line)
                            .pointerInput(semis) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    state.noteDown(semis)
                                    waitForUpOrCancellation()
                                    state.noteUp()
                                }
                            },
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        if (pitchClass(semis) == 0 || semis == 0) {
                            Text(noteLabel(semis), fontSize = 8.sp, color = if (black) Dim else TextHi, modifier = Modifier.padding(end = 3.dp))
                        }
                    }
                }
            }
            // grille : touche pour poser, déplacer ou retirer une note
            Canvas(
                Modifier
                    .weight(1f)
                    .height((ROWS * ROW_DP).dp)
                    .background(Bg)
                    .pointerInput(page, lo, len) {
                        detectTapGestures { pos ->
                            val cw = size.width / 16f
                            val rh = size.height / ROWS.toFloat()
                            val c = floor(pos.x / cw).toInt().coerceIn(0, 15)
                            val r = floor(pos.y / rh).toInt().coerceIn(0, ROWS - 1)
                            val s = page * 16 + c
                            if (s < len) state.pianoTap(s, lo + (ROWS - 1) - r)
                        }
                    }
            ) {
                seq.version
                seq.tick
                val cw = size.width / 16f
                val rh = size.height / ROWS
                for (r in 0 until ROWS) {
                    val semis = lo + (ROWS - 1) - r
                    val black = pitchClass(semis) in BLACK_KEYS
                    drawRect(
                        if (semis == 0) Violet.copy(alpha = 0.16f) else if (black) Color(0xFF0E0F15) else ModuleHi,
                        Offset(0f, r * rh),
                        Size(size.width, rh),
                    )
                    drawLine(Line, Offset(0f, r * rh), Offset(size.width, r * rh), strokeWidth = 1f)
                }
                for (c in 0..16) {
                    drawLine(
                        if (c % 4 == 0) Color(0xFF3A3F58) else Line,
                        Offset(c * cw, 0f),
                        Offset(c * cw, size.height),
                        strokeWidth = if (c % 4 == 0) 2f else 1f,
                    )
                }
                // zone hors longueur de piste
                if (page * 16 + 16 > len) {
                    val valid = (len - page * 16).coerceIn(0, 16)
                    drawRect(Color(0xCC0B0C10), Offset(valid * cw, 0f), Size(size.width - valid * cw, size.height))
                }
                if (playStep >= page * 16 && playStep < page * 16 + 16) {
                    drawRect(Cyan.copy(alpha = 0.18f), Offset((playStep - page * 16) * cw, 0f), Size(cw, size.height))
                }
                for (c in 0 until 16) {
                    val s = page * 16 + c
                    if (s >= len || !pat.on[tr][s]) continue
                    val semis = pat.pitch[tr][s].let { if (it.isNaN()) 0 else it.toInt() }
                    val rowF = lo + (ROWS - 1) - semis
                    if (rowF in 0 until ROWS) {
                        drawRoundRect(
                            Brush.horizontalGradient(listOf(Violet, Pink), startX = c * cw, endX = (c + 1) * cw),
                            Offset(c * cw + 1.5f, rowF * rh + 1.5f),
                            Size(cw - 3f, rh - 3f),
                            CornerRadius(4f, 4f),
                        )
                    } else {
                        // note hors de la fenêtre : repère ambre en haut ou en bas
                        val y = if (semis > lo + ROWS - 1) 2f else size.height - 5f
                        drawRect(Amber, Offset(c * cw + 3f, y), Size(cw - 6f, 3f))
                    }
                }
            }
        }
        Text(
            "touches de gauche : joue le pad (pad ${tr + 1}) à cette hauteur. grille : touche pour poser une note, " +
                "touche la même case pour la retirer. repère ambre = note hors de la fenêtre (oct − / oct +).",
            fontSize = 10.sp,
            color = Dim,
        )
        Text(
            "hauteurs en demi-tons par rapport au son d'origine du pad. avec rec live, ce que tu joues sur les touches " +
                "pendant la lecture est enregistré dans le pattern.",
            fontSize = 10.sp,
            color = Dim,
        )
    }
}
