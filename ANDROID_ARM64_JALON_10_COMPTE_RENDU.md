# Jalon 10 — documents dans le stockage privé Android

12–13 septembre 2026. Références : rapports des jalons précédents, surtout
[jalon 9](ANDROID_ARM64_JALON_9_COMPTE_RENDU.md), et architecture de
[l’audit](ANDROID_ARM64_AUDIT.md). Base Aseprite `2f6965390`, LAF `433f819`.

**Sauvegarde, réouverture, modification, redémarrage et export PNG validés sur la
XP-Pen MDP1221.** Les fichiers utilisent le format Aseprite existant et des chemins
POSIX dans le stockage privé. Aucun SAF, fournisseur de documents, permission de
stockage public ou format Android spécifique n’a été ajouté.

Les manipulations et traits de ce jalon ont été réalisés **par événements adb
dans la véritable interface de l’application sur la tablette**, pas par une
commande de sauvegarde batch ni par fabrication externe du document. Ce jalon
ne revendique pas de nouvelle séance de dessin au doigt/stylet physique ; cette
validation appartient aux jalons 8 et 9.

## Inspection du chemin existant

- `src/app/commands/cmd_save_file.cpp` : Save utilise `saveAsDialog()` pour un
  document sans fichier, puis `saveDocumentInBackground()` ; Save As utilise le
  même dialogue. Après succès, `markAsSaved()`, nom du document et Recent Files
  sont mis à jour. Erreur/annulation conservent l’état nécessitant une sauvegarde.
- `src/app/file_selector.cpp`, `src/app/ui/file_selector.cpp` : le sélecteur
  interne choisit le dossier mémorisé, ou `base::get_user_docs_folder()`.
  Les noms relatifs sont joints au dossier choisi. Le dossier est mémorisé après
  validation ; les fichiers inexistants et les confirmations d’écrasement sont
  traités par le code existant.
- `src/app/file_system.cpp` : parcourt les fichiers POSIX et utilise également
  le helper de dossier utilisateur pour le point de départ.
- `src/app/commands/cmd_open_file.cpp` : sélecteur → FileOp → OpenFileJob →
  postLoad → document dans le contexte UI et ajout aux fichiers récents.
- `src/app/ui/doc_view.cpp` : le dialogue Save / Don't Save / Cancel boucle sur
  l’état modifié. Save réutilise la commande normale ; Cancel garde la vue ouverte.
- `src/app/recent_files.cpp` : conserve les chemins normalisés et les enregistre
  dans la configuration à la destruction. Aucun changement nécessaire.
- `src/app/file/file.cpp`, `src/dio/stdio.cpp` et les formats ASE/PNG : chaîne
  existante d’entrées/sorties ordinaires, conservée.
- `laf/base/fs_unix.h` : mkdir/stat/rename et autres helpers POSIX disponibles ;
  seul le choix du dossier des documents avait encore le repli HOME puis `/`.
- `src/main/android_main.cpp` : extrait déjà `runtime/` et configure `user/`
  depuis `internalDataPath`, avant `app_main()`.
- `src/app/crash/data_recovery.cpp`, `backup_observer.cpp` : récupération séparée
  sous `user/sessions`, inchangée. Elle ne remplace pas la sauvegarde explicite.

## Implémentation et fichiers

Modifiés :

1. `src/main/android_main.cpp` : crée automatiquement
   `<internalDataPath>/documents`, publie `LAF_ANDROID_DOCUMENTS_DIR` avant
   `app_main()` et journalise ce dossier en Debug. Les erreurs de création ou
   d’initialisation remontent dans le chemin d’erreur de démarrage existant.
2. `laf/base/fs_unix.h` : branche `LAF_ANDROID` dans `get_user_docs_folder()`
   lisant cette variable ; aucun HOME de desktop ni chemin d’appareil inventé.
   Les autres plateformes gardent leur comportement.
3. `src/app/file_selector.cpp` : sur Android, le dossier initial `/` hérité du
   bootstrap ou un dossier devenu inexistant revient au dossier des documents.
   Les autres dossiers valides choisis par l’utilisateur sont conservés.
4. `laf/os/android/input.cpp` : ajoute `AKEYCODE_MINUS → kKeyMinus` et
   `AKEYCODE_PERIOD → kKeyStop`. Les caractères continuent à provenir du
   KeyCharacterMap existant ; aucune IME ni nouvelle traduction générale.
5. `android/README.md` et référence du sous-module `laf`.

Créé : `ANDROID_ARM64_JALON_10_COMPTE_RENDU.md`, ce rapport.

Les moteurs de documents, le sélecteur commun, les dialogues, les formats,
les récents, la récupération, le rendu et les dynamiques ne sont pas réécrits.
Le manifeste est inchangé et ne demande aucune permission de stockage.

## Dossiers et fichiers observés

Racine dérivée au démarrage sur cet appareil :
`/data/user/0/org.aseprite.android/files/`.

