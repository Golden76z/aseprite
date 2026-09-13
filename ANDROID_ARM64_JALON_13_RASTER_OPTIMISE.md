# Jalon 13 — comparaison raster optimisé Android ARM64

13 septembre 2026. Références : [profilage initial](ANDROID_ARM64_JALON_13_PROFILAGE.md)
et [compte rendu du jalon 13](ANDROID_ARM64_JALON_13_COMPTE_RENDU.md).

**Le pan injecté passe de 77,04 à 9,41 ms de travail GUI par frame**, avec le même
rendu raster, la même présentation unique et les mêmes sondes. La composition
UI passe de 50,28 à 2,40 ms ; la copie/scaling devient le premier coût récurrent,
4,18 ms. L’utilisateur confirme maintenant : **« clairement plus fluide »**.
La portion physique récupérée montre **48,66 FPS**, mais le buffer a saturé :
ce chiffre décrit un extrait, pas un geste complet validé. Ces résultats ne
justifient pas de commencer EGL/GPU. Le pincement court a depuis été capturé
intégralement et l’utilisateur confirme le rendu, le vrai stylet et la variation
de pression : **validation fonctionnelle du jalon 13 acceptée**. Le benchmark
complet de pan pur reste une limite de mesure, distincte de cette acceptation.

## Configuration construite et réellement vérifiée

Nouvelle variante Android **`rasterProfile`**, dérivée de Release pour le packaging,
avec CMake **`RelWithDebInfo`**, `ASEPRITE_ANDROID_GESTURE_PROFILE=ON` et une archive
Skia distincte. Son APK est débogable pour permettre `adb run-as`, signé avec la
même clé locale que Debug. Elle conserve donc les données et le document.

Commandes effectives de `input.cpp`, `skia_surface.cpp`, `skia_window_android.cpp`,
`android_main.cpp`, `display.cpp`, `manager.cpp`, `editor.cpp` :

```text
--target=aarch64-none-linux-android26
-O2 -g -DNDEBUG
-DASEPRITE_ANDROID_GESTURE_PROFILE=1
-DSK_SUPPORT_GPU=0 -DSK_ENABLE_SKSL=1
```

Pas de `_DEBUG`, `DEBUGMODE` ni `-O0`. AGP ajoute aussi un `-g`, sans annuler `-O2`.
Les commandes complètes sont conservées dans
`android/build/jalon13-optimized-compile-evidence.json`, issues de
`android/app/.cxx/RelWithDebInfo/5zu684qh/arm64-v8a/compile_commands.json`.
Le niveau retenu n’est donc pas déduit du seul nom de variante Gradle.

Les sondes (horloge, scopes, queue, IDs, mutex, tableau de 16384 records, fenêtre
maximale de 15 s et flush après End) sont identiques au Debug. Seule leur garde
de compilation accepte désormais l’option explicite sous `NDEBUG`. Les assertions
et anciens diagnostics input/pression réservés au Debug sont désactivés.
Le Release ordinaire conserve l’option OFF ; il n’embarque pas ces sondes.
L’analyse ajoute seulement les agrégats `editor_paint` et la séparation Begin.

Aseprite/LAF : `-O2`. **Skia : `-O3 -DNDEBUG`**, avec
`-gline-tables-only -funwind-tables`. Le fichier `obj/skia.ninja` et les arguments
résolus GN ont été archivés. Les tables de lignes et de déroulement sont déjà
présentes dans le profil GN Android ; les `extra_cflags` explicites les répètent
sans changer le niveau d’optimisation.

Symboles préservés : `libaseprite.so` non stripé dans `.cxx/RelWithDebInfo/.../lib/`,
sections ELF `.debug_info`, `.debug_line`, `.symtab` vérifiées, et
`android/app/build/outputs/native-debug-symbols/rasterProfile/native-debug-symbols.zip`.
Build ID : **`1efdca34e7104733bc1f5ebef70db0df4ccb381d`**.
La bibliothèque de l’APK est stripée comme d’habitude ; cela ne retire pas les
sondes exécutables. Ce n’est pas une comparaison sans instrumentation.
Les dépendances ELF ne contiennent ni EGL, ni GLES, ni Vulkan.

