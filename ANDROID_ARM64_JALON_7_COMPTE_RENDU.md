# Compte rendu — Jalon 7 : interaction Android et interface à l’échelle 2

Date : 12 septembre 2026. Sources : [audit](ANDROID_ARM64_AUDIT.md), rapports
[1](ANDROID_ARM64_JALON_1_COMPTE_RENDU.md), [2](ANDROID_ARM64_JALON_2_COMPTE_RENDU.md),
[3](ANDROID_ARM64_JALON_3_COMPTE_RENDU.md), [4](ANDROID_ARM64_JALON_4_COMPTE_RENDU.md),
[validation tablette](ANDROID_ARM64_JALON_4_VALIDATION_TABLETTE.md),
[5](ANDROID_ARM64_JALON_5_COMPTE_RENDU.md) et surtout
[6](ANDROID_ARM64_JALON_6_COMPTE_RENDU.md).
Point de départ : Aseprite `fb27de347`, LAF `b5c393d`.

## Résultat

**L’interface Aseprite fonctionne à l’échelle 2 sur la XP-Pen MDP1221 et réagit
aux événements tactiles Android.** Les captures montrent Home, File, Edit,
Sprite et le dialogue New Sprite. Les coordonnées injectées passent réellement
par Android InputDispatcher, AInputQueue, LAF EventQueue puis `ui::Manager`.

Les essais automatisés sur la tablette valident le ciblage, le relâchement,
l’annulation, les contacts secondaires, les types Pen/Eraser, les boutons souris
et plusieurs touches. **Ils ne remplacent pas un essai avec le doigt et le stylet
physiques.** Aucune confirmation explicite de cet essai manuel n’a été reçue
pendant cette session ; le confort réel et le stylet matériel restent à valider.

| Élément | Résultat observé |
|---|---|
| Appareil adb | XP-Pen MDP1221, Android 14/API 34, `arm64-v8a`, appareil autorisé |
| Construction finale | `BUILD SUCCESSFUL in 3s`, code 0 ; 38 tâches, 7 exécutées, 31 à jour |
| Installation finale | `Performing Streamed Install`, `Success` |
| Dernier lancement à froid | `Status: ok`, `LaunchState: COLD`, `TotalTime: 903`, `WaitTime: 908` ms |
| Fenêtre physique | `2160x1440`, format initial 2 puis RGBA8888, format 1 |
| Surface Skia | `1080x720`, `rowBytes=4320`, `colorType=4`, échelle 2 |
| Buffer Android | `2160x1440`, stride 2160 pixels, format 1 |
| Présentation | agrandissement entier par duplication des pixels/lignes, sans filtrage ni GPU |
| État final | `state=RESUMED delayedResume=false finishing=false` |
| CPU final au repos | PID 6328 : 279 → 279 ticks en 12,055 s, `CLK_TCK=100` |
| Thread GUI au repos | TID 6352, état `S<`, attente `futex_wait_queue_me` |
| Thread Android principal | attente `do_epoll_wait` |
| Compilation/lien restant | aucune erreur |

La mesure CPU est un échantillon au repos, à la résolution de 10 ms, sans champ
texte actif. Elle ne mesure pas le coût des interactions. Aucun crash fatal n’est
observé pendant les scénarios finaux décrits ci-dessous. Les erreurs rencontrées
sur les versions intermédiaires sont consignées séparément.

## Architecture inspectée avant modification

- `laf/os/event.h`, `event_queue.h`, `pointer_type.h`, `keys.h` : le flux existant
  utilise les événements `Mouse*` pour tous les pointeurs, avec un `PointerType`.
  Le nom du type stylet dans ce dépôt est **`PointerType::Pen`**, pas `Stylus`.
- `src/ui/manager.cpp` : MouseEnter/MouseMove établissent la cible survolée ;
  MouseDown/MouseUp suivent la capture et le widget cible. Les positions des
  événements sont logiques. Une référence de fenêtre vide sélectionne l’unique
  affichage par défaut.
- Traductions Windows/macOS/X11 : positions adaptées à l’échelle de fenêtre,
  boutons LAF et types Touch/Pen/Eraser réutilisables. Aucun nouveau modèle de
  widget ou d’événement Android n’est nécessaire.
- `laf/os/window.h`, `laf/os/skia/skia_window_base.h` et son implémentation :
  dimensions de fenêtre physiques, surface raster divisée par `scale()`.
