# Jalon 4 — Validation sur tablette

Date : 12 septembre 2026, après activation du débogage USB sur la tablette.
Complément au [compte rendu de construction du jalon 4](ANDROID_ARM64_JALON_4_COMPTE_RENDU.md).

**Installation, chargement natif, création, maintien au premier plan, destruction
et second lancement sont maintenant vérifiés sur la tablette XPPen MDP1221.**
Aucun changement de code ni nouvelle compilation n’a été nécessaire.

## Appareil et APK testés

- Tablette : XPPen MDP1221, produit `MDP1221_EEA`.
- Android : API `34` (Android 14), retournée par `getprop` et confirmée par le log natif.
- ABI annoncées : `arm64-v8a,armeabi-v7a,armeabi`.
- État adb : `device`, connexion USB autorisée.
- Sources de l’APK : commit Aseprite `da6bda654`, sous-module LAF `c3e6752`.
- APK : `android/app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256 : `8b26ae7e5e4aaafc0c0b3beaf958f9ed9d16d78e57cb7c46eb4ee643c65d5f01`.

La construction de cet APK et ses vérifications de contenu/signature sont
documentées dans le compte rendu initial. Cette session teste le même artefact.

## Résultats observés

| Test | Résultat |
|---|---|
| Installation avec `adb install -r` | `Performing Streamed Install`, puis `Success` |
| Premier lancement avec `am start -W` | `Status: ok`, `LaunchState: COLD`, `TotalTime: 343`, `WaitTime: 349` |
| Chargement de `libaseprite.so` | Message natif reçu dans logcat |
| Entrée dans `ANativeActivity_onCreate` | Message natif reçu dans logcat |
| Callback `onStart` | `Android activity started` reçu |
| Maintien de l’activité | À 21:20:06, environ 38 secondes après le lancement, même PID `987`, activité au premier plan, `state=RESUMED`, `finishing=false` |
| Destruction normale | Retrait de la tâche Aseprite avec `am stack remove 748`, puis `Android activity destroyed` à 21:20:30.743 |
| Disparition de la tâche | Aseprite n’apparaît plus dans le relevé des activités après destruction |
| Second lancement | `Status: ok`, `LaunchState: COLD`, `TotalTime: 302`, `WaitTime: 309`, nouveau PID `1376`, nouveaux logs de chargement/création/démarrage |

Les temps `TotalTime` et `WaitTime` sont ceux retournés par Android, en millisecondes.
La tâche `748` a été identifiée comme contenant uniquement l’activité Aseprite
avant son retrait. Cette valeur est propre à cette exécution et ne doit pas être
réutilisée lors d’un prochain test.

## Extrait réel de logcat

Les lignes suivantes proviennent de la tablette ; ce ne sont plus des messages
attendus déduits du code :

```text
09-12 21:19:28.501   987   987 I Aseprite: Native library loaded: libaseprite.so
09-12 21:19:28.503   987   987 I Aseprite: ANativeActivity_onCreate entered
09-12 21:19:28.503   987   987 I Aseprite: Platform=Android ABI=arm64-v8a backend=skia GPU=0 SDK=34
09-12 21:19:28.503   987   987 I Aseprite: Android activity created; editor not started
09-12 21:19:28.504   987   987 I Aseprite: Android activity started
09-12 21:20:30.743   987   987 I Aseprite: Android activity destroyed
09-12 21:21:07.273  1376  1376 I Aseprite: Native library loaded: libaseprite.so
09-12 21:21:07.274  1376  1376 I Aseprite: ANativeActivity_onCreate entered
09-12 21:21:07.274  1376  1376 I Aseprite: Platform=Android ABI=arm64-v8a backend=skia GPU=0 SDK=34
09-12 21:21:07.274  1376  1376 I Aseprite: Android activity created; editor not started
09-12 21:21:07.275  1376  1376 I Aseprite: Android activity started
```

La capture utilisait les filtres `Aseprite:I AndroidRuntime:E libc:F` et a démarré
avant le lancement. Aucun diagnostic fatal concernant Aseprite n’a été observé
dans cette capture.

## Reproduction du démarrage

La session a sélectionné la tablette avec `adb -s` et son numéro de série.
Pour reproduire avec cette tablette comme unique appareil USB, utiliser `-d` :

```bash
aseprite_adb=/home/golden/Android/Sdk/platform-tools/adb
"$aseprite_adb" devices -l
"$aseprite_adb" -d install -r android/app/build/outputs/apk/debug/app-debug.apk
"$aseprite_adb" -d shell am force-stop org.aseprite.android
"$aseprite_adb" -d shell am start -W -n org.aseprite.android/android.app.NativeActivity
sleep 5
"$aseprite_adb" -d shell pidof org.aseprite.android
"$aseprite_adb" -d shell dumpsys activity activities
"$aseprite_adb" -d logcat -d -v threadtime -s Aseprite:I AndroidRuntime:E libc:F
```

Pour la destruction, identifier d’abord la tâche Aseprite avec `am stack list` ;
le test de cette session a ensuite utilisé `am stack remove 748`. Le log
`Android activity destroyed` confirme que le callback a été reçu avant la fin
du processus, contrairement à une simple vérification après arrêt forcé.

## Périmètre et suites

Les critères de démarrage natif du jalon 4 sont validés sur cet appareil.
Aucun blocage d’installation ou de chargement natif n’a été rencontré.

L’activité a été relancée après le test de destruction. Le dernier contrôle
confirme le PID `1376`, la tâche `749` au premier plan et l’état `RESUMED`,
`finishing=false`. L’écran applicatif vide
est cohérent avec ce jalon : `app_main()` n’est pas appelé et aucun pixel d’Aseprite
n’est présenté. Le fonctionnement de l’éditeur, le rendu, les entrées et le cycle
de vie Android complet restent hors du test. Leur implémentation n’a pas commencé.

Les fichiers locaux suivants conservent les observations de la session dans
`android/build/`, ignoré par Git :

```text
jalon4-tablette-logcat.txt
jalon4-tablette-launch.txt
jalon4-tablette-relaunch.txt
jalon4-tablette-activity.txt
jalon4-tablette-activity-stable.txt
jalon4-tablette-activity-destroyed.txt
jalon4-tablette-activity-final.txt
jalon4-tablette-destroy.txt
```

Ce compte rendu et les liens de documentation constituent les seuls changements
du commit de validation. L’APK et le code natif sont inchangés.
