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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rg406v.sampler.MODES
import com.rg406v.sampler.ModeDef
import com.rg406v.sampler.SamplerState
import com.rg406v.sampler.TAB_AUDIO
import com.rg406v.sampler.TAB_FX
import com.rg406v.sampler.TAB_KITS
import com.rg406v.sampler.TAB_MIX
import com.rg406v.sampler.TAB_PAD
import com.rg406v.sampler.TAB_PADFX
import com.rg406v.sampler.TAB_PADS
import com.rg406v.sampler.TAB_PIANO
import com.rg406v.sampler.TAB_PUNCH
import com.rg406v.sampler.TAB_SEQ
import com.rg406v.sampler.audio.Param

/*
 * Organisation de l'écran, inspirée de Maschine (bandeau du haut : projet, transport, patterns, niveaux)
 * et de la MPC One (rail de modes à gauche, grand écran au centre, mixer à faders).
 *
 *   ┌──────────────── bandeau : nom, transport, tempo, patterns, niveaux ───────────────┐
 *   │ rail  │                                                                           │
 *   │ PADS  │            écran du mode choisi (+ sous-pages, + bande des 16 pads)        │
 *   │ SEQ   │                                                                           │
 *   │ ...   │                                                                           │
 *   └───────┴───────────────────────────────────────────────────────────────────────────┘
 */

@Composable
internal fun AppShell(state: SamplerState) {
    Column(
        Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TopBar(state)
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeRail(state, Modifier.width(76.dp).fillMaxHeight())
            Screen(state, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

// ------------------------------------------------------------------ bandeau du haut

@Composable
private fun TopBar(state: SamplerState) {
    val shape = RoundedCornerShape(14.dp)
    val seq = state.seq
    val pad = state.pads[state.selectedPad]
    val n = state.selectedPad + 1
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Module, shape)
            .border(1.dp, Line, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    "rggg sampler · " + state.currentProject.ifBlank { "sans projet" },
                    fontSize = 10.sp,
                    color = Dim,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (state.recording) "● rec → pad $n"
                    else "pad $n · " + pad.displayName,
                    fontSize = 13.sp,
                    color = if (state.recording) Pink else TextHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val scale = state.engine.scale
            Pill("♪ " + scale.display().take(9), scale.value > 0f, AccFx, hPad = 8) { state.goTab(TAB_PIANO) }
            Pill(if (seq.playing) "■ stop" else "▶ play", seq.playing, AccSampler, hPad = 10) { state.seqToggle() }
            Pill("● rec", seq.recArmed, AccFm, hPad = 10) { seq.recArmed = !seq.recArmed }
            Knob(seq.bpm, AccFx, knobSize = 30.dp)
            Knob(state.engine.master, AccFx, knobSize = 30.dp, showValue = false)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("pattern", fontSize = 9.sp, color = Dim)
            Row(Modifier.weight(0.9f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in 0 until 8) {
                    Pill("${i + 1}", seq.current == i, AccSampler, Modifier.weight(1f), hPad = 0) { state.setPattern(i) }
                }
            }
            Box(Modifier.weight(1.1f)) { MeterStrip(state) }
        }
    }
}

// ------------------------------------------------------------------ rail de modes (à gauche)

@Composable
private fun ModeRail(state: SamplerState, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val current = state.modeIndex()
    Column(
        modifier.clip(shape).background(Module, shape).border(1.dp, Line, shape).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MODES.forEachIndexed { i, m ->
            val on = i == current
            val inner = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(inner)
                    .then(
                        if (on) Modifier.background(
                            Brush.verticalGradient(listOf(Violet.copy(alpha = 0.50f), Pink.copy(alpha = 0.50f))),
                            inner,
                        ) else Modifier.background(Bg, inner)
                    )
                    .border(1.dp, if (on) Violet else Line, inner)
                    .clickable { state.goMode(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.label,
                    fontSize = 10.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) TextHi else Dim,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                )
            }
        }
        val sh = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(sh)
                .background(if (state.shiftHeld) Pink else Bg, sh)
                .border(1.dp, Line, sh),
            contentAlignment = Alignment.Center,
        ) {
            Text("SHIFT", fontSize = 9.sp, color = if (state.shiftHeld) Color.White else Dim)
        }
    }
}

