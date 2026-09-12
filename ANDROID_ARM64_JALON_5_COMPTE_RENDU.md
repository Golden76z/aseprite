# Compte rendu — Jalon 5 : présentation raster Skia sur ANativeWindow

Date : 12 septembre 2026.
Références : [audit](ANDROID_ARM64_AUDIT.md), rapports des jalons 1 à 4 et
[validation sur tablette du jalon 4](ANDROID_ARM64_JALON_4_VALIDATION_TABLETTE.md).
Point de départ : Aseprite `eff908f97`, LAF `c3e6752`.

## Résultat

**La tablette XPPen MDP1221 affiche la mire dessinée sur la surface raster LAF/Skia.
Le chemin `SkiaSurface → SkiaWindowAndroid → ANativeWindow → écran` est validé
par logcat, inspection visuelle de la capture adb et lecture de ses couleurs.**

`app_main()` reste désactivé. Aucun démarrage de l’éditeur, rendu GPU, traitement
des entrées, intégration de fichiers ou adaptation de l’interface n’est ajouté.

| Vérification | Résultat réel |
|---|---|
| Construction APK | Code 0, `BUILD SUCCESSFUL in 6s` |
| Installation sur XPPen MDP1221, Android API 34 | `Performing Streamed Install`, puis `Success` |
| Premier lancement | `Status: ok`, `LaunchState: COLD`, `TotalTime: 385`, `WaitTime: 390` |
| Fenêtre native reçue | `2160x1440`, format initial `4` (`WINDOW_FORMAT_RGB_565`) |
| Format demandé par le backend | `WINDOW_FORMAT_RGBA_8888`, dimensions natives conservées |
| Surface Skia | `2160x1440`, `rowBytes=8640`, `colorType=4` (`kRGBA_8888_SkColorType`), échelle `1` |
| Tampon Android verrouillé | `2160x1440`, stride `2160` pixels, format `1` (RGBA8888) |
| Première présentation | `First raster frame presented` après copie et `unlockAndPost` réussis |
| État de l’activité | Au premier plan, `RESUMED`, `finishing=false` |
| Validation visuelle | Capture conforme : fond sombre, grand rectangle blanc, blocs cyan/rouge et diagonale jaune |
| Passage par l’accueil et retour | Fenêtre libérée, puis recréée dans le même processus ; mire retrouvée |
| Suppression complète de la tâche | Fenêtre libérée puis `Android activity destroyed` reçu |
| Nouveau lancement après destruction | `Status: ok`, `COLD`, `TotalTime: 409`, `WaitTime: 416` ; nouvelle présentation réussie |

Les temps de lancement sont exprimés en millisecondes.

## Implémentation

### Référence de fenêtre native

`SystemAndroid` conserve une référence acquise avec `ANativeWindow_acquire`.
`setNativeWindow(nullptr)` efface le pointeur accessible avant de libérer la
référence avec `ANativeWindow_release`. Le destructeur possède également un
repli de libération si nécessaire. La configuration initiale utilise
`ANativeWindow_setBuffersGeometry(window, 0, 0, WINDOW_FORMAT_RGBA_8888)` : les
zéros conservent les dimensions du consommateur Android.

`WindowAndroid::nativeHandle()` consulte cet état Android et retourne un pointeur
emprunté, utilisable immédiatement sur le thread principal. Il retourne nul
après détachement. Les callbacks et toute la présentation s’exécutent sur ce même
thread ; aucune opération de dessin asynchrone ne conserve le pointeur.

Chaque changement de fenêtre ajoute un événement callback sans effet à la file
LAF : sa condition variable peut réveiller un consommateur en attente, même sans
entrée Android. Ce jalon n’ajoute pas de boucle active ni de thread de dessin.

### Callbacks NativeActivity

`src/main/android_main.cpp` enregistre uniquement les callbacks de fenêtre requis
en plus de `onStart`/`onDestroy` existants :

- `onNativeWindowCreated` : journalisation, rattachement et dessin de la mire ;
- `onNativeWindowResized` : lecture des dimensions réelles, log en cas de changement
  de taille, redimensionnement de la surface et nouveau dessin ;
