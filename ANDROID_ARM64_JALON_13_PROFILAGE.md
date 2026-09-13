# Jalon 13 — profilage du pincement et du pan Android ARM64

13 septembre 2026. Référence fonctionnelle :
[compte rendu du jalon 13](ANDROID_ARM64_JALON_13_COMPTE_RENDU.md).

**Clôture fonctionnelle : jalon 13 validé sur la XP-Pen MDP1221.** L’utilisateur
confirme la fluidité puis le pincement et le rendu au vrai stylet avec pression.
La capture courte finale est complète (5758 records, overflow=0, 169/169 Navigation,
89 frames/posts ; **30,15 FPS** sur le pincement). Le CPU au repos reste normal.
Voir la [confirmation finale détaillée](ANDROID_ARM64_JALON_13_RASTER_OPTIMISE.md).
Le pan à 48,66 FPS reste un extrait saturé, pas un benchmark complet de pan pur.
Les étapes et attentes ci-dessous sont l’historique du diagnostic.

L’utilisateur signale maintenant un pincement et un pan physiques **lents** sur
la XP-Pen MDP1221. Le jalon reste ouvert. Après la phase de profilage,
l’utilisateur a autorisé la première optimisation : suppression de la
présentation redondante, détaillée en fin de rapport. Aucun plein écran permanent,
GPU/EGL, zoom continu ou remplacement du renderer.

**Résultat du profil initial, avant correction : le plein écran n’améliore pas
matériellement le temps de frame. Le chemin GUI raster est dominant, et chaque
redraw effectue deux copies/présentations du même framebuffer.** Une capture
physique de 11,09 s a depuis été récupérée : elle confirme ce diagnostic avant
correction (9,62 FPS sur un geste mixte). La version corrigée est maintenant
installée : sur les sondes injectées répétées, le pan passe de **93,87 à
77,04 ms/frame**, soit environ **13 FPS**. Les tableaux de la phase initiale
ci-dessous restent **injectés** ; la capture physique et la comparaison après
correction sont identifiées dans les nouvelles sections finales.

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

## Méthode et périmètre

- XP-Pen **MDP1221**, ADB `XCD1205AF825A05168`, Android 14, ARM64.
- Framebuffer **2160×1440 RGBA8888** ; présentation normale depuis une surface
  **944×629**, scale LAF 2, densité Android effective 366 dpi. `wm density`
  rapporte densité physique 320 et override 366, conservé pendant les essais.
- Document de profilage : copie de `pixel-test-01.aseprite`, **256×256 RGBA,
  deux couches**, traits colorés et transparence, nommée
  `files/documents/jalon13-profile-document.aseprite`.
- Le dessin utilisateur précédemment non enregistré a été sauvegardé sous
  `jalon13-user-sprite-backup.aseprite` et copié sur l’hôte. Archives des fichiers
  et préférences avant manipulation dans `android/build/jalon13-profile-*.tar`.
- Aseprite **Debug sans optimisation `-O`** et Skia **`is_debug=true`**, sans
  optimisation `-O` dans `obj/skia.ninja`. Ces résultats ne prédisent pas les
  performances d’un binaire Release optimisé.
- Instrumentation **uniquement Android `_DEBUG` et sans `NDEBUG`** : mémoire
  bornée à **16384 enregistrements / 15 secondes**, une séquence à deux doigts
  par token `debug.aseprite.profile`. Écriture CSV seulement après End/Cancel
  traité par la GUI et présentation terminée, hors durée chronométrée. Aucun
  journal de chaque événement en continu. Horloge commune `CLOCK_MONOTONIC`.
- Une sonde initiale vérifie la corrélation, puis **12 captures contrôlées** :
  écartement et pan dans l’ordre **normal A → immersif A → immersif B → normal B**,
  puis deux répétitions à échelle 1. Chaque sonde contient **40 MOVE**, des IDs
  réordonnés et un doigt restant déplacé après levée de l’autre. Avant chaque
  sonde : zoom 100 %, recentrage, stabilisation de l’affichage.
- Le nom technique `gesture-in` désigne ici l’**écartement** (distance ×2,5).
  Le pan est une translation diagonale de (+240,+130) px physiques à distance
  constante. Les trajectoires sont identiques entre normal et immersif.

