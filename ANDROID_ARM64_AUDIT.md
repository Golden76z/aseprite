# Audit du portage Android ARM64 d’Aseprite

Date : 12 septembre 2026.

Révisions inspectées :

- Aseprite : `375989a61`
- LAF : `ec6f2a5`
- Clipboard (`laf/clip`) : `964847c`

Cet audit repose sur la lecture du dépôt. Aucune configuration CMake, compilation ou exécution Android n’a été effectuée. Les conclusions de compatibilité restent donc à valider par compilation et sur appareil. Aucun fichier de code n’a été modifié pendant l’audit.

Tous les chemins ci-dessous sont relatifs à la racine du dépôt. Les nouveaux fichiers proposés n’existent pas encore.

## 1. Conclusion

Le portage nécessite principalement un backend Android dans le sous-module `laf`, ainsi qu’un démarrage et un empaquetage Android. Le code de dessin, les documents, les formats de fichiers et une grande partie de l’interface peuvent être conservés pour une première version.

La cible initiale proposée est précise : une seule fenêtre native, rendu logiciel Skia, dessin tactile et au stylet, clavier physique, et sauvegarde/réouverture dans le stockage privé de l’application.

Il n’existe pas de backend Android LAF, de manifeste Android applicatif ou de projet Gradle pour Aseprite dans ce dépôt. Les fichiers Android présents dans certaines dépendances ne constituent pas un portage de l’application.

## 2. Fichiers de compilation pertinents

| Fichier | Rôle et implications Android |
|---|---|
| [CMakeLists.txt](CMakeLists.txt) | Options globales, sélection des dépendances, hypothèses desktop dans les branches `UNIX AND NOT APPLE`. |
| [src/CMakeLists.txt](src/CMakeLists.txt) | Import possible du générateur hôte via `GEN_EXE`, copie des ressources et création de l’exécutable. Android nécessite une cible bibliothèque partagée et un empaquetage. |
| [src/gen/CMakeLists.txt](src/gen/CMakeLists.txt) | Générateur de code devant s’exécuter sur la machine de compilation. |
| [src/app/CMakeLists.txt](src/app/CMakeLists.txt) | Génération des widgets, préférences, thème, chaînes et commandes ; choix du code de recherche des polices ; liaison inconditionnelle à `clip` et `updater-lib`. |
| [laf/CMakeLists.txt](laf/CMakeLists.txt) | Backends disponibles : `none` et `skia`. Options du presse-papiers, des exemples et des tests. |
| [laf/base/CMakeLists.txt](laf/base/CMakeLists.txt) | Définit `LAF_LINUX` et lie directement `pthread` pour les cibles Unix non Apple. |
| [laf/os/CMakeLists.txt](laf/os/CMakeLists.txt) | Sélection des sources X11 par défaut ; dépendances X11, Xcursor, Xinput et Xrandr ; choix de la fenêtre Skia/X11. |
| [laf/cmake/FindSkia.cmake](laf/cmake/FindSkia.cmake) | Recherche des archives Skia, macros de plateforme, GL desktop, fontconfig, ordre des canaux et copie des données ICU. |
| [laf/clip/CMakeLists.txt](laf/clip/CMakeLists.txt) | Choix du backend de presse-papiers ; XCB requis dans la branche Unix. |
| [laf/dlgs/CMakeLists.txt](laf/dlgs/CMakeLists.txt) | Choix des dialogues natifs, avec repli vers X11. |
| [third_party/CMakeLists.txt](third_party/CMakeLists.txt) | Construction et options des dépendances embarquées. |
| [cmake/FindJpegTurbo.cmake](cmake/FindJpegTurbo.cmake) | Import de JPEG depuis la distribution Skia ou construction via `ExternalProject`. |

Les scripts `build.sh`, `.github/workflows/build.yml` et `laf/misc/skia-url.sh` sont orientés vers les plateformes desktop.

### Générateur hôte

`src/app/CMakeLists.txt` exécute `gen` pendant la construction. Un `gen` compilé pour Android ne peut pas être utilisé directement sur la machine hôte.

Le dépôt possède déjà la solution structurelle : `src/CMakeLists.txt` importe un exécutable externe lorsque `GEN_EXE` est renseigné. Il faut construire `gen` pour l’hôte, puis fournir son chemin absolu à la compilation Android.

## 3. Abstraction de plateforme

Deux couches se distinguent :

- `laf/base/platform.h` et `laf/base/platform.cpp` : identification du système, de sa version et de l’architecture CPU.
- `laf/os/system.h` : fenêtres, écrans, événements, état du clavier, saisie de texte, curseurs, surfaces et gestion des couleurs.