## Skia — révision et GN exacts

Révision inchangée : **`08a5439a6be726021c1c1905d23ce298a3edc5e4`**
(`m124-08a5439a6b`). Sortie dédiée `.deps/skia/out/android-arm64-profile` :

```gn
target_os = "android"
target_cpu = "arm64"
ndk = "/home/golden/Android/Sdk/ndk/28.2.13676358"
ndk_api = 26
is_debug = false
is_official_build = false
is_trivial_abi = false
skia_enable_tools = false
skia_enable_gpu = false
skia_use_gl = false
skia_use_system_expat = false
skia_use_system_icu = false
skia_use_system_libjpeg_turbo = false
skia_use_system_libpng = false
skia_use_system_libwebp = false
skia_use_system_zlib = false
skia_use_freetype = true
skia_use_harfbuzz = true
skia_pdf_subset_harfbuzz = true
skia_use_system_freetype2 = false
skia_use_system_harfbuzz = false
extra_cflags = [ "-gline-tables-only", "-funwind-tables" ]
```

Même NDK r28c, ABI ARM64 et API 26 que Debug. Les fonctionnalités texte et SkSL
sont conservées (`SK_ENABLE_SKSL=1`, FreeType, HarfBuzz). Les arguments résolus
confirment **`skia_enable_ganesh=false`**, **`skia_enable_graphite=false`**,
`skia_enable_gpu=false`, `skia_use_gl=false`. `is_official_build=false` et les
options de développement Skia dérivées, dont SkSL tracing, restent celles de la
référence ; aucune fonctionnalité texte/SkSL n’a été retranchée pour accélérer.

Reproduction, depuis la racine du dépôt, après écriture de ces GN args :

```sh
.deps/skia/bin/gn gen .deps/skia/out/android-arm64-profile --root=.deps/skia
/home/golden/Android/Sdk/cmake/3.22.1/bin/ninja \
  -C .deps/skia/out/android-arm64-profile -j 8 skia modules
android/gradlew -p android :app:assembleRasterProfile --console=plain --max-workers=4
adb -d install -r android/app/build/outputs/apk/rasterProfile/app-rasterProfile.apk
```

Skia : 1599 étapes terminées. APK : **BUILD SUCCESSFUL in 2m 33s**, 42 tâches.
Les premières commandes de configuration ont échoué (nom de tâche native puis
archives Skia encore en compilation) ; elles ne sont pas des builds benchmarkés.
La construction finale a configuré et lié les archives optimisées terminées.

## Conditions et contrôles de comparabilité

- XP-Pen MDP1221, Android 14 ; framebuffer **2160×1440**, surface **944×629**,
  scale 2, densité effective **366 dpi**, override Android conservé.
- Même document `jalon13-profile-document.aseprite`, 256×256 RGBA, deux couches.
- Même `InputProbe` : **40 MOVE**, mêmes trajectoires/IDs et coordonnées
  physiques (1140,600), doigt restant déplacé après geste. `gesture-in` signifie
  écartement ; le palier de zoom change une fois dans cette sonde.
- Avant chaque capture : touche 1 (`keyevent 8`), recentrage Shift+Z (`59,54`),
  mêmes pauses de stabilisation. Ordre optimisé : **pan A, pan B, pinch A, pinch B**.
- Comparaison directe aux quatre CSV **single-present A/B** du dernier Debug
  corrigé. Les deux côtés ont une présentation par redraw et les mêmes dimensions.
- Les 42 Navigation de chaque capture optimisée sont reçues, enqueued et traitées
  par l’éditeur. Aucun overflow des quatre captures. Pas de screenshot, dump ni
  écriture CSV pendant le geste mesuré ; récupération après fin.