Le profil distingue réception MotionEvent, reconnaissance Navigation, insertion
queue, retrait GUI, callback GUI, mise à jour éditeur, invalidation/redraw,
peinture éditeur, rendu du sprite, appels Skia `drawImageRect`, composition des
couches UI, présentation Android, attente du mutex natif, `ANativeWindow_lock`,
copie/scaling et `ANativeWindow_unlockAndPost`. Les scopes imbriqués ne sont pas
additionnés. Il n’y a pas de mesure GPU ni de latence photon/scanout.

Pour chaque mise à jour et présentation, les artefacts conservent timestamp,
ID, frame GUI, TID, dimensions physique/logique, changement de palier de zoom,
pan seul, pixels/octets copiés et durée. Les mises à jour sans présentation
immédiate sont distinguées ; elles ne sont pas automatiquement des pertes.
Le schéma et les commandes sont dans
[GESTURE_PROFILING.md](android/tests/GESTURE_PROFILING.md), l’analyse reproductible
dans [analyze_gesture_profile.py](android/tests/analyze_gesture_profile.py).

## Temps mesurés

Millisecondes, **moyenne / pire cas**, sur deux répétitions par ligne. « Frame »
ci-dessous signifie un cycle GUI **ayant réellement présenté**, incluant la
frame initiale du Begin. Une frame GUI contient ici **deux présentations**.

| État / geste injecté | Frames GUI / posts | Travail GUI par frame | Présentation par post | Lock | Copie/scaling | Unlock/post |
|---|---:|---:|---:|---:|---:|---:|
| Normal, écartement | 6 / 12 | 201,76 / 355,09 | 17,10 / 17,29 | 0,33 / 0,43 | 16,18 / 16,48 | 0,56 / 0,69 |
| Immersif, écartement | 6 / 12 | 201,67 / 354,92 | 17,04 / 17,17 | 0,32 / 0,43 | 16,13 / 16,24 | 0,56 / 0,65 |
| Normal, pan | 32 / 64 | 109,66 / 354,85 | 17,07 / 17,40 | 0,33 / 0,49 | 16,17 / 16,51 | 0,55 / 0,92 |
| Immersif, pan | 31 / 62 | 110,58 / 355,53 | 17,11 / 19,09 | 0,33 / 0,60 | 16,21 / 17,91 | 0,55 / 0,97 |
| Normal, 1:1, écartement | 4 / 8 | 694,47 / 896,83 | 3,81 / 4,06 | 0,32 / 0,39 | 2,92 / 3,07 | 0,55 / 0,62 |
| Normal, 1:1, pan | 8 / 16 | 495,61 / 894,47 | 3,89 / 4,41 | 0,31 / 0,33 | 2,97 / 3,32 | 0,59 / 0,94 |

La frame initiale du geste est particulièrement chère : environ **355 ms** en
présentation normale, **895 ms** en 1:1. La peinture initiale prend environ
**240 ms**, contre beaucoup moins pendant le pan établi. Elle est conservée
ci-dessus, pas éliminée comme un simple outlier.

### Pan établi et cadence visuelle

En excluant seulement la frame Begin, les frames de pan effectives donnent :

| État | Travail GUI moyen / pire | Intervalle entre frames moyen / pire | Cadence visuelle entre posts de frames distinctes |
|---|---:|---:|---:|
| Normal | 93,32 / 127,25 ms | 93,35 / 127,30 ms | 10,71–10,72 FPS |
| Immersif | 93,78 / 127,16 ms | 93,82 / 127,22 ms | 10,63–10,69 FPS |
| Normal 1:1 | 363,02 / 437,92 ms | 363,11 / 438,17 ms | ≈2,75 FPS |

Le comptage brut des posts aurait annoncé environ deux fois trop de FPS. Ce ne
sont pas deux états visuels différents du canevas.