- `src/app/modules/gui.cpp`, préférences et chargement du thème : `screen_scale`
  règle l’échelle de fenêtre ; l’échelle du thème/UI est une abstraction distincte.
  Le jalon 6 avait sauvegardé une échelle de fenêtre 1 sur Android.
- Les en-têtes du NDK local définissent le contrat attach/get/preDispatch/finish
  d’AInputQueue et le looper de NativeActivity. Aucun thread d’entrée supplémentaire
  ni activité Java/Kotlin n’est nécessaire.

## Entrées Android

`onInputQueueCreated` attache la file native au looper principal avec
`AInputQueue_attachLooper(..., ALOOPER_POLL_CALLBACK, ...)`. Le callback vide les
événements disponibles, respecte `AInputQueue_preDispatchEvent`, puis appelle
`AInputQueue_finishEvent` pour chaque événement traité. `onInputQueueDestroyed`
détache la file avant sa destruction. Il n’existe aucune boucle de sondage active.

Le nouveau `os::InputAndroid` produit uniquement des `os::Event` dans la file LAF
existante, qui réveille son thread GUI par condition variable. Les callbacks
Android ne manipulent ni widgets ni références de fenêtres GUI. Le système expose
la position physique et l’état des touches/modificateurs sous synchronisation.

| Entrée Android | Événements LAF générés |
|---|---|
| Premier doigt ACTION_DOWN | MouseEnter, MouseMove, MouseDown/LeftButton, type Touch |
| ACTION_MOVE du contact actif | MouseMove/Touch |
| ACTION_UP | MouseMove, MouseUp/LeftButton, puis fin du contact actif |
| ACTION_CANCEL | MouseMove hors cible à `(-1,-1)` logique, MouseUp/NoneButton, MouseLeave ; état pressé effacé |
| Contact secondaire | pas de nouvelle pression ; suivi de l’identifiant actif même si les indices changent |
| Levée du contact actif avant le secondaire | MouseUp ; le contact restant ne devient pas actif |
| TOOL_TYPE_STYLUS / ERASER | même flux de contact, type Pen / Eraser |
| Souris native | MouseEnter/Leave/Move, MouseDown/Up Left/Right/Middle selon les changements de buttonState |
| Roulette native | MouseWheel ; axe horizontal direct, axe vertical inversé vers la convention LAF |
| Clavier | KeyDown/KeyUp, scancode, répétition, modificateurs, Unicode de KeyCharacterMap |

Un relâchement normal conserve la cible jusqu’au traitement de MouseUp : envoyer
immédiatement MouseLeave empêcherait les clics existants. L’annulation déplace
au contraire le pointeur hors du contrôle avant de relâcher, pour ne pas activer
un bouton annulé. La perte de focus et la destruction de la fenêtre/file effacent
aussi les états pressés ; les touches encore actives reçoivent KeyUp.

Touches couvertes : A–Z, 0–9, Escape, Enter, Backspace, Delete, quatre flèches,
Space, Tab, Shift/Ctrl/Alt/Meta gauche/droite. Le caractère utilise le véritable
`android.view.KeyCharacterMap` via JNI sur le thread Android ; aucune hypothèse
ASCII/clavier américain. Les touches système non gérées, dont Back et volume,
sont rendues à Android. Aucune composition IME ou combinaison de touche morte.

Le nom `MouseDown` est celui de l’abstraction commune : un doigt conserve le type
Touch et un stylet le type Pen. Aucun événement souris Android synthétique n’est
créé pour contourner cette abstraction.

## Échelle et rendu

Android applique une fois `general.screenScale(2)` avec le marqueur de
configuration `[Android] TabletScaleInitialized`. Cela migre l’ancien réglage 1
et initialise une installation neuve ; les choix ultérieurs de l’utilisateur
restent conservés. Le backend annonce de nouveau `WindowScale`, borne son échelle
entière entre 1 et 4, et n’annonce toujours pas MultipleWindows. L’échelle du thème
reste 1 ; aucune taille de widget/police n’est modifiée individuellement.

Le chemin reste : UI existante → surface Skia raster → SkiaWindowAndroid →
ANativeWindow. Le copieur Android agrandit les pixels et les lignes par un facteur
entier, sans interpolation. Il lit `bitmap.rowBytes()` et écrit avec
`buffer.stride * 4` ; il ne copie pas le padding. RGBA est conservé, BGRA est
converti explicitement en permutant rouge/bleu, alpha conservé. Les dimensions
et formats incompatibles sont refusés et journalisés.