- Deux répétitions par geste, pas une étude thermique/randomisée. La cadence
  effective d’injection peut varier avec InputDispatcher et l’application ; le
  nombre de MOVE, la trajectoire et les temporisations demandées sont conservés.

## Pan établi — comparaison directe

Les coûts de frame ci-dessous excluent Begin. Les scopes sont imbriqués : la
peinture éditeur est comprise dans le dispatch peinture, et `renderSprite` dans
la peinture éditeur ; ne pas additionner toutes les lignes.

| Mesure, ms moyenne / pire | Debug une présentation | Raster optimisé une présentation |
|---|---:|---:|
| Travail GUI par frame | 77.042 / 110.415 | 9.410 / 15.318 |
| Composition UI Skia | 50.283 / 52.662 | 2.402 / 3.431 |
| Peinture éditeur (cumul/frame) | 0.242 / 0.326 | 0.035 / 0.061 |
| Dispatch peinture UI | 5.804 / 33.192 | 0.804 / 3.039 |
| renderSprite (cumul/frame) | 0.000 / 0.000 | 0.000 / 0.000 |
| Copie/scaling raster | 17.526 / 17.712 | 4.184 / 7.691 |
| ANativeWindow_lock | 0.344 / 0.376 | 0.562 / 0.978 |
| unlockAndPost | 0.566 / 0.635 | 0.599 / 1.263 |
| Présentation totale | 18.459 / 18.667 | 5.358 / 9.742 |

| Cadence / regroupement, pan | Debug | Optimisé |
|---|---:|---:|
| FPS visuels, A / B | 12,98 / 12,96 | **28,87 / 29,19** |
| Intervalle entre frames, moyenne / pire | 77,077 / 110,484 ms | 34,449 / 89,590 ms |
| Frames par geste, A / B (Begin inclus) | 18 / 18 | **41 / 41** |
| Navigation Update par geste | 40 / 40 | 40 / 40 |
| Updates combinés visuellement par geste | 23 / 23 | **0 / 0** |
| Maximum d’updates par frame | 7 | **1** |
| Queue → callback, moyenne / pire | 71,420 / 234,553 ms | **0,149 / 0,422 ms** |
| Attente mutex natif côté entrée, moyenne / pire | 3,281 / 16,925 ms | **0,000191 / 0,000308 ms** |

Les 9,41 ms mesurent le travail GUI, pas un intervalle d’affichage de 9,41 ms.
L’injection fournit environ 30 updates/s ; l’application attend entre eux. Les
29 FPS ne constituent donc pas une limite démontrée du raster. La mesure de pan
physique est nécessaire pour appliquer les seuils indicatifs 15/30 FPS.
`renderSprite=0` pendant ces pans établis signifie que le rendu du sprite est
réutilisé ; il a un coût au Begin et lors du changement de zoom.

## Pincement — frame changeant le palier

| Mesure, ms moyenne / pire | Debug une présentation | Raster optimisé une présentation |
|---|---:|---:|
| Travail GUI par frame | 165.385 / 165.423 | 9.338 / 9.886 |
| Composition UI Skia | 51.651 / 51.701 | 1.771 / 1.773 |
| Peinture éditeur (cumul/frame) | 87.214 / 87.306 | 2.293 / 2.294 |
| Dispatch peinture UI | 93.722 / 93.827 | 2.914 / 2.920 |
| renderSprite (cumul/frame) | 51.775 / 51.845 | 0.797 / 0.822 |
| Copie/scaling raster | 17.604 / 17.638 | 3.342 / 3.728 |
| ANativeWindow_lock | 0.310 / 0.310 | 0.372 / 0.379 |
| unlockAndPost | 0.721 / 0.818 | 0.571 / 0.631 |
| Présentation totale | 18.656 / 18.788 | 4.298 / 4.738 |

