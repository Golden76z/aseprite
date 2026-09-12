# Compte rendu — Jalon 6 : démarrage de l’interface Aseprite sur Android

Date : 12 septembre 2026. Références : [audit](ANDROID_ARM64_AUDIT.md), rapports
[1](ANDROID_ARM64_JALON_1_COMPTE_RENDU.md),
[2](ANDROID_ARM64_JALON_2_COMPTE_RENDU.md),
[3](ANDROID_ARM64_JALON_3_COMPTE_RENDU.md),
[4](ANDROID_ARM64_JALON_4_COMPTE_RENDU.md),
[validation tablette](ANDROID_ARM64_JALON_4_VALIDATION_TABLETTE.md) et
[5](ANDROID_ARM64_JALON_5_COMPTE_RENDU.md).
Point de départ : Aseprite `90ee96efe`, LAF `fccf992`.

## Résultat vérifié

**La vraie interface Aseprite est affichée sur la XPPen MDP1221 par le backend
Skia raster Android.** L’inspection des captures adb confirme les menus
`File / Edit / Sprite / Layer / Frame / Select / View / Help`, l’onglet Home,
les liens d’accueil et les listes de fichiers/dossiers récents.

Aucun document n’est ouvert : ni canevas de dessin ni boîte à outils d’un document
ne sont validés. Aucun événement tactile, stylet, souris ou clavier n’est ajouté.
La mire du jalon 5 est supprimée ; seul le chemin de rendu de l’interface utilise
la surface. Aucun nouveau pipeline graphique ni GPU n’est introduit.

| Vérification | Résultat |
|---|---|
| Construction finale | `BUILD SUCCESSFUL in 5s`, code 0 ; 38 tâches, 7 exécutées, 31 à jour |
| Installation finale | `Performing Streamed Install`, `Success` |
| Lancement final testé après installation | `Status: ok`, `LaunchState: COLD`, `TotalTime: 866`, `WaitTime: 874` ms |
| Entrée application | `Entering Aseprite app_main`, sur le thread GUI dédié |
| Initialisation | modules, outils, thème, polices, MainWindow, options puis `APP: Finish launching...` |
| Fenêtre native | `2160x1440`, format initial 4, passage RGBA8888 réussi |
| Surface Skia | `2160x1440`, `rowBytes=8640`, `colorType=4`, échelle 1 |
| Tampon Android | `2160x1440`, stride 2160 pixels, format 1 |
| Présentation | `First raster frame presented`, puis UI confirmée par capture |
| État Android | `state=RESUMED delayedResume=false finishing=false` |
| CPU au repos | 273 → 273 ticks CPU sur 12,058 secondes, `CLK_TCK=100` |
| Thread GUI au repos | TID 3349, état `S<`, attente `futex_wait_queue_me` |
| Accueil puis retour | même PID 3322 ; fenêtre détruite/libérée puis recréée ; UI retrouvée |
| Suppression normale de tâche | `app_main returned: 0`, puis `UI thread joined` |
| Relancement après destruction | `COLD`, `TotalTime: 888`, `WaitTime: 895` ms ; UI retrouvée, PID 3455 |

La première UI présentée à 21:48:53 est restée en place jusqu’au test d’accueil à
21:49:52, soit environ 59 secondes. La mesure CPU est un échantillon au repos,
à la résolution de 10 ms du compteur ; elle ne décrit pas le coût du premier
rendu. Aucun crash fatal n’est observé sur les deux lancements de la version
finale. Un crash d’investigation antérieur est décrit ci-dessous.

## Chemin de démarrage inspecté et retenu

- `src/main/main.cpp` : `app_main()` initialise locale/options, construit
  `os::System`, puis `app::App`, appelle `App::initialize()` et `App::run(true)`.
- `src/app/app.cpp` : création des modules et de `UISystem`, chargement des
  ressources, création/ouverture de `MainWindow`, traitement CLI et `Manager::run()`.
- `src/app/modules/gui.cpp` : choix de l’écran, lecture de la configuration de
  fenêtre, `system->makeWindow()`, thème et gestionnaire GUI.
