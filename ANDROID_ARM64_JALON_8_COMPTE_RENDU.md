# Jalon 8 — dessin physique au doigt et au stylet XP-Pen

12 septembre 2026. Références : les rapports précédents, surtout
[jalon 7](ANDROID_ARM64_JALON_7_COMPTE_RENDU.md) et
[ajustement de densité](ANDROID_ARM64_JALON_7_AJUSTEMENT_DENSITE.md).
Point de départ : Aseprite `24e4fbbab`, LAF `a853c62`.

**Le doigt et le stylet physiques dessinent dans le véritable document Aseprite.**
L’utilisateur a effectué les deux séries, avec les retours « ça a l’air de bien
fonctionner » puis « c’est bon ». Les traces distinguent le tactile `fts_ts`
(device 4, TOOL_TYPE_FINGER → Touch) et le stylet interne `tct_stylus`
(device 7, TOOL_TYPE_STYLUS → Pen). Les captures avant/après confirment les traits.
Aucun trait de validation n’a été injecté par adb.

La pression Android réelle du stylet a été mesurée : **0,007264 à 0,871147**
pendant les contacts, sans modifier les dynamiques des brosses. Aucun problème
reproduit n’a nécessité de correction fonctionnelle. La gomme matérielle et
l’annulation physique restent des cas non établis par cette session.

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
et lisent sa pression sans créer de nouveaux événements. Les deux séries réelles
confirment sa présence, mais aucun défaut signalé ne justifie son rejeu à ce stade.

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
| Stylet physique / gomme | hors de cette première série ; résultats stylet ci-dessous |
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
perçue exactement sous le doigt aux trois points prescrits n’a pas fait l’objet
d’un commentaire séparé ; le retour utilisateur global est positif. Les résultats
au stylet sont détaillés ci-dessous.

Le seul contact **injecté** de préparation est exclu de ces statistiques :
contact 1 sur OK, `device=-1`, DOWN pression 1 puis UP 0, aucun MOVE/historique.
Le marqueur `Jalon8_SETUP_COMPLETE_NO_MORE_INJECTION` est enregistré à 22:57:06.960.
La différence de pression au relâchement entre injection et doigt réel confirme
qu’il faut utiliser ACTION_UP, et non une pression nulle, pour terminer un contact.

## Résultats physiques — série au stylet

De 23:17:32.509 (hover) à 23:18:06.332 (dernier UP), après la consigne de trois
traits léger/normal/ferme, puis de traits rapides, bordures et taps UI. L’utilisateur
confirme ensuite « c’est bon ».

| Vérification | Observation |
|---|---|
| Matériel | device=7, `tct_stylus`, source=`0x5002`, tool=2 / TOOL_TYPE_STYLUS |
| Type LAF | `Pen`, valeur 4 dans `EditorPointer` |
| Contacts | **14 DOWN / 14 UP**, tous `end=up` |
| Déplacements | **336 ACTION_MOVE** livrés |
| Historique | **968 points**, jusqu’à **6 par événement** |
| Capture de l’éditeur | 11 DOWN avec captureAfter=1 ; 12 UP avec captureAfter=0 |
| Pression pendant les contacts | **0,007264..0,871147**, valeurs Android non remodelées |
| Pression des UP | **0,007264..0,022523**, donc pas nécessairement zéro |
| Hover | ACTION_HOVER_ENTER=9 observé, Pen, pression 0 ; aucune traduction de hover ajoutée |
| Boutons dans les échantillons | `buttons=0x0` uniquement |
| Eraser | aucun TOOL_TYPE_ERASER ni type LAF Eraser observé |
| État Android | RESUMED, même PID 7801, aucune assertion/crash observé |

Les messages UI peuvent être consommés par les menus : les nombres de contacts
natifs et de DOWN/UP de l’éditeur ne doivent pas être confondus. Tous les UP reçus
par l’éditeur dans cette série libèrent la capture. Aucun ACTION_CANCEL physique
n’a été observé, donc ce cas n’est pas déclaré validé par le jalon 8.

### Pression légère, normale et ferme

Les trois premiers contacts après la consigne donnent :

| Ordre demandé | Contact | Min contact | Max contact | Durée |
|---|---|---|---|---|
| Léger | 41 | 0,011536 | **0,310932** | 672,135 ms |
| Normal | 42 | 0,014039 | **0,662394** | 1 165,755 ms |
| Ferme sans forcer | 43 | 0,022523 | **0,871147** | 1 056,189 ms |

L’attribution léger/normal/ferme suit l’ordre explicitement demandé et la
confirmation de l’utilisateur. Ce n’est pas une mesure de force calibrée : les
minima incluent le début/fin de contact et ne représentent pas une pression
maintenue. Les maxima croissants montrent que des valeurs distinctes sont bien
livrées. Le minimum global 0,007264 provient d’un tap UI ultérieur, contact 50.

Les échantillons de tilt vont de 0,20944 à 0,68619 rad et ceux d’orientation de
−2,87999 à 3,14159 rad. Ce sont uniquement les points journalisés, pas les extrêmes
exhaustifs des axes de toute la série. Aucun de ces axes n’est transmis aux outils.

### Coordonnées et qualité du tracé

Exemples réels de la chaîne complète, à zoom document 100 % :

| Position native | Fenêtre LAF ajustée | UI logique | Document |
|---|---|---|---|
| `(920,08 ; 473,75)` | `(804,413)` | `(402,206)` | `(30,7)`, près du haut-gauche |
| `(958,52 ; 724,15)` | `(837,632)` | `(418,316)` | `(46,117)`, zone médiane gauche |
| `(1393,60 ; 1005,45)` | `(1217,877)` | `(608,438)` | `(236,239)`, près du bas-droite |

