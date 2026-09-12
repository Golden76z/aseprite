# Jalon 8 — dessin tactile physique observé, stylet en attente

12 septembre 2026. Références : les rapports précédents, surtout
[jalon 7](ANDROID_ARM64_JALON_7_COMPTE_RENDU.md) et
[ajustement de densité](ANDROID_ARM64_JALON_7_AJUSTEMENT_DENSITE.md).
Point de départ : Aseprite `24e4fbbab`, LAF `a853c62`.

**Le dessin tactile physique est maintenant observé dans le document.**
L’utilisateur indique : « ça a l’air de bien fonctionner ». Les traces reçues
proviennent uniquement du tactile `fts_ts` (device 4, TOOL_TYPE_FINGER → Touch),
et la capture confirme les traits blancs dans le document RGBA 256×256.
Le stylet et la gomme ne sont pas encore validés : aucun événement Pen/Eraser
physique n’a été reçu dans cette phase. Aucun trait n’a été injecté par adb.

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

## Résultats physiques — première série tactile

Fenêtre observée : du premier DOWN à 23:03:41.638 au dernier UP à 23:09:53.819.
Le retour utilisateur est positif mais général ; il ne confirme pas séparément
chaque point du protocole ni une série au stylet.

| Demande | Résultat |
|---|---|
| Entrée réelle reçue | device 4 `fts_ts`, source `0x1002`, tool=1, LAF Touch=3 |
| Contacts tactiles | 39 DOWN / 39 UP, fins `end=up` |
| Déplacements | 856 ACTION_MOVE livrés |
| Dessin dans l’éditeur | plusieurs traits séparés et continus visibles dans le document ; capture inspectée |
| Capture | les 37 DOWN traités par l’éditeur finissent avec capture=1 ; les 38 UP traités finissent avec capture=0 |
| Relâchements extérieurs | observés hors des limites du document, capture libérée |
| Annulation physique | aucun ACTION_CANCEL dans cette série ; pas de conclusion sur ce cas |
| Pression du doigt | **1.000000..1.000000**, DOWN/MOVE et historique ; UP également 1 dans les échantillons reçus |
| Historique tactile | **1 701 points**, jusqu’à **4 par événement** |
| Rejeu de l’historique | toujours absent ; aucun défaut signalé ne justifie encore de l’ajouter |
| Stylet physique / gomme | aucun événement tool=2/4, encore à tester |
| Pression stylet légère/normale/ferme | non mesurée |
| État Android après dessin | RESUMED, même PID 7801, aucun crash observé |
| Taille de l’UI | réglage conservé ; pas de problème précis signalé, avis détaillé encore absent |

Le nombre de messages de l’éditeur n’est pas celui des contacts natifs : les taps
sur l’interface peuvent être consommés par un autre widget. Ces comptes ne sont
pas présentés comme 39 traits de dessin. Aucune capture restant active après les
UP de l’éditeur n’est observée.

La capture montre des lignes continues, plusieurs tracés distincts et des points
sur le damier. Les discontinuités d’une image finale ne permettent pas de distinguer
les levées volontaires de la main d’un éventuel point manquant. L’utilisateur ne
signale pas de défaut ; aucune correction de fréquence ou interpolation n’est faite.
La latence perçue n’est pas quantifiée par cette capture statique.

### Coordonnées et bordures

Pour cette vue à zoom 100 %, le document occupe les coordonnées UI
`(372,199)..(628,455)`. Exemple réellement reçu :

```text
native=962.55,737.49 window=840,643 ui=420,321
EditorPointer down type=3 ui=420,321 canvas=48,122
canvasUiBounds=372,199..628,455 captureAfter=1
```

Des fins de trait sont également reçues au-delà du canevas, notamment
`canvas=(320,165)` puis `captureAfter=0`. Les coordonnées négatives hors document
sont attendues dans le chemin de l’éditeur ; aucun décalage correctif n’est ajouté.
Les traits couvrent plusieurs zones du document et ses bords. La correspondance
perçue exactement sous le doigt aux trois points prescrits reste à confirmer
explicitement ; aucune vérification au stylet n’est encore possible.

Le seul contact **injecté** de préparation est exclu de ces statistiques :
contact 1 sur OK, `device=-1`, DOWN pression 1 puis UP 0, aucun MOVE/historique.
Le marqueur `Jalon8_SETUP_COMPLETE_NO_MORE_INJECTION` est enregistré à 22:57:06.960.
La différence de pression au relâchement entre injection et doigt réel confirme
qu’il faut utiliser ACTION_UP, et non une pression nulle, pour terminer un contact.

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

- `android/build/jalon8-finger.png` : dessin après la série tactile, inspecté.
- `android/build/jalon8-finger-logcat.txt` : instantané des événements physiques.
- `android/build/jalon8-finger-summary.json` : comptes de cette série.
- `android/build/jalon8-activity-finger.txt` : état RESUMED après dessin.

Pas encore de `jalon8-stylus.png` : aucun essai Pen physique reçu.
Logcat continu : `android/build/jalon8-logcat.txt` ;
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

**Étape restante : essais du stylet physique et mesure de sa pression avec
l’utilisateur devant la tablette.** La série tactile donne un premier résultat
positif. Aucun changement fonctionnel ni nouvelle compilation n’est nécessaire
pour poursuivre avec le stylet ; l’APK de diagnostic et le document restent ouverts.

Effectuer la série au stylet avec trois contacts identifiés léger/normal/ferme,
les taps UI et les bordures, puis la gomme si le matériel en expose une. Confirmer
aussi le confort, les coordonnées et les éventuels défauts de continuité. Le jalon
pression devra s’appuyer sur ces mesures physiques et un comportement de capture
stable. Aucune dynamique de brosse n’est implémentée ici.
