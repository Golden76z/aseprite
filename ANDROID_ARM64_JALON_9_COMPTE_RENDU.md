# Jalon 9 — transport de la pression Android vers les dynamiques existantes

12 septembre 2026. Référence principale :
[jalon 8](ANDROID_ARM64_JALON_8_COMPTE_RENDU.md), ainsi que les rapports précédents.
Base Aseprite `bd54eade9`, LAF `1b478d6`.

**Raccordement compilé et installé ; validation physique des dynamiques en attente.**
Le document et les réglages de taille sont prêts sur la XP-Pen MDP1221.
Aucun résultat physique du jalon 9 n’est encore revendiqué dans cette version
du rapport. Les pressions mesurées au jalon 8 ne constituent pas une validation
du nouveau transport jusqu’aux brosses.

## Contrat et chemin inspectés avant modification

`laf/os/event.h` stocke un flottant, zéro par défaut. Les backends Windows
(Wintab normalisé ou pression Pointer / 1024), macOS (`event.pressure`) et X11
(axe normalisé) fournissent le domaine 0..1 attendu par les dynamiques.

Chemin Android :

1. `laf/os/android/input.cpp` : `InputAndroid::motion()` lit
   `AMotionEvent_getPressure(event, index)` du contact actif.
2. `pointer()` appelle `Event::setPressure()`, puis `queue_event()` ;
   `laf/os/android/event_queue.cpp` stocke l’événement et réveille le thread GUI.
3. `src/ui/manager.cpp` : `generateMessages()` transmet la pression aux
   `handleMouseDown()` / `handleMouseMove()` et à leurs `MouseMessage`.
4. `src/app/ui/editor/glue.h` : `pointer_from_msg()` copie la pression du message
   dans `tools::Pointer` ; les états existants de l’éditeur appellent le tool loop.
5. `src/app/tools/tool_loop_manager.cpp` : `getSpriteStrokePt()` puis
   `adjustPointWithDynamics()` consomment le Pointer pour les outils freehand
   compatibles. Les brosses pleines/contour ne sont pas rendues sensibles par
   modification de leur sémantique.
6. `src/app/tools/point_shapes.h` applique taille et gradient ; son gradient RGBA
   existant permet une variation alpha entre une couleur transparente et opaque.

Le Manager commun ne transmet pas la pression du `MouseUp` à son message UI :
celui-ci reste à zéro. Ce comportement desktop existant est conservé. Le MOVE
précédant UP et l’Event UP Android conservent leur valeur NDK, même non nulle.

## Mapping

| Pointeur | Pression dans os::Event |
|---|---|
| Pen | Valeur NDK normalisée, inchangée dans 0..1 |
| Eraser | Même règle si Android expose réellement ce type ; aucun test matériel ni synthèse |
| Touch | 0, comportement LAF précédent conservé, même si Android fournit 1 |
| Mouse / Unknown | 0, comportement précédent conservé |
| Annulation synthétique | 0 ; relâchement toujours déterminé par l’action |

La protection locale borne uniquement les valeurs finies hors 0..1 ; NaN/inf
deviennent zéro. **0,871147 reste 0,871147.** Aucun maximum matériel mesuré ne sert
de calibration. La sélection du contact, UP/CANCEL et capture sont inchangés.

Aseprite applique ensuite ses **seuils utilisateur existants**, 0,1/0,9 par défaut
dans `data/pref.xml`. Entre eux, `p = (pression - min) / (max - min)`, puis bornage.
Ce traitement commun est conservé : il ne faut pas le confondre avec une double
mise à l’échelle Android. Touch et Mouse restent à force pleine dans ce code
commun, malgré leur pression Event nulle.

## Fichiers

Créés :

- `laf/os/android/pressure.h` : règle locale de transport de pression.
- `laf/os/android/tests/pressure_test.cpp` : contrat de transport via la vraie queue.
- `android/tests/PRESSURE_DYNAMICS.md` : protocole physique taille puis alpha.
- `ANDROID_ARM64_JALON_9_COMPTE_RENDU.md` : ce rapport.

Modifiés :

