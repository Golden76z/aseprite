# Jalon 8 — dessin physique : préparation terminée, essais matériels en attente

12 septembre 2026. Références : les rapports précédents, surtout
[jalon 7](ANDROID_ARM64_JALON_7_COMPTE_RENDU.md) et
[ajustement de densité](ANDROID_ARM64_JALON_7_AJUSTEMENT_DENSITE.md).
Point de départ : Aseprite `24e4fbbab`, LAF `a853c62`.

**Ce document ne valide pas encore le dessin physique.** Le nouvel APK de
mesure est installé et un vrai document 256×256 RGBA transparent est ouvert.
La suite exige les gestes de l’utilisateur au doigt puis au stylet.
Aucun trait de validation n’a été injecté par adb.

## Préparation vérifiée

- Tablette XP-Pen MDP1221 connectée et autorisée, Android API 34, arm64-v8a.
- Construction finale : `BUILD SUCCESSFUL in 5s`, 38 tâches, 7 exécutées,
  31 à jour ; aucune erreur de compilation ou de lien.
- Installation : `Performing Streamed Install`, `Success`.
- Lancement : `Status: ok`, `COLD`, `TotalTime: 1163`, `WaitTime: 1168` ms.
- Processus 7801, activité `state=RESUMED delayedResume=false finishing=false`.
- Densité 366 dpi, framebuffer 2160×1440, surface Skia 944×629,
  rowBytes 3776. Aucun changement d’échelle ni de présentation.
- Document créé par l’interface existante : Ctrl+N, champs 256×256, RGBA,
  fond transparent, bouton OK. Outil crayon disponible, taille affichée 1 px,
  zoom de document 100 %. Palette, barre d’outils et damier visibles.

Commande de build :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4 \
  > android/build/jalon8-assemble-2.log 2>&1