Chaque geste présente **3 frames** dans les deux builds ; 40 Navigation Update
sont traitées, avec un seul changement de palier. Le quotient première/dernière
frame est 5,50–5,60 FPS en Debug et 3,85–3,93 en optimisé : **ce n’est pas une
régression du débit de zoom**, puisque les frames sont espacées par les paliers
et le déroulement du geste. Le coût de la frame changeant le zoom passe bien de
165,38 à 9,34 ms. Ne pas présenter ce quotient comme le FPS d’un zoom continu.

Queue → callback : **32,800 / 232,881 ms → 0,155 / 0,345 ms**.
Attente mutex natif côté entrée : **0,125 / 9,007 ms → 0,000151 / 0,000308 ms**.
Dans les frames présentées, 6 updates combinés par geste en Debug, aucun en
optimisé ; les updates sans changement affiché restent traités sans redraw.

## Begin — coût séparé

| Mesure, ms moyenne / pire | Debug une présentation | Raster optimisé une présentation |
|---|---:|---:|
| Travail GUI par frame | 336.637 / 336.822 | 22.622 / 22.735 |
| Composition UI Skia | 78.003 / 78.074 | 2.759 / 2.769 |
| Peinture éditeur (cumul/frame) | 71.069 / 71.085 | 1.654 / 1.665 |
| Dispatch peinture UI | 237.899 / 237.950 | 15.731 / 15.746 |
| renderSprite (cumul/frame) | 59.915 / 59.941 | 0.994 / 1.000 |
| Copie/scaling raster | 17.592 / 17.656 | 2.942 / 2.993 |
| ANativeWindow_lock | 0.315 / 0.317 | 0.344 / 0.344 |
| unlockAndPost | 0.558 / 0.562 | 0.513 / 0.515 |
| Présentation totale | 18.489 / 18.558 | 3.814 / 3.867 |

Ce tableau utilise Begin des deux pans. Pour les pincements : GUI
**334,82 / 335,00 ms → 22,74 / 22,81 ms**. Begin reste le cas isolé le plus cher,
dont environ **15,7–15,8 ms de peinture UI** en optimisé, mais n’explique plus
un pan établi à moins de 15 FPS. Aucun correctif spéculatif de ce chemin ajouté.

## Régression minimale de la variante optimisée

| Contrôle | Résultat / preuve |
|---|---|
| Home/UI et document | Rendu inspecté ; ouverture du document de référence réussie ; démarrage à froid 706 ms, PID 4334. |
| Touch | Menus, contrôles et clavier activés par taps Android ; gestuelle via le vrai InputDispatcher/AInputQueue. Le doigt physique est confirmé par la capture complète finale `device=4`. |
| Pinch/pan | Quatre sondes réussies, dimensions attendues, 42/42 callbacks éditeur par sonde, une présentation par frame. |
| IME | Quatre touches **du clavier Gboard affiché** tapées pour saisir « opti » ; champ correct et quatre commits UTF-16 enregistrés. Apparition/disparition du clavier et restauration de l’inset. Ce n’est pas uniquement `adb input text`. |
| Save/open privé | Save As `opti.aseprite` créé (1800 octets), document fermé puis rouvert et inspecté. Original de profilage rouvert ensuite. |
| SAF | Open External lance réellement `ACTION_OPEN_DOCUMENT` et Documents Android ; annulation puis retour réussis. Pas de nouvelle exportation externe dans ce contrôle. |
| Home → retour | Destruction/libération et recréation natives à 11:49:08, canevas réaffiché correctement. |
| Vrai stylet / pression | **Confirmés par l’utilisateur** en réponse à la demande explicite de dessin au vrai stylet et variation d’épaisseur selon la pression : « c’est fait et le rendu est niquel ». Réglages Size/Pressure 1–12 px inspectés. Aucun nouveau relevé numérique de pression revendiqué. |
| Stabilité | Aucun crash/ANR ni échec de buffer dans le journal de régression du PID 4334. Quatre tests hôte queue/raster/densité/pression réussis, puis reconstruits et repassés sous `RelWithDebInfo` (`-O2 -g -DNDEBUG`, contrôles `require` actifs). |