- `laf/os/android/input.h`, `input.cpp` : lecture, Event::setPressure et trace Debug bornée.
- `laf/os/android/tests/CMakeLists.txt` : test hôte supplémentaire.
- `src/app/ui/editor/editor.cpp` : diagnostic Debug Android message → Pointer.
- `src/app/tools/tool_loop_manager.cpp`, `.h` : diagnostic Debug Android du calcul
  existant, sans modification fonctionnelle des dynamiques partagées.
- `android/README.md` et référence du sous-module `laf`.

Ni densité, ni raster, ni courbe, ni historique, ni IME/tilt/boutons ne sont modifiés.
Les diagnostics n’émettent pas chaque MOVE : un échantillon par tranche de
pression, réinitialisé par contact/tool loop, plus DOWN/UP côté Event.

## Construction et installation vérifiées

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4 \
  > android/build/jalon9-assemble-3.log 2>&1
```

Résultat final : `BUILD SUCCESSFUL in 5s`, 38 tâches, 7 exécutées, 31 à jour.
Aucune erreur restante de compilation/lien. Le premier groupe LAF avait compilé
en 3 s. Le groupe diagnostic a rencontré `no member named 'buttons' in
'ui::MouseMessage'; did you mean 'button'?`, corrigé avec l’accesseur réel.

APK : `android/app/build/outputs/apk/debug/app-debug.apk`.

```bash
/home/golden/Android/Sdk/platform-tools/adb -d install -r \
  android/app/build/outputs/apk/debug/app-debug.apk
/home/golden/Android/Sdk/platform-tools/adb -d shell am force-stop org.aseprite.android
/home/golden/Android/Sdk/platform-tools/adb -d shell am start -W \
  -n org.aseprite.android/android.app.NativeActivity
```

Installation : `Success`. Lancement : `Status: ok`, `COLD`, `TotalTime: 1192`,
`WaitTime: 1203` ms. Processus 8851, activité visible et reprise.

Tests hôte : CMake `-S laf/os/android/tests -B android/build/laf-android-tests`,
puis `cmake --build android/build/laf-android-tests --parallel 4` et
`ctest --test-dir android/build/laf-android-tests --output-on-failure`, avec les
binaires SDK `/home/golden/Android/Sdk/cmake/3.22.1/bin/`.
**4/4 réussis, 0,11 s** : queue, raster, densité, pression. Le nouveau test vérifie
notamment 0,007264 / 0,310932 / 0,662394 / 0,871147 inchangés après passage dans
la queue, les types de pointeur et un UP à pression non nulle. Un nom initial
de type dans le test a été corrigé en `EventQueueImpl`, la classe réelle.

## Préparation physique et preuves disponibles

Document créé avec l’interface normale : 512×512, RGBA, transparent, zoom 100 %.
Crayon, brosse ronde rouge opaque, Size / Pressure activé, minimum 1, maximum 32,
Angle et Gradient désactivés ; seuils utilisateur inchangés. Le panneau Preview
a été fermé pour dégager le document. Aucun trait de validation injecté.

- `android/build/jalon9-pressure-settings.png` : réglages inspectés visuellement.
- `android/build/jalon9-before-pressure.png` : document vide avant tests.
- `android/build/jalon9-logcat.txt` : collecte en cours, phases marquées dans logcat.
- `android/build/jalon9-prior-sessions.tar` : sauvegarde de l’autorecovery du jalon
  précédent avant relance ; ce n’est pas une sauvegarde `.aseprite` du document.

Les taps adb de préparation sont explicitement des injections (`device=-1`).
Leurs traces Touch montrent raw=1 et Event=0. Elles ne valident pas le stylet réel.

## Validation restant à réaliser

- Traits physiques léger/normal/ferme et progressifs : **en attente**.
- Égalité représentative Android → Event → message → Pointer : **en attente sur appareil**.
- Consommation effective par les dynamiques et variation visible de taille : **en attente**.
- Gradient alpha avec couleur de fond transparente : **à configurer puis tester**.
- Capture, relâchements, tactile et stabilité après dessin : **à revérifier**.

Le blocage actuel est la validation manuelle au vrai stylet, pas une erreur de
compilation. La suite immédiate est de terminer ces tests ; aucune extension
pression/tilt/historique ne se justifie avant leur résultat.
