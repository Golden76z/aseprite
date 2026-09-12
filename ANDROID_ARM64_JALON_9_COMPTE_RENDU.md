# Jalon 9 — transport de la pression Android vers les dynamiques existantes

12 septembre 2026. Référence principale :
[jalon 8](ANDROID_ARM64_JALON_8_COMPTE_RENDU.md), ainsi que les rapports précédents.
Base Aseprite `bd54eade9`, LAF `1b478d6`.

**La taille et l’opacité répondent à la pression du vrai stylet XP-Pen.** Les traces
et les captures après dessin établissent le transport jusqu’aux dynamiques
existantes, la variation de largeur puis la variation alpha à largeur fixe.
L’utilisateur a terminé chacune des deux séries (« c’est bon »). Aucun moteur de
brosse ni aucune courbe Android n’ont été ajoutés.

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

## Première validation physique — taille

Session du 12 septembre, 23:38:34 à 23:39:29, processus 8851. Aucun trait injecté.
Les neuf contacts Pen sont ceux du vrai périphérique stylus, suivis d’un contact
tactile réel. Le retour utilisateur ne précise pas séparément chacun des gestes
demandés : les trois premiers contacts ne sont donc pas arbitrairement étiquetés
« léger / normal / ferme », plusieurs contacts étant des interactions UI.

| Observation | Résultat |
|---|---|
| Pen physique | 9 DOWN / UP, 527 MOVE, 1 569 points historiques, maximum 6 par événement |
| Domaine contact observé | 0,003052..0,692669, historique compris |
| Taille visible | Deux longs traits de largeur variable et des marques près du bord ; amincissements jusqu’à un tracé très fin |
| Doigt | Un trait large et constant, Event/message/Pointer=0 ; 30 MOVE, 69 points historiques |
| Capture éditeur | Les 5 DOWN Pen et le DOWN Touch observés passent à capture=1 ; chacun des 6 UP revient à capture=0 |
| UP Pen non nul | Par exemple 0,003052 reste 0,003052 dans Event ; capture libérée ensuite |
| Annulation physique | Aucun CANCEL observé dans cette série ; pas de validation nouvelle de ce cas |
| Gomme physique | Aucun TOOL_TYPE_ERASER observé |
| Qualité | Pas de trou manifeste sur les longs traits capturés ; pas de défaut ou capture bloquée signalé par l’utilisateur |

Valeurs représentatives du **même chemin physique**, aux coordonnées UI indiquées :

| UI | Android | Event | Message | Pointer | Dynamique après seuils existants |
|---|---|---|---|---|---|
| 430,174 | 0,072880 | 0,072880 | 0,072880 | 0,072880 | 0,000000 |
| 430,179 | 0,258439 | 0,258439 | 0,258439 | 0,258439 | Non échantillonnée à cette valeur exacte |
| 422,224 | 0,505158 | 0,505158 | 0,505158 | 0,505158 | 0,506447 |
| 343,440 | 0,039980 | 0,039980 | 0,039980 | 0,039980 | 0,000000 |

Autre échantillon outil : Pointer=0,310016 → dynamique=0,262521, conforme aux
seuils 0,1/0,9. Aucun arrondi binaire, inversion ou calibration Android observé.

**Correction de diagnostic réellement rencontrée :** `Stroke::Pt::size` est un
float mais la trace initiale utilisait `%d`. Les champs imprimés `size`,
`gradientSensor`, `gradient` de cette première série sont invalides et ne servent
pas de preuve numérique. Les valeurs pression/Pointer/dynamique précédentes dans
la trace correspondent aux types attendus et concordent avec les autres étapes.
Conversion explicite `int(pt.size)` ajoutée uniquement à l’argument du log,
sans modifier le calcul ni le dessin.

Build correctif : même commande Gradle avec sortie
`android/build/jalon9-assemble-4.log`, **BUILD SUCCESSFUL in 4s**, 38 tâches,
7 exécutées / 31 à jour. Réinstallation `Success`, lancement `Status: ok`,
`COLD`, `TotalTime: 1069`, `WaitTime: 1075` ms, nouveau processus 9254.

Preuves conservées :

- `android/build/jalon9-pressure-strokes.png` : capture inspectée, taille variable réelle.
- `android/build/jalon9-size-pressure-path.txt` : extrait borné des traces de cette série.
- `android/build/jalon9-size-activity.txt` : état de l’activité après dessin.
- `android/build/jalon9-size-sessions.tar` : archive des sessions de récupération,
  sans prétendre à un enregistrement du document complet.

## Deuxième validation physique — alpha/opacité

Nouveau document transparent RGBA 512×512. Crayon rond de **taille fixe 32**,
Size et Angle désactivés, **Gradient / Pressure**, sens **BG > FG**, **No Dithering**,
seuils 0,1/0,9 conservés. Premier plan bleu de palette (RGB 91,110,225), fond
RGB 0,0,0 **alpha 0**. Réglages effectués dans l’interface normale : échange des
couleurs avec X, curseur alpha du sélecteur à zéro, puis nouvel échange.

Les boutons de couleur tout en bas restent dans une zone interceptée par les
surcouches système ; le sélecteur alpha situé au-dessus et le raccourci X ont
permis le réglage sans changer insets ni échelle.

- `android/build/jalon9-opacity-settings.png` : réglages inspectés visuellement.
- `android/build/jalon9-before-opacity.png` : document vide avant cette série.
- Marqueur logcat : `JALON9_OPACITY_PHYSICAL_READY`.

Session réelle du 12 septembre, 23:49:46 à 23:49:57, processus 9254. Quatre traits
verticaux distincts, device 7, source `0x5002`, TOOL_TYPE_STYLUS → LAF Pen=4.
L’utilisateur confirme la fin du test ; la capture a été inspectée directement.