Captures `jalon13-optimized-{home,document,ime-typed,private-reopened,saf,resumed,dynamics}.png`
et `jalon13-optimized-regression-logcat.txt`. Sauvegarde avant installation :
`jalon13-optimized-preinstall.tar`. Le document `opti.aseprite` est une copie de
régression, distincte du fichier utilisé pour les benchmarks.

## Physique — captures séparées demandées

Tokens : **`physical-optimized-pinch`**, puis **`physical-optimized-pan`**.
Geste de 2–3 secondes chacun, levée complète et pause de 5 secondes entre les deux,
puis traits au vrai stylet avec variation de pression. Le collecteur écoute
seulement le message de fin `AsepriteProfile` avant de récupérer le CSV et d’armer
le token suivant. Il ne lit pas de CSV et ne prend pas de captures pendant le geste.

La fenêtre de pincement a été armée pendant trois minutes sans capture complète.
Le pan a ensuite été armé séparément. Après manipulation de la version optimisée,
l’utilisateur confirme **« clairement plus fluide »**. Ce retour valide une
amélioration ressentie ; il ne vaut pas confirmation implicite du stylet, de la
pression ou de tous les critères fonctionnels du jalon.

La capture `jalon13-profile-physical-optimized-pan.csv` a été récupérée. Le log
à 12:00:44, PID 4334, indique **16384 records, overflow=9298**. Le buffer a donc
saturé avant la fin du geste ; aucun End n’est présent dans le CSV. L’analyse
marque maintenant explicitement ces captures incomplètes et le comparateur les
refuse comme références complètes. Un ID enqueued sans callback dans cette trace
tronquée n’est **pas une preuve de perte d’événement par l’application**.

Sur la seule portion enregistrée, **6,884 s entre les posts extrêmes** :

- `device=4`, doigts physiques ; **336 frames / 336 posts**, mêmes dimensions
  **2160×1440 / 944×629**. Deux changements de palier sont aussi présents :
  le token « pan » ne garantit pas un pan pur.
- Cadence observée **48,66 FPS** ; intervalle **20,55 ms moyen**, **132,39 ms pire**.
  Ce n’est ni la cadence du geste complet ni une comparaison physique appariée.
- Travail GUI des frames présentées **12,02 / 39,47 ms** (moyenne/pire),
  composition **2,36 / 5,91 ms**, copie **4,45 / 12,51 ms**,
  lock **3,15 / 27,31 ms**, post **0,70 / 2,29 ms** ; présentation
  **8,30 / 34,06 ms**. Les scopes imbriqués ne s’additionnent pas.
- **413 MOVE, 797 samples historiques**, jusqu’à trois par événement ;
  intervalle de réception **16,76 ms moyen**. Queue → callback observé
  **6,00 / 39,32 ms**, attente mutex d’entrée **2,07 / 29,72 ms**.
- L’attente de lock est plus visible qu’avec la sonde à environ 30 Hz. Son lien
  causal avec la cadence de présentation reste à isoler ; aucune modification
  de synchronisation ni optimisation spéculative n’est ajoutée.

Cet extrait soutient la viabilité du raster. À cette étape, la reprise courte
et la confirmation du stylet/pression étaient encore attendues ; elles sont
maintenant consignées ci-dessous. Le pan pur complet reste non mesuré.

Le dernier CSV physique Debug corrigé, récupéré avant installation, contient un
geste mixte de 11,05 s à **11,59 FPS** et 125 frames/posts : référence historique
distincte, pas le même geste ni un pan isolé.

## Confirmation finale et pincement physique complet

