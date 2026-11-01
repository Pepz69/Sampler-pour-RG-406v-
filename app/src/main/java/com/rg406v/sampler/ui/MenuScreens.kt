package com.rg406v.sampler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rg406v.sampler.SamplerState
import com.rg406v.sampler.audio.AudioEngine
import com.rg406v.sampler.audio.DefaultSounds
import com.rg406v.sampler.audio.PunchFx

@Composable
private fun NameField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(24)) },
        singleLine = true,
        textStyle = TextStyle(color = TextHi, fontSize = 12.sp),
        cursorBrush = SolidColor(Violet),
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(Bg, shape)
            .border(1.dp, Line, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/** Ligne d'une liste enregistrée : nom, charger, supprimer (avec confirmation en deux touches). */
@Composable
private fun SavedRow(
    name: String,
    confirming: Boolean,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    acc: Accent,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Bg, shape)
            .border(1.dp, Line, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(name, fontSize = 12.sp, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Pill("charger", false, acc, onClick = onLoad)
        Pill(if (confirming) "sûr ?" else "suppr", confirming, AccFm, onClick = onDelete)
    }
}

// ------------------------------------------------------------------ kits, sons, séquences

@Composable
internal fun KitsPanel(state: SamplerState) {
    var kitName by remember { mutableStateOf("mon kit") }
    var seqName by remember { mutableStateOf("ma séquence") }
    var confirm by remember { mutableStateOf("") }
    val pad = state.pads[state.selectedPad]

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("kits", state.kitsView == 0, AccSampler) { state.kitsView = 0 }
            Pill("sons → pads", state.kitsView == 1, AccGrain) { state.kitsView = 1 }
            Pill("séquences", state.kitsView == 2, AccFx) { state.kitsView = 2 }
        }

        when (state.kitsView) {
            0 -> {
                Text("kit actuel : ${state.currentKit}", fontSize = 11.sp, color = TextHi)
                SectionTitle("banques d'usine")
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.builtinNames.forEachIndexed { k, n ->
                        Pill(n, state.currentKit == n, AccSampler, Modifier.weight(1f), hPad = 4) { state.applyBuiltin(k) }
                    }
                }
                Text(
                    "chaque banque remplace les 16 sons et remet les réglages des pads à zéro. " +
                        "les effets et la séquence ne changent pas.",
                    fontSize = 10.sp,
                    color = Dim,
                )
                SectionTitle("enregistrer le kit actuel")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NameField(kitName, { kitName = it }, Modifier.weight(1f))
                    Pill("enregistrer", false, AccSampler) { state.saveKitAs(kitName) }
                }
                Text("un kit garde les 16 sons (y compris enregistrés ou édités) et tous les réglages des pads.", fontSize = 10.sp, color = Dim)
                SectionTitle("mes kits")
                if (state.kitList.isEmpty()) Text("aucun kit enregistré", fontSize = 11.sp, color = Dim)
                state.kitList.forEach { n ->
                    SavedRow(
                        n, confirm == "k:$n",
                        onLoad = { state.loadKitNamed(n); kitName = n },
                        onDelete = {
                            if (confirm == "k:$n") { state.deleteKit(n); confirm = "" } else confirm = "k:$n"
                        },
                        acc = AccSampler,
                    )
                }
            }

            1 -> {
                Text(
                    "pad cible : ${state.selectedPad + 1} (${if (pad.mode.value.toInt() == 2) pad.fmName else pad.sampleName})",
                    fontSize = 11.sp,
                    color = TextHi,
                )
                Text("choisis le pad à gauche, puis touche un son pour l'y mettre.", fontSize = 10.sp, color = Dim)
                SectionTitle("bibliothèque")
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.builtinNames.forEachIndexed { k, n ->
                        Pill(n, state.libKit == k, AccGrain, Modifier.weight(1f), hPad = 4) { state.libKit = k }
                    }
                }
                if (!state.libReady) {
                    Text("chargement des banques...", fontSize = 11.sp, color = Dim)
                } else {
                    val sounds = DefaultSounds.kits[state.libKit.coerceIn(0, DefaultSounds.kits.size - 1)].sounds
                    for (row in 0 until 4) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (c in 0 until 4) {
                                val smp = sounds[row * 4 + c]
                                val shape = RoundedCornerShape(10.dp)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clip(shape)
                                        .background(Bg, shape)
                                        .border(1.dp, Line, shape)
                                        .clickable { state.assignSound(state.selectedPad, smp) }
                                        .padding(3.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        smp.name,
                                        fontSize = 9.sp,
                                        color = TextHi,
                                        maxLines = 2,
                                        textAlign = TextAlign.Center,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                SectionTitle("autres sources")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("fichier...", false, AccSampler) { state.requestPick(state.selectedPad) }
                    Pill("● micro", state.recording, AccFm) { state.toggleRecord() }
                    Pill("vider", false, AccFm) { state.clearPad(state.selectedPad) }
                }
                SectionTitle("échanger le pad ${state.selectedPad + 1} avec")
                for (row in 0 until 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (c in 0 until 8) {
                            val n = row * 8 + c
                            Pill("${n + 1}", false, AccFx, Modifier.weight(1f), hPad = 0) { state.swapPads(state.selectedPad, n) }
                        }
                    }
                }
                Text("l'échange déplace le son et les réglages (mode, enveloppe, etc.).", fontSize = 10.sp, color = Dim)
            }

            else -> {
                SectionTitle("séquence (8 patterns)")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NameField(seqName, { seqName = it }, Modifier.weight(1f))
                    Pill("enregistrer", false, AccFx) { state.saveSeqAs(seqName) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("nouvelle (vide)", false, AccFm) { state.newSequence() }
                }
                Text(
                    "une séquence garde les 8 patterns, les pas, p-locks, tempo et swing. " +
                        "elle est indépendante du kit : tu peux jouer la même séquence avec un autre kit.",
                    fontSize = 10.sp,
                    color = Dim,
                )
                SectionTitle("mes séquences")
                if (state.seqList.isEmpty()) Text("aucune séquence enregistrée", fontSize = 11.sp, color = Dim)
                state.seqList.forEach { n ->
                    SavedRow(
                        n, confirm == "s:$n",
                        onLoad = { state.loadSeqNamed(n); seqName = n },
                        onDelete = {
                            if (confirm == "s:$n") { state.deleteSeq(n); confirm = "" } else confirm = "s:$n"
                        },
                        acc = AccFx,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ punch-in

@Composable
internal fun PunchPanel(state: SamplerState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("effets punch-in", fontSize = 16.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
            Pill("■ tout couper", false, AccFm) { state.panic() }
            Knob(state.engine.punch.mix, AccFx, knobSize = 36.dp)
        }
        Text("maintiens un bouton pour appliquer l'effet à tout le son, relâche pour revenir.", fontSize = 10.sp, color = Dim)
        for (row in 0 until 4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 3) {
                    val k = row * 3 + c
                    val on = state.punchActive[k]
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .drawBehind {
                                if (on) drawRoundRect(
                                    Pink.copy(alpha = 0.22f),
                                    Offset(-4f, -4f),
                                    Size(size.width + 8f, size.height + 8f),
                                    CornerRadius(24f, 24f),
                                )
                            }
                            .clip(shape)
                            .then(
                                if (on) Modifier.background(Brush.linearGradient(listOf(Violet, Pink)), shape)
                                else Modifier.background(Bg, shape)
                            )
                            .border(1.dp, if (on) Pink else Line, shape)
                            .pointerInput(k) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    state.punchDown(k)
                                    waitForUpOrCancellation()
                                    state.punchUp(k)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(PunchFx.NAMES[k], fontSize = 12.sp, color = if (on) Color.White else TextHi, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Text(
            "loop 1/2 à 1/32 : répète les derniers instants (beat repeat). stutter : coupe le son en rythme. " +
                "reverse : lit à l'envers. tape stop : le son ralentit jusqu'à l'arrêt. octave ± : décale la hauteur. " +
                "unison : épaissit. filtre : balayage passe-bas.",
            fontSize = 10.sp,
            color = Dim,
        )
        Text(
            "manette : maintiens R1 puis appuie sur A B X Y et la croix (punch 1 à 8) ; avec L1 en plus : punch 9 à 12. " +
                "R1 + Start : tout couper.",
            fontSize = 10.sp,
            color = Dim,
        )
    }
}

// ------------------------------------------------------------------ réglages audio

@Composable
internal fun AudioPanel(state: SamplerState) {
    val eng = state.engine
    val t = rememberTick(150)
    t.intValue // relit les valeurs du moteur (non observables) à intervalles réguliers
    val sr = eng.sampleRate
    val blk = eng.blockSize
    val nbuf = eng.bufferBlocks
    val totalMs = blk * nbuf * 1000f / sr

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("audio : tampon et latence", fontSize = 16.sp, fontWeight = FontWeight.Light)
        Text(
            "latence de sortie ≈ %.1f ms   (bloc %d × %d = %d images à %d Hz)".format(totalMs, blk, nbuf, blk * nbuf, sr),
            fontSize = 11.sp,
            color = TextHi,
        )

        SectionTitle("préréglages")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Pill("mini", blk == 128 && nbuf == 2, AccSampler, Modifier.weight(1f), hPad = 2) { state.applyPreset(128, 2) }
            Pill("équilibré", blk == 256 && nbuf == 2, AccSampler, Modifier.weight(1f), hPad = 2) { state.applyPreset(256, 2) }
            Pill("sûr", blk == 512 && nbuf == 3, AccSampler, Modifier.weight(1f), hPad = 2) { state.applyPreset(512, 3) }
            Pill("max", blk == 1024 && nbuf == 4, AccSampler, Modifier.weight(1f), hPad = 2) { state.applyPreset(1024, 4) }
        }

        SectionTitle("taille du bloc (images)")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in intArrayOf(64, 128, 256, 512, 1024)) {
                Pill("$n", blk == n, AccGrain, Modifier.weight(1f), hPad = 0) { state.setBlock(n) }
            }
        }
        SectionTitle("nombre de blocs dans le tampon")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in 1..8) {
                Pill("$n", nbuf == n, AccGrain, Modifier.weight(1f), hPad = 0) { state.setBufferBlocks(n) }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("anti-craquement auto", eng.autoBuffer, AccFx) { eng.autoBuffer = !eng.autoBuffer }
            Text("agrandit le tampon seul si ça craque", fontSize = 10.sp, color = Dim, modifier = Modifier.weight(1f))
        }

        SectionTitle("mode de la piste audio")
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Pill("faible latence", eng.perfMode == 0, AccSampler, Modifier.weight(1f), hPad = 2) { state.setPerf(0) }
            Pill("normal", eng.perfMode == 1, AccSampler, Modifier.weight(1f), hPad = 2) { state.setPerf(1) }
            Pill("économie", eng.perfMode == 2, AccSampler, Modifier.weight(1f), hPad = 2) { state.setPerf(2) }
        }

        SectionTitle("diagnostic")
        val shape = RoundedCornerShape(10.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Bg, shape)
                .border(1.dp, if (state.cpu > 85f) Hot else Line, shape)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("charge", fontSize = 10.sp, color = Dim, modifier = Modifier.width(48.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(TrackCol)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((state.cpu / 100f).coerceIn(0.01f, 1f))
                            .fillMaxSize()
                            .background(if (state.cpu > 80f) Hot else if (state.cpu > 55f) Amber else Cyan)
                    )
                }
                Text("%.0f%%".format(state.cpu), fontSize = 11.sp, color = TextHi, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("craquements", fontSize = 10.sp, color = Dim, modifier = Modifier.weight(1f))
                Text("${eng.underruns}", fontSize = 12.sp, color = if (eng.underruns > 0) Amber else TextHi)
                Pill("remettre à 0", false, AccFx) { state.resetUnderruns() }
            }
            Text("tampon réel : ${eng.bufferFrames} images (%.1f ms)".format(state.bufMs), fontSize = 10.sp, color = Dim)
        }
        Text(
            "ça craque : monte le tampon (ou prends « sûr »). trop de latence au jeu : descends vers « mini ». " +
                "si la charge dépasse 80 %, coupe des effets (reverb, delay, granulaire) ou agrandis le bloc.",
            fontSize = 10.sp,
            color = Dim,
        )

        SectionTitle("entrée micro")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(state.inGain, AccFm, knobSize = 40.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Pill(if (state.inMonitor) "écoute du niveau : on" else "écoute du niveau : off", state.inMonitor, AccFm) { state.toggleMonitor() }
                Text("le niveau d'entrée (in) s'affiche en haut ; le voyant rouge s'allume en cas de saturation.", fontSize = 10.sp, color = Dim)
            }
        }
        Text(
            "niveaux : touche le bandeau en haut pour remettre à zéro les voyants et les crêtes. " +
                "la sortie sature à 0 dB : baisse le master ou les volumes des pads.",
            fontSize = 10.sp,
            color = Dim,
        )
    }
}
