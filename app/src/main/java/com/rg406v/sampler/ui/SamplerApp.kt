package com.rg406v.sampler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rg406v.sampler.SamplerState
import com.rg406v.sampler.audio.PadParams
import com.rg406v.sampler.audio.Param

// Palette : ardoise bleutée, corail pour ce qui sonne, bleu clair pour ce qu'on édite.
private val Bg = Color(0xFF14161D)
private val PanelBg = Color(0xFF1D2029)
private val PadIdle = Color(0xFF292D3A)
private val PadEdge = Color(0xFF353A4A)
private val Coral = Color(0xFFFF7A59)
private val Sky = Color(0xFF7FB2FF)
private val Dim = Color(0xFF8B91A3)
private val Track = Color(0xFF313644)

@Composable
fun SamplerApp(state: SamplerState) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Coral)) {
        Surface(color = Bg, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PadGrid(state, Modifier.weight(0.5f).fillMaxHeight())
                RightPanel(state, Modifier.weight(0.5f).fillMaxHeight())
            }
        }
    }
}

// ------------------------------------------------------------------ pads

@Composable
private fun PadGrid(state: SamplerState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until 4) {
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (col in 0 until 4) {
                    PadButton(state, row * 4 + col, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

private val padHints = listOf("A", "B", "X", "Y", "↑", "→", "↓", "←")

@Composable
private fun PadButton(state: SamplerState, idx: Int, modifier: Modifier) {
    val pad = state.pads[idx]
    val active = state.padActive[idx]
    val selected = state.selectedPad == idx
    val bankActive = (idx >= 8) == state.bankHeld
    val shape = RoundedCornerShape(10.dp)
    val hint = (if (idx >= 8) "L1+" else "") + padHints[idx % 8]

    Box(
        modifier
            .clip(shape)
            .background(if (active) Coral else if (bankActive) PadIdle else PanelBg)
            .border(if (selected) 2.dp else 1.dp, if (selected) Sky else PadEdge, shape)
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
            fontSize = 10.sp,
            color = if (active) Bg else Dim,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        )
        Text(
            pad.sampleName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (active) Bg else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 4.dp),
        )
        if (pad.loop) {
            Text(
                "boucle",
                fontSize = 9.sp,
                color = if (active) Bg else Sky,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ panneau de droite

@Composable
private fun RightPanel(state: SamplerState, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(PanelBg).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Master", fontSize = 12.sp, color = Dim, modifier = Modifier.width(52.dp))
            MiniSlider(state.engine.master, Modifier.weight(1f), Sky)
            Text(
                "%.0f%%".format(state.engine.master.value * 100f),
                fontSize = 11.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TabButton("Effets", state.tab == 0, Modifier.weight(1f)) { state.tab = 0 }
            TabButton("Pad ${state.selectedPad + 1}", state.tab == 1, Modifier.weight(1f)) { state.tab = 1 }
        }
        if (state.tab == 0) FxPanel(state) else PadPanel(state)
    }
}

@Composable
private fun TabButton(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Sky else PadIdle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (on) Bg else Color.White)
    }
}

// ------------------------------------------------------------------ effets

@Composable
private fun FxPanel(state: SamplerState) {
    val sel = state.fx.getOrNull(state.selectedFx) ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.fx.forEachIndexed { i, e ->
                val chosen = i == state.selectedFx
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (e.enabled) Coral else PadIdle)
                        .border(if (chosen) 2.dp else 1.dp, if (chosen) Sky else PadEdge, RoundedCornerShape(8.dp))
                        .clickable { state.selectedFx = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        e.short,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (e.enabled) Bg else Color.White,
                        maxLines = 1,
                    )
                }
            }
        }
        Text("Signal : de gauche à droite", fontSize = 10.sp, color = Dim)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(sel.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            SmallButton("◀") { state.moveFx(-1) }
            SmallButton("▶") { state.moveFx(1) }
            Switch(checked = sel.enabled, onCheckedChange = { sel.enabled = it })
        }
        sel.params.forEach { ParamRow(it, Coral) }
        Text(
            "Sticks : gauche = 1er et 2e réglage, droit = 3e",
            fontSize = 10.sp,
            color = Dim,
        )
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .width(34.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PadIdle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 12.sp) }
}

// ------------------------------------------------------------------ pad

@Composable
private fun PadPanel(state: SamplerState) {
    val pad = state.pads[state.selectedPad]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                pad.sampleName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { state.requestPick(state.selectedPad) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
            ) { Text("Charger", fontSize = 12.sp) }
        }
        Waveform(pad)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("Boucle", pad.loop) { pad.loop = !pad.loop }
            ToggleChip("Gate", pad.gate || pad.loop) { pad.gate = !pad.gate }
        }
        listOf(pad.volume, pad.pan, pad.pitch, pad.start, pad.end, pad.attack, pad.decay, pad.sustain, pad.release)
            .forEach { ParamRow(it, Sky) }
        Text(
            "Sticks : gauche = pitch / volume, droit = début / fin",
            fontSize = 10.sp,
            color = Dim,
        )
    }
}

@Composable
private fun ToggleChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Sky else PadIdle)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 11.sp, color = if (on) Bg else Color.White) }
}

@Composable
private fun Waveform(pad: PadParams) {
    val name = pad.sampleName // lu pour recomposer quand le sample change
    val peaks = pad.sample?.peaks
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Bg)
    ) {
        val w = size.width
        val h = size.height
        if (peaks != null && name.isNotEmpty()) {
            val step = w / peaks.size
            for (i in peaks.indices) {
                val x = i * step + step / 2f
                val a = peaks[i] * h * 0.45f
                drawLine(Sky.copy(alpha = 0.85f), Offset(x, h / 2f - a), Offset(x, h / 2f + a), strokeWidth = step.coerceAtLeast(1f))
            }
        }
        val sx = pad.start.value * w
        val ex = pad.end.value * w
        drawRect(Color(0xAA000000), Offset(0f, 0f), Size(sx, h))
        drawRect(Color(0xAA000000), Offset(ex, 0f), Size((w - ex).coerceAtLeast(0f), h))
        drawLine(Coral, Offset(sx, 0f), Offset(sx, h), strokeWidth = 2f)
        drawLine(Coral, Offset(ex, 0f), Offset(ex, h), strokeWidth = 2f)
    }
}

// ------------------------------------------------------------------ réglages

@Composable
private fun ParamRow(p: Param, color: Color) {
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, fontSize = 11.sp, color = Dim, maxLines = 1, modifier = Modifier.width(62.dp))
        MiniSlider(p, Modifier.weight(1f), color)
        Text(
            p.display(),
            fontSize = 11.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(78.dp),
        )
    }
}

/** Slider compact, pensé pour un petit écran tactile. */
@Composable
private fun MiniSlider(p: Param, modifier: Modifier, color: Color) {
    var w by remember { mutableFloatStateOf(1f) }
    Box(
        modifier
            .fillMaxHeight()
            .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(p) { detectTapGestures { p.norm = it.x / w } }
            .pointerInput(p) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    p.norm = change.position.x / w
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val th = 6.dp.toPx()
            val r = 7.dp.toPx()
            val fw = size.width * p.norm
            drawRoundRect(Track, Offset(0f, cy - th / 2f), Size(size.width, th), CornerRadius(th / 2f))
            drawRoundRect(color, Offset(0f, cy - th / 2f), Size(fw, th), CornerRadius(th / 2f))
            drawCircle(Color.White, r, Offset(fw.coerceIn(r, size.width - r), cy))
        }
    }
}
