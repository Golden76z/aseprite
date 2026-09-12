# Compte rendu — Jalon 2 : squelette du backend Android LAF

Date : 12 septembre 2026.

Sources de référence : [audit](ANDROID_ARM64_AUDIT.md) et
[compte rendu du jalon 1](ANDROID_ARM64_JALON_1_COMPTE_RENDU.md).

## Résultat

**Les trois types de plateforme manquants sont maintenant résolus. La cible
Android ARM64 `laf-os` se construit, y compris son archive `liblaf-os.a`.
La compilation complète progresse puis échoue dans `src/updater/user_agent.cpp`.**

| Blocage initial | Résolution vérifiée par compilation ARM64 |
|---|---|
| `EventQueueImpl` indéfini | Implémentation Android sélectionnée dans `laf/os/common/event_queue.cpp` |
| `SkiaWindowPlatform` indéfini | Alias vers `SkiaWindowAndroid`, dérivé de `SkiaWindowBase<WindowAndroid>` |
| `SkiaSystemBase` indéfini | Sélection de `SystemAndroid`, dérivé de `CommonSystem` |

La bibliothèque finale `libaseprite.so` n’a pas atteint l’édition de liens.
Aucun APK ni éditeur fonctionnel n’est produit par ce jalon.

## Implémentation réalisée

### File d’événements

`laf/os/android/event_queue.h` et `.cpp` implémentent le contrat de
`os::EventQueue` avec un mutex, une condition variable et une file FIFO :

- `getEvent(ev, 0.0)` retourne immédiatement ; si la file est vide, `ev` devient `Event::None`.
- Un délai positif attend un événement jusqu’à expiration du délai, exprimé en secondes.
- `kWithoutTimeout` attend indéfiniment jusqu’à l’arrivée d’un événement.
- `queueEvent()` peut être appelé depuis un autre thread et réveille le consommateur.
- Un événement déjà présent est récupéré sans attendre, y compris avec un délai infini.
- Les callbacks restent des événements : la file ne les exécute pas elle-même.
- La destruction des événements, des références de fenêtres et des captures de callbacks
  se fait hors du verrou. Leur destruction peut ainsi réentrer dans la file sans interblocage.

Cette file ne dépend pas de la réception d’entrées Android. Aucune traduction tactile
ou de stylet et aucun raccordement à une boucle d’événements native Android ne sont ajoutés.

### Système

`SystemAndroid` réutilise les valeurs conservatrices de `CommonSystem` pour les
services indisponibles : absence d’écran découvert, de menus et de curseurs natifs,
état clavier vide et opérations d’entrée sans effet.

Son `defaultWindow()` consulte la fenêtre logique vivante. La destruction de cette
fenêtre retire sa référence non propriétaire ; le système ne garde donc pas un
pointeur Android vers une fenêtre déjà détruite. Les opérations communes Skia qui
utilisent la fenêtre par défaut passent par cet accesseur.

Sur Android, les capacités Skia annoncées sont limitées à `WindowScale` et
`ColorSpaces`, fournies par le rendu raster commun. `MultipleWindows`, les curseurs
personnalisés, le redimensionnement natif et le basculement GPU ne sont pas annoncés.

### Fenêtre logique et adaptation Skia

`WindowAndroid` stocke la géométrie, l’échelle, le titre et les états logiques de
visibilité, minimisation et plein écran. Les dimensions restent positives et
multiples de l’échelle. Les changements de taille ou d’échelle passent par le
point d’extension `onResize()` attendu par `SkiaWindowBase`.

Une seule fenêtre logique peut exister à la fois sur le thread UI. Une seconde
construction lève `std::runtime_error("Android supports only one logical window")`.
Une nouvelle fenêtre peut être créée une fois la précédente détruite, après
libération de ses références, y compris celles retenues par les événements.

Les états stockés sont des demandes logiques, pas des observations d’une fenêtre
Android réelle. Le handle natif et l’écran restent nuls ; la maximisation, le focus,
la capture souris, les curseurs natifs, l’IME et l’invalidation native n’ont pas
d’implémentation. Aucun écran ni surface native fictifs ne sont créés.