La détection ARM64 existe déjà avec `__aarch64__`. L’identité Android n’existe pas : l’énumération des systèmes et les branches correspondantes doivent être étendues.

`laf/os/common/system.h` fournit des implémentations par défaut réutilisables. Les bases de plateforme actuelles sont :

```text
laf/os/win/system.h
laf/os/osx/system.h
laf/os/x11/system.h
```

`laf/os/skia/skia_system.h` hérite de la base de plateforme choisie par `SkiaSystemBase`, puis fournit les surfaces et fenêtres Skia. Ajouter une base Android à ce mécanisme est le changement architectural minimal.

`LAF_BACKEND=none` ne fournit pas de solution graphique Android : `NoneSystem` conserve les fabriques de fenêtres et de surfaces nulles de `CommonSystem`. De plus, le CMake OS continue à sélectionner des sources de plateforme desktop.

Il faut définir `LAF_ANDROID` séparément de `LAF_LINUX`. Conserver `LAF_LINUX` activerait notamment les inclusions X11 de `src/app/app.cpp` et `src/app/file_selector.cpp`.

## 4. Fenêtres et rendu

| Fichier | Responsabilité |
|---|---|
| `laf/os/window.h` | Contrat de fenêtre : géométrie, échelle, visibilité, invalidation, présentation, curseur, capture, écran et handle natif. |
| `laf/os/window_spec.h` | Paramètres de création d’une fenêtre. |
| `laf/os/screen.h` | Dimensions de l’écran, zone disponible et espace colorimétrique. |
| `laf/os/surface.h` | API de dessin utilisée par l’interface. |
| `laf/os/skia/skia_surface.cpp` | Implémentation Skia des surfaces, à réutiliser. |
| `laf/os/skia/skia_window_base.h` | Allocation et redimensionnement des surfaces, espace colorimétrique, rendu GPU et repli raster. |
| `laf/os/skia/skia_window.h` | Sélection de la fenêtre propre à chaque plateforme. |
| `laf/os/skia/skia_window_x11.cpp` | Exemple concret de présentation raster et de rattachement du contexte GL. |
| `src/ui/display.cpp` | Composition de l’interface, invalidation puis appel à `swapBuffers()`. |

Le chemin de rendu existant est :

```text
Widgets et éditeur Aseprite
  → ui::Display
  → os::Window + os::Surface
  → surface Skia
  → présentation propre à la plateforme
```

La première implémentation peut conserver Skia en rendu logiciel et présenter ses pixels dans une fenêtre native Android.

Attention : le repli raster de `SkiaWindowBase` alloue une surface, mais ne présente pas ses pixels. Sur X11, ce travail est effectué dans `SkiaWindowX11::onPaint()`. Le backend Android doit fournir sa propre présentation, respecter le pas des lignes et l’ordre des canaux, et appliquer une mise à l’échelle au plus proche voisin.

Pour une accélération GPU ultérieure, le contrat est `laf/os/gl/gl_context.h`, avec l’intégration Skia commune dans `laf/os/skia/skia_gl.cpp`. Il faudra une implémentation EGL/GLES et la gestion de la perte/recréation de surface.

### Limitation à une fenêtre

Le backend initial doit annoncer des capacités adaptées. Cela ne suffit toutefois pas à interdire les fenêtres multiples : `src/ui/system.cpp::set_multiple_displays()` accepte directement la valeur reçue, et l’application l’appelle à partir des préférences.

Pour ce premier portage, cette fonction doit imposer le mode à un seul affichage sur Android. Les fenêtres de l’interface restent alors internes à cet affichage.

## 5. Abstraction des entrées

`laf/os/event.h` représente déjà :

- Touches pressées/relâchées, code point Unicode, modificateurs et répétition.
- Déplacement de pointeur, boutons, molette et double-clic.
- Type de pointeur : souris, tactile, stylet et gomme, notamment.
- Pression du stylet.
- Grossissement par pincement avec `TouchMagnify`.
- Redimensionnement, focus, fermeture et callbacks mis en file.

Les autres fichiers centraux sont :

```text
laf/os/event_queue.h
laf/os/common/event_queue.cpp
laf/os/keys.h
laf/os/pointer_type.h
src/ui/manager.cpp
```

`src/ui/manager.cpp` transforme les événements OS en messages UI et transmet le type de pointeur et la pression vers l’éditeur. Le premier portage peut donc traduire les événements Android sans réécrire cette chaîne.

