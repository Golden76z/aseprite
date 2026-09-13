# Jalon 13 — pincement et déplacement du canevas à deux doigts

13 septembre 2026. Base Aseprite `0a184ef5c`, LAF `97b25d0`, rapports précédents,
notamment [jalon 12](ANDROID_ARM64_JALON_12_COMPTE_RENDU.md),
[jalon 9](ANDROID_ARM64_JALON_9_COMPTE_RENDU.md) et les deux rapports du jalon 7.

## Décision finale — jalon 13 validé fonctionnellement

**Validation acceptée sur la XP-Pen MDP1221 avec la variante raster optimisée.**
Après « clairement plus fluide », l’utilisateur confirme le pincement court et
le dessin au vrai stylet avec variation de pression : **« c’est fait et le rendu
est niquel »**. Ces retours complètent les essais physiques déclarés au début
et la régression ciblée après correction de performance.

La dernière capture est complète : **5758 records, overflow=0**, **169 Navigation
traitées sur 169**, **89 frames et 89 présentations** en 3,253 s ; cadence
**30,15 FPS** entre les posts extrêmes du pincement. Le CPU au repos reste normal
après ces essais (**0 tick supplémentaire en 10,057 s**), sans crash/ANR ni échec
de buffer observé sur le PID testé. Le profiler est désarmé après récupération.

Les mesures complètes figurent dans le
[rapport raster optimisé](ANDROID_ARM64_JALON_13_RASTER_OPTIMISE.md). Les **48,66 FPS
du pan restent ceux d’un extrait tronqué**, avec deux changements de zoom ; aucun
benchmark complet de pan pur n’est revendiqué. La validation du vrai stylet et
de sa pression repose sur la confirmation de l’utilisateur, pas sur un nouveau
journal numérique de pression. Les verdicts « non établis » ci-dessous décrivent
les étapes historiques antérieures à cette confirmation finale.

**Suite recommandée : cadrer le zoom progressif demandé par l’utilisateur**, en
conservant le raster optimisé. La copie/scaling est la prochaine cible CPU si le
besoin se présente. Aucun GPU/EGL, zoom progressif ou nouveau jalon implémenté
pendant cette validation.

## Historique des audits et du profilage

**Essais physiques effectués par l’utilisateur sur la XP-Pen MDP1221 ; audit et
reprise des tests le 13 septembre 2026. Sens du pincement physique et reprise du
dessin à un doigt confirmés. Quatre tests hôte et six cas injectés réussis ;
CPU au repos normal lors de la reprise, aucun crash/ANR observé sur le PID de cette reprise.
Clôture complète encore non établie : les preuves enregistrées ne couvrent pas
tous les critères.** Ce premier audit n’avait pas identifié de défaut reproductible.
Les tests injectés restent distincts des essais avec de vrais doigts et le vrai stylet.

**Actualisation — lenteur physique signalée par l’utilisateur :** le pincement
et le pan à deux doigts sont ressentis comme lents. Le critère de fluidité n’est
donc pas validé et le jalon reste ouvert. Le
[rapport de profilage](ANDROID_ARM64_JALON_13_PROFILAGE.md) décrit les sondes
Debug ajoutées et les 12 captures contrôlées réalisées sur la tablette.
Sur les pans injectés établis : **93,32 ms/frame en normal**, **93,78 ms en
immersif**, **363,02 ms en présentation 1:1**. Le plein écran ne procure pas de
gain matériel dans cette comparaison. La composition GUI raster domine ;
deux copies/présentations du framebuffer sont aussi effectuées à chaque redraw.
Ces temps décrivent la phase initiale. Une capture physique récupérée ensuite
confirme **9,62 FPS sur un geste mixte**, avec deux présentations par redraw.
Après accord de l’utilisateur, la présentation redondante a été supprimée,
l’APK Debug reconstruit/installé et les tests repris : **4/4 tests hôte réussis**,
une présentation par redraw, pan injecté **93,87 → 77,04 ms/frame** sur une
comparaison fraîche à deux répétitions (environ **13 FPS** après correction).
Le détail et la capture physique sont dans le rapport lié. Le rendu après
recréation de surface est vérifié ; le ressenti physique après correction reste
à confirmer. Aucun zoom progressif ni nouveau jalon n’a été commencé.

