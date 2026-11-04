# RG Sampler – sampler multi-effets pour Anbernic RG406V

Kotlin + Jetpack Compose, moteur audio maison (AudioTrack float, mode faible latence).

## Compiler

**Android Studio** : File > Open sur ce dossier, attendre la synchro Gradle, puis Build > Build APK.
Si Android Studio demande un wrapper : `gradle wrapper --gradle-version 8.7` (ou laisse-le utiliser sa version de Gradle).

**GitHub Actions (rien à installer)** : crée un dépôt, pousse ce dossier sur `main`.
L'onglet Actions > "Build APK" produit l'artefact `RG406V-Sampler-apk` (fichier `app-release.apk`).
Tu peux aussi le lancer à la main (workflow_dispatch).

## Installer
Copie l'APK sur la RG406V et installe-le (autorise les sources inconnues).


## RGGGSAMPLER4666 : nouveautés

- **Séquenceur façon Elektron** (onglet seq) : 8 patterns, 16 pistes (une par pad), pas de 1 à 64 par piste,
  p-locks par pas (hauteur, volume, pan, début), probabilité, conditions de trig (1:2, 2:3...), micro-timing,
  retrig, durée de note, swing, mute, copier/coller de pattern, enregistrement live.
- **Piano roll** (onglet piano) : joue le pad sélectionné à différentes hauteurs et place les notes sur la grille.
- **EQ paramétrique** 6 bandes avec courbe, spectre du mix et points à glisser (style FabFilter).
- **Compresseur** avec « side-kick » déclenché par les notes d'un pad (groove kick/basse du KO II), sur le master et sur chaque pad.
- **Punch-in** (onglet punch) : loop 1/2 à 1/32 (beat repeat), stutter, reverse, tape stop, octave +/−, unison, filtre.
- **Banques** : classic, 808, lo-fi, synth. **Kits** et **séquences** enregistrables, assignation des sons aux pads, échange de pads.
- **Réglages audio** (onglet audio) : taille du bloc, tampon, préréglages, anti-craquement automatique, mode de la piste, diagnostic.
- **Niveaux** d'entrée et de sortie en dB avec voyants de saturation (touche le bandeau pour remettre à zéro).
- **Sauvegarde automatique** de tout (kit, effets, séquence, réglages audio).

Manette : Select change d'onglet (fx master > pad > fx pad > seq > piano > kits > punch > audio).
Start : play/stop (séquenceur) ou action de l'onglet. R1 maintenu + boutons : punch-in. R1 + Start : tout couper.
L2/R2 : effet précédent/suivant (onglets fx) ou pattern précédent/suivant (seq, piano).

## Interface
Style sombre et minimal : modules arrondis, boutons rotatifs à arc dégradé (glisse verticalement pour régler, double-tap = valeur par défaut), accents violet / cyan / rose selon le mode du pad.

## Fonctions
- 16 pads, sons synthétisés fournis (kick, snare, hats, basse, lead...) pour jouer tout de suite
- Chargement de WAV 16 bits, MP3, OGG, FLAC, AAC (30 s max par sample) via le sélecteur de fichiers ;
  les samples choisis sont rechargés au lancement suivant
- Sampling micro : onglet Pad, bouton "Micro" (ou R2) pour démarrer/arrêter ; le sample va sur le pad sélectionné
- Trois modes par pad (onglet Pad) : Sampler, Granulaire (nuage de grains : position, taille, densité, spray, détune, scan) et FM (2 opérateurs + feedback : ratio, index, feedback, mod decay)
- Gamme globale (sous Master) : chaque pad joue une note différente (chromatique, majeur, mineur, pentatonique)
- Micro : le sample va sur le pad sélectionné ; toucher un autre pad pendant l'enregistrement change la destination
- Édition de sample par pad (Pad > Édition) : garder / supprimer la sélection, couper les silences, inverser, fondus, normaliser, time-stretch (durée changée, hauteur gardée), transposition (hauteur changée, durée gardée), découpe en parties réparties sur les pads suivants, annulation (3 niveaux). Repères début/fin réglables au doigt sur la forme d'onde. Les éditions sont sauvegardées.
- Par pad : volume, pan, pitch, début/fin, boucle, gate, ADSR
- Effets : filtre (LP/HP/BP), distorsion, bitcrusher, chorus, delay ping-pong, reverb
- Effets individuels par pad : chaque pad a sa propre chaîne de 6 effets (onglet "FX pad"), avant la chaîne master
- Chaîne d'effets réordonnable (◀ ▶ ou L3/R3), chaque effet activable

