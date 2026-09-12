# Compte rendu — Premier jalon de compilation Android ARM64

Date : 12 septembre 2026.

Référence initiale : [audit Android ARM64](ANDROID_ARM64_AUDIT.md).
Documentation de construction : [android/README.md](android/README.md).

Révisions de départ : Aseprite `375989a61`, LAF `ec6f2a5`, clip `964847c`.
Les résultats ci-dessous incluent les modifications locales du premier jalon.

## 1. Résultat obtenu

**L’infrastructure Android est créée et la configuration CMake Android réussit. La compilation ARM64 démarre, puis s’arrête sur les types du backend Android LAF qui n’ont pas encore été implémentés.**

Le résultat correspond au périmètre demandé : préparer la construction et exposer les premiers blocages réels, sans réaliser le portage graphique complet.

La cible CMake `aseprite` est désormais une bibliothèque partagée sur Android, avec une sortie prévue nommée `libaseprite.so`. Cette bibliothèque n’a pas encore été produite : la compilation échoue avant l’édition de liens. Aucun APK n’a été produit ni exécuté.

Ce document rapporte les vérifications de la session d’implémentation et les journaux présents dans le dépôt. Sa rédaction n’a pas relancé de compilation ni modifié le code.

## 2. Périmètre réalisé

- Création du projet Gradle Android et de son manifeste.
- Ajout d’un wrapper Gradle avec version et empreinte SHA-256 fixées.
- Sélection exclusive de l’ABI `arm64-v8a`.
- Création de la cible CMake partagée Android avec code indépendant de la position pour ses dépendances statiques.
- Construction séparée du générateur `gen` pour Linux, puis transmission par `GEN_EXE` à la compilation Android.
- Définition de `LAF_ANDROID`, distincte de `LAF_LINUX`.
- Séparation des sources et bibliothèques desktop X11/XCB dans les branches Android.
- Recherche des archives Skia, JPEG et WebP à leurs emplacements explicites hors du sysroot NDK.
- Construction locale de la version Skia attendue pour Android ARM64, en rendu logiciel.
- Corrections limitées aux erreurs de construction rencontrées.

Le seul point d’entrée applicatif Android ajouté est un stub NativeActivity dans `src/main/android_main.cpp`. Il compile ; s’il était lié puis lancé, il journaliserait l’absence de backend et terminerait l’activité. Il ne lance pas la boucle de l’application desktop.

## 3. Environnement utilisé

| Élément | Valeur |
|---|---|
| Hôte | Linux x86-64 |
| Java | JDK 17 |
| Gradle | 9.1.0 |
| Plugin Android Gradle | 9.0.1 |
| SDK de compilation et cible | API 36 |
| API Android minimale | 26 |
| NDK | `28.2.13676358` |
| CMake du SDK | 3.22.1 |
| Générateur de compilation | Ninja |
| ABI | `arm64-v8a` |
| Skia | `m124-08a5439a6b` |
| Révision complète de Skia | `08a5439a6be726021c1c1905d23ce298a3edc5e4` |
| Configuration Skia testée | Debug, Android ARM64, GPU désactivé |

Skia et ses dépendances ont été récupérés et construits dans les emplacements ignorés par Git suivants :

```text
.deps/skia
.deps/skia/out/android-arm64
```

Les commandes de préparation de cette dépendance figurent dans [android/README.md](android/README.md). Gradle ne télécharge ni ne construit automatiquement Skia.

## 4. Options de construction appliquées

```text
LAF_BACKEND=skia
LAF_WITH_CLIP=ON
LAF_WITH_EXAMPLES=OFF
ENABLE_TESTS=OFF
ENABLE_BENCHMARKS=OFF
ENABLE_NEWS=OFF
ENABLE_UPDATER=OFF
ENABLE_DRM=OFF
ENABLE_SCRIPTING=OFF
ENABLE_WEBSOCKET=OFF
ENABLE_STEAM=OFF
ENABLE_SENTRY=OFF
ENABLE_DESKTOP_INTEGRATION=OFF
ENABLE_QT_THUMBNAILER=OFF
ENABLE_I18N_STRINGS=OFF
ENABLE_TRIAL_MODE=OFF
```

Android utilise également `SK_BUILD_FOR_ANDROID` et `SK_SUPPORT_GPU=0`, en cohérence avec la construction Skia effectuée.

`clip` reste lié, car l’application en dépend. Android sélectionne son implémentation existante `clip_none.cpp`, locale au processus. Aucune intégration au presse-papiers système Android n’a été ajoutée.

WebP reste activé. Son archive ARM64 est correctement trouvée ; la fonctionnalité n’a pas été désactivée pour contourner une erreur de recherche.

