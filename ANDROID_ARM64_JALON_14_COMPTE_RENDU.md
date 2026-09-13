# Jalon 14 — plein écran immersif et insets Android

13 septembre 2026. Base fonctionnelle : jalon 13 validé, variante raster optimisée,
[jalon 13 raster](ANDROID_ARM64_JALON_13_RASTER_OPTIMISE.md),
[jalon 13](ANDROID_ARM64_JALON_13_COMPTE_RENDU.md),
[jalon 12 IME](ANDROID_ARM64_JALON_12_COMPTE_RENDU.md).

## État observé avant modification

- NativeActivity pose `AWINDOW_FLAG_FULLSCREEN` à la création : la barre d’état
  est masquée, mais pas la navigation. Un helper expérimental Debug peut changer
  les flags historiques du decor ; il ne constitue pas un mode produit stable.
- Aucune gestion unifiée des WindowInsets. `ImeBridge` installe son propre
  listener sur le decor seulement lors de la première édition ; API 30+ transmet
  uniquement l’inset inférieur IME au backend, lié à la génération de saisie.
- `SystemAndroid` soustrait ce seul inset à la hauteur native ; présentation et
  mapping partagent cette hauteur mais supposent une origine (0,0), sans marges
  latérales/supérieures pour barres ou découpe.
- XP-Pen MDP1221 API 34 : framebuffer **2160×1440**, raster UI **944×629**, scale 2,
  densité physique 320 / override 366 dpi. Gboard du jalon 12 : inset 842,
  hauteur de contenu 598 et UI 944×261.
- Dumpsys avant modification : statusBars `[0,0][2160,55]`, **visible=false** ;
  navigationBars `[0,1330][2160,1440]`, **visible=true**, hauteur **110 px**.
  Sources mandatorySystemGestures présentes en haut (55) et en bas (110) ;
  systemGestures latéraux vides. Cutout et waterfall : zéro.
- L’image de référence montre les boutons Android au-dessus du bas d’Aseprite ;
  la surface native inclut donc une zone partiellement recouverte, pas une zone
  utilisable entièrement dégagée.
- Fenêtres système détectées : SidebarDropTarget, ShellDropTarget,
  ScreenDecorOverlay et ScreenDecorOverlayBottom ; leur attribution et leur
  éventuel inset seront vérifiés avant tout traitement. Aucun offset XP-Pen
  codé en dur n’est prévu.

Preuves avant changement : `android/build/jalon14-before.png`,
`jalon14-before-window.txt`, sauvegarde `jalon14-preinstall-backup.tar`.

## Résultat et statut

**Jalon 14 physiquement validé et clôturé sur la XP-Pen MDP1221 le
13 septembre 2026.** L’utilisateur confirme explicitement les essais avec les
vrais doigts et le vrai stylet sur cette version plein écran, en complément des
contrôles automatisés précédents. Aucun défaut reproductible signalé ou établi
par les diagnostics disponibles ; aucune modification de code, reconstruction
ou réinstallation effectuée pour cette clôture.

La barre d’état et la navigation sont masquées pendant le dessin. L’écran ne
présente plus de bande noire permanente ni de boutons Android superposés au bas
de l’éditeur. Les menus, la timeline et la barre d’état Aseprite sont visibles.
Un balayage depuis le bord révèle naturellement les barres **en superposition
transitoire** ; elles sont de nouveau masquées sur la capture prise 4 secondes
plus tard. Android ne signale pas cette superposition transitoire comme une
réduction de contenu : les contrôles de bord peuvent être brièvement recouverts
pendant cette action volontaire, sans déplacement de la scène.

## Implémentation et API