L’écartement centré injecté ne change le palier de zoom affiché **qu’une fois
par sonde**, bien que les 40 updates modifient son accumulateur. Les trois frames
présentées en mode normal donnent des intervalles moyens de **189,71 ms**
(pire **318,85 ms**), et **198,47 ms** en immersif (pire **350,84 ms**).
Le quotient entre première et dernière frame est 5,20–5,34 FPS en normal,
4,81–5,29 en immersif, mais **ce chiffre n’est pas un benchmark de débit de
zoom continu** : les paliers ne demandent pas tous une nouvelle image. Les traces
conservent aussi la durée totale du geste et le nombre de frames par seconde de
cette fenêtre. À la fin de cette première phase, le FPS physique était encore manquant.
La capture mixte récupérée ensuite est décrite plus bas ; elle ne constitue pas
deux essais séparés de pincement et de pan.

### Où va le temps ?

Moyennes par frame de **pan établi**, sans la frame Begin ; scopes disjoints :

| Étape | Normal | Immersif | 1:1 |
|---|---:|---:|---:|
| Composition des couches UI par Skia | 50,32 ms | 50,40 ms | 295,59 ms |
| Dispatch peinture/redraw UI | 5,89 ms | 5,94 ms | 26,40 ms |
| Mises à jour éditeur traitées dans la frame | 2,68 ms | 2,95 ms | 32,83 ms |
| Copie/scaling, **deux fois** | 32,35 ms | 32,43 ms | 5,95 ms |
| Lock + post, deux fois | 1,77 ms | 1,76 ms | 1,76 ms |

L’invalidation `flushRedraw` est petite : moyenne **0,15 ms**, pire **0,47 ms**
pour le pan normal. Les appels Skia d’image sont observables dans le détail,
mais sont déjà inclus dans peinture/composition et ne doivent pas être ajoutés.
Le coût principal établi est la **composition GUI raster**, environ 54 % de la
frame normale, suivie des deux copies, environ 35 %.

La frame où **le palier de zoom change** coûte **184,58 ms** en moyenne,
pire **185,68 ms**, contre **93,32 ms** pour un pan établi. En immersif elle coûte
**185,19 ms**, pire **186,76 ms**. Le changement de zoom nécessite un nouveau rendu
sprite : appels `renderSprite` autour de **59 ms** dans ce cas, en plus du travail
Skia/UI. Un update qui ne change que l’accumulateur n’a pas ce coût. Les mises à
jour éditeur elles-mêmes sont petites : en moyenne **0,074 ms** pour les événements
de la sonde zoom, **0,993 ms** pour ceux de la sonde pan.

## Double présentation confirmée

Le code et les traces concordent :

```text
Display::flipDisplay()
  → SkiaWindowAndroid::invalidateRegion()
      → swapBuffers() → lock → copie entière → post
  → SkiaWindowAndroid::swapBuffers()
      → lock → copie entière → post
```

`laf/os/skia/skia_window_android.h` appelle `swapBuffers()` depuis
`invalidateRegion()`, alors que `src/ui/display.cpp` appelle ensuite explicitement
`swapBuffers()`. Les **12 traces** montrent exactement deux posts par frame GUI.
Chaque post écrit **3 110 400 pixels / 12 441 600 octets** (sans padding), soit
**24 883 200 octets écrits par frame GUI**. Le framebuffer reste 2160×1440 dans
les trois états. Ce doublon était seulement identifié à la fin du profilage ;
sa correction autorisée ensuite est décrite en fin de rapport.

## Entrée, attente et regroupement

Exemple détaillé de réception, **pan normal A injecté**, moyenne / pire cas :
âge du MOVE courant à l’entrée du backend **13,80 / 17,97 ms** ; exécution de
`InputAndroid::motion` **6,24 / 33,35 ms** ; réception → insertion Navigation
**6,54 / 33,35 ms**. Les deux dernières durées se recouvrent et incluent l’attente
du mutex de conversion. L’intervalle de réception des MOVE est **37,16 / 66,60 ms**
sur cette sonde. Les traces séparent aussi retrait de la queue et début du
callback GUI ; elles ne confondent pas insertion et prise en charge éditeur.

- **504 événements Navigation** (Begin + 40 Update + End, ×12) enqueued,
  retirés et traités par callback/éditeur : **aucune perte de queue observée**.
  Aucun overflow du buffer de profilage dans les captures retenues.
- Sur les deux pans normaux, **80 updates → 30 frames portant un update** :
  **50 updates combinés visuellement**, jusqu’à **7 par frame**. Les événements
  sont tous traités ; les redessins reflètent l’état obtenu après plusieurs
  updates. En 1:1, jusqu’à **25 updates par frame**, et **74 combinés sur 80**.
