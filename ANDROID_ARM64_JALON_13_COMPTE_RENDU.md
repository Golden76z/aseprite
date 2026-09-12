# Jalon 13 — pincement et déplacement du canevas à deux doigts

13 septembre 2026. Base Aseprite `0a184ef5c`, LAF `97b25d0`, rapports précédents,
notamment [jalon 12](ANDROID_ARM64_JALON_12_COMPTE_RENDU.md),
[jalon 9](ANDROID_ARM64_JALON_9_COMPTE_RENDU.md) et les deux rapports du jalon 7.

**Implémentation compilée, installée et vérifiée par injections sur la XP-Pen.
Validation avec de vrais doigts et le vrai stylet encore en attente.** Les tests
injectés ne sont pas présentés comme une validation physique du ressenti.

## Architecture inspectée

- `laf/os/event.h` : `TouchMagnify` existe, avec un delta de magnification.
- `laf/os/osx/view.mm::magnifyWithEvent()` transmet le delta du trackpad ;
  `laf/os/win/window.cpp` convertit la manipulation en scale − 1.
- `src/ui/manager.cpp::handleTouchMagnify()` vise capture ou widget sous la souris.
  La timeline, la palette et le sélecteur de fichiers acceptent aussi cet événement.
  Le réutiliser aveuglément pour deux doigts aurait zoomé ces autres interfaces.
- `src/app/ui/editor/state_with_wheel_behavior.cpp::onTouchMagnify()` utilise
  `Zoom::fromScale(internalScale * (1 + magnification))`.
- `src/render/zoom.cpp` conserve une échelle interne continue, mais choisit le
  niveau affiché dans les paliers existants, de 1/64 à 64. Aucun nouveau moteur
  de zoom ni seuil de geste Android n’est nécessaire.
- `Editor::setZoomAndCenterInMouse()` conserve approximativement le point sous
  le pointeur ; il borne l’ancrage à la partie visible du sprite.
- `ScrollingState::onMouseMove()` fait `viewScroll() - delta`, puis
  `Editor::setEditorScroll()`. Le nouveau pan utilise exactement cette convention.
- `DrawingState` possède déjà l’annulation transactionnelle via
  `ToolLoopManager::cancel()` et `destroyLoopIfCanceled()`. Un simple MouseUp
  aurait validé le premier point du doigt : le début de navigation annule plutôt
  ce trait provisoire, en conservant le dessin à un doigt immédiat.

## Implémentation

Nouvel événement LAF `Event::TouchNavigation`, avec phases Begin/Update/End/Cancel,
positions logiques actuelle/précédente et rapport de distance. Il est distinct du
zoom de trackpad, et seul l’éditeur accepte son message UI.

```text
AInputQueue / AMotionEvent, main looper Android
→ InputAndroid : reconnaissance de deux TOOL_TYPE_FINGER
→ Event::TouchNavigation → EventQueue + réveil du thread GUI
→ ui::Manager : CallbackMessage ordonné après le MouseDown initial
→ TouchNavigationMessage vers la cible d’origine
→ Editor : annulation du trait provisoire, Zoom existant et setEditorScroll
→ rendu Skia raster Android existant
```

Le Manager résout la cible après exécution des messages de pression précédents,
car l’éditeur peut seulement alors avoir acquis la capture. Le test du widget
sous le milieu du geste se fait dans l’éditeur, avec `manager()->pick()` : aucune
zone d’écran codée en dur. Un menu/dialogue/champ/liste n’accepte pas le geste.
La pression initiale sur un contrôle est libérée hors cible sans clic de validation.

La cible reste celle du début du geste, même quand le milieu sort du canevas.
L’ouverture d’une fenêtre avec focus, sa fermeture et `freeWidget()` effacent la
cible retenue. Aucun pointeur de widget n’est envoyé au thread Android.
Les transformations/déplacements d’objets déjà actifs autres que DrawingState ou
StandbyState ne sont pas repris comme navigation.

Un aperçu de pinceau pouvait réapparaître à l’ancienne position du premier doigt
lors du défilement : il est masqué jusqu’à la prochaine entrée de pointeur. Le
fichier restait intact avant cette correction ; celle-ci supprime le faux repère
visuel. Les mouvements/dessins suivants reprennent l’aperçu normal.

## États et isolation du stylet

| État | Comportement |
|---|---|
| Idle | Attend un ACTION_DOWN |
| SinglePointer | MouseEnter/Move/Down/Up existants, pression et type conservés |
| TwoFingerGesture | Deux IDs Finger, initialisation distance/milieu, puis navigation uniquement |
| AwaitFreshDown | Après relâchement d’un doigt ou interruption de geste, tous les contacts restants sont ignorés |

- Un nouveau DOWN réinitialise la séquence. CANCEL libère l’état ; après un CANCEL
  de geste, aucun ID actif ne subsiste et MOVE seul ne peut pas commencer un trait.
