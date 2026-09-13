# Jalon 15 — diagnostic stylet avancé et interface tablette agrandie

13 septembre 2026. Base : jalons 8, 9, 12, 13 raster optimisé et 14 physiquement
validés. Ce jalon remplace la proposition IME du jalon 14 ; aucun chantier de
composition IME, clipboard ou GPU n’est ouvert.

## Inspection avant changement de comportement

- `laf/os/event.h` : pression flottante, PointerType et MouseButton ; aucun champ
  tilt/orientation. Boutons Left/Right/Middle/X1/X2, pas de capteur barrel dédié.
- `laf/os/pointer_type.h` : Pen et Eraser existants.
- `src/ui/message.h`, `src/ui/manager.cpp`, `src/app/ui/editor/glue.h` et
  `src/app/tools/pointer.h` : position/type/bouton/pression transmis, aucun axe
  d’inclinaison ni d’orientation.
- `src/app/tools/dynamics.h` : Static, Pressure, Velocity seulement.
  `tool_loop_manager.cpp` pilote taille, angle et gradient avec pression/vitesse.
  **Pas de chemin tilt réutilisable dans cette révision.** Pas de conversion
  vers la pression, de capteur partagé ou de nouveau moteur ajouté.
- Windows Wintab/Pointer, macOS et X11 transportent la pression, le type et les
  boutons dans ces mêmes abstractions ; aucune transmission tilt jusqu’aux
  dynamiques détectée.
- Android : pression déjà transportée sans courbe ; Eraser conservé lorsqu’exposé.
  Bouton gauche systématique pour le contact stylet, hover ignoré avant contact.
  Les échantillons physiques du jalon 8 montraient tilt 0,20944..0,68619 rad,
  orientation −2,87999..3,14159 rad, boutons 0 et HOVER_ENTER. Ils ne prouvent pas
  le comportement des boutons physiques et ne remplacent pas une nouvelle série.
- Densité actuelle : facteur density/320 limité pour préserver 480/640 pixels
  logiques minimum sur petit/grand axe. Scale LAF 2 et thème 1. Présentation
  nearest-neighbor et input partagent la géométrie du contenu du jalon 14.
- Politique prévue : préférence globale Android Default (100 %) / Large (112 %),
  sans retoucher les polices ni chaque widget. Valeur choisie conservée ; pas de
  bascule imposée à chaque lancement.

**Statut : UI Default/Large implémentée, construite, installée et testée par
injection. Diagnostic stylet borné disponible. Validation physique du jalon 15
en attente : aucun verdict de confort ou résultat de bouton/tilt/survol nouveau
n’est encore revendiqué.** Les fonctionnalités stylet dépendant de cette collecte
ne sont pas déclarées terminées.

## Partie A — possibilités réelles et collecte

### Tilt et orientation