// ------------------------------------------------------------------ écran central

@Composable
private fun Screen(state: SamplerState, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val md = MODES[state.modeIndex()]
    val withStrip = state.tab == TAB_PAD || state.tab == TAB_PADFX || state.tab == TAB_SEQ || state.tab == TAB_PIANO
    Column(
        modifier.clip(shape).background(Module, shape).border(1.dp, Line, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (md.tabs.size > 1) SubTabs(state, md)
        if (withStrip) PadStrip(state)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                TAB_PADS -> PadsScreen(state)
                TAB_PAD -> PadPanel(state)
                TAB_SEQ -> SeqPanel(state)
                TAB_PIANO -> PianoPanel(state)
                TAB_MIX -> MixerScreen(state)
                TAB_KITS -> KitsPanel(state)
                TAB_PUNCH -> PunchPanel(state)
                TAB_AUDIO -> AudioPanel(state)
                else -> FxPanel(state)
            }
        }
    }
}

@Composable
private fun SubTabs(state: SamplerState, md: ModeDef) {
    val outer = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(outer)
            .background(Bg, outer)
            .border(1.dp, Line, outer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        md.tabs.forEachIndexed { k, t ->
            val on = state.tab == t
            val inner = RoundedCornerShape(9.dp)
            val label = when (t) {
                TAB_FX -> "fx master"
                TAB_PADFX -> "fx pad ${state.selectedPad + 1}"
                TAB_SEQ -> "pas à pas"
                TAB_PIANO -> "piano roll"
                TAB_PUNCH -> "punch-in"
                else -> md.subs[k]
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(inner)
                    .then(
                        if (on) Modifier.background(
                            Brush.horizontalGradient(listOf(Violet.copy(alpha = 0.40f), Pink.copy(alpha = 0.40f))),
                            inner,
                        ) else Modifier
                    )
                    .clickable { state.goTab(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 11.sp, color = if (on) TextHi else Dim, maxLines = 1)
            }
        }
    }
}

/** Les 16 pads en miniature : choisir le pad édité et l'écouter, depuis n'importe quel écran. */
@Composable
private fun PadStrip(state: SamplerState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until 16) {
            val sel = state.selectedPad == i
            val act = state.padActive[i]
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(26.dp)
                    .clip(shape)
                    .background(if (act) Violet else if (sel) ModuleHi else Bg, shape)
                    .border(if (sel) 1.5.dp else 1.dp, if (sel) Cyan else Line, shape)
                    .pointerInput(i) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            state.padDown(i)
                            waitForUpOrCancellation()
                            state.padUp(i)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("${i + 1}", fontSize = 9.sp, color = if (sel || act) TextHi else Dim, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ mode PADS : grande grille + fiche du pad

@Composable
private fun PadsScreen(state: SamplerState) {
    val pad = state.pads[state.selectedPad]
    val mode = pad.mode.value.toInt()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PadGrid(state, Modifier.weight(1.3f).fillMaxHeight())
        Column(
            Modifier.weight(0.7f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("pad ${state.selectedPad + 1}", fontSize = 16.sp, fontWeight = FontWeight.Light)
            Text(
                pad.displayName + " · " + pad.mode.display().lowercase(),
                fontSize = 11.sp,
                color = Dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob(pad.volume, AccSampler, knobSize = 40.dp)
                Knob(pad.pitch, AccGrain, knobSize = 40.dp)
            }
            SectionTitle("aller à")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                EditBtn("sample", Modifier.weight(1f)) { state.goTab(TAB_PAD) }
                EditBtn("fx pad", Modifier.weight(1f)) { state.goTab(TAB_PADFX) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                EditBtn("séquence", Modifier.weight(1f)) { state.goTab(TAB_SEQ) }
                EditBtn("mixer", Modifier.weight(1f)) { state.goTab(TAB_MIX) }
            }
            SectionTitle("raccourcis manette")
            Text(
                "A B X Y + croix = pads 1–8 · L1 = pads 9–16 · R1 = punch-in · " +
                    "L2 / R2 = pattern − / + · Select = mode suivant · " +
                    "Select maintenu (shift) + pad = choisir sans jouer, + L2/R2 = sous-page, + start = rec.",
                fontSize = 10.sp,
                color = Dim,
            )
        }
    }
}

// ------------------------------------------------------------------ mode MIXER

@Composable
private fun MixerScreen(state: SamplerState) {
    val bank = remember { mutableIntStateOf(0) }
    state.seq.version // relit mute / solo quand ils changent
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("pads 1–8", bank.intValue == 0, AccSampler) { bank.intValue = 0 }
            Pill("pads 9–16", bank.intValue == 1, AccSampler) { bank.intValue = 1 }
            Spacer(Modifier.weight(1f))
            Text("M / S agissent sur le séquenceur", fontSize = 10.sp, color = Dim)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (k in 0 until 8) {
                val i = bank.intValue * 8 + k
                MixChannel(state, i, Modifier.weight(1f).fillMaxHeight())
            }
            MixMaster(state, Modifier.width(54.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun MixChannel(state: SamplerState, i: Int, modifier: Modifier) {
    val pad = state.pads[i]
    val sel = state.selectedPad == i
    val muted = state.seq.mute[i]
    val solo = state.soloPad == i
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (sel) ModuleHi else Bg, shape)
            .then(
                if (sel) Modifier.border(1.5.dp, AccSampler.brush(), shape)
                else Modifier.border(1.dp, Line, shape)
            )
            .clickable { state.selectedPad = i }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${i + 1}", fontSize = 11.sp, color = TextHi)
        Text(
            pad.displayName,
            fontSize = 8.sp,
            color = Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Knob(pad.pan, AccFx, knobSize = 28.dp, showValue = false)
        VFader(pad.volume, AccSampler, Modifier.weight(1f).width(26.dp))
        Text(pad.volume.display(), fontSize = 8.sp, color = Dim, maxLines = 1)
        Pill("M", muted, AccFm, Modifier.fillMaxWidth(), hPad = 0) { state.toggleMute(i) }
        Pill("S", solo, AccGrain, Modifier.fillMaxWidth(), hPad = 0) { state.toggleSolo(i) }
    }
}

@Composable
private fun MixMaster(state: SamplerState, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clip(shape).background(ModuleHi, shape).border(1.dp, Line, shape).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("master", fontSize = 10.sp, color = TextHi)
        VFader(state.engine.master, AccFx, Modifier.weight(1f).width(30.dp))
        Text(state.engine.master.display(), fontSize = 8.sp, color = Dim, maxLines = 1)
    }
}

/** Fader vertical : glisser ou toucher pour régler, double-tap = valeur par défaut. */
@Composable
internal fun VFader(p: Param, acc: Accent, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(Bg, shape)
            .border(1.dp, Line, shape)
            .pointerInput(p) {
                detectTapGestures(
                    onDoubleTap = { p.value = p.defaultValue },
                    onTap = { o -> p.norm = (1f - o.y / size.height).coerceIn(0f, 1f) },
                )
            }
            .pointerInput(p) {
                detectVerticalDragGestures { change, _ ->
                    p.norm = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height * p.norm.coerceIn(0f, 1f)
            drawRoundRect(
                brush = acc.brush(size.width, size.height),
                topLeft = Offset(0f, size.height - h),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(10f, 10f),
                alpha = 0.85f,
            )
            drawLine(Color.White, Offset(0f, size.height - h), Offset(size.width, size.height - h), strokeWidth = 4f)
        }
    }
}