La structure actuelle n’expose ni un ensemble arbitraire de contacts simultanés ni un état de composition de texte. La sélection du pointeur actif, les identifiants tactiles, l’annulation et la reconnaissance des gestes doivent être traités dans le backend. `System::setTextInput()` fournit le point d’intégration pour un futur clavier logiciel/IME.

La file Android doit respecter les trois comportements de `getEvent()` : interrogation sans attente, attente avec délai et attente indéfinie. Elle doit aussi pouvoir être réveillée par les callbacks provenant d’autres threads. Attendre uniquement les entrées Android casserait les temporisateurs et callbacks existants de l’interface.

## 6. Abstraction du système de fichiers

Le dépôt permet de réutiliser les opérations POSIX, mais ne possède pas de système de fichiers virtuel couvrant tous les accès.

| Fichier | Comportement actuel |
|---|---|
| `laf/base/fs_unix.h` | Opérations POSIX, énumération des dossiers et chemins spéciaux. |
| `laf/base/file_handle.cpp` | Ouverture des fichiers avec `fopen()`. |
| `src/dio/file_interface.h`, `src/dio/stdio.cpp` | Interfaces d’entrée/sortie de documents, dont une implémentation `FILE*`. |
| `src/app/file_system.cpp` | Modèle du navigateur de fichiers, avec `opendir/readdir/stat` dans la branche Unix. |
| `src/app/resource_finder.cpp` | Recherche des ressources et configurations à partir du binaire, du dossier personnel et de XDG. |
| `src/app/file_selector.cpp` | Choix du dialogue natif, puis repli vers le sélecteur interne. |
| `src/app/ui/file_selector.cpp` | Sélecteur de fichiers intégré à Aseprite. |

Hypothèses précises à remplacer :

- `get_app_path()` lit `/proc/self/exe`, qui ne fournit pas un emplacement de ressources applicatives Android.
- Les fichiers temporaires se rabattent sur `/tmp`.
- Le dossier des documents se rabat sur le dossier personnel, puis `/`.
- Les ressources sont cherchées dans des emplacements d’installation desktop.
- `laf/base/fs.cpp` réserve à `LAF_LINUX` la comparaison sensible à la casse lors du calcul de chemins relatifs. Ce comportement doit être conservé pour Android.

La première version peut extraire les ressources empaquetées vers le stockage privé et conserver des chemins ordinaires pour les documents. Les URI des fournisseurs de documents Android ne peuvent pas être transmises telles quelles à cette chaîne. Une étape ultérieure devra copier entre les flux du fournisseur et les fichiers de travail locaux, ou étendre plus largement les interfaces d’entrée/sortie.

## 7. Dépendances et blocages

| Élément | Constat dans le dépôt et traitement initial |
|---|---|
| X11, Xcursor, Xinput, Xrandr | Dépendances obligatoires dans `laf/os/CMakeLists.txt`. Ajouter une branche Android avant les replis desktop. |
| Presse-papiers XCB | `laf/clip/CMakeLists.txt` exige XCB sous Unix. `LAF_WITH_CLIP=OFF` ne suffit pas : `app-lib` utilise et lie `clip` sans condition. Sélectionner initialement `clip_none.cpp`. |
| Limites de `clip_none.cpp` | Stockage local au processus pour les données personnalisées et le texte. Les opérations d’images natives renvoient `false`. Ce n’est pas une intégration au presse-papiers système Android. |
| Dialogues desktop | `laf/dlgs/file_dialog_x11.cpp` inclut Xlib et lance des outils de dialogue desktop. Exclure cette source sur Android. Le retour nul existant de `FileDialog::make()` permet le repli vers le sélecteur intégré. |
| Skia externe | `laf/misc/skia-tag.txt` référence `m124-08a5439a6b`. Il faut des archives Android ARM64 compatibles ; le script de téléchargement choisit uniquement des distributions desktop. |
| Détection ARM64 de Skia | `FindSkia.cmake` assimile par défaut les cibles 64 bits à `x64`, sauf cas Apple ARM64. Ajouter le traitement Android ou fournir explicitement les chemins des archives. |
| GL, fontconfig et pixels | `FindSkia.cmake` sélectionne GL desktop, fontconfig, `SK_BUILD_FOR_UNIX` et `SK_R32_SHIFT=16` pour X11. Android exige des réglages cohérents avec sa propre compilation de Skia. Le support GPU est actuellement forcé par les définitions de compilation. |
| Polices et composition | `laf/text/skia_font_mgr.cpp` choisit fontconfig sur Linux. Ajouter la construction d’un gestionnaire de polices Android. Conserver les dépendances attendues FreeType, HarfBuzz, `skshaper`, `skunicode` et les données ICU. |
| Découverte des polices applicatives | `src/app/fonts/font_path_unix.cpp` explore notamment `~/.fonts`, `/usr/local/share/fonts` et `/usr/share/fonts`. Ajouter des emplacements adaptés à Android. |
| JPEG | La cible `libjpeg-turbo` est obligatoire. Le mode Skia attend une archive JPEG séparée dans `SKIA_LIBRARY_DIR`. Le chemin `ExternalProject` hors Skia ne transmet pas les paramètres de toolchain Android. |
| PNG | Le CMake racine suppose que Skia contient les symboles PNG sur Unix non Apple. Cette hypothèse doit être vérifiée ou remplacée pour la distribution Android choisie. |
| Générateur de code | Construire `gen` pour l’hôte et le fournir avec `GEN_EXE`. |
| Threads | Remplacer la liaison desktop littérale à `pthread` dans LAF base par une configuration adaptée à la cible. |
| UUID | `laf/base/uuid_unix.cpp` lit `/proc/sys/kernel/random/uuid`. Remplacer cette dépendance par une génération aléatoire compatible Android. Les UUID de calques l’utilisent même sans scripting. |
| Identification du système | `src/updater/user_agent.cpp` utilise les champs de distribution Linux dans son dernier `else`. Ajouter Android même si la recherche de mises à jour est désactivée : `updater-lib` est toujours construit. |
| Lancement externe | `laf/base/launcher.cpp` invoque `setsid xdg-open` dans sa branche Unix. Cette branche ne convient pas à Android. |
| Réseau et intégrations | Désactiver initialement news, updater, DRM, scripting/WebSocket, Steam et Sentry. Le CMake racine ne configure pas de backend TLS natif Android. |