| Usage | Sous-dossier |
|---|---|
| Ressources extraites | `runtime/` |
| Préférences, palettes utilisateur, récupération | `user/` |
| Documents | **`documents/`** |

Chemin exact du document principal :
**`/data/user/0/org.aseprite.android/files/documents/test-jalon10.aseprite`**.
Ce chemin observé n’est pas codé en dur dans l’implémentation.

| Fichier privé | Taille vérifiée par adb/run-as |
|---|---|
| `test-jalon10.aseprite`, première sauvegarde | 1 471 octets |
| `test-jalon10.aseprite`, après modification sauvegardée | **1 802 octets** |
| `test-jalon10-copy.aseprite`, Save As | 1 802 octets |
| `test-jalon10.png`, export | **2 374 octets** |

L’écriture réelle valide le caractère accessible en écriture du dossier.
Les fichiers sont observés en mode `-rw-------`, appartenant à l’UID de l’app.

## Build, installation et corrections réellement rencontrées

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4 \
  > android/build/jalon10-assemble-2.log 2>&1
```

APK : `android/app/build/outputs/apk/debug/app-debug.apk`.

- Groupe dossier privé : **BUILD SUCCESSFUL in 5s**, log `jalon10-assemble-1.log`.
- Essai réel de nom : `test-jalon10.aseprite` devenait `testjalon10aseprite` dans
  le champ du dialogue. Cause : `scancode()` ne reconnaissait ni tiret ni point.
  Corrigé par les deux mappings Android ci-dessus.
- Groupe clavier : **BUILD SUCCESSFUL in 3s**, 38 tâches, 7 exécutées, 31 à jour.
  Aucune erreur de compilation/lien restante. Le nom exact est désormais visible
  dans le sélecteur puis sur disque.
- Réinstallation finale : `Performing Streamed Install`, **Success**.
- Lancement final de validation : `Status: ok`, `COLD`, `TotalTime: 1098`,
  `WaitTime: 1103` ms ; processus **9905**.

```bash
/home/golden/Android/Sdk/platform-tools/adb -d install -r \
  android/app/build/outputs/apk/debug/app-debug.apk
/home/golden/Android/Sdk/platform-tools/adb -d shell am force-stop org.aseprite.android
/home/golden/Android/Sdk/platform-tools/adb -d shell am start -W \
  -n org.aseprite.android/android.app.NativeActivity