- `onNativeWindowRedrawNeeded` : dessin et présentation synchrones avant retour ;
- `onNativeWindowDestroyed` : log, effacement du pointeur et libération native.

L’état privé de l’activité possède un `os::System` créé par la fabrique existante
et une seule fenêtre LAF. La fenêtre logique et sa surface peuvent survivre à la
perte de la fenêtre native ; au retour, elles réutilisent la nouvelle référence.
La destruction complète de l’activité libère cet état. Aucun autre callback de
cycle de vie n’est implémenté.

### Présentation et formats

`SkiaWindowAndroid::swapBuffers()` présente la surface déjà fournie par
`SkiaWindowBase`. `invalidateRegion()` peut emprunter ce même chemin de
présentation complète. Aucun nouveau type de surface ni pipeline parallèle
n’est créé.

Après `ANativeWindow_lock`, le backend vérifie le format RGBA8888, le pointeur
de pixels, les dimensions exactes et un stride au moins égal à la largeur.
La copie passe par `SkBitmap::readPixels`, avec une destination explicitement
RGBA8888 et un pas de ligne de `buffer.stride * 4` octets. Skia utilise le
`rowBytes()` propre au bitmap source. Une source BGRA8888 est convertie
explicitement par cette API vers RGBA8888 ; les autres types sont refusés avec
un log d’erreur.

Il n’y a donc aucune assimilation implicite entre largeur et stride, ni entre
largeur multipliée par quatre et pas de ligne source. Les deux pas valent ici
8640 octets : le matériel n’a pas fourni de lignes rembourrées pendant ces essais.
Les cas de padding et de source BGRA n’ont pas été exercés sur cette tablette.

Tout verrouillage réussi est équilibré par `ANativeWindow_unlockAndPost`, y
compris si la validation ou la copie échoue. Le message de première présentation
n’est émis que si la copie et le post ont tous deux réussi. Les erreurs sont
journalisées, sans réinterprétation silencieuse d’un format incompatible.

### Mire et échelle

La mire est dessinée avec le `SkCanvas` de la surface obtenue par
`system->makeWindow(WindowSpec(width, height, 1))->surface()`, celle utilisée
par les clients LAF et la future interface Aseprite. Les coordonnées des
rectangles dépendent des dimensions réelles de cette surface.

L’échelle est fixée explicitement à `1`, soit un pixel Skia pour un pixel natif.
La surface et le tampon ont donc tous deux 2160 × 1440 pixels. Le backend refuse
actuellement une présentation avec une autre échelle ou des dimensions source/
destination différentes. Aucun calcul de densité Android n’est ajouté.

Les couleurs déterministes sont : fond `(16,24,32)`, blanc `(255,255,255)`,
cyan `(0,192,224)`, rouge `(240,64,32)` et jaune `(255,208,0)`. La diagonale
mesure huit pixels d’épaisseur. Les barres système Android restent visibles.

## Construction et appareil

Commande exacte, depuis la racine :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4
```

APK testé :

```text
/home/golden/Desktop/dev/utils/aseprite-android/android/app/build/outputs/apk/debug/app-debug.apk
```

SHA-256 : `b4cb97ba9583abee1fe97ea616f53d7885fe3b410be298b0a4af033b6c72bb7b`.

La première compilation du backend a signalé :

```text
error: no matching member function for call to 'readPixels'
```

La surcharge de cette révision Skia exige cinq arguments : les coordonnées source
`0, 0` ont été ajoutées. La compilation APK finale a réussi en 6 secondes,
avec 37 tâches, dont 7 exécutées et 30 à jour. Aucun autre blocage de compilation,
de liaison ou de présentation n’a été rencontré.

Commandes adb utilisées avec l’unique tablette USB connectée :

```bash
aseprite_adb=/home/golden/Android/Sdk/platform-tools/adb
"$aseprite_adb" -d install -r android/app/build/outputs/apk/debug/app-debug.apk
"$aseprite_adb" -d shell am force-stop org.aseprite.android
# Capture lancée dans un terminal séparé avant le démarrage :
"$aseprite_adb" -d logcat -v threadtime -T 1 -s Aseprite:I AndroidRuntime:E libc:F \
  > android/build/jalon5-logcat.txt