```

APK installé : `android/app/build/outputs/apk/debug/app-debug.apk`.
Première étape de diagnostic LAF : build réussi en 3 s dans
`android/build/jalon8-assemble-1.log`. Deuxième étape : ajout de la mesure au
niveau de l’éditeur, build réussi en 5 s, APK ci-dessus.

## Instrumentation ajoutée

Uniquement dans les builds Debug :

- `MotionSample` journalise les valeurs NDK brutes : device/source/tool/action,
  boutons, positions native/fenêtre LAF/UI, pression, tilt/orientation,
  taille d’historique et timestamp Android. DOWN/UP, trois premiers MOVE par
  contact et une observation de hover par type ; pas de log à chaque MOVE.
- `MotionSummary` conserve le nombre de MOVE, la présence/quantité d’historique,
  les extrêmes de pression durant le contact, la durée et le plus grand intervalle
  entre timestamps d’événements livrés. Les extrêmes couvrent DOWN/MOVE et leurs
  points historiques ; le relâchement est journalisé séparément.
- `EditorPointer` mesure les DOWN/UP reçus dans le véritable éditeur : position
  UI, position dans le document, limites du canevas en coordonnées UI et état
  de capture après traitement par l’état courant de l’éditeur.

Ces diagnostics ne modifient pas les événements, ne transmettent pas la pression
aux brosses et ne rejouent pas l’historique. Les logs sont omis en Release.
Aucune nouvelle traduction de hover, bouton latéral, tilt ou pression.

## Chemin existant inspecté

`InputAndroid::motion()` sélectionne le contact actif et transmet sa position
courante par `pointer()` à LAF EventQueue. Le Manager transforme ces événements
en messages UI et respecte la capture. `DrawingState` capture le pointeur au
commencement d’un tracé et le relâche à la fin du tracé normal.

`Editor::screenToEditor()` enlève l’origine du viewport et le padding, applique
le scroll puis retire le zoom/projection du document. Ce calcul commun n’a pas
été modifié. `DelayedMouseMove` utilise le même calcul, avec l’auto-scroll aux
bords ; le délai des outils freehand est zéro dans le code inspecté.

L’historique Android n’est actuellement pas rejoué. Les diagnostics le comptent
et lisent sa pression sans créer de nouveaux événements. Sa nécessité ne pourra
être jugée qu’après observation de traits physiques.

## Résultats physiques à ce stade

| Demande | Résultat |
|---|---|
| Tests réellement effectués avec le doigt | Aucun reçu depuis la préparation |
| Doigt : contrôles, traits, relâchements | En attente de l’utilisateur |
| Stylet physique : UI et dessin | En attente |
| Gomme physique | Non testée ; comportement matériel inconnu |
| Tool types physiques reçus | Aucun pendant cette phase de mesure |
| Pression minimale/maximale réelle | Non mesurée |
| Pression légère/ferme | Non mesurée ; essais séparés nécessaires |
| Pression du doigt | Non mesurée |
| Historique MOVE physique | Présence inconnue, besoin de replay non établi |
| Alignement haut-gauche/centre/bas-droite | En attente de gestes et d’observation utilisateur |
| Latence, trous, sauts ou relâchement manqué | Non évalués physiquement |
| Confort de la taille d’UI | Avis utilisateur en attente ; échelle conservée |

Le seul contact enregistré après installation au moment de ce compte rendu est
le tap **injecté** sur OK : `device=-1`, `tool=1`, pression DOWN 1 puis UP 0,
aucun MOVE/historique. Ces valeurs ne sont pas des mesures du doigt physique.
Le marqueur `Jalon8_SETUP_COMPLETE_NO_MORE_INJECTION` est enregistré à 22:57:06.960.
Aucune autre injection n’est utilisée pour dessiner.

## Capacités déclarées par Android — pas des mesures de contact

`dumpsys input` identifie dans cette session :

- Device 7, `tct_stylus Mouse`, agrégeant les nœuds `tct_stylus` et sa souris :
  sources incluant STYLUS, axes Android pression 0..1, tilt et orientation.
  L’axe brut de pression du pilote annonce 0..16383.
- Device 4, `fts_ts` : écran tactile, pression Android annoncée 0..1,
  axe brut de pression absent, calibration `touch.pressure.calibration: none`.

Cela ne prouve ni la plage réellement atteinte ni l’existence d’une gomme
physique. Les identifiants sont propres à la session et doivent être recoupés
avec les prochains MotionSample et les gestes déclarés par l’utilisateur.

## Preuves et procédure

Captures produites :

- `android/build/jalon8-before.png` : état avant installation.
- `android/build/jalon8-home.png` : Home à la densité actuelle.
- `android/build/jalon8-document-settings.png` : dialogue réglé à 256×256 RGBA.
- `android/build/jalon8-document.png` : vrai document vide, capture inspectée.

Pas encore de `jalon8-finger.png` ni de `jalon8-stylus.png` : il n’y a pas eu
d’essai physique à capturer. Logcat : `android/build/jalon8-logcat.txt` ;
capacités : `android/build/jalon8-input-devices.txt` ; état Android :
`android/build/jalon8-activity.txt`. Les artefacts restent locaux, ignorés par Git.

La procédure reproductible est dans
[android/tests/PHYSICAL_DRAWING.md](android/tests/PHYSICAL_DRAWING.md).
La capture logcat est laissée active en attendant les gestes.

## Fichiers

Créés :

- `laf/os/android/motion_diagnostics.h`
- `android/tests/PHYSICAL_DRAWING.md`
- `ANDROID_ARM64_JALON_8_COMPTE_RENDU.md`

Modifiés :

- `laf/os/android/input.h`, `input.cpp` : observation Debug du flux brut et résumé
  lors d’une interruption de focus/lifecycle ; traduction existante inchangée.
- `src/app/ui/editor/editor.cpp` : trace Debug Android des coordonnées et capture
  aux frontières DOWN/UP de l’éditeur, sans changement de traitement.
- `laf` : référence du sous-module mise à jour.

## Point bloquant et suite

**Blocage exact : les gestes physiques et leur observation nécessitent
l’utilisateur devant la tablette.** La préparation, la compilation et
l’installation ont réussi ; aucun problème de dessin physique ne peut encore
être affirmé ou corrigé.

Effectuer d’abord la série au doigt, puis celle au stylet avec trois contacts
identifiés léger/normal/ferme et la gomme si le matériel en expose une. Mettre à
jour ce rapport avec les mesures réelles avant de déclarer le jalon validé.
Le jalon pression devra ensuite s’appuyer sur ces valeurs et sur un comportement
de capture stable. Aucune dynamique de brosse n’est implémentée ici.