| Contact | Maximum Android durant le contact, historique compris | Capture |
|---|---|---|
| 10 — premier trait | 0,342001 | Damier nettement visible sous le bleu |
| 11 — deuxième trait | 0,545443 | Couleur plus couvrante que le premier |
| 12 — troisième trait | 0,739547 | Couleur beaucoup plus opaque |
| 13 — quatrième trait progressif | 0,742111 | Départ translucide, puis bleu plus couvrant en descendant |

Les trois premiers traits correspondent à l’ordre demandé léger / normal / ferme,
sans mesure indépendante de la force appliquée. La quatrième trace confirme une
montée progressive. Un cinquième trait explicitement décroissant et une série de
traits rapides ne sont **pas présents dans cette capture/session** : ces sous-cas
ne sont pas déclarés validés. Aucun tap tactile supplémentaire n’a été reçu dans
cette série ; le dessin tactile réel est établi par la première série.

- **4 DOWN / 4 UP**, 390 MOVE, 1 221 points historiques, maximum 6 par événement.
- Domaine contact observé : **0,006531..0,742111**, historique compris.
- Les quatre DOWN éditeur prennent la capture ; les quatre UP la libèrent.
- UP non nuls, par exemple **0,022401** et **0,006531**, conservés dans Event :
  le relâchement ne dépend pas d’une pression nulle.
- Tous les échantillons outil corrigés indiquent `sizeSensor=0 size=32
  gradientSensor=1`. La pression pilote effectivement le gradient, pas la taille.

Comparaison représentative sur le même chemin :

| UI | Android = Event = message = Pointer | Dynamique = gradient | Taille |
|---|---|---|---|
| 370,110 | 0,123543 | 0,029428 | 32 |
| 387,475 | 0,501801 | 0,502251 | 32 |
| 459,110 | 0,232192 | 0,165240 | 32 |
| 456,143 | 0,512849 | 0,516061 | 32 |
| 542,398 | 0,501312 | 0,501640 | 32 |

À plus forte pression, l’échantillon outil `Pointer=0,705243` donne
`dynamics=gradient=0,756554`, toujours avec les seuils utilisateur 0,1/0,9.
Les trois autres étapes n’échantillonnent pas forcément cette valeur exacte :
les traces sont bornées indépendamment, elles ne consignent pas chaque MOVE.

La capture `android/build/jalon9-opacity-strokes.png` montre des largeurs
comparables et une transparence différente, ainsi qu’une progression le long du
quatrième trait. **Validation visuelle positive de l’alpha via le Gradient
existant.** Les empreintes circulaires superposées restent perceptibles sur les
traits les plus translucides ; aucun trou ne les sépare. Leur visibilité seule
n’établit pas un défaut de livraison Android et ne justifie pas un rejeu de
l’historique ou une interpolation dans ce jalon. Aucune optimisation ajoutée.

Preuves supplémentaires :

- `android/build/jalon9-opacity-pressure-path.txt` : extrait des traces physiques.
- `android/build/jalon9-final-activity.txt` : `state=RESUMED delayedResume=false
  finishing=false`, même processus 9254 après les traits.
- `android/build/jalon9-idle.txt` : mesure CPU avant puis après la série.
- `android/build/jalon9-opacity-sessions.tar` : archive des sessions disponibles,
  sans garantie qu’un document `.aseprite` ait été enregistré.

## Stabilité, limites et suite

Aucun `Fatal signal`, `FATAL EXCEPTION`, message d’abandon ou assertion fatale
relevé dans le logcat collecté. L’activité reste reprise et le document visible.
Avant dessin, zéro tick CPU supplémentaire en 5,057 s au repos : aucune boucle
active constatée. Après dessin, le compteur reste également à 1 556 ticks pendant
5,057 s, soit zéro tick supplémentaire ; relevé conservé dans `jalon9-idle.txt`.
Les diagnostics bornés et la présentation événementielle sont conservés.

Le transport Android garde le domaine **0..1 sans calibration**. Les dynamiques
partagées appliquent leurs seuls seuils existants. Doigt et souris gardent une
pression Event nulle ; le doigt physique dessine toujours à taille pleine.
La souris n’a pas fait l’objet d’une nouvelle validation physique dans ce jalon.
Le cas CANCEL reste couvert par le chemin existant inchangé, sans nouvelle
interruption physique reproduite. Aucun eraser matériel n’a été observé.

La densité, les coordonnées et le raster sont inchangés : 366 dpi,
2160×1440 physiques, surface logique 944×629, nearest-neighbor. Les superpositions
système en bas restent une limite d’accès à certains contrôles. Aucune adaptation
de densité, de fenêtre ou de widgets n’a été introduite ici.

**Aucun blocage de compilation, de lancement ou de transport de pression restant
observé.** La taille et l’alpha sont validés physiquement et visuellement ; les
sous-cas non effectués ci-dessus restent des limites de couverture, pas des bugs
supposés. Le prochain jalon recommandé est la sauvegarde/réouverture fiable de
documents sur Android, dans une tâche séparée. Aucune implémentation de fichiers,
SAF, tilt, boutons, gomme synthétique, gestes ou GPU n’est commencée ici.

## Commits publiés

- LAF `433f819` : transport normalisé et test de contrat.
- Aseprite `4cb32399f` : raccordement du sous-module, traces et protocole.
- Aseprite `9d8095792` : validation taille et correction du format du diagnostic.
- La clôture documentaire de ce rapport fait l’objet d’un commit supplémentaire.

Branches `android-port` des forks GitHub `Golden76z/laf` et `Golden76z/aseprite`.
Captures, logs et APK restent dans les répertoires de build ignorés par Git.