# Dans l’autre terminal :
"$aseprite_adb" -d shell am start -W -n org.aseprite.android/android.app.NativeActivity
"$aseprite_adb" -d shell dumpsys activity activities
# Attendre la fin de l’animation de lancement avant la capture :
sleep 5
"$aseprite_adb" -d exec-out screencap -p > android/build/jalon5-screen.png
```

Un passage à l’accueil avec `input keyevent KEYCODE_HOME`, suivi du même
`am start -W`, a validé la recréation de fenêtre native dans le processus existant.
La tâche Aseprite `750`, préalablement identifiée avec `am stack list`, a ensuite
été retirée avec `am stack remove 750`. Ce numéro est propre à cette session.
Un nouveau lancement a laissé la tâche `751` ouverte, dans le processus `2175`.

## Logs réels et captures

```text
09-12 21:31:08.031  1944  1944 I Aseprite: Native window created 2160x1440 format=4
09-12 21:31:08.053  1944  1944 I Aseprite: Raster surface created 2160x1440 rowBytes=8640 colorType=4 scale=1
09-12 21:31:08.061  1944  1944 I Aseprite: Android buffer locked 2160x1440 stride=2160 format=1
09-12 21:31:08.064  1944  1944 I Aseprite: First raster frame presented
09-12 21:31:51.947  1944  1944 I Aseprite: Native window destroyed 2160x1440 format=1
09-12 21:31:51.947  1944  1944 I Aseprite: Native window cleared and reference released
09-12 21:32:14.541  1944  1944 I Aseprite: Native window created 2160x1440 format=4
09-12 21:32:53.233  1944  1944 I Aseprite: Native window destroyed 2160x1440 format=1
09-12 21:32:53.233  1944  1944 I Aseprite: Native window cleared and reference released
09-12 21:32:53.240  1944  1944 I Aseprite: Android activity destroyed
09-12 21:33:44.052  2175  2175 I Aseprite: First raster frame presented
```

Aucun changement de dimensions n’a été observé pendant ces essais ; le callback
de redimensionnement est raccordé, mais aucun log de taille différente n’est
revendiqué.

Captures adb locales de 2160 × 1440 pixels :

- `android/build/jalon5-screen.png` : première mire, inspectée visuellement ;
- `android/build/jalon5-screen-return.png` : après retour depuis l’accueil ;
- `android/build/jalon5-screen-final.png` : après destruction et nouveau lancement.

La première capture montre bien toute la mire attendue. Des mesures avec
ImageMagick confirment les cinq couleurs exactes aux points `(135,480)`,
`(432,480)`, `(810,480)`, `(1350,960)` et `(1080,720)`, également sur les captures
de retour et finale. Cela vérifie notamment l’absence d’inversion rouge/bleu.
La première tentative de capture immédiatement après le dernier lancement avait
encore saisi l’animation système ; elle a été remplacée par une capture après
stabilisation, dont les couleurs correspondent à la mire.

Les captures, journaux et APK restent dans les dossiers de construction ignorés
par Git. Aucun screenshot du bureau d’accueil n’a été utilisé pour la validation.

## Fichiers modifiés et GitHub

```text
src/main/android_main.cpp
laf/os/android/system.h
laf/os/android/system.cpp
laf/os/android/window.h
laf/os/android/window.cpp
laf/os/skia/skia_window_android.h
laf/os/skia/skia_window_android.cpp
laf                         # Référence du sous-module dans le dépôt principal
android/README.md
```

Fichier créé : `ANDROID_ARM64_JALON_5_COMPTE_RENDU.md`.
Les listes CMake et le projet Gradle incluent déjà ces sources Android ; aucune
modification de leur sélection ni des branches desktop n’a été nécessaire.

Commit LAF : [`fccf992`](https://github.com/Golden76z/laf/commit/fccf992), branche
`android-port`. Le commit Aseprite contenant ce compte rendu référence ce commit
LAF et contient la mire native ainsi que la documentation mise à jour.

**Prochain blocage exact : aucun observé dans le périmètre de ce jalon.**
La présentation raster demandée fonctionne ; les blocages d’un futur démarrage
de l’éditeur ne sont ni testés ni anticipés ici.