```

Aucun nouveau test unitaire miroir de l’implémentation : la vérification porte
sur les véritables opérations UI, leurs fichiers, les réouvertures et le redémarrage.

## Résultats des opérations UI

Document créé par Ctrl+N : **256×256, RGBA, transparent, une image**. Crayon rond
12 px ; trois traits rouges sur Layer 1, puis Shift+N et trait bleu sur Layer 2.
La timeline montre bien les deux calques.

| Opération | Résultat constaté |
|---|---|
| Save sur document neuf | Sélecteur dans documents, nom saisi, bouton OK : statut « saved », marque de modification disparue, fichier de 1 471 octets |
| Fermer puis Open | Ctrl+W ferme sans avertissement ; Ctrl+O et sélection du nom restituent le document, deux calques, couleurs et damier |
| Éditer après réouverture | Nouveau trait vert dessiné normalement ; marque de modification et dialogue de fermeture apparaissent |
| Cancel | Garde la vue et le trait vert ouverts ; fichier strictement identique à la sauvegarde initiale |
| Don't Save | Ferme la vue ; fichier strictement identique ; la réouverture ne contient pas le trait abandonné |
| Save depuis le dialogue de fermeture | Enregistre le nouveau trait vert, puis ferme ; fichier de 1 802 octets et trait retrouvé à la réouverture |
| Save As | Ctrl+Shift+S enregistre `test-jalon10-copy.aseprite`, document non modifié ensuite |
| Save sur fichier associé | Ctrl+S après Home/retour réussit, puis fermeture sans avertissement |
| Export PNG | Export File, 100 %, Canvas, Visible layers, All frames ; avertissement normal de perte des calques accepté ; fichier PNG créé |

Les contenus ASE n’ont pas été reconstruits par un script. Une lecture de leur
en-tête valide seulement les preuves : magic `0xA5E0`, une image, 256×256,
32 bits et taille déclarée égale à la taille du fichier.

Le PNG a été extrait par `run-as`, identifié **256×256 sRGBA**, puis inspecté
visuellement : les traits rouges, bleu et vert sont présents. Son pixel de coin
est RGBA `(0,0,0,0)`, confirmant une zone transparente. Les calques distincts
restent dans l’ASE ; le PNG est naturellement aplati. Aucun fournisseur Android
ni partage n’est utilisé pour cet export.

## Cycle de vie et persistance après nouveau processus

Home → retour via lancement de l’activité existante : **HOT**, 114 ms.
Logcat observe explicitement `Native window destroyed`, référence libérée,
puis nouvelle fenêtre **2160×1440**, format demandé RGBA8888. Le document reste
visible, puis sauvegardable et fermable normalement.

Ensuite, File → Exit ferme Aseprite normalement :

```text
09-13 00:03:00.602  9905  9953 I Aseprite: Aseprite app_main returned: 0
09-13 00:03:01.090  9905  9905 I Aseprite: Android activity destroyed; UI thread joined
```

Une fois l’activité fermée, `adb shell am kill org.aseprite.android` retire le
processus éventuellement gardé en cache. Le lancement suivant est **COLD**,
883 ms, nouveau PID **10479**. La sauvegarde présente le même SHA-256 avant et
après ce changement de processus :

```text
5548991f195d42949573ddf3d9a59ec5a97d829a5e51010fcf2c451e3d1b1507
```

Home affiche `test-jalon10.aseprite`, le PNG et la copie dans Recent Files.
Un tap sur le document principal rouvre le dessin enregistré avec ses deux
calques, le trait vert ajouté et la transparence. Capture inspectée, pas seulement
existence du fichier. État final : `RESUMED`, `finishing=false`, document ouvert.

Aucun crash, assertion fatale ou blocage de capture observé pendant ces opérations.
Les DOWN/UP de dessin passent par la queue existante ; les dialogues sont
utilisables après dessin et réouverture.

## Captures et diagnostics conservés

Tous les chemins suivants sont relatifs à `android/build/` et ignorés par Git :

- `jalon10-before-save.png`, `jalon10-saved.png` : avant/après première sauvegarde.
- `jalon10-save-selector.png` : nom exact et dossier documents.
- `jalon10-reopened.png` : première réouverture.
- `jalon10-unsaved-dialog.png`, `jalon10-cancel-keeps-edit.png`,
  `jalon10-discard-closed.png`, `jalon10-after-discard-reopen.png`.
- `jalon10-save-on-close.png`, `jalon10-edited-reopened.png`.
- `jalon10-save-as-copy.png`.
- `jalon10-export-dialog.png`, `jalon10-export.png` (véritable PNG exporté).
- `jalon10-home-return.png`, `jalon10-restarted-home.png`,
  **`jalon10-reopened-after-restart.png`**.
- `jalon10-logcat.txt` : cycle de vie et diagnostic Debug du dossier.
- `jalon10-native-app.log`, `jalon10-restart-app.log` : logs FILE existants,
  chemins Save/Load, sans ajout de logs par lecture/écriture.
- `jalon10-files.txt`, `jalon10-final-activity.txt`.
- `jalon10-initial.aseprite`, `jalon10-final.aseprite` : copies des fichiers réels
  pour contrôle, respectivement avant et après modification sauvegardée.

Exemples de commandes de vérification :

```bash
aseprite_adb=/home/golden/Android/Sdk/platform-tools/adb
"$aseprite_adb" -d shell run-as org.aseprite.android ls -l files/documents
"$aseprite_adb" -d shell run-as org.aseprite.android sha256sum \
  files/documents/test-jalon10.aseprite
"$aseprite_adb" -d exec-out run-as org.aseprite.android \
  cat files/documents/test-jalon10.png > android/build/jalon10-export.png
"$aseprite_adb" -d exec-out screencap -p > android/build/jalon10-reopened-after-restart.png
```

## Limites exactes et prochain jalon

**Aucun blocage de persistance privée restant observé.** La validation ne couvre
pas une interruption au milieu d’une écriture ou un stockage plein ; les chemins
d’erreur de sauvegarde communs sont conservés, sans nouvelle promesse d’atomicité.

Deux limites d’interface préexistantes restent concrètes :

- Les boutons du sélecteur initialement en bas étaient dans une zone interceptée
  par les barres système. Le glissement normal de sa barre de titre vers le haut
  a rendu OK tappable, et la position du dialogue est mémorisée. Entrée permet
  également de valider. Aucun inset ni widget n’est redessiné dans ce jalon.
- Saisir un nom personnalisé nécessite encore des événements de clavier matériel
  (adb pendant cette validation) ; aucune IME ni clavier logiciel n’existe encore.

Prochain jalon recommandé : **import/export SAF explicite autour des fichiers de
travail privés**, avec choix du fournisseur, copie de flux et traitement des
annulations/erreurs. Les URI des fournisseurs ne devront pas être traitées comme
des chemins POSIX. Ce sera une implémentation séparée : aucun SAF n’est commencé ici.

## Commits

- LAF `4850917` : dossier des documents Android.
- Aseprite `2308eeabc` : création du dossier et choix initial du sélecteur.
- LAF `39c6714` : ponctuation nécessaire aux noms de fichiers.
- Un commit Aseprite final référence ce LAF et publie ce rapport avec la validation.

Branches `android-port`, forks GitHub `Golden76z/laf` et `Golden76z/aseprite`.