L’utilisateur répond à la demande de pincement court puis de dessin au vrai stylet
avec épaisseur variable selon la pression : **« c’est fait et le rendu est niquel »**.
Ce retour confirme les essais demandés et le rendu satisfaisant. Le stylet et sa
pression sont validés par l’observation de l’opérateur ; la capture de geste ne
mesure pas elle-même des échantillons de pression du stylet.

`jalon13-profile-physical-optimized-pinch-short-01.csv`, PID 4334 :

- **5758 records, overflow=0**, Begin et End présents, durée **3,253 s**.
- `device=4`, **176 MOVE et 296 samples historiques** ; **169 Navigation**
  (Begin + 167 Update + End) enqueued, callbacks et traitements éditeur,
  **aucun événement Navigation perdu** et aucun regroupement d’updates dans
  les frames présentées.
- **89 frames / 89 posts**, mêmes surfaces 2160×1440 / 944×629.
  **30,15 FPS** entre première et dernière frame, **27,36 frames/s** sur toute
  la fenêtre du geste. Intervalle **33,16 / 149,39 ms** (moyenne/pire).
  Ces intervalles incluent les mouvements qui ne changent pas le palier affiché ;
  ils ne mesurent pas le débit maximal d’un zoom continu.
- Quatre updates changent le palier : leur frame coûte **8,75 / 9,56 ms**.
  Composition UI **1,65 / 2,70 ms** ; présentation **4,19 / 7,07 ms**, dont
  lock **0,37 / 0,48 ms**, copie **3,29 / 5,92 ms**, post **0,52 / 1,04 ms**.
- Queue → callback **0,150 / 0,409 ms**, sans accumulation anormale ; aucune
  perte/capture bloquée constatée dans la séquence et la reprise rapportée.

Dernier contrôle au repos, après les essais physiques : **1609 → 1609 ticks CPU
sur 10,057 s**, même PID 4334. Aucun crash/ANR ni échec de buffer observé dans le
journal final conservé. Capture écran `jalon13-optimized-physical-final.png`.
Le token du profiler a été vidé après collecte : aucune nouvelle capture armée.

**Décision : jalon 13 fonctionnellement validé sur la XP-Pen MDP1221**, avec
la variante raster optimisée, sur la base des essais cumulés et du retour
utilisateur. Cela ne transforme pas l’extrait de pan saturé en benchmark complet,
ni les confirmations utilisateur en mesures instrumentées du stylet.

## Interprétation et suite

Sur le pan contrôlé optimisé, la copie/scaling (**4,18 ms**, environ 44 % du travail
GUI) dépasse désormais la composition UI (**2,40 ms**, environ 26 %). Lock/post
cumulent environ 1,16 ms ; l’attente de queue/mutex a pratiquement disparu.
Le sprite n’est pas rerendu pendant ce pan établi. Begin est une pointe séparée.

**Le raster reste une piste viable ; EGL/GPU n’est pas justifié par ces mesures.**
L’extrait physique dépasse le seuil indicatif de 30 FPS et l’utilisateur
confirme le gain de fluidité. Le pincement court est maintenant complet, sans
overflow ; seul le pan pur intégral manque à la comparaison métrologique.
La prochaine cible CPU mesurée est la **copie/scaling plein framebuffer**, puis
la peinture du Begin, si une optimisation supplémentaire devient nécessaire.
Avant tout changement de copie, compléter le benchmark de pan pur et vérifier le contrat
ANativeWindow ; aucune de ces optimisations n’est implémentée ici.

Artefacts reproductibles : `jalon13-optimized-{pan,pinch}-comparison.json`, les
quatre CSV `jalon13-profile-optimized-{a,b}-gesture-{pan,in}.csv`, analyses détaillées,
flags/GN/révision/build ID et logs dans `android/build/`. Comparateur suivi dans
`android/tests/compare_gesture_profiles.py`. La configuration optimisée est
installée ; aucun EGL/GLES/Vulkan, zoom continu, insets ou geste nouveau ajouté.