- Nouveau `WindowUiBridge` Java, attaché dès la création de NativeActivity.
  API 30+ : `Window.setDecorFitsSystemWindows(false)`,
  `WindowInsetsController.hide(Type.systemBars())`,
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`. Le flag historique FULLSCREEN est
  retiré sur cette branche. Politique cutout `ALWAYS` ; `SHORT_EDGES` sur 28–29.
- Compatibilité 26–29 : flags de decor `LAYOUT_STABLE`, `LAYOUT_FULLSCREEN`,
  `LAYOUT_HIDE_NAVIGATION`, `FULLSCREEN`, `HIDE_NAVIGATION`, `IMMERSIVE_STICKY`.
  Aucun réglage système global ni préférence utilisateur ne change.
- Listener unique sur le decor, présent même avant la première saisie. Il lit
  séparément les insets visibles, stables, gestes, gestes obligatoires, éléments
  tactiles système, découpe et IME. Le listener consomme les insets avant propagation aux descendants pour
  éviter une seconde réduction du SurfaceView ou de la cible IME de 1 px.
- Contenu = maximum par bord de **barres visibles, découpe, IME**. Les valeurs
  stables ne sont pas ajoutées. Les zones de balayage restent prioritaires pour
  Android ; aucune exclusion gestuelle n’est imposée pour cacher la navigation.
- Sur cet appareil, `systemGestures`, `mandatorySystemGestures` **et même
  `tappableElement`** conservent 55/110 px lorsque les barres sont masquées.
  Une première version réservant ces zones produisait des bandes inutiles :
  ce défaut a été corrigé avant les tests finaux. Leur géométrie n’est donc pas
  assimilée à une obstruction permanente. Les barres réellement visibles
  protègent déjà leurs propres cibles tactiles.
- `SOFT_INPUT_ADJUST_NOTHING` évite de cumuler resize Android et réduction native.
  API 30+ utilise `Type.ime()` et `isVisible(ime)` ; le fallback 26–29 utilise les
  insets historiques et le rectangle visible, sans seuil en pixels par modèle.
- Restauration immersive au lancement, retour de focus et fermeture effective
  de l’IME. Pas de polling, de timer récurrent ou de remise en plein écran à
  chaque événement. Les doublons d’insets sont ignorés ; les callbacks d’une
  ancienne activité sont rejetés par génération native indépendante de l’IME.
- Un même rectangle physique est utilisé pour la taille logique, la copie raster
  décalée et la transformation des positions tactiles/stylet. La densité reste
  calculée depuis la surface entière. Les pixels exclus sont effacés pour éviter
  de réafficher un ancien buffer ; la copie principale reste nearest-neighbor.
  Pression et algorithme pinch/pan ne sont pas modifiés.

Références API : [plein écran immersif Android](https://developer.android.com/develop/ui/views/layout/immersive),
[WindowInsetsController](https://developer.android.com/reference/android/view/WindowInsetsController),
[types d’insets](https://developer.android.com/reference/android/view/WindowInsets.Type).

## Valeurs et dimensions relevées

Insets en pixels physiques, ordre gauche/haut/droite/bas :

| État | Barres visibles | Barres stables | IME | Marges appliquées |
|---|---|---|---|---|
| Avant, jalon 13 | 0/0/0/110 | 0/55/0/110 | 0 | 0 (barre superposée) |
| Démarrage, avant masquage | 0/55/0/110 | 0/55/0/110 | 0 | 0/55/0/110 |
| Immersif établi | 0/0/0/0 | 0/55/0/110 | 0 | 0/0/0/0 |
| Gboard ouvert | 0/0/0/110 | 0/55/0/110 | 0/0/0/842 | 0/0/0/842 |
| Gboard fermé | 0/0/0/0 | 0/55/0/110 | 0 | 0/0/0/0 |

Découpe et waterfall nuls. Gestes/gestes obligatoires/tappable : 0/55/0/110,
y compris en immersif. Les barres transitoires photographiées ne provoquent pas
de nouveau rectangle de contenu dans les callbacks de cette tablette.

| État | Framebuffer | Zone physique utilisée | Fenêtre LAF avant scale | Raster UI |
|---|---|---|---|---|
| Avant | 2160×1440 | 2160×1440, bas recouvert sur 110 px | 1888×1258 | 944×629 |
| Après, dessin | 2160×1440 | 2160×1440 entièrement dégagée | 1888×1258 | 944×629 |
| Après, Gboard | 2160×1440 | 2160×598 | 1888×522 | 944×261 |
| Après fermeture/retour | 2160×1440 | 2160×1440 | 1888×1258 | 944×629 |

Scale fenêtre 2, scale thème 1, densité 366 dpi inchangés. Le gain de surface
accessible vient du retrait de la superposition Android, pas d’un changement de
résolution du raster ou d’une réduction de la taille des contrôles.

## Overlay XP-Pen

`dumpsys window` identifie `SidebarDropTarget` comme fenêtre **SystemUI**
(type DOCK_DIVIDER), sans surface et invisible lors de l’inspection.
`ShellDropTarget` est une APPLICATION_OVERLAY SystemUI avec appop
SYSTEM_ALERT_WINDOW, également sans surface/invisible. Aucun inset fourni par
ces deux fenêtres. Aucun service d’accessibilité activé n’a été relevé.

`ScreenDecorOverlay` et `ScreenDecorOverlayBottom` sont des panneaux SystemUI
translucides de 46×1440, non tactiles, marqués `IS_ROUNDED_CORNERS_OVERLAY`, sur
les côtés gauche/droit. Ces noms ne suffisent pas à les attribuer à une poignée
XP-Pen ; leurs flags correspondent aux décorations des coins. Ils ne déclarent
pas de marge latérale de contenu.

Les captures finales ne montrent pas de poignée latérale identifiable couvrant
un contrôle. **L’attribution de la poignée signalée précédemment reste non
confirmée** : elle n’était pas visible lors de ce relevé. Aucun inset spécifique
n’a été exposé. Si un overlay vendeur se dessine au-dessus de l’application sans
inset, cette application ne peut pas en déduire une zone sûre de façon fiable.
Aucun offset 46/55/110 ni autre valeur XP-Pen n’est codé en dur.

## Construction et tests

Variante `rasterProfile`, même Skia optimisé et même architecture raster que le
jalon 13. Commande :

```sh
android/gradlew -p android :app:assembleRasterProfile --console=plain --max-workers=4
```

Build réussi, APK installé par `adb -d install -r`. Compilation inspectée :
`--target=aarch64-none-linux-android26`, `-O2 -g -DNDEBUG`,
`ASEPRITE_ANDROID_GESTURE_PROFILE=1`, `SK_SUPPORT_GPU=0` dans les nouveaux/anciens
fichiers natifs concernés. Skia reste `is_debug=false`, `-O3`, révision épinglée
`08a5439a6be726021c1c1905d23ce298a3edc5e4` et GN du rapport raster optimisé.

APK : `android/app/build/outputs/apk/rasterProfile/app-rasterProfile.apk`.
SHA-256 : `4f0f79de208444b6e3d8b4770e76f0e0ff0f963429f9a743765a68fd88a68e64`.
Preuves : `jalon14-build-final.log`, `jalon14-build-evidence.json` dans
`android/build/`.

Tests hôte : **4/4 passent** (queue, raster, métriques/coordonnées, pression).
Les nouveaux cas vérifient quatre marges asymétriques, positions de bord et hors
contenu, réduction IME/restauration, marges excessives et surface vide. Les tests
raster vérifient une copie décalée RGBA/BGRA, les pixels nearest-neighbor, les
marges nettoyées et les sentinelles de stride/source intactes. `git diff --check`
réussit à la racine et dans LAF.

Sur la tablette, avec injections ADB et captures réellement inspectées :

| Vérification | Résultat |
|---|---|
| Lancement, Home/UI, ouverture document | OK |
| Menu File tout en haut, timeline/état en bas | Visibles ; ouverture File par tap conforme |
| Save As, focus nom, Gboard | OK, dialogue et boutons au-dessus du clavier |
| Saisie via touches Gboard | « ui » commis via InputConnection (taps injectés) |
| Sauvegarde privée puis réouverture | `ui.aseprite`, 1800 octets, rendu conforme |
| Back cache Gboard puis refocus du champ | OK, dimensions restaurées puis réduites |
| Home → retour avec Gboard ouvert | OK, dialogue et clavier accessibles |
| Fermeture dialogue/IME | Retour 944×629, barres masquées |
| Balayage de bord, attente 4 s | Barres révélées puis masquées automatiquement |
| SAF Open External → annulation → retour | OK, plein écran restauré |
| Home → retour sans clavier | OK |
| Destruction/recréation de surface | Observée lors de SAF et Home, sans taille résiduelle |
| Alignement toucher/clavier/menu | OK sur les taps injectés et les tests de mapping |
| Vrais doigts, vrai stylet/pression après modification | Validés ensuite par l’utilisateur ; voir validation physique ci-dessous |

Les fichiers antérieurs ont été sauvegardés avant installation. Les essais ont
utilisé le document de profilage et une copie `ui.aseprite`, sans écraser le
document original. La validation SAF ici porte sur le lancement/annulation et le
retour ; les transferts complets du jalon 11 ne sont pas tous rejoués.

## Régression performance

Même document 256×256, centre de trajectoire (1140,600), 40 MOVE, reset zoom
avec touche 1 puis recentrage Shift+Z. Un pan et un pinch injectés, comparés aux
deux répétitions optimisées antérieures. Framebuffer 2160×1440, raster 944×629,
scale 2 : contrôlés par le script. Toutes les captures sont complètes : 42
événements Begin/Navigation/End reçus par la GUI et l’éditeur, 40 Navigation,
aucun callback perdu, une seule présentation par redraw. Aucun changement de
densité, de préférences ou d’architecture.

Durées en ms, **moyenne / pire**, hors Begin :

| Mesure | Pan optimisé jalon 13 | Pan immersif jalon 14 |
|---|---:|---:|
| Cycle GUI | 9.410 / 15.318 | 6.578 / 9.739 |
| Composition UI | 2.402 / 3.431 | 1.681 / 2.040 |
| Editor paint | 0.035 / 0.061 | 0.030 / 0.330 |
| Copie raster | 4.184 / 7.691 | 2.895 / 3.033 |
| ANativeWindow lock | 0.562 / 0.978 | 0.373 / 0.435 |
| unlock/post | 0.599 / 1.263 | 0.510 / 0.803 |
| Présentation | 5.358 / 9.742 | 3.790 / 4.283 |

Pan : **41 frames**, 40 mises à jour visuelles, **28,94 FPS**, cadence imposée
par la sonde (~29 FPS dans la référence). Aucune fusion d’updates nécessaire.
Begin coûte **22,71 ms**, contre 22,62 ms dans la référence. Pas de renderSprite
mesuré pendant les frames de pan seules, comme auparavant.

Pinch injecté : trois redraws, dont un changement effectif de niveau de zoom.
Coût de cette frame **10,39 ms** (référence 9,34 ms), copie **2,85 ms**
(référence 3,34 ms), présentation **3,76 ms** (référence 4,30 ms). Begin
**23,22 ms**, contre 22,74 ms. Le nombre réduit de redraws est celui du zoom par
niveaux existant : le chiffre ~3,92 FPS entre redraws de cette sonde n’est pas
une limite de capacité du moteur. Les 40 mises à jour de navigation passent.

**Aucune régression matérielle décelée sur cet essai court.** La baisse du coût
de pan est favorable mais ne démontre pas un gain causé par le plein écran :
mesures faites à des moments différents, DVFS/état thermique non contrôlés,
échantillon court. La conclusion antérieure « fullscreen n’est pas une
optimisation renderer » demeure. Aucun benchmark matriciel supplémentaire
n’est nécessaire pour ce contrôle de non-régression.

Repos après les transitions : PID 5900, **0 tick sur 10,09 s**, horloge 100 Hz,
soit **0 % CPU d’un cœur** à cette résolution de mesure. Pas de boucle active
détectée. Aucun crash/ANR, rejet de copie, échec lock/post ou exception de bridge
relevé depuis le lancement de cette version. Les anciennes entrées du buffer
crash Android précèdent cette installation et ne sont pas attribuées à ce test.

Preuves dans `android/build/` : `jalon14_suite.py`, `jalon14-gestures.log`,
`jalon13-profile-fullscreen-a-gesture-pan.csv`,
`jalon13-profile-fullscreen-a-gesture-in.csv`, leurs résumés JSON,
`jalon14-pan-comparison.json`, `jalon14-pinch-comparison.json`,
`jalon14-idle.json`, `jalon14-final-device-log.txt`.

## Validation physique finale — 13 septembre 2026

Source : compte rendu explicite de l’utilisateur sur la XP-Pen MDP1221, avec les
vrais doigts et le vrai stylet. Les observations ci-dessous sont des résultats
physiques déclarés, distincts des injections et mesures automatisées précédentes.

| Essai physique | Résultat confirmé |
|---|---|
| Menus du haut au doigt | Correctement accessibles |
| Contrôles du bas et timeline | Accessibles |
| Alignement du vrai stylet avec le rendu | Correct |
| Variation de pression | Fonctionne toujours |
| Pinch | Fonctionne correctement |
| Pan à deux doigts | Fonctionne correctement |
| Save As + Gboard | Fonctionne correctement |
| Ouverture du clavier | Contenu utilisable |
| Fermeture du clavier | Interface restaurée sur tout l’écran |
| Swipe de bord | Barres système révélées normalement |
| Remasquage des barres | Taille et coordonnées de l’interface préservées |
| Contrôles importants | Aucun durablement masqué |
| Crash / ANR / pointeur bloqué | Aucun constaté par l’utilisateur |
| Décalage toucher/stylet et interface | Aucun constaté par l’utilisateur |

Cette confirmation clôt les points physiques auparavant en attente. La limitation
technique d’un éventuel overlay vendeur sans inset reste documentée ; elle ne
constitue pas un défaut bloquant constaté sur cette validation.

### Diagnostics du jeton `fullscreen-physical-01`

Récupération tentée sur l’appareil connecté `XCD1205AF825A05168` :
`files/jalon13-profile-fullscreen-physical-01.csv` **absent** (`cat` via `adb shell
run-as` : code 1, « No such file or directory »). Une recherche dans le stockage
privé ne retrouve que les deux CSV injectés `fullscreen-a-gesture-pan` et
`fullscreen-a-gesture-in`. Le jeton était encore présent dans la propriété
`debug.aseprite.profile`, mais aucune confirmation de sauvegarde de ce jeton
n’apparaît dans les logs disponibles. La cause de cette absence n’est pas établie.

**Aucun FPS ni temps de frame physique supplémentaire ne peut être attribué à ce
jeton.** Les performances chiffrées de la section précédente restent celles des
sondes injectées ; elles ne sont pas présentées comme une capture physique.
L’absence de CSV ne remet pas en cause le résultat fonctionnel directement
confirmé par l’utilisateur et ne démontre pas un défaut reproductible du produit.

Le processus est toujours PID **5900**. L’historique `ApplicationExitInfo` ne
montre aucune nouvelle sortie depuis la mise à jour du paquet à **12:21:58**
(ancien PID 5794, raison PACKAGE UPDATED). Aucun nouveau crash/ANR de l’application
n’a été trouvé dans les diagnostics récupérés. Les derniers insets applicatifs
journalisés retrouvent `[0,0,0,0]`, IME masqué, et un raster **944×629** ; ils
proviennent des vérifications antérieures et ne sont pas une trace complète des
essais physiques. La mesure de repos précédente (0 tick sur 10,09 s) est
conservée, sans prétendre à une nouvelle mesure CPU pendant ces essais.

Les preuves de récupération sont conservées dans `android/build/` :

- `jalon14-physical-retrieval.json` et `jalon14-physical-profile-unavailable.txt` ;
- `jalon14-physical-profile-search.txt` ;
- `jalon14-physical-log.txt` et `jalon14-physical-app-log.txt` ;
- `jalon14-physical-window.txt`, `jalon14-physical-exit-info.txt` ;
- `jalon14-physical-property.txt` et `jalon14-physical-pid.txt`.

Le jeton de profilage est désarmé après récupération pour terminer la session.
Aucune injection de geste, modification des préférences ou nouvelle capture de
substitution n’est utilisée pour remplacer le CSV manquant.

## Fichiers concernés par ce jalon

| Fichier | Modification |
|---|---|
| `android/app/src/main/java/org/aseprite/android/WindowUiBridge.java` | Nouveau propriétaire fullscreen/insets de durée de vie activité |
| `android/app/src/main/java/org/aseprite/android/ImeBridge.java` | Retrait de son listener d’insets, saisie conservée |
| `src/main/android_main.cpp` | Attachement/focus/destruction du bridge, retrait du helper fullscreen expérimental du chemin actif |
| `laf/os/android/window_ui.h`, `window_ui.cpp` | JNI et callbacks GUI versionnés |
| `laf/os/CMakeLists.txt` | Compilation du nouveau bridge natif |
| `laf/os/android/system.h`, `system.cpp` | Rectangle de contenu à quatre marges partagé par taille et entrée |
| `laf/os/android/display_metrics.h` | Rectangle borné et taille logique à densité constante |
| `laf/os/android/raster.h` | Présentation dans un sous-rectangle et nettoyage des marges |
| `laf/os/skia/skia_window_android.cpp` | Utilisation du rectangle commun, compte des pixels copiés/effacés |
| `laf/os/android/text_input.cpp` | Retrait de l’ancien callback IME viewport |
| `laf/os/android/tests/display_metrics_test.cpp`, `raster_test.cpp` | Contrats de coordonnées et copie avec marges |
| `android/README.md`, `android/tests/README.md` | Politique actuelle et validation |
| `ANDROID_ARM64_JALON_14_COMPTE_RENDU.md` | État initial, réalisation, preuves et limites |

Les modifications de profilage du jalon 13 déjà présentes dans le workspace
ne sont pas attribuées à ce jalon. Pas de commit ou publication effectué.

## Captures

Captures PNG originales dans `android/build/` :

- [Avant](android/build/jalon14-before.png), [Home immersif](android/build/jalon14-home.png),
  [document plein écran](android/build/jalon14-document.png),
  [menu File](android/build/jalon14-file-menu.png).
- [Save As + Gboard](android/build/jalon14-save-as-ime.png),
  [texte saisi](android/build/jalon14-gboard-text.png),
  [clavier fermé](android/build/jalon14-ime-closed.png),
  [réouverture privée](android/build/jalon14-private-reopened.png).
- [Barres transitoires](android/build/jalon14-bars-revealed.png),
  [barres cachées à nouveau](android/build/jalon14-bars-hidden-again.png).
- [SAF](android/build/jalon14-saf-picker.png),
  [retour SAF](android/build/jalon14-saf-return.png),
  [Home → retour](android/build/jalon14-home-return.png).
- [Back clavier](android/build/jalon14-ime-back.png),
  [refocus](android/build/jalon14-ime-refocused.png),
  [Home avec IME → retour](android/build/jalon14-ime-home-return.png),
  [état final automatisé](android/build/jalon14-final-device.png).

## Limites et suite recommandée

Les barres transitoires recouvrent momentanément les bords, conformément au mode
Android choisi ; elles restent accessibles par geste et se masquent seules.
Un overlay vendeur sans inset ne peut pas être compensé automatiquement. Les
API 26–29, un écran avec découpe et un autre mode de navigation Android ne sont
pas validés sur un second matériel. Les cas géométriques sont couverts sur hôte.

**Proposition de jalon 15 — composition IME et cohérence de l’édition de texte.**
Commencer par examiner le contrat LAF/Entry existant, puis définir la préédition
visible, le texte environnant et la synchronisation du curseur/sélection avec
Gboard. Validation ciblée envisagée : accents et mots composés, correction avant
commit, déplacement du curseur, suppression et changement de champ, en conservant
les insets validés ici. Cette étape améliorerait la saisie au-delà du commit
actuel décrit au jalon 12. **Proposition seulement : aucune implémentation
commencée.** Tilt, boutons, clipboard et GPU/EGL restent hors de cette clôture.
Le raster optimisé reste adapté.