**Actualisation — raster optimisé instrumenté :** la variante `rasterProfile`
(Aseprite/LAF `-O2`, Skia même révision `-O3`, `NDEBUG`, mêmes sondes bornées) est
construite et installée. Le [rapport comparatif détaillé](ANDROID_ARM64_JALON_13_RASTER_OPTIMISE.md)
mesure **77,04 → 9,41 ms/frame de pan injecté**, composition UI **50,28 → 2,40 ms**,
copie/scaling **17,53 → 4,18 ms**, une présentation par redraw. Les 40 updates du
pan produisent maintenant 40 frames distinctes ; les ~29 FPS sont limités par
l’injection, pas une mesure du pan physique. Home/UI, ouverture, saisie Gboard,
save/open privé, lancement SAF et recréation de surface passent. Le pan/pincement
physiques optimisés et le vrai stylet/pression restent demandés séparément.
Aucun EGL/GPU n’est justifié par les mesures contrôlées actuelles ; la copie/scaling
est la prochaine cible CPU mesurée. Le jalon demeure ouvert pour la validation physique.

**Retour physique après optimisation :** l’utilisateur confirme **« clairement
plus fluide »**. La capture réelle récupérée contient **336 frames à 48,66 FPS**
sur un extrait de 6,884 s, toujours une présentation par frame. Elle est tronquée
(`overflow=9298`, End absent) et contient deux changements de zoom : elle n’est
pas présentée comme un benchmark complet de pan pur. Le
[rapport optimisé](ANDROID_ARM64_JALON_13_RASTER_OPTIMISE.md) détaille ces limites.
Le gain ressenti est confirmé ; restent les captures courtes complètes séparées
et la confirmation du vrai stylet/pression avant clôture physique du jalon.

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

## Premier audit des essais physiques — preuves disponibles avant reconnexion

L’utilisateur confirme avoir effectué avec de vrais doigts et le vrai stylet
XP-Pen : pincement entrant/sortant, pan horizontal/vertical/diagonal, ajout du
deuxième doigt, levée d’un doigt puis des deux, dessin à un doigt après les gestes,
dessin au stylet et variation de pression après les gestes. Cette déclaration
établit l’exécution de ces essais ; elle ne précise pas leur verdict individuel
ni le ressenti. Une clarification sur les résultats observés a été demandée.

### Séquences physiques effectivement enregistrées

`android/build/jalon13-logcat.txt`, lignes **515–650**, contient sept séquences
matérielles, contacts **9 à 15**, de **01:32:45.936 à 01:37:03.768**, toutes sur
le PID **17642** : `device=4`, `source=0x1002`, `tool=1`, `laf=Touch`.
Elles se distinguent des sondes précédentes (`device=-1`). Leur attribution
physique repose aussi sur la confirmation de l’utilisateur, pas sur le nom des
captures. Aucun contact Pen matériel n’est présent dans ce journal.

| Contact | Distance début → dernière distance, px physiques | Zoom affiché début → fin | Échelle interne début → fin |
|---|---|---|---|
| 9 | 442,37 → 271,92 | 100 % → 50 % | 1 → 0,6147 |
| 10 | 724,05 → 428,88 | 50 % → 33,33 % | 0,6147 → 0,3641 |
| 11 | 225,63 → 985,23 | 33,33 % → 200 % | 0,3641 → 1,5899 |
| 12 | 757,89 → 581,45 | 200 % → 100 % | 1,5899 → 1,2197 |
| 13 | 392,99 → 576,58 | 100 % → 200 % | 1,2197 → 1,7896 |
| 14 | 681,34 → 982,24 | 200 % → 300 % | 1,7896 → 2,5799 |
| 15 | 630,73 → 812,76 | 300 % → 300 % | 2,5799 → 3,3245 |