## Manette
| Commande | Action |
|---|---|
| A, B, X, Y | pads 1 à 4 |
| Croix ↑ → ↓ ← | pads 5 à 8 |
| L1 maintenu + les 8 boutons | pads 9 à 16 |
| L2 / R2 | effet précédent / suivant |
| L3 / R3 | déplacer l'effet sélectionné vers la gauche / la droite de la chaîne |
| Start | onglet Effets : active/coupe l'effet. Onglet Pad : charger un sample |
| Select | change d'onglet : FX master → Pad → FX du pad sélectionné |
| R1 (maintenu) | les boutons déclenchent les punch-in ; R1 + Start = tout couper |
| Sticks | onglet Effets : gauche X/Y = 1er/2e réglage, droit X = 3e. Onglet Pad : gauche X = pitch, gauche Y = volume, droit X = début, droit Y = fin |

Tout est aussi jouable au tactile (multi-touch).

## Réglages utiles
- Craquements audio : dans `AudioEngine.kt`, passe `BLOCK` de 256 à 512. Latence plus basse : 128.
- Si une gâchette ou un stick ne réagit pas, le mapping des axes se règle dans `MainActivity.onGenericMotionEvent`.
- Les réglages de pads et d'effets ne sont pas sauvegardés (seuls les fichiers de samples le sont).

## Effets créatifs (par pad et master)
- **Manipulator** (`man`) : transpositeur ±24 demi-tons, fine, spread stéréo, retour, fenêtre, tilt.
- **Filterverse** (`fvr`) : filtre LP12/LP24/HP/BP/notch/peigne/voyelle/phaser, drive, LFO (5 formes), suiveur d'enveloppe, décalage stéréo.
Effets originaux inspirés de l'esprit de ces plugins Polyverse, pas leur code.

## Interface audio USB
Menu « audio » : choix du périphérique de sortie et d'entrée (USB, casque, interne). Mets le Zoom H8 en mode Audio Interface, branche-le en OTG, puis choisis-le dans les deux listes.

## Automation
Chaque effet (master et pads) a une section « automation » sous ses boutons : cible, forme du LFO, vitesse (4 mesures à 1/16 ou libre en Hz) et profondeur. Le LFO tourne autour de la valeur du bouton.

## Automation par mouvements
Section « automation : mouvements » de chaque effet : « rec on », bouge un bouton d'effet pendant que la boucle tourne (1, 2, 4 ou 8 mesures, redémarre avec le séquenceur), « rec off » pour valider. La courbe est rejouée en boucle, sauvegardée avec l'état, effaçable par réglage.

## Projets
Onglet « kits » > « projets » : enregistrer / charger / supprimer un projet complet (sons, pads, effets, automations, patterns, tempo, réglages audio).

## Mises à jour sans perdre ses projets
`app/rggg-release.jks` est la clé de signature fixe. Garde ce fichier dans le dépôt : tant que la clé est la même, une nouvelle version s'installe par-dessus l'ancienne. Exporte aussi tes projets en .zip (onglet kits > projets > exporter).

## Organisation de l'écran
Bandeau du haut (projet, play, rec, tempo, patterns 1-8, niveaux) · rail de modes à gauche : PADS, SEQ (pas / piano), SAMPLE, MIXER, FX (master / pad / punch-in), PROJET, SETUP · Select = mode suivant, Select maintenu = Shift.