- Attente queue → callback pendant le pan normal : **85,35 ms** en moyenne,
  pire **252,67 ms**. En 1:1 : **354,21 ms**, pire **792,37 ms**.
- Le thread d’entrée attend également le mutex natif utilisé par la conversion
  de coordonnées : pendant le pan normal, moyenne **5,91 ms par acquisition**,
  pire **33,73 ms**. `swapBuffers()` conserve ce même mutex pendant lock/copie/post.
  En 1:1 cette attente tombe à **0,071 ms**, pire **6,14 ms**, alors que la GUI
  devient beaucoup plus lente. Le mutex est donc un facteur supplémentaire,
  pas l’explication dominante de la lenteur globale.
- Les sondes injectées fournissent environ un MOVE courant toutes les **33 ms**,
  avec un peu d’historique de batching Android. **Cette cadence est celle des
  sondes ; elle ne mesure pas la fréquence du digitizer physique.**

Les anciens gestes **physiques** du jalon 13, PID 17642, contacts 9–15,
contiennent **1062 MOVE courants et 1893 échantillons historiques en 20,337 s**
(cumul des durées des sept contacts), soit environ **52 MOVE courants/s** et
**145 échantillons courants + historiques/s**, avec jusqu’à quatre échantillons
d’historique et un pire écart d’événement de **151,221 ms**. Android fournit donc
bien de l’historique lors des gestes réels. Le backend ne le rejoue pas pour
la navigation. Ce résumé historique n’établit pas la cause des écarts ni la
latence de livraison sous le nouveau profil ; **la rareté intrinsèque des
MotionEvent physiques n’est pas démontrée**. Il serait incorrect d’attribuer
les 10 FPS mesurés à un capteur de 10 Hz.

## Plein écran et échelle 1

Le réglage OS `policy_control` a été essayé puis restauré ; Android l’acceptait
sans masquer sa barre de navigation. Il n’a donc pas été compté comme immersif.
Un helper sous **`android/app/src/debug/java/`**, piloté par
`debug.aseprite.immersive`, masque temporairement les barres puis restitue les
flags du decor à la désactivation. Captures et dumpsys vérifient leur disparition.
Le framebuffer et la surface logique **restent identiques**. Ordre ABBA,
deux répétitions de chaque geste : **pas d’amélioration matérielle**, avec pan
établi environ **0,5 % plus lent** en immersif dans cet échantillon. Les écarts
ne justifient pas une optimisation fullscreen.

L’override Debug `debug.aseprite.scale1` force temporairement une vraie surface
**2160×1440**, sans toucher à `wm density` ni à la préférence de scale. La copie
passe par le chemin 1:1 et baisse d’environ **16,2 à 3,0 ms**, mais la surface UI
contient **5,24 fois plus de pixels**. La composition établie passe de **50,3 à
295,6 ms**. Ce n’est pas une optimisation exploitable telle quelle ; cela montre
qu’enlever le scaling seul ne résout pas le coût du rendu/compositing GUI.
L’expérience modifie également la taille physique des widgets et du sprite ;
elle n’est pas une comparaison à contenu visuel strictement identique.

## Conclusion et prochaine optimisation recommandée

1. **Premier changement recommandé : supprimer la présentation redondante**
   dans le contrat invalidate/flip Android, puis reprendre le même profil et
   les tests d’affichage/lifecycle. Budget théoriquement évitable : environ
   **17 ms/frame** et une copie entière. Ce gain est une estimation issue du
   profil, pas un résultat après correction.
2. Mesurer ensuite la même chaîne avec **Skia et le code raster optimisés**,
   en conservant les sondes Debug si nécessaire, avant de décider d’un changement
   d’architecture. Le coût dominant actuel est le compositing CPU d’un build
   sans optimisation, puis les copies redondantes. Réduire les régions composées
   et analyser la grosse peinture Begin sont les autres pistes ciblées.
3. Refaire un **pincement et un pan physiques instrumentés** pour vérifier
   cadences, latence et regroupement sur le vrai flux, puis le ressenti après
   toute future optimisation. Le signalement utilisateur de lenteur reste ouvert.