À l’échelle 2, la surface est 1080×720 et remplit les 2160×1440 pixels natifs.
Les positions physiques sont divisées par l’échelle de la fenêtre :
`(1000,600) → (500,300)`. L’échelle est publiée atomiquement vers le traducteur
Android. Le tap File effectivement testé, `(18,12)`, devient `(9,6)`.

L’inspection des images montre des menus/textes deux fois plus grands que le
jalon 6, sans flou ni chevauchement nouveau dans Home ou New Sprite. Une inspection
numérique de la zone supérieure gauche 600×200 a trouvé 30 000 blocs de 2×2 pixels
identiques, sans divergence. Le confort au doigt reste une appréciation à confirmer
sur le matériel. Les autres résolutions, densités et dimensions non divisibles
par l’échelle ne sont pas validées par cette session.

`AWINDOW_FLAG_FULLSCREEN` masque seulement la barre d’état qui couvrait les menus.
La navigation Android reste superposée en bas et la poignée XP-Pen à gauche.
Elles ne couvrent pas File/Edit/Home dans les captures, mais peuvent couvrir
le bas ou le bord d’autres contenus. Aucun mode immersif JNI ni refonte des insets.

## Construction et installation reproductibles

Depuis la racine du dépôt, avec les prérequis du [README Android](android/README.md) :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4
```

Commande finale réellement exécutée avec journal :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4 \
  > android/build/jalon7-assemble-8.log 2>&1
```

APK : `android/app/build/outputs/apk/debug/app-debug.apk`.
Chemin absolu :
`/home/golden/Desktop/dev/utils/aseprite-android/android/app/build/outputs/apk/debug/app-debug.apk`.

SHA-256 de l’APK installé :
`666e00875623612c8afb40cac4dc0042bbaec661fafce40cd2cb86d3e335bcef`.
L’archive contient `lib/arm64-v8a/libaseprite.so` et
`lib/arm64-v8a/libc++_shared.so`, sans autre ABI. Ressources : 190 fichiers
extraits au démarrage. Les options de compilation du jalon 6 sont conservées.

```bash
aseprite_adb=/home/golden/Android/Sdk/platform-tools/adb
"$aseprite_adb" devices -l
"$aseprite_adb" -d install -r android/app/build/outputs/apk/debug/app-debug.apk
"$aseprite_adb" -d shell am force-stop org.aseprite.android
"$aseprite_adb" -d shell am start -W -n org.aseprite.android/android.app.NativeActivity
"$aseprite_adb" -d shell input touchscreen tap 18 12
"$aseprite_adb" -d exec-out screencap -p > android/build/jalon7-menu-touch.png
```

## Validation des interactions sur la tablette

Les commandes et le générateur d’événements reproductible sont documentés dans
[android/tests/README.md](android/tests/README.md). `InputProbe.java` est un outil
shell de test API 34, exécuté par `app_process`, jamais empaqueté dans l’APK.

| Essai | Observation |
|---|---|
| Touch tap `(18,12)` | File ouvert, capture inspectée ; Down/Up Touch logique `(9,6)` |
| Touch tap `(66,12)` | Edit ouvert, capture inspectée ; logique `(33,6)` |
| Home New File | ouverture du dialogue New Sprite, capture produite |
| DOWN puis CANCEL sur le bouton Cancel | dialogue conservé ; pas d’activation du bouton |
| Tap normal après annulation | le dialogue se ferme ; aucun état pressé persistant observé |
| Annulation immédiate en rafale | même résultat, capture inspectée |
| Deux contacts avec réordonnancement | une seule paire Down/Up ; File s’ouvre |
| Contact actif levé avant l’autre | une seule paire Down/Up ; Edit s’ouvre, pas de promotion secondaire |
| Stylet injecté `(120,12)` | type Pen, logique `(60,6)`, menu Sprite visible |
| Gomme injectée `(120,12)` | type Eraser, même ciblage et menu visible |
| Souris avec buttonState explicite | boutons gauche/droit identifiés, menu File visible |
| Roulette verticale | MouseWheel logique `(500,300)`, delta `(0,1)` ; pas de défilement visuel affirmé dans une liste vide |
| Escape | ferme le menu |
| Ctrl+N | ouvre New Sprite |
| Chiffres 6 puis 4 | largeur 64 visible dans New Sprite |
| Flèche gauche, Delete, Backspace | scancodes et KeyDown/Up reçus dans le champ |
| Ctrl+Q | `app_main returned: 0`, destruction et jonction du thread |