`SkiaWindowAndroid` associe cette base à `SkiaWindowBase`. Le chemin existant de
`SkiaWindow` initialise la surface raster commune ; aucune présentation de ses
pixels vers Android n’est ajoutée. Le point d’entrée NativeActivity du jalon 1
reste un stub qui termine l’activité et ne lance pas l’éditeur.

### Sélection CMake

`laf/os/CMakeLists.txt` sélectionne les nouvelles sources dans sa branche `ANDROID`,
correspondant à la définition `LAF_ANDROID` fournie par `laf-base`. Les listes de
sources Windows, macOS et Linux restent inchangées pour ces plateformes.

La configuration du jalon 1 est conservée : `arm64-v8a`, API minimale 26,
`LAF_BACKEND=skia`, `SK_SUPPORT_GPU=0`, même révision Skia et mêmes options de
fonctionnalités. Aucun module UI ou éditeur n’a été désactivé.

## Fichiers créés

```text
laf/os/android/event_queue.h
laf/os/android/event_queue.cpp
laf/os/android/system.h
laf/os/android/system.cpp
laf/os/android/window.h
laf/os/android/window.cpp
laf/os/skia/skia_window_android.h
laf/os/skia/skia_window_android.cpp
laf/os/android/tests/CMakeLists.txt
laf/os/android/tests/event_queue_test.cpp
ANDROID_ARM64_JALON_2_COMPTE_RENDU.md
```

Les deux fichiers de tests constituent un projet hôte indépendant. Ils ne modifient
pas `ENABLE_TESTS=OFF` dans la construction de l’application Android.

## Fichiers modifiés

```text
laf/os/CMakeLists.txt
laf/os/common/event_queue.cpp
laf/os/skia/skia_window.h
laf/os/skia/skia_system.h
android/README.md
laf                         # Référence du sous-module dans le dépôt principal
```

L’audit et le compte rendu du premier jalon sont conservés comme documents historiques.

## Commandes exactes et résultats

Commandes exécutées depuis la racine du dépôt, avec le SDK et Skia déjà préparés
selon [android/README.md](android/README.md).

### Construction complète Android — échec sur le nouveau blocage

```bash
android/gradlew -p android ':app:buildCMakeDebug[arm64-v8a]' \
  --console=plain --max-workers=4
```

La dernière exécution a été journalisée ainsi :

```bash
android/gradlew -p android ':app:buildCMakeDebug[arm64-v8a]' \
  --console=plain --max-workers=4 > android/build/jalon2-final-build.log 2>&1
```

Résultat : **code de sortie 1, `BUILD FAILED in 6s`**.
`configureHostGen`, `buildHostGen` et `configureCMakeDebug[arm64-v8a]` réussissent.
L’échec vient de la compilation de `updater-lib`, pas de la configuration CMake.
Le générateur reste un exécutable ELF Linux x86-64 transmis par `GEN_EXE`.

### Cible LAF seule — réussite

```bash
/home/golden/Android/Sdk/cmake/3.22.1/bin/cmake \
  --build android/app/.cxx/Debug/3x1d695f/arm64-v8a \
  --target laf-os --parallel 4
```

Résultat : **code de sortie 0**. L’archive est présente à cet emplacement :

```text
android/app/.cxx/Debug/3x1d695f/arm64-v8a/lib/liblaf-os.a
```

Le composant `3x1d695f` est le répertoire de configuration Gradle de cette session.
La commande Gradle ci-dessus reste le point d’entrée indépendant de ce nom.

Les quatre nouvelles unités de compilation Android ont chacune produit un objet
ELF AArch64. Leurs commandes contiennent `LAF_ANDROID`, aucune définition de
`LAF_LINUX`, `--target=aarch64-none-linux-android26` et `SK_SUPPORT_GPU=0`.
Les sources communes de sélection de la file, du système et de la fenêtre Skia
compilent également. Au dernier inventaire, le dossier natif contient 464 objets ;
ce nombre ne constitue pas une validation de l’ensemble de l’application.

### Tests hôtes de la file — réussite