Les distances de fin sont celles du dernier MOVE traité, conservées dans le log
End. Dans les sept cas, le rapport d’échelle interne correspond, aux arrondis
du journal près, au rapport des distances. Rapprocher les doigts réduit le zoom ;
les écarter l’augmente. Le contact 15 reste dans le même palier affiché.

Les sept séquences ont chacune un Begin et un End côté Android et côté éditeur,
sans Cancel enregistré. Le journal ne conserve que les trois premiers Update
de chaque geste et les états éditeur Begin/End : il ne permet pas de juger la
fluidité, les oscillations entre paliers ou toute la trajectoire du pan.

### Verdict par critère demandé

« Non établi » indique une preuve insuffisante, **pas un échec constaté**.

| Critère physique | Verdict de l’audit | Preuve et limite |
|---|---|---|
| Sens du pincement correct | **Confirmé** | Sept rapports distance/échelle cohérents, avec réduction et agrandissement effectifs du zoom. |
| Pincement stable au ressenti | **Non établi** | Pas de vidéo ni de verdict utilisateur explicite ; les Update sont échantillonnés. Les valeurs finales cohérentes ne suffisent pas à certifier le ressenti. |
| Sens du pan correct | **Partiellement étayé** | Contact 15 : milieu (402,215) → (371,223), scroll (390,295) → (421,287), soit des deltas opposés conformes à la convention. Les trois pans horizontal/vertical/diagonal ne sont pas identifiables séparément dans les traces. |
| Aucun trait parasite à l’arrivée du deuxième doigt | **Non établi visuellement** | Un seul `Input Touch down` par séquence, puis Begin reçu par l’éditeur ; le code annule DrawingState. Aucune capture ni comparaison du document après ces gestes physiques ne confirme le résultat pixel. Le hash des essais injectés ne couvre pas cette période. |
| Le doigt restant ne commence pas de nouveau trait | **Confirmé pour les transitions enregistrées** | Aucun nouveau `Input Touch down` ni `EditorPointer down` entre Begin/End et le prochain ACTION_DOWN frais. Pour 12, 13 et 15, ACTION_POINTER_UP puis ACTION_UP apparaissent sans nouvelle pression synthétisée. Une longue trajectoire du doigt restant n’est pas enregistrée. |
| Dessin à un doigt après gestes | **Non établi** | Les nouveaux DOWN physiques atteignent l’éditeur, mais tous les contacts matériels enregistrés deviennent des gestes ; aucun trait autonome complet après gestes n’est conservé. Essai déclaré effectué par l’utilisateur. |
| Dessin au stylet après gestes | **Non établi** | Tous les Pen enregistrés ont `device=-1` et précèdent les gestes matériels. Essai physique déclaré effectué par l’utilisateur. |
| Dynamique de pression après gestes | **Non établie** | Les Pen injectés ont une pression de contact constante [1,1]. Aucun échantillon du vrai stylet après gestes, ni résultat visuel correspondant. Essai physique déclaré effectué par l’utilisateur. |
| Aucun pointeur/capture bloqué | **Partiellement confirmé** | Sept fins de geste traitées et reprise de nouvelles séquences physiques ; aucun blocage observé dans cet extrait. Pas de diagnostic final de capture ni de dessin de reprise enregistré après le contact 15. |
| Aucun crash/ANR | **Aucun observé dans la période enregistrée** | Même PID et traitement GUI jusqu’à 01:37:03.768, aucun message fatal/ANR dans le journal. Le tampon crash et le dumpsys sauvegardés précèdent les gestes ; ils ne certifient pas la totalité des essais déclarés. |
| CPU au repos normal après essais physiques | **Non établi après ces essais** | 0 tick supplémentaire en 10,029 s dans la mesure existante, mais celle-ci date de 01:26, avant le premier geste matériel enregistré à 01:32. |

