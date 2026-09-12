# Compte rendu — Jalon 4 : APK debug et entrée NativeActivity

Date : 12 septembre 2026.

**Validation ultérieure sur tablette : installation, démarrage natif, maintien
de l’activité et destruction réussis. Voir le [rapport de validation réelle](ANDROID_ARM64_JALON_4_VALIDATION_TABLETTE.md).
Le document ci-dessous conserve les résultats de la construction initiale,
effectuée avant la connexion adb.**

Références : [audit](ANDROID_ARM64_AUDIT.md),
[jalon 1](ANDROID_ARM64_JALON_1_COMPTE_RENDU.md),
[jalon 2](ANDROID_ARM64_JALON_2_COMPTE_RENDU.md),
[jalon 3](ANDROID_ARM64_JALON_3_COMPTE_RENDU.md).
Point de départ : Aseprite `cb7200681`, LAF `c3e6752`.

## Résultat

**L’APK debug est généré, signé et contient la bibliothèque ARM64 attendue.
adb ne détecte aucun appareil : installation et démarrage natif restent à vérifier
sur Android. Aucune observation logcat d’exécution n’est revendiquée.**

| Vérification | Résultat |
|---|---|
| `:app:assembleDebug` | Code 0 ; `BUILD SUCCESSFUL in 17s` |
| Bibliothèque dans l’APK | `lib/arm64-v8a/libaseprite.so` présente |
| Runtime C++ dans l’APK | `lib/arm64-v8a/libc++_shared.so` présent |
| Autres ABI natives | Aucune |
| Manifeste empaqueté | NativeActivity exportée, métadonnées de bibliothèque et fonction correctes |
| Signature APK | Vérifiée par `apksigner`, signature v2, un signataire |
| Alignement ZIP | Vérifié par `zipalign`, bibliothèques natives alignées sur 16 Kio |
| Appareil adb | Aucun, avant et après la construction |
| Installation | Non tentée : aucun appareil |
| Lancement | Non tenté : aucun appareil |
| Entrée dans `ANativeActivity_onCreate` sur Android | Non vérifiée |
| Maintien de l’activité et réception de `onDestroy` | Non vérifiés sur appareil |

## APK exact

Chemin relatif :

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Chemin absolu sur cette machine :

```text
/home/golden/Desktop/dev/utils/aseprite-android/android/app/build/outputs/apk/debug/app-debug.apk
```

Taille : **53 471 884 octets**.

SHA-256 de l’APK produit lors de cette session :

```text
8b26ae7e5e4aaafc0c0b3beaf958f9ed9d16d78e57cb7c46eb4ee643c65d5f01
```

L’APK est un produit local de construction ignoré par Git. Le commit contient
les sources et le compte rendu, pas le binaire ni la clé de signature debug.

## Modifications

### Entrée native

`src/main/android_main.cpp` comporte maintenant :

- un constructeur de bibliothèque qui journalise son chargement avec le tag `Aseprite` ;
- un message à l’entrée de `ANativeActivity_onCreate` ;
- les informations Android/ARM64/Skia, l’état GPU et l’API du système fournie par
  `ANativeActivity::sdkVersion` ;
- l’enregistrement de deux callbacks, `onStart` et `onDestroy`, qui journalisent
  les notifications réelles transmises par Android ;
- un message de fin d’initialisation de l’activité.

Les assertions de compilation utilisent `base::Platform` pour confirmer Android
et ARM64. Aucun numéro de version système n’est inventé.

L’appel à `ANativeActivity_finish()` est supprimé. Le point d’entrée retourne
immédiatement à la boucle principale gérée par Android, sans attente bloquante,
thread de travail ni boucle active. Le code n’appelle pas `app_main()` et ne crée
pas de système ou fenêtre LAF. Aucune ressource supplémentaire n’exige une
libération dans `onDestroy`.

### Manifeste et empaquetage

`android/app/src/main/AndroidManifest.xml` conserve `android.app.NativeActivity`,
`android:hasCode="false"`, l’activité exportée et les filtres MAIN/LAUNCHER.
La métadonnée `android.app.func_name=ANativeActivity_onCreate` est ajoutée
explicitement, à côté de `android.app.lib_name=aseprite`.

Le projet Gradle existant fournit déjà `arm64-v8a` uniquement, la cible CMake
partagée `aseprite` et `ANDROID_STL=c++_shared`. Sa configuration n’a nécessité
aucune modification : la construction APK a correctement empaqueté les deux
bibliothèques. Aucun fichier d’activité Java/Kotlin n’a été ajouté. L’APK contient
des fichiers DEX produits par l’outillage existant ; leur présence ne correspond
pas à l’ajout d’une activité applicative Java/Kotlin dans ce jalon.

Le manifeste binaire a été inspecté avec `aapt2` : package `org.aseprite.android`,
versionCode `1`, versionName `0.1-build-milestone`, minSdk `26`, targetSdk `36`,
application débogable, `hasCode=false`, `extractNativeLibs=false` et métadonnées
NativeActivity attendues.