- Les IDs sont suivis indépendamment de leur indice dans le MotionEvent.
- Un troisième contact annule la navigation ; aucune commande à trois doigts.
- Un Pen/Eraser actif reste dans le chemin existant. Les contacts qui ne contiennent
  pas son ID, son type et son appareil sont ignorés. Un doigt secondaire dans le
  même événement ne devient ni pression supplémentaire ni geste.
- Pas de reconnaissance de paume personnalisée, pas de geste à partir du hover.
- Ni pression, ni inclinaison, ni boutons, ni historique ne sont modifiés.

**Politique de transition explicite :** si le deuxième doigt rejoint un trait au
premier doigt, le trait actuellement non terminé est annulé en entier. Les traits
précédemment relâchés restent intacts. Cela évite une marque au début du geste,
sans retarder les pressions à un doigt.

## Coordonnées, zoom et pan

Framebuffer toujours 2160×1440, densité 366 dpi, surface logique 944×629, fenêtre
LAF à échelle entière 2 et présentation effective environ 2,29×. Aucun changement
du raster nearest-neighbor, de la densité ou des dimensions de widgets.

- Distance : coordonnées physiques non arrondies de `AMotionEvent_getX/Y()`.
  Le rapport distance actuelle / précédente est sans unité, indépendant du zoom
  du document et de la densité. Il évite un zoom parasite dû à l’arrondi séparé
  des deux doigts durant une translation rigide.
- Milieu : moyenne physique, puis même conversion `SystemAndroid::toDisplayPosition()`
  et division par `inputScale()` que le pointeur existant. Les positions finales
  et les deltas de pan sont des pixels UI logiques.
- Pan : `setEditorScroll(viewScroll() - (position - previous))`.
- Zoom : `Zoom::fromScale(zoom.internalScale() * ratio)`, accumulateur borné aux
  extrêmes de `Zoom::fromLinearScale()`. Entre deux paliers, `setZoom()` conserve
  seulement la nouvelle valeur interne. Lors du changement réel de palier,
  `setZoomAndCenterInMouse(next, previous, ZoomBehavior::MOUSE)` précède le pan.
- Le milieu du pincement est choisi explicitement ; la préférence de centrage
  de la molette ne remplace pas ce point par le centre de la fenêtre.

Pas de mise à l’échelle de la surface Android pour simuler le zoom du document.
Pas de boucle de scrutation ni de rendu continu. Des contacts stationnaires
ne génèrent pas d’Update si position et distance n’ont pas changé.

## Compilation, installation, commits

Commande exacte depuis la racine :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4
```

Dernière compilation native : **BUILD SUCCESSFUL in 15s**, 40 tâches,
7 exécutées, 33 à jour (`android/build/jalon13-assemble-5.log`).
Le premier passage a signalé un appel inexistant `hideBrushPreview()` ; il a été
corrigé pour utiliser `m_brushPreview.hide()`. Aucun module désactivé.

APK : `android/app/build/outputs/apk/debug/app-debug.apk`.
Installation `adb -d install -r` : **Success**. Dernier lancement : **Status: ok**,
COLD, `TotalTime: 1179`, PID **17642**, NativeActivity RESUMED.

```bash
/home/golden/Android/Sdk/platform-tools/adb -d install -r \
  android/app/build/outputs/apk/debug/app-debug.apk
/home/golden/Android/Sdk/platform-tools/adb -d shell am start -W \
  -n org.aseprite.android/android.app.NativeActivity