Aucune dépendance comparable à un système de fenêtres desktop n’a été identifiée dans les algorithmes centraux d’images/documents ni dans les bibliothèques embarquées zlib, GIF, PNG, pixman, JSON/XML, archive et utilitaires. Cela ne garantit pas leur compilation Android : les vérifications de toolchain et de liaison restent à effectuer.

## 8. Ensemble minimal pratique de nouvelles implémentations

Cette liste vise le premier jalon décrit en conclusion, pas une version Android complète.

| Nouveaux fichiers proposés | Responsabilité |
|---|---|
| `src/main/android_main.cpp` | Entrée NativeActivity/native-app-glue ; initialisation de l’état Android, extraction des ressources, attente d’une fenêtre utilisable, puis appel de `app_main()` existant. |
| `laf/base/android.h`, `laf/base/android.cpp` | Chemins applicatifs explicites et informations Android partagés par le code base/app. |
| `laf/base/uuid_android.cpp` | Implémentation de `Uuid::Generate()` sans fichier proc Linux. |
| `laf/os/android/system.h`, `laf/os/android/system.cpp` | Dérivé de `CommonSystem`, état applicatif et de fenêtre, accès à l’écran, état clavier et position du pointeur. Une petite implémentation d’écran peut y résider. |
| `laf/os/android/event_queue.h`, `laf/os/android/event_queue.cpp` | Traitement des événements Android de cycle de vie et d’entrée, traduction des touches, file, délais et réveils. |
| `laf/os/android/window.h`, `laf/os/android/window.cpp` | Contrat `os::Window` et points d’extension attendus par `SkiaWindowBase`. |
| `laf/os/skia/skia_window_android.h`, `laf/os/skia/skia_window_android.cpp` | Association de `WindowAndroid` à `SkiaWindowBase`, présentation raster. |

Ce jalon ne nécessite pas de nouvelle implémentation de surface Android, de moteur de rendu documentaire, de format de fichier ou de toolkit de widgets.

## 9. Plan de première implémentation

### Étape 1 — Construction et empaquetage Android

Créer :

```text
android/settings.gradle.kts
android/build.gradle.kts
android/app/build.gradle.kts
android/app/src/main/AndroidManifest.xml
android/build-skia.sh
```

Modifier :

```text
CMakeLists.txt
src/CMakeLists.txt
```

Construire une bibliothèque partagée `libaseprite.so` pour `arm64-v8a`, avec du code indépendant de la position dans les bibliothèques statiques liées. Inclure `src/main/main.cpp` existant et le nouveau point d’entrée Android.

Construire `gen` pour l’hôte, puis passer son chemin absolu via `GEN_EXE`. Réutiliser la sortie de `copy_data` comme entrée d’empaquetage des ressources.

Configuration fonctionnelle initiale :

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

Ces options ne constituent pas une commande de compilation fonctionnelle avant l’implémentation des branches Android. Le rendu raster devra également être configuré de manière cohérente dans Skia et LAF.