### Transitions et décision de correction

Le contact 15 illustre `ACTION_DOWN` à **01:36:57.969** → DOWN éditeur à
**01:36:57.982** → ajout du deuxième doigt / Begin à **01:36:58.029** → Begin
éditeur à **01:36:58.031** → `ACTION_POINTER_UP` / End à **01:37:03.699** →
End éditeur et `ACTION_UP` à **01:37:03.768**. Aucun nouveau DOWN intermédiaire.
Cela concorde avec `SinglePointer → TwoFingerGesture → AwaitFreshDown`.
Le `captureAfter=1` du DOWN initial est attendu ; il ne constitue pas une preuve
de capture bloquée après le geste. L’annulation du trait passe par
`DrawingState::cancelForTouchNavigation()`, sans MouseUp validant le trait.

**Aucun défaut reproductible n’est établi par cet audit.** Aucune correction de
code, recompilation, réinstallation ou injection supplémentaire effectuée.
Lors du contrôle, `adb devices -l` ne liste aucun appareil, y compris hors
sandbox ; impossible de récupérer les événements ultérieurs ou de renouveler
les mesures CPU/crash. Aucune répétition physique n’est présentée comme exécutée.

## Reprise des tests sur la tablette reconnectée

13 septembre 2026, à partir de 10:17. Appareil ADB **XCD1205AF825A05168**,
modèle **MDP1221**, PID **30489**. `dumpsys package` indique une dernière mise à
jour à **01:21:07** : l’APK du jalon 13 est toujours installé. Les limites du
premier audit ci-dessus décrivent les fichiers alors disponibles ; cette reprise
apporte les preuves supplémentaires suivantes.

### Tests exécutés et résultats

- **Quatre tests hôte réussis**, 0 échec, 0,13 s : queue d’événements, raster,
  conversion de densité et pression. Construction Ninja à jour ; journal conservé
  dans `android/build/jalon13-recheck-host-tests.log`.
- Le journal récupéré avant les nouvelles injections contient déjà des entrées
  physiques `device=4`. Après le geste du contact 7, deux traits à un doigt
  autonomes sont enregistrés, contacts **8 et 9**, de **10:17:18.075 à
  10:17:20.723** : 65 puis 47 MOVE, UP normal, `captureAfter=0` dans les deux
  cas. La capture `jalon13-recheck-before.png` montre les traits. **Dessin à un
  doigt après geste et libération de capture confirmés sur ces séquences.**
- Un document RGBA transparent 256×256 séparé a été créé et enregistré sous
  `files/documents/jalon13-recheck-20260913.aseprite`. Le document utilisateur
  `Sprite-0001` reste ouvert dans son onglet.

Les tests ci-dessous sont des **injections**, effectuées entre **10:21:48 et
10:22:15**, avec positions et IDs contrôlés. Le zoom est remis à 100 % et le
document recentré avant chaque cas.

| Test | Résultat mesuré |
|---|---|
| Écartement (`gesture-in`, nom technique de la sonde) | 100 % → 200 %, échelle interne 1 → 2,5 ; fichier inchangé. |
| Rapprochement (`gesture-out`) | 100 % → 33,33 %, échelle interne 1 → 0,3 ; fichier inchangé. |
| Pan diagonal (+240,+130) physiques | Scroll (289,128) → (185,72), zoom et échelle interne restent à 1 ; fichier inchangé. |
| Pan puis ACTION_CANCEL | Phase Cancel reçue par l’éditeur, même déplacement ; fichier inchangé. |
| Ajout du deuxième doigt puis levée du doigt initial | Les sondes attendent 80 ms avant le deuxième contact puis déplacent le doigt restant de 300 px après la levée ; aucun trait persistant. |
| Trait doigt injecté après gestes | Document modifié ; UP éditeur avec `captureAfter=0` ; Undo restitue le fichier initial. |
| Trait Pen injecté après gestes | Document modifié ; UP éditeur avec `captureAfter=0` ; Undo restitue le fichier initial. Pression injectée constante, pas une preuve de dynamique physique. |