```

Commits publiés sur `github/android-port` :

- LAF `7c38230` : événement et reconnaissance Android, isolation Pen.
- Aseprite `7495e070b` : routage GUI, annulation du trait et navigation éditeur.
- Aseprite `742203780` : sondes de gestes et isolation du stylet.

Les quatre tests hôte existants (queue, raster, densité, pression) passent.
`git diff --check` passe dans Aseprite et LAF.

## Tests contrôlés réellement exécutés sur l’appareil

Ces tests utilisent Android InputDispatcher, avec des positions et des outils
injectés. Les captures proviennent de l’affichage réel de la tablette.

Document de test : copie privée de `pixel-test-01.aseprite`, nommée
`files/documents/test-jalon13.aseprite`, 256×256 RGBA, deux couches, traits colorés
et transparence. Le fichier de référence n’est pas modifié.

| Test | Résultat observé |
|---|---|
| Pincement sortant, 40 mouvements | Zoom réel 100 % → 200 %, internalScale 1 → 2,5 |
| Pincement entrant | Zoom 200 % → 50 %, internalScale 2,5 → 0,75 ; capture inspectée |
| Pan (+240,+130) physiques | Scroll (354,137) → (249,81), zoom 50 % et internalScale 0,75 inchangés |
| Original finger UP, autre doigt déplacé de 300 px | Aucun nouveau MouseDown/trait, fichier intact |
| Pan puis ACTION_CANCEL | Fin phase Cancel, fichier intact, commandes suivantes utilisables |
| Geste commencé dans File puis nouveau tap File | Aucun zoom éditeur ; menu utilisable, capture inspectée |
| Pen + un doigt secondaire | Un seul Pen Down, dix MOVE et Pen Up normal, aucun geste |
| Trait stylet seul injecté | Down/18 MOVE/Up normaux, pression Event 1 conservée |
| Trait à un doigt injecté | Down/Move/Up et annulation normale par Undo |
| Ctrl+S / Ctrl+Z / raccourcis de zoom | Fonctionnels pendant ces essais |

Après les gestes, sauvegarde par Ctrl+S et vérification via `adb run-as` :
SHA-256 identique pour copie et référence :
`a5422dab478393f023943bb877bac53c83b7c5bb6205ab469cd1493d7e4d9417`.
La sauvegarde et la comparaison ne se limitent donc pas à l’apparence d’un aperçu.

Une sonde artificielle Pen + **deux** doigts a reçu ACTION_CANCEL d’Android au
premier MOVE. Aseprite l’a respecté. Elle ne valide pas une interaction physique
mixte à trois contacts ; aucune tentative de contourner l’annulation Android.
Le cas Pen + **un** doigt a ensuite reçu une séquence complète sans annulation.

## Essais physiques — en attente

La tablette est laissée sur le document de test. Une demande de manipulations a
été adressée à l’utilisateur : pincer/écarter, déplacer dans plusieurs directions,
lever un doigt, puis dessiner au doigt et au stylet avec variation de pression.

À ce stade, **ne sont pas encore revendiqués pour ce jalon** : ressenti du pincement
physique, pan physique, régression physique stylet/pression, ni absence de traits
parasites confirmée par l’utilisateur. Les jalons antérieurs restent la référence
pour la validation physique du dessin et de la pression avant cette modification.

## Captures et traces locales

- `android/build/jalon13-ready.png` : document avant navigation, 100 %.
- `android/build/jalon13-preview-fixed.png` : zoom 200 %, aperçu parasite masqué.
- `android/build/jalon13-final-zoom-out.png` : zoom 50 %.
- `android/build/jalon13-final-after-pan.png` : déplacement du document.
- `android/build/jalon13-final-after-cancel.png` : état après annulation du geste.
- `android/build/jalon13-menu-regression.png` : File ouvert après un geste rejeté.
- `android/build/jalon13-pen-finger-regression.png` : essai Pen + doigt injectés.
- `android/build/jalon13-physical-before.png` : état laissé pour les essais physiques.
- `android/build/jalon13-logcat.txt` : collecte complète des essais.
- `android/build/jalon13-navigation-evidence.txt` : extraits début/fin, zoom et scroll.
- `android/build/jalon13-pen-evidence.txt` : séquence Pen + doigt injectée.
- `android/build/jalon13-idle.json` : CPU au repos.
- `android/build/jalon13-crash-buffer.txt` : contrôle du tampon crash.

Les captures périodiques `jalon13-physical-NN.png` ne constituent pas à elles seules
une preuve de gestes physiques : elles doivent être rapprochées des événements
matériels et de la confirmation de l’utilisateur.

## Performance et limites

PID 17642 : **1203 → 1203 ticks CPU pendant 10,029 secondes** au repos après les
essais, sans activité d’entrée. Aucun busy-loop observé. Aucun crash/ANR observé
sur ce PID durant les tests ; les traces de crash de sessions antérieures ne sont
pas attribuées à cette exécution.

- Zoom visuel par paliers Aseprite, avec accumulation continue ; pas de zoom
  arbitraire interpolé. Le point d’ancrage est approximatif et suit les contraintes
  existantes de visibilité et d’arrondi du sprite.
- La barre système Android reste comme aux jalons précédents.
- Aucun historique tactile rejoué, aucune rotation, aucun geste à trois doigts.
- Les gestes n’interrompent pas les autres états complexes de transformation.
- Le ressenti et les régressions physiques restent à confirmer sur cette version.

**Prochain travail : terminer la validation physique du jalon 13**, corriger
uniquement un défaut reproductible si elle en révèle un. Aucun jalon inclinaison,
boutons, clipboard, GPU ou extension IME commencé.

## Fichiers créés/modifiés

Créés : `laf/os/touch_navigation.h` et ce compte rendu.

Modifiés :

- `laf/os/event.h`
- `laf/os/android/input.h`
- `laf/os/android/input.cpp`
- `src/ui/message_type.h`
- `src/ui/message.h`
- `src/ui/manager.h`
- `src/ui/manager.cpp`
- `src/app/ui/editor/drawing_state.h`
- `src/app/ui/editor/drawing_state.cpp`
- `src/app/ui/editor/editor.h`
- `src/app/ui/editor/editor.cpp`
- `android/tests/InputProbe.java`
- `android/tests/README.md`
- `android/README.md`
- référence du sous-module `laf`.