Android expose `AMOTION_EVENT_AXIS_TILT` en radians, zéro pour une pointe
perpendiculaire à l’écran et π/2 pour un stylet couché. L’orientation décrit la
direction du stylet dans le plan de l’écran, en radians (domaine stylet −π..π).
Références : [MotionEvent](https://developer.android.com/reference/android/view/MotionEvent.html),
[axes et fonctions NDK](https://developer.android.com/ndk/reference/group/input).

La révision courante n’a **aucune unité attendue pour tilt dans Aseprite**, car
aucun champ ni capteur tilt n’existe dans Event → MouseMessage → tools::Pointer →
DynamicsOptions. L’option Angle des dynamiques utilise Pressure ou Velocity,
pas l’orientation du stylet. Aucune conversion tilt → angle de brosse n’est donc
implémentée. Les radians sont conservés tels quels dans les diagnostics ; les
degrés du résumé correspondent uniquement à `radians × 180/π`. Aucun min/max
observé n’est utilisé pour normaliser un axe ou modifier la pression.

| Donnée | Observation physique historique (jalon 8) | Nouvelle série jalon 15 |
|---|---|---|
| Tilt | 0,20944..0,68619 rad | En attente |
| Orientation | −2,87999..3,14159 rad | En attente |
| Boutons | 0x0 dans les points alors journalisés | Presses réelles à examiner |
| Eraser | Aucun TOOL_TYPE_ERASER observé | Matériel/essai à confirmer |
| Hover | HOVER_ENTER livré | Continuité et aperçu à vérifier physiquement |

Un effet visible piloté par tilt n’est pas testable avec les capteurs actuels.
Pencil et les autres outils gardent leurs sémantiques. Le chemin pression du
jalon 9 est inchangé, y compris les seuils utilisateur des dynamiques.

### Boutons, gomme et hover

Les bits Android standards sont `BUTTON_STYLUS_PRIMARY = 0x20` et
`BUTTON_STYLUS_SECONDARY = 0x40`. LAF fournit déjà Left/Right/Middle, et l’éditeur
possède ses comportements de clic droit ; toutefois aucune correspondance n’est
ajoutée tant que le comportement des boutons physiques n’est pas observé.
L’absence de bits dans un court relevé ancien ne prouve pas l’absence de bouton.

TOOL_TYPE_ERASER reste traduit en PointerType::Eraser lorsque livré, sans gomme
synthétique. Le hover stylet reste ignoré par le backend de contact à ce stade ;
la collecte et le retour utilisateur doivent établir le comportement réel avant
une correction ciblée. Aucun raccourci latéral ou comportement de survol nouveau
n’est inventé.

### Diagnostic borné

`laf/os/android/stylus_diagnostics.h` ajoute une observation sans modifier les
événements. Présente en Debug et rasterProfile, absente du Release normal :

- activation par nouveau jeton `debug.aseprite.stylus` ;
- durée maximale 30 s à partir du premier événement Pen/Eraser ;
- maximum 384 lignes `AsepriteStylus` par jeton ;
- MOVE/HOVER_MOVE échantillonnés à 10 Hz, transitions action/boutons conservées
  jusqu’au plafond ;
- temps monotone/temps MotionEvent, device/source/type/action/bits boutons,
  x/y/pression/tilt/orientation et taille d’historique ;
- lecture de propriété au plus une fois par seconde pendant les événements
  stylet ; aucun timer de réveil ni journalisation perpétuelle des MOVE.

Le helper a été vérifié via `InputProbe pen-fingers` sous un jeton distinct
`jalon15-injected-check` : 7 lignes, **device −1**, type STYLUS, pression 1 jusque
sur UP. L’analyseur reconnaît explicitement qu’aucun périphérique physique n’a
été observé. Cela vérifie le fonctionnement du diagnostic et le relâchement sans
pression nulle ; **ce n’est pas une mesure physique de tilt ou de bouton**.

Premier jeton physique proposé : `jalon15-stylus-01`, sans ligne physique obtenue
au moment du relevé. Jeton final armé, séparé de l’injection :
**`jalon15-stylus-physical-02`**. Protocole demandé : vertical, incliné dans les
quatre directions, variation de direction/pression, survol, chaque bouton en
survol/contact et gomme physique si présente. L’utilisateur doit également
préciser les commandes réellement présentes sur son stylet.

Récupération/analyse sans confondre une sortie d’erreur avec une capture :

```sh
adb -d logcat -d -s AsepriteStylus:I '*:S' > android/build/jalon15-physical-stylus.log
python3 android/tests/analyze_stylus_sample.py android/build/jalon15-physical-stylus.log \
  --token jalon15-stylus-physical-02 --output android/build/jalon15-physical-stylus-summary.json
```

L’analyseur rejette un jeton sans échantillon. Ses extrema sont ceux des points
échantillonnés, pas des limites calibrées du matériel. On ne déduit pas une
fréquence exhaustive de hover à partir de cet échantillonnage.

## Partie B — taille d’interface

### Préférence et formule

**Preferences → General → Tablet UI Size** :

- **Default (100%)** : comportement antérieur ;
- **Large (112%)** : multiplication globale de la densité effective par 1,12.

Stockage via l’infrastructure Preferences existante : `[general] android_ui_scale`.
Application avec Apply/OK, chargement au démarrage. Les installations neuves ou
sans valeur restent Default ; la XP-Pen a été explicitement passée à Large dans
l’UI pour ce jalon. Une valeur non reconnue retombe sur Default. Le choix n’est
pas écrasé au lancement. Le réglage est masqué sur les autres plateformes.

Pour une surface physique W×H, une densité D, un scale LAF entier S et le
multiplicateur M (1, 1,12 ou 1,20) :

```text
F = min((D / 320) × M,
        min(min(W,H) / (480 × S), max(W,H) / (640 × S)))
Lfull.x = S × max(1, floor(W / (F × S)))
Lfull.y = S × max(1, floor(H / (F × S)))
Lcontent.x = max(S, S × floor(Lfull.x × content.width  / W / S))
Lcontent.y = max(S, S × floor(Lfull.y × content.height / H / S))
UI = Lcontent / S
```

Les 480/640 sont la limite générale héritée du port, pas des cibles XP-Pen. La
limite est calculée sur la surface entière : ouvrir Gboard ne modifie pas la
taille apparente des contrôles. Aucun réglage système de densité n’est changé.

### Dimensions effectivement observées

| Mode | Framebuffer | Fenêtre LAF complète | Raster UI | UI avec IME 842 px |
|---|---|---|---|---|
| Default, M=1 | 2160×1440 | 1888×1258 | **944×629** | 944×261 |
| Large, M=1,12 | 2160×1440 | 1686×1124 | **843×562** | **843×233** |

Densité Android 366 dpi, Screen Scaling 200 % (S=2), UI Element Scaling 100 %
(thème) inchangés. L’agrandissement mesuré est d’environ **12 %** sur chaque axe,
sous réserve de l’arrondi des pixels. Le raster composé passe de 593 776 à
473 766 pixels (−20,2 %), tandis que le framebuffer reste identique.

### Présentation et coordonnées

La présentation raster générique existante accepte directement la nouvelle
surface ; aucune modification de son algorithme n’a été nécessaire. Pixels
nearest-neighbor, sans interpolation, une présentation par redraw conservée.

Pour chaque position physique p, `SystemAndroid::toDisplayPosition` retire
l’origine du contenu puis calcule `floor(p × Lcontent / contentExtent)` ;
`InputAndroid::pointer` divise avec floor par S. C’est le même échantillon source
que celui choisi pour le pixel affiché. La fonction commune sert à Touch et Pen.
Le midpoint pinch/pan utilise ce même mapping ; la distance entre doigts reste
mesurée en pixels physiques, donc son rapport ne dépend pas de la taille UI.
Aucun offset ni coordonnée de résolution particulière n’est introduit dans le
code produit.

## Validation réalisée sur la tablette

| Contrôle | Résultat automatisé / inspecté |
|---|---|
| Default → Large → Default → Large | Bascule à chaud réussie, tailles natives/raster conformes |
| Persistance | `android_ui_scale = 112` sauvegardé ; Home après relancement toujours agrandi |
| Home, menus, toolbar, timeline, statut | Captures inspectées, agrandissement modéré, pas de découpe durable |
| Menu File et commandes | Taps injectés aux positions affichées correctement ciblés |
| Mapping sur toute la largeur/hauteur, bords du contenu | Tests hôte du ratio et du retour IME réussis |
| Pen + doigt secondaire | Injection à (1300,780) → (1350,800), trait à la position attendue ; doigt secondaire ignoré |
| Fin Pen avec pression non nulle | UP=1 dans la sonde, relâchement et Undo fonctionnels |
| Save As + Gboard | Fenêtre 843×233, champ, liste et boutons utilisables sans redesign |
| Saisie Gboard | « grand » saisi via touches Gboard injectées |
| Sauvegarde privée | `grand.aseprite`, 1800 octets, réouvert ensuite |
| Fermeture clavier | Retour au raster 843×562 |
| SAF Open External → Cancel | Sélecteur lancé, retour immersif correct |
| Home → retour | Surface restituée avec Large |
| Barres transitoires | Swipe de bord révèle, masquage naturel et dimensions préservées |
| Pression physique / alignement réel | Validation antérieure acquise, nouvelle validation encore attendue |
| Confort physique et fluidité perçue | En attente du verdict utilisateur pour Large |

Le trait de la sonde a été annulé ; les documents antérieurs ont été sauvegardés
avant installation. La copie `grand.aseprite` sert aux essais physiques à venir.
Les tests géométriques ne remplacent pas la vérification physique des coins du
canvas, du midpoint et de l’alignement de la pointe.

## Régression de performance — raster optimisé

Même APK `rasterProfile`, même document de profilage 256×256, centre physique
(1140,600), 40 MOVE, reset zoom touche 1 / recentrage Shift+Z. Deux pans et un
pinch par taille. Les dimensions UI diffèrent volontairement, pas le framebuffer
ou le scénario. Captures complètes, tous les Begin/Navigation/End arrivent à la
GUI et à l’éditeur, une seule présentation par redraw, aucun update perdu.

Pan hors Begin, durées **moyenne / pire en ms**, 80 frames par taille :

| Étape | Default 944×629 | Large 843×562 |
|---|---:|---:|
| Cycle GUI | 10.154 / 15.874 | 8.607 / 14.138 |
| Composition UI | 2.394 / 3.590 | 1.801 / 2.684 |
| Editor paint | 0.037 / 0.135 | 0.028 / 0.060 |
| Copie/scaling | 4.877 / 8.187 | 4.232 / 7.505 |
| Lock | 0.546 / 0.938 | 0.520 / 0.971 |
| Unlock/post | 0.719 / 1.211 | 0.679 / 1.275 |
| Présentation | 6.157 / 10.136 | 5.445 / 9.456 |

Détail par répétition (conservé pour rendre la variabilité visible) :

| Mode / essai | GUI moyenne ms | Copie moyenne ms | FPS de présentation |
|---|---:|---:|---:|
| default a | 6.630 | 2.926 | 29.47 |
| default b | 13.678 | 6.828 | 29.22 |
| large a | 7.682 | 3.734 | 29.05 |
| large b | 9.532 | 4.731 | 29.67 |

L’ordre était Default A, Large A, Large B, Default B. La première augmentation
apparente du coût Large a motivé cette répétition : Default varie lui aussi
fortement. Ces mesures ne permettent pas d’attribuer une différence précise au
seul facteur de taille, faute de contrôle de fréquence/température CPU. Elles
ne montrent **pas de ralentissement substantiel propre à Large**, ni de perte
de cadence : 41 frames par pan, 40 mises à jour visuelles, sans fusion, à la
cadence ~29 FPS de la sonde. Aucune réduction vers 110 % ni optimisation
spéculative n’est retenue sur cette base. La fluidité physique reste à confirmer.

- Default : Begin pan 32.43 ms ; frame de changement de zoom 10.46 ms, composition 1.84 ms, copie 2.88 ms.
- Large : Begin pan 19.29 ms ; frame de changement de zoom 8.06 ms, composition 1.34 ms, copie 2.83 ms.

Pinch injecté : trois redraws par geste, dont un changement effectif du niveau
prédéfini. Les 40 updates sont transmis ; ce faible nombre de redraws n’est pas
une limite de débit du raster. Aucun zoom continu ajouté.

Repos : **1 tick / 10,08 s**, horloge 100 Hz, soit environ **0,10 % d’un cœur**.
Aucun crash/ANR de l’application, échec lock/post, rejet de copie ou exception du
bridge d’insets dans le relevé de cette session. Le rendu reste CPU/raster ;
aucun motif de démarrer GPU/EGL ici.

## Build, tests et fichiers

Construction réussie : `android/gradlew -p android :app:assembleRasterProfile
--console=plain --max-workers=4` (40 s après les modifications de préférence).
Aseprite/LAF `-O2 -g -DNDEBUG`, instrumentation de profilage explicite,
`--target=aarch64-none-linux-android26`, `SK_SUPPORT_GPU=0`. Skia optimisé inchangé
par rapport au jalon 13 (`is_debug=false`, `-O3`, même révision et args GN).

APK : `android/app/build/outputs/apk/rasterProfile/app-rasterProfile.apk`.
SHA-256 : `68f1df4759451f26e2b0bc33d99710d73df38f525882ee3b2e9af0d2803e4ddf`.
Les flags des fichiers concernés sont archivés dans `jalon15-build-evidence.json`.

Tests hôte **4/4 réussis** : queue, raster, display_metrics et pression. Les cas
ajoutés couvrent Large, le retour Default, valeur inconnue, minimum de surface,
IME/restauration et égalité entre pixel rendu et cible sur tous les x/y de la
surface de référence. Le helper de capture est vérifié par injection distincte,
et son analyseur rejette les logs sans échantillon. Pas de test physique simulé.

| Fichier | Rôle dans ce jalon |
|---|---|
| `laf/os/android/stylus_diagnostics.h` | Nouveau diagnostic stylet opt-in borné |
| `laf/os/android/input.h`, `input.cpp` | Branchement observation, comportement de contact inchangé |
| `laf/os/android/display_metrics.h` | Multiplicateur global avec limite de dimensions héritée |
| `laf/os/android/system.h`, `system.cpp` | Même facteur pour géométrie et entrée |
| `laf/os/android/tests/display_metrics_test.cpp` | Contrats Large/Default/IME/mapping |
| `data/pref.xml` | Préférence persistante `android_ui_scale` |
| `data/widgets/options.xml`, `data/strings/en.ini` | Choix Android Default/Large |
| `src/app/modules/gui.cpp` | Chargement de la préférence avant création de fenêtre |
| `src/app/commands/cmd_options.cpp` | Application à chaud par les préférences existantes |
| `android/tests/analyze_stylus_sample.py` | Résumé des axes/actions/bits observés |
| `android/README.md`, `android/tests/README.md` | Usage du réglage et du diagnostic |
| `ANDROID_ARM64_JALON_15_COMPTE_RENDU.md` | Inspection, mesures et état de validation |

Aucun changement des abstractions partagées Event/Pointer/Dynamics, de courbe de
pression, de moteur de brosse ou du renderer dans ce jalon. Les modifications
des jalons précédents présentes dans le workspace sont conservées et ne sont pas
comptées comme nouvelles. Aucun commit/publication effectué.

## Captures et preuves

Les captures originales sont dans `android/build/` :

- [Default](android/build/jalon15-ui-default.png) et [Large](android/build/jalon15-ui-large.png) ;
- [préférences Default](android/build/jalon15-options-default.png) et
  [préférences Large](android/build/jalon15-options-large.png) ;
- [Save As/Gboard en Large](android/build/jalon15-large-ime.png),
  [texte Gboard](android/build/jalon15-large-gboard-text.png),
  [clavier fermé](android/build/jalon15-large-ime-closed.png) ;
- [menu File](android/build/jalon15-large-menu.png),
  [SAF](android/build/jalon15-large-saf.png),
  [retour SAF](android/build/jalon15-large-saf-return.png) ;
- [Home → retour](android/build/jalon15-large-home-return.png),
  [Home après relancement](android/build/jalon15-large-home-relaunch.png) ;
- [barres révélées](android/build/jalon15-large-bars-revealed.png),
  [barres remasquées](android/build/jalon15-large-bars-hidden.png) ;
- [avant Pen injecté](android/build/jalon15-injected-pen-before.png),
  [après Pen injecté](android/build/jalon15-injected-pen-after.png), trait annulé ensuite.

Preuves supplémentaires : `jalon15-ui-performance.json`, CSV
`jalon13-profile-jalon15-{default,large}-{a,b}-gesture-pan.csv` et
`jalon13-profile-jalon15-{default,large}-a-gesture-in.csv`, scripts de suite,
`jalon15-host-tests.log`, `jalon15-idle.json`, `jalon15-device-log.txt`,
`jalon15-device-window.txt`, `jalon15-large-preferences.ini` et
`jalon15-injected-stylus-summary.json`.

## Limites et suite

Le confort de la taille UI 120 % est accepté par l’utilisateur (voir le retour
ci-dessous). Les nouvelles plages physiques tilt/orientation, bits des boutons,
gomme et suivi
du survol doivent encore être relevés. Sans cela, le jalon complet **reste ouvert**.
Une correction hover ou un mapping de bouton ne sera décidé que sur cette preuve.
Les dynamiques tilt ne sont pas disponibles dans cette version d’Aseprite.

La prochaine action est cette validation physique, pas un autre chantier. Après
clôture seulement, prochain jalon proposé : améliorer la composition IME et la
cohérence curseur/sélection dans le contrat LAF existant. Aucune implémentation
IME/clipboard, GPU, palm rejection ou UI spécifique par widget n’est commencée.

## Ajustement demandé après essai — 120 %

Retour utilisateur : « Un peu plus grande encore » après la version 112 %.
Ce retour précise que 112 % reste trop petit ; il ne constitue pas une validation
des autres essais physiques en attente.

Ajout d’un troisième choix **Larger (120%)**, conservant Default (100%) et Large
(112%). C’est environ **7,1 % de plus que 112 %**, sans passage au scale entier 3.
Même politique globale, mêmes insets et mapping, valeur persistante 120.
Dimensions calculées : **786×524** en plein écran, **786×217** avec Gboard
(inset physique 842 px). Tests de mapping aux coordonnées physiques et tests
hôte conservés, sans modification du comportement stylet.

Version 120 % construite et installée ; choix Larger sélectionné dans les
préférences et `android_ui_scale = 120` vérifié sur disque. Raster **786×524**
confirmé par les profils, **786×217** avec IME par les traces. Save As + Gboard
inspectés : champ et boutons accessibles, fermeture par tap sur Cancel puis
retour plein écran correct. Aucun changement de dialogue nécessaire.

Tests hôte 4/4 réussis. Contrôle injecté 40 MOVE pan + pinch : captures complètes,
une présentation par redraw. Pan : GUI **11.92 ms** en moyenne
(pire 13.38), composition **2.03 ms**,
copie **6.42 ms**, cadence **28.88 FPS**
imposée par la sonde. Frame de changement de zoom : 7.94 ms.
Aucune régression de cadence détectée dans ce contrôle court.

Captures : [UI 120 %](android/build/jalon15-ui-larger.png),
[Save As/Gboard 120 %](android/build/jalon15-120-ime.png),
[état final sur la copie de test](android/build/jalon15-120-final.png).
Preuves : `jalon15-120-build.log`, `jalon15-120-tests.log`,
`jalon15-120-performance.json`, `jalon15-120-preferences.ini`, `jalon15-120-log.txt`.
SHA-256 du nouvel APK : `6676e4711636e9ace34a58e9617ab40f38e106cf4bba3e1ab2aff7b0471d1199`.
Retour utilisateur après installation de 120 % : « Niquel commit moi tout ça et
push ». Le confort de cette taille est donc accepté. Ce retour ne remplace pas
les mesures physiques stylet encore en attente ; le jalon complet reste ouvert.