Bilan de la phase initiale : aucune preuve ne rend GPU/EGL clairement nécessaire.
La première correction ciblée ci-dessous conserve reconnaissance, zoom par
paliers, densité et architecture du rendu.

## Validation et artefacts

- APK Debug reconstruit/installé ; dernière construction **BUILD SUCCESSFUL in
  12s**, 40 tâches, 7 exécutées, 33 à jour. Le dernier passage remet seulement
  les includes de profilage à leur place normale ; mêmes probes/algorithmes.
- Les **4 tests hôte** queue/raster/densité/pression passent après instrumentation.
- Contrôle du header avec `NDEBUG` : instrumentation absente. Le helper Java
  est uniquement dans le source set Debug. `git diff --check` passe dans Aseprite
  et LAF. Contrôle des 12 CSV : tailles bornées, durées positives, 504/504 IDs
  traités et deux posts par frame.
- `android/build/jalon13-profile-{normal,immersive,scale1}-{a,b}-gesture-{in,pan}.csv`
  et sorties `.summary.json`, `.frames.json`, `.presentations.json`, `.updates.json`.
- Agrégats : `jalon13-profile-aggregate.json`, `jalon13-profile-steady-pan.json`.
  Orchestration locale : `jalon13_profile_matrix.py`. Les échecs initiaux de
  préparation ne contiennent pas de CSV valide et sont exclus des résultats.
- Captures normal/immersif/scale1 et dumpsys `jalon13-profile-*-window.txt` ;
  archives de sauvegarde et logs de compilation sous `android/build/`.
- L’override scale1 est supprimé, les préférences archivées avant l’essai scale1
  sont restaurées, et l’app est relancée en densité normale avec barres système.
  Le profiler réel est réarmé pour les manipulations manquantes.


## Capture physique récupérée avant correction

`jalon13-profile-physical-normal-final.csv`, récupérée au début de la reprise :
**12 516 enregistrements**, Begin et End présents, durée **11,090 s**, `device=4`,
outil doigt. La fenêtre est complète et reste sous les limites du buffer.
Elle mêle changements de zoom et translations : elle n’est pas assimilée à deux
protocoles physiques séparés ni utilisée comme comparaison contrôlée avant/après.

- **108 frames GUI, 216 posts**, soit **9,62 FPS** entre première et dernière
  frame. Intervalle moyen **103,99 ms**, pire **357,96 ms**.
- Frames classées pan seul : **92,96 / 99,74 ms** (moyenne/pire, 101 frames).
  Frames avec changement de zoom : **189,63 / 227,07 ms** (5 frames).
- Présentation par post : **17,06 / 18,13 ms** ; lock **0,33 / 1,24 ms**,
  copie **16,15 / 17,24 ms**, post **0,55 / 1,14 ms**.
- Composition Skia : **49,98 / 77,88 ms** par frame présentée.
- **618 MOVE courants et 1134 échantillons historiques**, jusqu’à 4 par événement.
  Réception : intervalle **17,94 / 66,79 ms** ; âge du sample courant à réception
  **3,76 / 23,03 ms**. Le flux reçu est donc nettement plus fréquent que les
  frames affichées ; il ne s’agit pas d’une entrée physique limitée à 10 Hz.
- **610 Navigation enqueued, callbacks et traitements éditeur**, sans perte
  constatée entre ces étapes. Attente queue → callback **75,62 / 357,94 ms**.
  Attente du mutex natif côté entrée **4,07 / 34,33 ms**.

Cette capture physique confirme le coût de composition et de double copie
identifié avec les sondes injectées. Le ressenti après correction reste à recueillir.

## Première optimisation autorisée — une présentation par redraw

Le défaut exact est la transition **redraw GUI → invalidation native → flip** :
`Display::flipDisplay()` appelle `invalidateRegion()` puis `swapBuffers()`, mais
l’override Android de la première méthode appelait déjà la seconde.

La correction retire seulement cet override dans
`laf/os/skia/skia_window_android.h`. Android hérite de l’invalidation sans effet
de `WindowAndroid`, et le flip explicite reste responsable de la présentation.
Les demandes de redraw du lifecycle Android continuent d’appeler directement
`swapBuffers()`. Aucune modification du geste, du mutex, de la copie, des paliers
ou du compositing ; une seule variable de comportement a changé.