### Étape 2 — Séparer Android de Linux desktop

Modifier :

```text
laf/base/CMakeLists.txt
laf/base/platform.h
laf/base/platform.cpp
laf/base/process.cpp
laf/os/CMakeLists.txt
laf/os/common/event_queue.cpp
laf/os/skia/skia_system.h
laf/os/skia/skia_window.h
laf/dlgs/CMakeLists.txt
laf/clip/CMakeLists.txt
src/updater/user_agent.cpp
```

Définir `LAF_ANDROID` sans `LAF_LINUX`, sélectionner les nouvelles sources, exclure `laf/os/common/main.cpp` de la cible Android, choisir `clip_none.cpp` et omettre les sources de dialogue X11.

Dans `process.cpp`, vérifier aussi les inclusions : certains en-têtes utilisés par l’implémentation Unix générique sont actuellement protégés par `LAF_LINUX`.

Conserver la fabrique Skia de `System::make()`. Une fabrique de moteur de rendu Android indépendante n’est pas nécessaire.

### Étape 3 — Adapter la dépendance Skia épinglée

Modifier :

```text
laf/cmake/FindSkia.cmake
CMakeLists.txt
laf/text/skia_font_mgr.cpp
```

Construire les archives Android ARM64 de Skia et de ses dépendances à la révision compatible indiquée par le dépôt. Ajouter la détection de plateforme/architecture Android et une configuration de rendu logiciel cohérente entre Skia et LAF.

Supprimer pour Android les réglages desktop GL/fontconfig et l’ordre des canaux imposé pour X11. Résoudre explicitement PNG, JPEG et les archives de composition de texte. Empaqueter `icudtl.dat` et vérifier son chargement effectif avec cette version de Skia ; la copie actuelle à côté d’un exécutable desktop ne démontre pas son fonctionnement dans un APK.

### Étape 4 — Démarrage, fenêtre, présentation et entrées

Implémenter les fichiers proposés à la section 8.

Le cycle de vie doit libérer les ressources de présentation lorsque la surface native disparaît, puis les reconstruire lorsqu’elle revient, tout en conservant l’état de l’éditeur tant que le processus survit.

Modifier également :

```text
src/ui/system.cpp
```

Y imposer un seul affichage pour Android. Implémenter d’abord le dessin à un doigt, le stylet avec pression et gomme, les touches physiques, et la souris avec boutons/molette. Les annulations de pointeur et pertes de focus doivent réinitialiser les états pressés.

### Étape 5 — Ressources et stockage privé

Modifier :

```text
laf/base/fs_unix.h
laf/base/fs.cpp
src/app/resource_finder.cpp
src/app/fonts/font_path_unix.cpp
laf/base/launcher.cpp
```

Faire passer les chemins spéciaux par l’état Android explicite. Extraire les fichiers empaquetés de `data/` avant l’initialisation de l’application. Fournir des dossiers accessibles en écriture pour configuration, documents et cache, ainsi que des emplacements adaptés pour les polices.

Empêcher l’utilisation de `xdg-open` sur Android ; le lancement d’applications externes sera ajouté ensuite. Réutiliser le sélecteur intégré et son modèle POSIX pour les fichiers privés.

### Étape 6 — Validation du jalon

Critères d’acceptation :

- L’APK ARM64 démarre avec thème, polices, chaînes et widgets chargés.
- Le rendu logiciel respecte les couleurs, le pas des lignes et la mise à l’échelle entière.
- Le dessin tactile et au stylet fonctionne ; une annulation ne laisse pas un trait actif.
- Les temporisateurs et callbacks de fond réveillent correctement la boucle d’événements.
- Le redimensionnement et la recréation de surface après retour au premier plan fonctionnent.
- La création, sauvegarde, fermeture et réouverture de fichiers `.aseprite` et PNG fonctionnent dans le stockage privé.
- Les préférences sont conservées après relancement.

## 10. Travail après le premier jalon

Les fonctionnalités suivantes nécessitent des implémentations supplémentaires et ne sont pas couvertes par le minimum précédent :

- Clavier logiciel, saisie de texte et composition IME.
- Import/export avec les fournisseurs de documents Android et gestion des URI.
- Intégration au presse-papiers système, notamment pour les images.
- Accélération EGL/GLES et récupération après perte de contexte.
- Gestes multitactiles plus complets et adaptation ergonomique de l’interface.
- Réactivation et validation séparées du réseau, scripting et autres intégrations.

Le premier résultat attendu est une application Android capable d’ouvrir son interface, de dessiner et de sauvegarder localement, avec une séparation claire entre les implémentations Android et Linux desktop.