Après **chaque** geste, Ctrl+S puis lecture par `adb exec-out run-as` donnent
le SHA-256 initial :
`19d191e6b2c9d5e6c13c0f5b3e0dd1a7711da17c52926b7c4e8ff69567ea72bc`.
Le même hash est retrouvé après Undo de chacun des deux traits injectés.
Les captures du pan et du trait Pen ont été inspectées : canevas déplacé sans
marque dans le premier cas, trait visible dans le second.

Preuves : `android/build/jalon13-recheck-suite.json`,
`jalon13-recheck-suite-evidence.txt`, `jalon13-recheck-suite-logcat.txt` et
captures `jalon13-recheck-gesture-*.png`, `jalon13-recheck-*-stroke.png`.
L’orchestration locale est conservée dans `android/build/jalon13_recheck.py`.

L’historique Android des sorties de processus précise aussi que l’ancien PID
**17642** s’est arrêté à **02:14:46.292** pour **USER REQUESTED / FORCE STOP**,
pas pour crash ou ANR. Le dernier crash natif listé date de **00:53:26.743**,
PID 14627, avant l’APK final du jalon 13. Source :
`jalon13-recheck-suite-exit-info.txt`.

### Reprise physique préparée

Après les injections, le document de test a été remis à blanc, à 100 %, et
**Size / Pressure** activé dans le popup Dynamics, plage **1–12 px**, Angle et
Gradient désactivés. Capture des réglages : `jalon13-recheck-new-dialog.png`.
Une collecte de trois minutes avec logcat et captures périodiques
`jalon13-recheck-live-NN.png` a été démarrée. Les gestes puis les traits physiques
ont été demandés à l’utilisateur, avec son verdict sur le ressenti, les marques
parasites et la variation d’épaisseur. Cette fenêtre de collecte n’a reçu
**aucun nouvel événement Aseprite** ; les captures périodiques montrent le même
canevas blanc. Aucun nouvel essai physique doigt/stylet n’est donc revendiqué
pour cette fenêtre. Le document reste prêt pour les manipulations manquantes.

### CPU et stabilité lors de la reprise

PID **30489**, même heure de démarrage avant/après (`starttime=118112215`) :
**3820 → 3820 ticks CPU en 10,045 s**, soit **0 tick CPU supplémentaire** au
repos après les gestes physiques de 10:17 et les tests injectés. Aucune entrée
Aseprite dans la collecte live pendant cette mesure. Le CPU au repos est normal
sur cet intervalle ; aucun busy-loop constaté.

Le PID reste 30489, l’activité est au premier plan, aucun crash/ANR de cette
exécution n’est retrouvé dans les diagnostics collectés. Les anciennes entrées
fatales du tampon système ne sont pas attribuées à cette reprise.
Sources : `jalon13-recheck-idle.json`, `jalon13-recheck-final-activity.txt`,
`jalon13-recheck-final-logcat.txt`, `jalon13-recheck-final-crash.txt`,
`jalon13-recheck-final-exit-info.txt`.

**Verdict actualisé :** CPU au repos, dessin physique à un doigt et libération
de sa capture désormais confirmés sur cette reprise ; tests injectés tous
réussis, aucun défaut reproductible constaté. Ressenti du pincement, les trois
directions de pan physique, absence visuelle de traits parasites physiques,
dessin au vrai stylet et sa dynamique de pression restent à documenter avec
les nouvelles manipulations et le retour de l’utilisateur.

