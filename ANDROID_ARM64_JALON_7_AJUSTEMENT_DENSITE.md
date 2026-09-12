# Ajustement du jalon 7 — interface légèrement agrandie, adaptée à l’écran

12 septembre 2026. Suite au retour utilisateur : l’interface du
[jalon 7](ANDROID_ARM64_JALON_7_COMPTE_RENDU.md) reste un peu petite ; conserver
une adaptation à l’écran et augmenter modérément la taille globale.

## Résultat sur la XP-Pen

L’APK est construit, installé et lancé. L’interface est **environ 14,4 % plus
grande** que le premier jalon 7. Home, File et New Sprite sont visibles dans les
captures ; aucune taille de widget ou police n’est modifiée individuellement.

| Mesure | Avant | Maintenant |
|---|---|---|
| Framebuffer natif | 2160×1440 | 2160×1440 |
| Surface logique Skia/UI | 1080×720 | 944×629 |
| rowBytes Skia | 4320 | 3776 |
| Échelle entière LAF | 2 | 2 |
| Agrandissement effectif vers l’écran | 2× | environ 2,288× horizontal / 2,289× vertical |
| Buffer Android | RGBA8888, stride 2160 | RGBA8888, stride 2160 |

La légère différence entre les rapports horizontal/vertical vient de l’arrondi
à des dimensions logiques entières. L’interface remplit toujours toute la fenêtre.
Les barres de navigation et la poignée XP-Pen restent superposées comme auparavant.

## Adaptation retenue

La densité provient d’Android via `AConfiguration_fromAssetManager` puis
`AConfiguration_getDensity`, au démarrage et lors d’un changement de configuration.
Sur cette tablette : **366 dpi**. `adb shell wm density` indique une densité
physique configurée de 320 et une surcharge système de 366. Aucune valeur système
n’a été modifiée pendant ce travail ; l’application respecte la valeur effective.

Les API communes d’Aseprite utilisent des échelles entières. Passer de 2 à 3 aurait
agrandi l’interface de 50 %. L’adaptation reste donc dans le backend Android :

- Espace de coordonnées fenêtre/écran LAF ajusté par `density / 320`.
- Échelle de fenêtre LAF conservée à 2 : un pixel UI correspond approximativement
  à un dp Android, sous réserve de l’arrondi et de la limite d’espace disponible.
- Sur cette tablette : fenêtre LAF 1888×1258 → UI 944×629 → pixels natifs 2160×1440.
- Limite d’agrandissement calculée d’après les dimensions natives : au moins
  480 pixels logiques sur le petit axe et 640 sur le grand axe. Cela évite de
  réduire indéfiniment l’espace de travail sur un petit écran à forte densité.
- Densité absente/indéfinie : conservation du rapport précédent à 320 dpi.

Il n’y a aucun test du modèle XP-Pen ni de résolution 2160×1440 dans le code.
La préférence d’échelle entière et les choix de l’utilisateur restent conservés.
Les changements de configuration déclenchent une actualisation par la file GUI
existante. Aucun polling ni rendu permanent n’est ajouté.

## Rendu et coordonnées tactiles

Le rendu reste Skia raster → SkiaWindowAndroid → ANativeWindow. Le copieur accepte
désormais les rapports non entiers et sélectionne le pixel source le plus proche
selon la convention d’origine supérieure gauche. Il ne mélange pas les couleurs.
Le chemin rapide entier du jalon 7 reste utilisé quand les dimensions le permettent.
Les strides source/destination et les conversions explicites BGRA/RGBA sont conservés.

Un rapport non entier produit nécessairement des pixels UI de largeur physique
variable, ici deux ou trois pixels. Il n’est donc pas parfaitement uniforme comme
un facteur entier, mais aucune interpolation floutante n’est appliquée.