## Commande de construction

Depuis la racine du dépôt :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4
```

Exécution journalisée de cette session :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4 \
  > android/build/jalon4-assemble.log 2>&1
```

Résultat : **code 0, 37 tâches, dont 36 exécutées et une à jour**.
Aucune erreur de compilation, de liaison ou d’empaquetage n’a été rencontrée.

## Vérifications du produit

Commandes exécutées sur l’APK produit :

```bash
/home/golden/Android/Sdk/build-tools/36.0.0/aapt2 dump badging \
  android/app/build/outputs/apk/debug/app-debug.apk
/home/golden/Android/Sdk/build-tools/36.0.0/aapt2 dump xmltree \
  android/app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml
/home/golden/Android/Sdk/build-tools/36.0.0/apksigner verify --verbose \
  android/app/build/outputs/apk/debug/app-debug.apk
/home/golden/Android/Sdk/build-tools/36.0.0/zipalign -c -P 16 -v 4 \
  android/app/build/outputs/apk/debug/app-debug.apk
```

Les bibliothèques ont également été extraites dans le dossier local ignoré
`android/build/jalon4-apk-check/` pour inspection avec `file`, `llvm-readelf` et
`llvm-nm`. La bibliothèque Aseprite empaquetée est un ELF partagé AArch64 strippé,
avec `ANativeActivity_onCreate` et `app_main(int, char**)` toujours exportés.
Ses segments LOAD sont alignés sur `0x4000` (16 Kio).

Ses dépendances dynamiques déclarées sont `libandroid.so`, `liblog.so`, `libdl.so`,
`libm.so`, `libc++_shared.so` et `libc.so`. Le runtime C++ est bien présent dans
l’APK ; les autres bibliothèques sont celles du système Android.

Ces vérifications portent sur l’artefact. Elles ne prouvent pas son chargement
effectif par un appareil.

## adb et validation manuelle

Commande exécutée :

```bash
/home/golden/Android/Sdk/platform-tools/adb devices -l
```

Sortie réelle, également conservée dans `android/build/jalon4-adb-devices.log` :

```text
List of devices attached

```

**Aucune installation, aucun lancement et aucune capture logcat sur appareil
n’ont donc été effectués.** Aucun émulateur n’a été créé pour remplacer cette
validation.

Après connexion d’un appareil ARM64 autorisé, sous Android API 26 ou supérieure,
exécuter depuis la racine du dépôt :

```bash
aseprite_adb=/home/golden/Android/Sdk/platform-tools/adb
"$aseprite_adb" devices -l
"$aseprite_adb" install -r android/app/build/outputs/apk/debug/app-debug.apk
"$aseprite_adb" shell am force-stop org.aseprite.android
"$aseprite_adb" shell am start -W -n org.aseprite.android/android.app.NativeActivity
sleep 5
"$aseprite_adb" shell pidof org.aseprite.android
"$aseprite_adb" shell dumpsys activity activities > android/build/jalon4-activity.txt
"$aseprite_adb" logcat -d -v threadtime -s Aseprite:I AndroidRuntime:E libc:F \
  > android/build/jalon4-logcat.txt
cat android/build/jalon4-logcat.txt
```

Si plusieurs appareils sont listés, ajouter `-s SERIAL` aux commandes adb.
Vérifier les horodatages du lancement courant dans logcat et la présence de
`org.aseprite.android` comme activité reprise dans `jalon4-activity.txt` : un PID
encore présent ne prouve pas, seul, que l’activité reste ouverte.

Messages **attendus d’après le code, non observés sur appareil** :

```text
Native library loaded: libaseprite.so
ANativeActivity_onCreate entered
Platform=Android ABI=arm64-v8a backend=skia GPU=0 SDK=<API de l’appareil>
Android activity created; editor not started
Android activity started
```

Une destruction normale de l’activité doit passer par le callback qui émet
`Android activity destroyed`. Un arrêt forcé ou la mort du processus ne garantit
pas l’appel à `onDestroy`. Aucun écran de test ni dessin factice n’est ajouté.

## Fichiers et point d’arrêt

Fichiers modifiés :

```text
src/main/android_main.cpp
android/app/src/main/AndroidManifest.xml
android/README.md
```

Fichier créé :

```text
ANDROID_ARM64_JALON_4_COMPTE_RENDU.md
```

Les fichiers Gradle, les sous-modules et les rapports précédents sont inchangés.
Le commit du jalon est publié sur la branche `android-port` du dépôt
`Golden76z/aseprite`.

**Prochain obstacle vérifié : absence d’appareil adb pour valider installation,
chargement natif, entrée dans l’activité et maintien en vie.** Aucun nouveau
blocage de code n’a été observé. Le jalon de rendu n’a pas été commencé.