## 5. Générateur de code côté hôte

Le nouveau projet `android/host-tools/CMakeLists.txt` construit uniquement les dépendances nécessaires à `gen` : LAF base, cfg/SimpleIni et tinyxml2. Il refuse une configuration en compilation croisée.

L’exécutable produit sur la machine de validation est :

```text
android/build/host-tools/bin/gen
```

Son format a été vérifié : exécutable ELF Linux x86-64. Son exécution sur `data/pref.xml` a généré un en-tête de préférences de 1 317 lignes.

Les tâches Gradle exécutent la configuration et la construction de ce générateur avant la configuration CMake Android. Le chemin absolu de l’exécutable est transmis dans `GEN_EXE`. CMake vérifie son existence et sa capacité à s’exécuter sur l’hôte.

La cible Android `generate_files` a ensuite réalisé **67 étapes de génération** avec ce générateur hôte : préférences, widgets, chaînes, thème, identifiants de commandes et contrôle des traductions.

## 6. Erreurs rencontrées et corrections effectuées

| Erreur observée | Cause et correction |
|---|---|
| `SimpleIni.h` introuvable pendant la compilation de cfg pour l’hôte | Ajout du chemin d’inclusion existant de SimpleIni dans le projet hôte isolé. |
| `xcb/xcb.h not found` pendant la configuration Android | La branche Unix de clip choisissait XCB. Android utilise maintenant `clip_none.cpp`. |
| `Could NOT find X11` | Android héritait du choix de plateforme desktop dans LAF OS. Les sources et bibliothèques X11 sont exclues de la branche Android. |
| Archives Skia/JPEG introuvables malgré leur présence | La recherche CMake appliquait le sysroot NDK aux chemins fournis. Les chemins explicites Android contournent maintenant cette transformation ; les arguments `PATH` de recherche Skia ont aussi été corrigés en `PATHS`. |
| Réglages GL/fontconfig desktop hérités par Android | Ajout des réglages Android pour la configuration Skia logicielle, sans liaison GL desktop ni fontconfig. |
| `aligned_alloc` non disponible à la compilation | Le NDK expose cette fonction à partir de l’API 28, alors que la cible est l’API 26. Ajout d’une branche Android utilisant `posix_memalign`. |
| Types `SkSurface_Base` et `SkSurface_Raster` incomplets | Le code raster utilise ces types même lorsque le GPU est désactivé. Leur en-tête requis est maintenant inclus hors de la condition GPU. |
| WebP activé mais archive non trouvée | Correction de la recherche de son archive Android hors du sysroot, sans désactivation de WebP. |

Ces modifications ne comprennent aucune implémentation de fenêtre ou d’entrée Android. Les sources communes Skia système/fenêtre restent dans la compilation, ce qui laisse apparaître les points de portage encore absents.

## 7. Vérifications et état exact

| Vérification | Résultat |
|---|---|
| Chargement du projet Gradle et du plugin Android | Réussi |
| Construction du générateur Linux `gen` | Réussie |
| Exécution du générateur Linux | Réussie |
| Configuration et génération CMake Android | Réussies |
| Cible `aseprite` dans le modèle CMake | `SHARED_LIBRARY`, sortie prévue `lib/libaseprite.so` relativement au dossier de compilation native |
| Génération des fichiers applicatifs | 67 étapes réussies |
| Construction Android de `laf-base` | Réussie |
| Compilation du point d’entrée NativeActivity | Réussie ; objet ELF AArch64 vérifié |
| Traitement du manifeste Android | Réussi |
| Assertions compilées sur l’identité de plateforme | Android et ARM64 confirmés ; `LAF_LINUX` absent |
| Vérification des commandes de compilation | 986 entrées ARM64/API 26 ; aucune source X11 OS/clipboard ni source du générateur `gen` |
| Code indépendant de la position | Vérifié dans les commandes des bibliothèques ; les entrées de l’utilitaire bsdunzip utilisent séparément PIE |
| Compilation native complète d’Aseprite | Échec sur les types de backend manquants |
| Édition de liens de `libaseprite.so` | Non atteinte |
| Construction ou exécution d’un APK | Non réalisée |

Au dernier inventaire, 257 fichiers objets étaient présents dans le dossier de compilation Android. Plusieurs archives de dépendances avaient été construites, dont LAF base, clip, FreeType, libarchive, GIF, zlib, cmark, JSON et XML. Cela ne signifie pas que l’ensemble d’Aseprite compile.

Aucune compilation desktop complète ni validation d’exécution Android n’est revendiquée. La reconstruction du générateur Linux a réussi après les modifications partagées.

## 8. Premiers blocages restants, réellement observés