Les sorties réussies concernent des injections Android. Ni la pression, ni la
latence d’un stylet physique, ni un clavier/souris matériels ne sont validés.
La roulette horizontale est traduite mais n’a pas été testée sur l’appareil.

Extraits du log final :

```text
22:30:32.503 Input Touch down physical=18,12 logical=9,6 scale=2 button=1
22:30:32.505 Input Touch up physical=18,12 logical=9,6 scale=2 button=1
22:34:56.585 Native window destroyed 2160x1440 format=1
22:34:56.585 Native window cleared and reference released
22:34:56.592 Android input queue detached
22:34:56.642 Aseprite app_main returned: 0
22:34:56.642 Android activity destroyed; UI thread joined
22:34:58.455 Android input queue attached
22:34:58.543 Runtime resources extracted: 190 files
22:34:58.543 Entering Aseprite app_main
22:34:58.994 Raster surface created 1080x720 rowBytes=4320 colorType=4 scale=2
22:34:58.999 Android buffer locked 2160x1440 stride=2160 format=1
22:34:59.019 First raster frame presented
```

Le dernier lancement est resté RESUMED pendant la mesure au repos. La suppression
de la tâche avec File → Import ouvert a terminé sans assertion. Accueil Android
puis retour conserve le rendu après recréation de fenêtre. Exit puis relance dans
le même processus a été vérifié par capture, et non par les seuls logs.

## Erreurs rencontrées et corrections limitées

1. Une première version ne contenait que l’échelle et pas encore le traitement
   d’entrée. Un événement Motion non consommé a provoqué un timeout/ANR de
   distribution (8 007 ms). La version suivante attache et termine les événements
   AInputQueue ; ce timeout n’a pas été reproduit dans les scénarios finaux.
2. Une première copie via `SkPixmap.scalePixels` avec filtrage nearest coûtait
   environ 458 ms sur ce build Debug. La duplication entière spécialisée donne
   environ 20 ms entre le premier lock et la présentation dans le lancement final.
   Il s’agit d’un échantillon, pas d’un benchmark de débit continu.
3. La suppression de tâche avec un sous-menu ouvert a déclenché SIGABRT, puis
   la capture de stderr a identifié exactement :
   `src/ui/menu.cpp:1101: Assertion failed: menubox`.
   Le callback Android ferme maintenant la boucle de menus sur le thread GUI et
   laisse ses messages s’exécuter avant d’envoyer CloseApp. Le code partagé de
   `ui::Menu` n’est pas modifié. L’essai intermédiaire d’appeler `closeAll()` a
   produit `error: 'closeAll' is a private member of 'ui::Menu'` ; l’implémentation
   finale utilise l’API publique `MenuBox::cancelMenuLoop()`.
4. Exit quittait l’application sans terminer NativeActivity. Après ajout de
   `ANativeActivity_finish`, une relance dans le même processus affichait une
   image noire malgré une présentation réussie. `ui::AppState` restait kClosing,
   ce qui fait ignorer le layout dans MainWindow::onResize. L’entrée Android
   réinitialise cet état avant chaque `app_main()`. La file est purgée après
   jonction et teardown pour ne pas rejouer des événements d’une ancienne activité.
   Les captures de relance et de retour confirment la correction.

Le stderr natif est désormais conservé dans `files/user/native-stderr.log`, car
Android pouvait tuer le processus supprimé avant l’écriture du tombstone.
Le journal final ne contient pas d’assertion. Le logcat global de session conserve
volontairement les échecs intermédiaires : il ne faut pas les attribuer au dernier
APK. Les premières erreurs Java d’InputProbe concernaient l’outil shell ; sa version
finale utilise InputManagerGlobal d’API 34. Aucun Java de production n’a été ajouté.

## Tests hôte

Le projet autonome `laf/os/android/tests` a été configuré/construit avec le CMake
3.22.1 du SDK, puis CTest : **2 tests sur 2 réussis** (environ 0,11 s).

- Contrat de file existant : polling, délais, attente infinie, réveil entre threads.
- Nouveau contrat raster : RGBA et BGRA, échelles 1/2/4, couleurs/alpha distincts,
  strides source/destination indépendants, padding intact, refus des buffers
  incompatibles sans écriture.

Commandes exactes dans [android/README.md](android/README.md). Ces tests exécutent
le code portable sur Linux ; les tests de l’éditeur Android restent désactivés
comme demandé aux jalons précédents.