Les trois grands traits traversent le document verticalement. Plusieurs fins
sont hors de sa limite basse : `(46,303)`, `(88,266)`, `(148,290)` pour un document
haut de 256 pixels, toujours avec captureAfter=0. Les traits restent découpés par
les limites du document dans la capture. Les traits rapides suivants sont séparés
et visibles près du bas-droite, puis d’autres traits apparaissent à gauche.

Un tap Pen à `(22,19 ; 7,12)` physique devient `(9,3)` UI, emplacement de File ;
les contacts suivants dans l’interface sont suivis de nouveaux traits valides.
Il n’y a pas de capture d’écran du menu pendant qu’il était ouvert au stylet :
la preuve disponible est le flux physique, la poursuite du dessin et le retour
utilisateur. Aucun décalage n’est signalé ; aucune correction par offset n’est faite.

La comparaison de `jalon8-finger.png` et `jalon8-stylus.png` confirme les nouveaux
traits du stylet. Aucune pression variable n’est appliquée à leur épaisseur :
le crayon reste à 1 px. Les captures statiques ne mesurent ni la position exacte
sous une pointe qui masque l’écran, ni la latence de bout en bout.

Les trois grands traits reçoivent environ 58 événements MOVE/s ; leurs plus grands
intervalles entre timestamps de paquets livrés sont 34,624 / 35,482 / 34,178 ms.
L’historique contient des échantillons intermédiaires. Aucun problème de traits
troués ou de latence gênante n’est rapporté ; aucune optimisation, interpolation
ou rejeu d’historique n’est ajouté sans défaut constaté.

### Gomme et confort

Aucun événement Eraser ni bouton non nul n’apparaît dans les échantillons. Cela
ne prouve pas l’absence d’une gomme matérielle : sa présence et sa manière
d’activation ne sont pas confirmées. Aucun mode gomme fictif n’est ajouté.

L’UI reste à 366 dpi / 944×629 logique / 2160×1440 physique, agrandissement nearest
approximativement 2,29×. Aucun nouveau problème de taille n’est signalé pendant
les essais ; le réglage est conservé. Les barres Android et la poignée XP-Pen
restent les limites d’insets connues. Après dessin, le thread GUI 7865 attend dans
`futex_wait_queue_me` et le principal dans `do_epoll_wait` : pas de boucle active
observée au repos dans cet instantané.

## Capacités déclarées par Android — pas des mesures de contact

`dumpsys input` identifie dans cette session :

- Device 7, `tct_stylus Mouse`, agrégeant les nœuds `tct_stylus` et sa souris :
  sources incluant STYLUS, axes Android pression 0..1, tilt et orientation.
  L’axe brut de pression du pilote annonce 0..16383.
- Device 4, `fts_ts` : écran tactile, pression Android annoncée 0..1,
  axe brut de pression absent, calibration `touch.pressure.calibration: none`.

Cela ne prouve ni la plage réellement atteinte ni l’existence d’une gomme
physique. Les identifiants sont propres à la session et doivent être recoupés
avec les MotionSample physiques et les gestes déclarés par l’utilisateur.

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

- `android/build/jalon8-stylus.png` : nouveaux traits du stylet, capture inspectée.
- `android/build/jalon8-pen-logcat.txt` : phase de stylet physique isolée.
- `android/build/jalon8-pen-summary.json` : mesures par contact.
- `android/build/jalon8-activity-stylus.txt` : état RESUMED après la série au stylet.

Logcat continu : `android/build/jalon8-logcat.txt` ;
capacités : `android/build/jalon8-input-devices.txt` ; état Android :
`android/build/jalon8-activity.txt`. Les artefacts restent locaux, ignorés par Git.

La procédure reproductible est dans
[android/tests/PHYSICAL_DRAWING.md](android/tests/PHYSICAL_DRAWING.md).
Les deux séries sont enregistrées ; le document est conservé ouvert sur la tablette.

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

## Résultat final et prochaine étape

**Aucun bloqueur de compilation, de lien ou de dessin n’a été reproduit pendant
les deux séries physiques.** Le doigt et le stylet dessinent, les relâchements
libèrent la capture, les contacts hors canevas ne la laissent pas active et
l’activité reste stable. La pression variable du véritable stylet est confirmée.
La gomme matérielle et l’annulation physique restent non validées ; les avis de
confort/latence/alignement sont positifs mais généraux, sans mesure instrumentée
sous la pointe. Aucun sous-système supplémentaire n’a été implémenté.

Prochain jalon recommandé : transmettre la pression Android du Pen dans le champ
existant `os::Event::setPressure()` depuis `laf/os/android/input.cpp`, puis valider
les dynamiques de pression déjà présentes. Le chemin commun existe :
`src/ui/manager.cpp` → `src/app/ui/editor/glue.h::pointer_from_msg()` →
`src/app/tools/tool_loop_manager.cpp::adjustPointWithDynamics()`.
Conserver ACTION_UP/CANCEL comme fin de contact, puisque la pression au relâchement
n’est pas nécessairement nulle. Ne pas recalibrer le maximum Android 1 sur le seul
maximum observé 0,871147 ni inventer une courbe à partir de ces quelques traits.
**Cette transmission et les dynamiques restent à implémenter dans un autre jalon.**

Commits de préparation : Aseprite `88d2154a9`, LAF `1b478d6` ; première preuve
physique au doigt consignée dans `b2cfefcdd`. La présente mise à jour ne modifie
que le rapport : l’APK testé reste celui construit et installé en préparation.