La compilation parallèle a signalé les erreurs suivantes. Leur ordre d’apparition peut varier entre deux lancements.

### File d’événements

```text
laf/os/common/event_queue.cpp:22
error: unknown type name 'EventQueueImpl'
```

La sélection du type concret de file d’événements ne possède pas encore d’implémentation Android.

### Fenêtre Skia

```text
laf/os/skia/skia_window.h:37
error: expected class name
class SkiaWindow : public SkiaWindowPlatform
```

`SkiaWindowPlatform` n’est pas défini pour Android. Les erreurs suivantes sur `override`, `setScale`, `initializeSurface` et `clientSize` découlent de cette base absente.

### Système Skia

```text
laf/os/skia/skia_system.h:41
error: unknown class name 'SkiaSystemBase'
```

La base de système Android attendue par `SkiaSystem` n’existe pas encore.

Le travail s’est arrêté à ces points de backend, conformément au périmètre limité du jalon. Aucun correctif pour des erreurs situées au-delà de cette compilation n’est proposé dans ce compte rendu.

## 9. Commandes exactes

À exécuter depuis la racine du dépôt, avec le SDK et la dépendance Skia décrits ci-dessus disponibles.

### Configuration Android uniquement — réussie

```bash
android/gradlew -p android ':app:configureCMakeDebug[arm64-v8a]' \
  --console=plain --max-workers=4
```

### Tentative de compilation native — échec attendu au point d’arrêt actuel

```bash
android/gradlew -p android ':app:buildCMakeDebug[arm64-v8a]' \
  --console=plain --max-workers=4
```

### Générateur hôte et manifeste

```bash
android/gradlew -p android :app:buildHostGen --console=plain
android/gradlew -p android :app:processDebugMainManifest --console=plain
```

Pour utiliser une autre installation Skia :

```bash
android/gradlew -p android ':app:buildCMakeDebug[arm64-v8a]' \
  -Paseprite.skiaDir=/chemin/absolu/vers/skia \
  -Paseprite.skiaLibraryDir=/chemin/absolu/vers/skia/out/android-arm64 \
  --console=plain --max-workers=4
```

## 10. Fichiers créés pendant l’implémentation

```text
android/.gitignore
android/README.md
android/settings.gradle.kts
android/build.gradle.kts
android/app/build.gradle.kts
android/app/src/main/AndroidManifest.xml
android/gradlew
android/gradlew.bat
android/gradle/wrapper/gradle-wrapper.jar
android/gradle/wrapper/gradle-wrapper.properties
android/host-tools/CMakeLists.txt
src/main/android_main.cpp
```

Le présent compte rendu est un document supplémentaire :

```text
ANDROID_ARM64_JALON_1_COMPTE_RENDU.md
```

## 11. Fichiers modifiés pendant l’implémentation

```text
CMakeLists.txt
cmake/FindJpegTurbo.cmake
src/CMakeLists.txt
laf/base/CMakeLists.txt
laf/base/platform.h
laf/base/memory.cpp
laf/cmake/FindSkia.cmake
laf/clip/CMakeLists.txt
laf/dlgs/CMakeLists.txt
laf/os/CMakeLists.txt
laf/os/skia/skia_surface.cpp
```

Les modifications LAF se trouvent dans un sous-module Git ; clip est un sous-module imbriqué. Aucun commit ni changement de révision des sous-modules n’a été réalisé. L’audit initial est conservé sans modification.

Le CMake de zlib avait renommé son fichier source `zconf.h` pendant la configuration. Ce renommage automatique a été annulé en fin de session pour restaurer le fichier d’origine ; aucun changement zlib n’est inclus dans ce jalon.

## 12. Journaux disponibles

Ces fichiers sont des produits de compilation locaux, ignorés par Git :

- [Configuration Android](android/build/configure.log)
- [Dernière tentative de compilation native](android/build/android-build.log)
- [Génération des fichiers applicatifs](android/build/generate-files.log)
- [Construction du générateur via Gradle](android/build/host-gradle.log)
- [Traitement du manifeste](android/build/manifest.log)
- [Synchronisation des dépendances Skia](android/build/skia-sync.log)
- [Compilation Skia](android/build/skia-build.log)

## 13. Fonctionnalités non implémentées

Le jalon n’ajoute ni entrées stylet/tactiles, ni gestes, ni intégration du système de fichiers Android, ni Storage Access Framework, ni presse-papiers système, ni IME, ni EGL/GLES, ni présentation complète de fenêtre Skia, ni refonte de l’interface.

Ces éléments restent hors du périmètre réalisé. Le résultat livré est une infrastructure de compilation utilisable pour poursuivre le portage, avec des erreurs de backend identifiées par le compilateur.