- `src/ui/system.cpp` : le constructeur de `UISystem` affecte
  `main_gui_thread = std::this_thread::get_id()`.
- `src/ui/manager.cpp` : la boucle GUI attend dans `EventQueue::getEvent()` avec
  le délai du prochain timer, ou une attente infinie. Les événements `Callback`
  s’exécutent sur ce thread ; `CloseApp` utilise le chemin existant de fermeture.
- `src/app/resource_finder.cpp`, `src/CMakeLists.txt` : recherche des fichiers
  runtime et contenu du répertoire `data/` livré sur desktop.

`src/main/android_main.cpp` lance donc un **thread possédé et rejoint**, après
réception d’ANativeWindow. Ce thread extrait les ressources puis appelle
`app_main(2, {"aseprite", "--verbose"})`. La boucle bloquante d’Aseprite ne bloque
pas le looper Android. L’initialisation App/UI reste entièrement dans son chemin
existant ; `src/main/main.cpp` et `src/app/app.cpp` ne sont pas modifiés.

Le callback de destruction demande une fermeture par `CloseApp`, attend la fin du
thread et détruit son état. Dans le scénario testé sans document, l’arrêt normal
prend environ 55 ms entre destruction de fenêtre et fin du callback d’activité.
La fermeture avec documents modifiés/dialogues modaux n’est pas validée ici.

## Ressources

Gradle prépare `android/app/build/generated/runtimeAssets/` avec les mêmes fichiers
source `data/` que la cible CMake `copy_data`, les documents README/AUTHORS/EULA/
licences qu’elle ajoute, et `icudtl.dat` de la révision Skia utilisée. Il écrit un
index trié `runtime-files.txt`, puis ajoute ce dossier aux assets avant leur fusion.
Les en-têtes générés par le `gen` Linux restent compilés dans la bibliothèque.

L’APK final contient **190 fichiers runtime**, dont les thèmes, images, polices,
widgets XML, chaînes anglaises et palettes, plus l’index. `icudtl.dat` fait
799 712 octets. Sa source est la même que dans `laf/cmake/FindSkia.cmake` :
`third_party/externals/icu/flutter/icudtl.dat` sous le dossier Skia configuré.

Avant d’entrer dans Aseprite, ces fichiers sont extraits avec `AAssetManager` dans
`ANativeActivity::internalDataPath/runtime`. Les réglages restent séparés dans
`internalDataPath/user`. Sur cette tablette :

```text
/data/user/0/org.aseprite.android/files/runtime/data/
/data/user/0/org.aseprite.android/files/user/
/data/user/0/org.aseprite.android/files/user/Aseprite.log
```

L’override existant `ASEPRITE_USER_FOLDER` désigne le dossier utilisateur.
Une branche `LAF_ANDROID` de `ResourceFinder::includeDataDir()` consulte les
surcharges utilisateur puis `ASEPRITE_ANDROID_DATA_DIR`, fourni au démarrage.
`ICU_DATA` désigne le dossier runtime. Aucun chemin de build hôte n’est utilisé
pour retrouver des ressources sur Android ; aucune permission de stockage public
ni SAF n’est nécessaire. L’extraction réécrit les ressources empaquetées au
lancement ; ce jalon n’ajoute pas de système de migration des assets.

Le journal Aseprite confirme notamment :

```text
EXT: Extension 'aseprite-theme' loaded
I18N: /data/user/0/org.aseprite.android/files/runtime/data/strings/en.ini found
TOOL: Done. 26 tools, 12 groups.
THEME: Loading theme default
THEME: Loading fonts
FIND: "/data/user/0/org.aseprite.android/files/runtime/data/fonts/fonts.xml" (found)
APP: GUI mode
APP: Processing options...
APP: Finish launching...
```

Les widgets sont instanciés et le texte rendu dans la capture. Des avertissements
existants `Warning setting ... to a widget with style ...` apparaissent dans le
journal ; ils ne bloquent pas l’affichage. La découverte des polices système
Android n’a pas été implémentée : les polices livrées suffisent au démarrage observé.