### Comparaison contrôlée fraîche avant/après

Même APK Debug instrumenté, mêmes bibliothèques non optimisées, document,
trajectoires, framebuffer **2160×1440** et surface **944×629**. Deux répétitions
zoom/pan avant installation, puis deux après, en état système normal.
Les quatre nouvelles captures avant correction ont bien deux posts par frame ;
les quatre captures après en ont exactement un. Chaque capture traite les
42 Navigation (Begin, 40 Update, End) jusqu’à l’éditeur ; aucun overflow après
correction (`AsepriteProfile`, PID 3434).

| Mesure | Avant | Après |
|---|---:|---:|
| Pan établi, moyenne / pire | 93,87 / 128,26 ms | **77,04 / 110,42 ms** |
| FPS du pan injecté, deux répétitions | 10,58–10,71 | **12,96–12,98** |
| Frame changeant le palier de zoom, moyenne / pire | 185,79 / 185,98 ms | **165,38 / 165,42 ms** |
| Composition UI pendant le pan, moyenne | 50,43 ms | 50,28 ms |
| Présentation totale par frame de pan établi | 34,26 ms | **18,46 ms** |
| Octets copiés par redraw | 24 883 200 | **12 441 600** |

Le travail par frame de pan baisse de **17,9 %**, et la cadence augmente
approximativement de **22 %**. La frame Begin reste chère : pire **336,82 ms**
après correction pour le pan. La correction améliore la lenteur sans la résoudre
complètement. Le FPS du pincement injecté reste un indicateur inadapté au débit
continu, car cette trajectoire ne change le palier qu’une fois par répétition.

Par présentation pendant le pan après correction, moyenne / pire :
**18,46 / 18,67 ms**, dont lock **0,34 / 0,38 ms**, copie **17,53 / 17,71 ms**,
post **0,57 / 0,63 ms**. Le coût par copie n’a pas diminué ; c’est le nombre de
copies qui a été divisé par deux. Les coûts par appel varient légèrement entre
les captures ; aucune modification de l’algorithme de copie n’est revendiquée.

### Vérifications et suite

- Construction Debug : **BUILD SUCCESSFUL in 6s** ; installation ADB réussie,
  lancement à froid réussi en 1179 ms, PID 3434.
- **4/4 tests hôte** queue/raster/densité/pression passent ; diffs sans erreur
  d’espacement. La preuve de non-régression du nombre de posts vient des traces
  Android du vrai backend, pas d’un test qui reproduit seulement le code retiré.
- Affichage inspecté au lancement, ouverture du document avec apparition puis
  disparition du clavier, zoom/pan injectés, retour Home → app. Une véritable
  destruction/recréation de surface est enregistrée à 11:19:25–26 ; le canevas
  réapparaît correctement. Aucun échec lock/post, crash ou ANR dans le journal
  conservé du PID testé.
- Dessins et préférences sauvegardés avant installation :
  `android/build/jalon13-single-present-backup.tar`. Aucune densité ni préférence
  d’échelle modifiée pour cette correction.
- Captures `jalon13-profile-{duplicate-before,single-present}-{a,b}-gesture-{in,pan}.csv`,
  analyses associées et `jalon13-single-present-comparison.json` sous `android/build/`.
- Reprise physique demandée sur la version corrigée : pincement/écartement, pause,
  pan à deux doigts. Les tokens `physical-single-present-pinch` et
  `physical-single-present-pan` permettent des captures séparées.
  Aucune capture complète reçue pendant la fenêtre d’attente de cette reprise.
  Le premier token reste armé ; le collecteur temporaire a terminé son attente.
  Après récupération du premier geste, réarmer le second token pour le pan.

**Étape suivante réalisée : build raster/Skia optimisé avec sondes conservées**,
détaillé dans le [rapport comparatif](ANDROID_ARM64_JALON_13_RASTER_OPTIMISE.md).
La composition UI passe de 50,28 à 2,40 ms ; copie/scaling et validation physique
sont désormais prioritaires. Le zoom progressif reste une évolution ultérieure. La validation fonctionnelle finale est désormais acceptée et documentée en tête
de ce rapport ; les données de pan pur restent partielles.