## Captures et preuves locales

| Fichier dans `android/build/` | Contenu |
|---|---|
| `jalon6-screen.png` | référence précédente à l’échelle 1 |
| `jalon7-screen-scaled.png` | Home final à l’échelle 2, inspecté |
| `jalon7-menu-touch.png` | File ouvert par tap Android, inspecté |
| `jalon7-menu-edit.png` | Edit ouvert, inspecté |
| `jalon7-menu-stylus-injected.png` | Sprite ouvert par Pen injecté, inspecté |
| `jalon7-menu-eraser-injected.png` | Sprite ouvert par Eraser injecté |
| `jalon7-new-dialog-second.png` | dialogue New Sprite, inspecté |
| `jalon7-cancel.png`, `jalon7-cancel-burst.png` | dialogue maintenu après annulation, inspectés |
| `jalon7-after-cancel-tap.png` | dialogue fermé par le tap suivant, inspecté |
| `jalon7-multi.png`, `jalon7-active-up.png` | menus après les essais de contacts multiples, inspectés |
| `jalon7-keyboard.png` | largeur 64, inspecté |
| `jalon7-key-edit.png` | édition du champ au clavier |
| `jalon7-menu-mouse-left.png`, `jalon7-menu-mouse-right.png` | clics souris |
| `jalon7-home-tap.png` | interaction Home |
| `jalon7-final-submenu.png` | sous-menu avant destruction finale |
| `jalon7-relaunch.png`, `jalon7-screen-return.png` | interface après Exit/relance puis Home/retour, inspectées |
| `jalon7-logcat.txt` | journal de session, versions intermédiaires incluses |
| `jalon7-activity-final.txt` | état final RESUMED |
| `jalon7-cpu.json`, `jalon7-threads.txt` | mesure au repos et attentes des threads |
| `jalon7-native-stderr-final.txt` | diagnostic natif du dernier lancement |

Les APK, captures et journaux restent des artefacts locaux ignorés par Git.

## Fichiers du jalon

Créés :

- `laf/os/android/input.h`
- `laf/os/android/input.cpp`
- `laf/os/android/raster.h`
- `laf/os/android/tests/raster_test.cpp`
- `android/tests/InputProbe.java`
- `android/tests/README.md`
- `ANDROID_ARM64_JALON_7_COMPTE_RENDU.md`

Modifiés :

- `laf/os/CMakeLists.txt` — source input uniquement dans la branche Android.
- `laf/os/android/system.h`, `system.cpp` — état d’entrée et échelle partagée.
- `laf/os/android/window.cpp` — échelle de fenêtre existante réactivée.
- `laf/os/skia/skia_system.h` — capacité WindowScale Android.
- `laf/os/skia/skia_window_android.cpp` — copie raster avec agrandissement entier.
- `laf/os/android/tests/CMakeLists.txt` — test raster autonome.
- `src/main/android_main.cpp` — callbacks d’entrée, fullscreen minimal et corrections
  de fermeture/relance révélées par les essais.
- `src/app/modules/gui.cpp` — initialisation/migration de l’échelle sous LAF_ANDROID.
- `android/app/src/main/AndroidManifest.xml` — commentaire obsolète corrigé uniquement.
- `android/README.md` — état et commandes du jalon 7.
- `laf` — référence du sous-module mise à jour vers le commit `7e217bc`.

Le backend LAF est commité séparément dans le fork Golden76z/laf ; le commit du
jalon dans Golden76z/aseprite contient l’intégration, les sondes, ce rapport et
la référence correspondante. Branche `android-port` dans les deux dépôts.

## Limites et prochain jalon

**Aucun bloqueur de compilation, de lien ou d’exécution ne subsiste dans les
scénarios finaux testés.** La validation physique doigt/stylet reste à faire.
Les insets de navigation et la poignée XP-Pen restent une limitation visible.
Le dessin dans un document, les fermetures avec documents modifiés, la rotation
et les autres densités/résolutions ne sont pas validés par ces essais.

Prochain jalon recommandé : valider matériellement les taps et les glissés au
doigt/stylet dans un document simple, y compris le ciblage et l’annulation de
capture, avant de traiter séparément la pression. Aucun support de pression,
tilt, bouton latéral, gestes multiples, IME, SAF, presse-papiers système ou GPU
n’est implémenté ici. Aucun redesign ni adaptation par widget.