## Captures et traces locales

- `android/build/jalon13-ready.png` : document avant navigation, 100 %.
- `android/build/jalon13-preview-fixed.png` : zoom 200 %, aperçu parasite masqué.
- `android/build/jalon13-final-zoom-out.png` : zoom 50 %.
- `android/build/jalon13-final-after-pan.png` : déplacement du document.
- `android/build/jalon13-final-after-cancel.png` : état après annulation du geste.
- `android/build/jalon13-menu-regression.png` : File ouvert après un geste rejeté.
- `android/build/jalon13-pen-finger-regression.png` : essai Pen + doigt injectés.
- `android/build/jalon13-physical-before.png` : état laissé pour les essais physiques.
- `android/build/jalon13-logcat.txt` : injections puis sept gestes physiques ;
  s’arrête à 01:37:03.768, ne couvre pas tous les essais déclarés.
- `android/build/jalon13-navigation-evidence.txt` : extraits début/fin, zoom et scroll.
- `android/build/jalon13-pen-evidence.txt` : séquence Pen + doigt injectée.
- `android/build/jalon13-idle.json` : CPU au repos.
- `android/build/jalon13-crash-buffer.txt` : contrôle du tampon crash.

Audit des captures : `jalon13-physical-before.png` et les **24 captures 00 à 23**
sont identiques octet pour octet (un seul SHA-256 pour 25 fichiers). La dernière
date de **01:28**, avant les premiers gestes matériels enregistrés à **01:32**.
L’image inspectée montre le document à 100 %, les traits colorés et la barre
système. Elle ne montre pas le résultat des gestes physiques. Les captures
`final-zoom-out`, `final-after-pan` et `pen-finger-regression` ont également été
inspectées ; elles documentent les injections antérieures.

`jalon13-activity.txt` (01:29) indique NativeActivity au premier plan, PID 17642,
avant les gestes matériels. Le dernier événement de `jalon13-crash-buffer.txt`
date de **00:53:26.720**, dans une session antérieure.

## Performance et limites

PID 17642 : **1203 → 1203 ticks CPU pendant 10,029 secondes** au repos après les
essais **injectés**, sans activité d’entrée. Aucun busy-loop observé lors de cette
mesure de 01:26. Aucun crash/ANR observé dans le journal disponible de ce PID ;
les traces de crash de sessions antérieures ne sont pas attribuées à cette
exécution. La reprise décrite ci-dessus fournit une nouvelle mesure après les
gestes physiques de 10:17 et les injections : 3820 → 3820 ticks en 10,045 s.

- Zoom visuel par paliers Aseprite, avec accumulation continue ; pas de zoom
  arbitraire interpolé. Le point d’ancrage est approximatif et suit les contraintes
  existantes de visibilité et d’arrondi du sprite.
- La barre système Android reste comme aux jalons précédents.
- Aucun historique tactile rejoué, aucune rotation, aucun geste à trois doigts.
- Les gestes n’interrompent pas les autres états complexes de transformation.
- Les essais physiques sont déclarés effectués ; leur verdict complet reste à
  documenter pour les régressions doigt/stylet/pression ; le ressenti fait désormais
  l’objet d’un signalement explicite de lenteur, analysé dans le profilage lié.

**Décision finale : jalon 13 validé fonctionnellement**, selon la confirmation
utilisateur et les vérifications finales en tête de rapport. La lenteur signalée
initialement est résolue au ressenti avec le raster optimisé. Le benchmark complet
de pan pur reste une limite documentaire ; l’extrait physique ne permet pas de
certifier sa cadence sur toute la durée.

Le prochain travail recommandé est le cadrage du zoom progressif demandé, sans
changer d’architecture. Insets/fullscreen, inclinaison, boutons du stylet,
presse-papiers et composition IME restent des possibilités ultérieures ; aucune
nouvelle fonctionnalité n’a été commencée ici.

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