```bash
/home/golden/Android/Sdk/cmake/3.22.1/bin/cmake \
  -S laf/os/android/tests -B android/build/laf-android-tests -G Ninja \
  -DCMAKE_MAKE_PROGRAM=/home/golden/Android/Sdk/cmake/3.22.1/bin/ninja \
  -DCMAKE_BUILD_TYPE=Release

/home/golden/Android/Sdk/cmake/3.22.1/bin/cmake \
  --build android/build/laf-android-tests --parallel 4

/home/golden/Android/Sdk/cmake/3.22.1/bin/ctest \
  --test-dir android/build/laf-android-tests --output-on-failure
```

Résultat : **1/1 test CTest réussi**, en 0,11 seconde lors de la validation.
Il couvre l’interrogation immédiate, l’expiration, les événements préexistants,
le réveil depuis un autre thread des deux types d’attente, la libération réentrante
des captures de callbacks et 400 événements provenant de quatre producteurs avec
ordre FIFO par producteur et consommation unique.

Ces tests exécutent le code portable de la file Android sur Linux. Ils ne valident
ni une fenêtre sur appareil, ni la présentation, ni le cycle de vie Android.
Aucune compilation desktop complète n’est revendiquée.

## Erreurs rencontrées pendant ce jalon

1. Après ajout de la file : les objets de file compilent ; les deux bases Skia
   sont encore absentes, conformément à l’ordre d’implémentation.
2. Première compilation de la fenêtre : le stub référençait `NativeCursor::NoCursor`,
   qui n’existe pas dans ce dépôt. Correction vers la valeur existante `Hidden` ;
   les demandes de curseur natif retournent toujours `false`.
3. Première compilation du test autonome : `base/config.h` était absent. Le projet
   de test génère désormais cet en-tête depuis le modèle LAF, avec détection de
   `stdint.h` et de l’endianness de l’hôte.
4. Après ces corrections : `laf-os` compile et l’application atteint le blocage
   suivant, laissé intact.

## Nouveau blocage exact

Les quatre diagnostics suivants sont les erreurs de compilation restantes de la
dernière tentative. Seul le préfixe absolu du dépôt est retiré ici :

```text
src/updater/user_agent.cpp:57:10: error: no member named 'distroName' in 'base::Platform'
src/updater/user_agent.cpp:58:13: error: no member named 'distroName' in 'base::Platform'
src/updater/user_agent.cpp:59:12: error: no member named 'distroVer' in 'base::Platform'
src/updater/user_agent.cpp:60:22: error: no member named 'distroVer' in 'base::Platform'
```

Objet en échec :

```text
src/updater/CMakeFiles/updater-lib.dir/user_agent.cpp.o
```

Le dernier `#else` de `getFullOSString()` utilise les champs propres à Linux alors
qu’Android possède désormais une identité distincte. `src/updater/CMakeLists.txt`
construit toujours `user_agent.cpp` dans `updater-lib` ; `ENABLE_UPDATER=OFF`
désactive la vérification des mises à jour, pas cette source.

Aucune erreur d’édition de liens de `libaseprite.so` n’a été observée : cette phase
n’a pas été atteinte. Ce compte rendu ne prédit aucun blocage situé au-delà.

## Commits et GitHub

Les modifications locales du jalon 1 ont d’abord été enregistrées puis poussées
séparément, avant l’implémentation du jalon 2 :

| Dépôt | Jalon 1 | Jalon 2 |
|---|---|---|
| Aseprite | [`11e6a2389`](https://github.com/Golden76z/aseprite/commit/11e6a2389) | Commit contenant ce compte rendu et la nouvelle référence LAF |
| LAF | [`a834fba`](https://github.com/Golden76z/laf/commit/a834fba) | [`dcec8d1`](https://github.com/Golden76z/laf/commit/dcec8d1) |
| clip | [`3465b3d`](https://github.com/Golden76z/clip/commit/3465b3d) | Aucun changement |

Branche utilisée : `android-port`, dans les forks `Golden76z/aseprite`,
`Golden76z/laf` et `Golden76z/clip`. Les URL des deux sous-modules modifiés ont été
ajustées dans les commits du jalon 1 pour permettre un clonage récursif de ces forks.

Les journaux de construction restent locaux, dans `android/build/`, ignoré par Git :
`jalon2-queue.log`, `jalon2-backend.log`, `jalon2-build.log`,
`jalon2-final-build.log`, `jalon2-laf-os.log` et `jalon2-laf-os-final.log`.