## Blocages effectivement rencontrés et corrections

1. **Gradle**, première construction :

   ```text
   Error : You cannot add Provider instances to the Android SourceSet API.
   ```

   Le dossier d’assets est enregistré comme chemin résolu dans `assets.directories`,
   avec dépendance explicite des tâches `merge*Assets` vers `prepareRuntimeAssets`.
   La construction suivante réussit. Le chemin ICU initial est aussi corrigé pour
   correspondre au fichier effectivement utilisé par FindSkia.

2. **Premier lancement, SIGABRT** après entrée dans `app_main()` : la pile donne
   `base_assert → load_gui_config → create_main_window → init_module_gui →
   App::initialize → app_main`. Symbolisation de l’adresse `0x1ee4b8c` avec le
   `llvm-addr2line` du NDK et la bibliothèque non dépouillée :

   ```text
   app::load_gui_config(os::WindowSpec&, bool&) at src/app/modules/gui.cpp:286
   ```

   C’est `ASSERT(screen)` : `CommonSystem::primaryScreen()` renvoyait nul.
   `SystemAndroid` fournit maintenant un `AndroidScreen` dont les limites et la
   zone utilisable viennent de la fenêtre native réelle. Aucun écran X11 fictif.

3. **Lancement suivant**, initialisation complète mais présentation refusée :

   ```text
   Raster surface created 1080x720 rowBytes=4320 colorType=4 scale=2
   Unsupported raster scale or color type
   ```

   Aseprite choisissait l’échelle desktop initiale 2. `WindowAndroid` utilise
   maintenant les dimensions natives dès sa construction, conserve l’échelle 1
   et ignore les demandes de changement d’échelle non prises en charge.
   `SkiaSystem` n’annonce plus `WindowScale` sur Android. Aucun calcul de densité,
   agrandissement logiciel ou modification des préférences desktop n’est ajouté.

La copie raster du jalon 5 (`readPixels`, validation du format et des strides,
`lock/unlockAndPost`) reste identique. Sa seule modification est le verrou de
protection nécessaire à la présentation sur le nouveau thread GUI.

## Fenêtre, événements et synchronisation

L’état natif Android existe avant `System::make()`, sans créer un second System.
`SystemAndroid::setNativeWindow()` acquiert/libère la référence native sous mutex.
`lockNativeWindow()` fournit un verrou qui reste tenu pendant toute la copie et
le post ; la destruction attend donc une présentation en cours avant de libérer
la référence. Aucun pointeur natif brut non protégé n’est exposé via `nativeHandle()`.

Les callbacks envoient des événements à la file Android existante. Le thread GUI
met à jour la géométrie logique et présente sa propre surface. Le redraw synchrone
Android attend un acquittement sur condition variable, ou la fin d’application
si le démarrage échoue. Aucune attente active ni boucle de sondage n’est ajoutée.
Le test accueil/retour confirme le réveil depuis le repos et la présentation après
remplacement d’ANativeWindow. `MultipleWindows` reste absent des capacités.

## Captures et limites visuelles

Fichiers locaux, ignorés par Git :

- `android/build/jalon6-screen.png` : première interface, inspectée visuellement ;
- `android/build/jalon6-screen-stable.png` : avant passage à l’accueil ;
- `android/build/jalon6-screen-return.png` : après recréation, inspectée ;
- `android/build/jalon6-screen-relaunch.png` : après arrêt et relancement, inspectée.

Les images font 2160 × 1440 pixels et montrent l’interface attendue, sans mire.
À l’échelle 1, menus et textes sont très petits. Les zones vides des listes Home
occupent presque tout l’écran. Les barres de statut/navigation Android et
l’overlay XP-Pen recouvrent une partie de l’interface. Ce jalon ne change ni la
mise en page d’Aseprite ni la gestion des insets Android.

## Commandes et artefacts

