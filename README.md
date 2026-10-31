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
| R1 | stoppe tous les sons |
| Sticks | onglet Effets : gauche X/Y = 1er/2e réglage, droit X = 3e. Onglet Pad : gauche X = pitch, gauche Y = volume, droit X = début, droit Y = fin |

Tout est aussi jouable au tactile (multi-touch).

## Réglages utiles
- Craquements audio : dans `AudioEngine.kt`, passe `BLOCK` de 256 à 512. Latence plus basse : 128.
- Si une gâchette ou un stick ne réagit pas, le mapping des axes se règle dans `MainActivity.onGenericMotionEvent`.
- Les réglages de pads et d'effets ne sont pas sauvegardés (seuls les fichiers de samples le sont).