L’entrée convertit d’abord les coordonnées natives vers l’espace fenêtre LAF,
puis les divise par l’échelle entière. Le pointeur global système utilise aussi
cet espace ajusté. La correspondance avec l’échantillonnage raster est testée pour
chaque colonne et chaque ligne de la résolution de la tablette.
Exemples observés : File `(20,14)` physique → `(8,6)` logique ;
Edit `(76,14)` → `(33,6)`.

## Validation

Commande de construction depuis la racine :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4
```

Journal : `android/build/density-assemble-1.log`.
Résultat : `BUILD SUCCESSFUL in 3s`, 38 tâches, 7 exécutées, 31 à jour.
APK : `android/app/build/outputs/apk/debug/app-debug.apk`.
Installation `adb -d install -r` : `Success`.
Lancement : `Status: ok`, `LaunchState: COLD`, `TotalTime: 1217`, `WaitTime: 1222` ms.

```text
Android display density=366 dpi
Raster surface created 944x629 rowBytes=3776 colorType=4 scale=2
Android buffer locked 2160x1440 stride=2160 format=1
First raster frame presented
Input Touch down physical=20,14 logical=8,6 scale=2 button=1
Input Touch up physical=20,14 logical=8,6 scale=2 button=1
```

File et Edit s’ouvrent après des taps injectés par adb. New Sprite s’ouvre via
Ctrl+N, conserve sa disposition et tient dans l’écran. Accueil Android puis retour
retrouve le rendu après destruction/recréation de fenêtre, même PID 6587.
Le retour est `HOT`, `TotalTime: 139`, `WaitTime: 143` ms. L’activité est RESUMED.

CPU au repos : **405 → 405 ticks** en 12,058 secondes sur ce PID. Aucune boucle
active observée dans cet échantillon. Aucun crash ou rejet de copie raster
observé pendant ces essais.

Tests hôte : **3/3 réussis** en 0,11 s. Le nouveau test couvre différentes densités,
les dimensions portrait/paysage, la limite sur petit écran, les rapports raster
fractionnaires, les canaux RGBA/BGRA, les strides/paddings et le ciblage du pointeur.
Les autres écrans/densités sont testés par calcul sur l’hôte ; aucun autre appareil
ni changement de densité système en direct n’a été validé.

Captures locales, non commitées :

- `android/build/jalon7-screen-scaled.png` : référence avant cet ajustement.
- `android/build/density-screen.png` : interface agrandie.
- `android/build/density-menu-touch.png` : File ouvert, inspecté.
- `android/build/density-edit.png` : Edit ouvert.
- `android/build/density-dialog.png` : New Sprite, inspecté.
- `android/build/density-return.png` : Home après retour Android, inspecté.
- `android/build/density-dialog-closed.png` : fermeture par tap du bouton Cancel.

Le ressenti « juste assez grand » reste à confirmer par l’utilisateur sur la
tablette ; les essais automatisés vérifient le rendu et le ciblage.

## Fichiers

Créés :

- `laf/os/android/display_metrics.h`
- `laf/os/android/tests/display_metrics_test.cpp`
- `ANDROID_ARM64_JALON_7_AJUSTEMENT_DENSITE.md`

Modifiés :

- `laf/os/android/system.h`, `system.cpp` : densité et espace fenêtre/écran commun.
- `laf/os/android/window.cpp` : dimensions de fenêtre adaptées.
- `laf/os/android/input.cpp` : conversion des positions avant le flux LAF existant.
- `laf/os/android/raster.h` : copie nearest pour rapports non entiers.
- `laf/os/skia/skia_window_android.cpp` : sélection du copieur adapté.
- `laf/os/android/tests/CMakeLists.txt` : test supplémentaire.
- `src/main/android_main.cpp` : lecture de densité, configuration et redimensionnement.
- `android/README.md`, `android/tests/README.md` : documentation actualisée.
- `laf` : référence du sous-module mise à jour.

Aucun changement des widgets, des thèmes, de la pression, des gestes, de l’IME,
du GPU ou du stockage. Aucun bloqueur de build ou d’exécution rencontré.