Depuis la racine du dépôt :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4
```

APK testé :

```text
/home/golden/Desktop/dev/utils/aseprite-android/android/app/build/outputs/apk/debug/app-debug.apk
```

SHA-256 : `154974671216148b64ea3a2fff9b0a712c55d97d0a2279ebf32dddc645b90631`.
L’archive contient `lib/arm64-v8a/libaseprite.so` et
`lib/arm64-v8a/libc++_shared.so`. La déclaration NativeActivity du manifeste
reste celle déjà validée. Aucun fichier Java/Kotlin d’activité n’est ajouté.

```bash
aseprite_adb=/home/golden/Android/Sdk/platform-tools/adb
"$aseprite_adb" devices -l
"$aseprite_adb" -d install -r android/app/build/outputs/apk/debug/app-debug.apk
"$aseprite_adb" -d shell am force-stop org.aseprite.android
# Dans un terminal séparé, avant le lancement :
"$aseprite_adb" -d logcat -v threadtime -T 1 \
  -s Aseprite:I AndroidRuntime:E libc:F DEBUG:F > android/build/jalon6-logcat.txt
# Lancement :
"$aseprite_adb" -d shell am start -W -n org.aseprite.android/android.app.NativeActivity
sleep 5
"$aseprite_adb" -d exec-out screencap -p > android/build/jalon6-screen.png
"$aseprite_adb" -d shell dumpsys activity activities > android/build/jalon6-activity.txt
"$aseprite_adb" -d shell run-as org.aseprite.android cat files/user/Aseprite.log
```

Tablette détectée : `XCD1205AF825A05168 device`, modèle `MDP1221`, API 34.
Le test de fermeture a retiré la tâche Aseprite **754**, identifiée au préalable
par `am stack list`, avec `am stack remove 754`. Ce numéro est propre à la session.
Le processus final 3455 reste ouvert après relancement.

Extraits logcat réels de la version finale :

```text
09-12 21:48:52.832  3322  3349 I Aseprite: Runtime resources extracted: 190 files
09-12 21:48:52.832  3322  3349 I Aseprite: Entering Aseprite app_main
09-12 21:48:53.329  3322  3349 I Aseprite: Raster surface created 2160x1440 rowBytes=8640 colorType=4 scale=1
09-12 21:48:53.334  3322  3349 I Aseprite: Android buffer locked 2160x1440 stride=2160 format=1
09-12 21:48:53.336  3322  3349 I Aseprite: First raster frame presented
09-12 21:49:52.821  3322  3322 I Aseprite: Native window cleared and reference released
09-12 21:49:54.433  3322  3322 I Aseprite: Native window created 2160x1440 format=4
09-12 21:50:15.638  3322  3349 I Aseprite: Aseprite app_main returned: 0
09-12 21:50:15.639  3322  3322 I Aseprite: Android activity destroyed; UI thread joined
09-12 21:50:55.528  3455  3482 I Aseprite: First raster frame presented
```

## Fichiers modifiés

```text
android/app/build.gradle.kts
src/main/android_main.cpp
src/app/resource_finder.cpp
laf/os/android/system.h
laf/os/android/system.cpp
laf/os/android/window.h
laf/os/android/window.cpp
laf/os/skia/skia_system.h
laf/os/skia/skia_window_android.cpp
laf                         # Référence du sous-module
android/README.md
```

Fichier créé : `ANDROID_ARM64_JALON_6_COMPTE_RENDU.md`.
Aucun changement CMake ni désactivation supplémentaire de module.
Les branches Windows/macOS/Linux restent inchangées. Les APK, captures et journaux
restent dans les dossiers de build ignorés.

Commit LAF : [`b5c393d`](https://github.com/Golden76z/laf/commit/b5c393d),
branche `android-port`. Le commit Aseprite contenant ce rapport référence ce
commit LAF et livre le raccordement NativeActivity ainsi que les assets.

**Blocage runtime restant pour afficher Home : aucun observé.** L’arrêt demandé
est atteint avec la validation visuelle de l’interface principale. Le prochain
jalon recommandé est le raccordement limité de la file d’entrée Android aux
événements LAF pour permettre les premières interactions ; la prise en charge
avancée du stylet/pression peut être traitée séparément. L’échelle et les barres
système restent des limitations visibles à prendre en charge dans un jalon dédié.
