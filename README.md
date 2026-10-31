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

## Fonctions
- 16 pads, sons synthétisés fournis (kick, snare, hats, basse, lead...) pour jouer tout de suite
- Chargement de WAV 16 bits, MP3, OGG, FLAC, AAC (30 s max par sample) via le sélecteur de fichiers ;
  les samples choisis sont rechargés au lancement suivant
- Par pad : volume, pan, pitch, début/fin, boucle, gate, ADSR
- Effets : filtre (LP/HP/BP), distorsion, bitcrusher, chorus, delay ping-pong, reverb
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
| Select | bascule onglet Effets / Pad |
| R1 | stoppe tous les sons |
| Sticks | onglet Effets : gauche X/Y = 1er/2e réglage, droit X = 3e. Onglet Pad : gauche X = pitch, gauche Y = volume, droit X = début, droit Y = fin |

Tout est aussi jouable au tactile (multi-touch).

## Réglages utiles
- Craquements audio : dans `AudioEngine.kt`, passe `BLOCK` de 256 à 512. Latence plus basse : 128.
- Si une gâchette ou un stick ne réagit pas, le mapping des axes se règle dans `MainActivity.onGenericMotionEvent`.
- Les réglages de pads et d'effets ne sont pas sauvegardés (seuls les fichiers de samples le sont).
