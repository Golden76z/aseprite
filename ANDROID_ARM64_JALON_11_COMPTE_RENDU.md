# Jalon 11 — import et export Android SAF

13 septembre 2026. Base : Aseprite `d25041bca`, LAF `39c6714`, rapports
précédents et surtout [jalon 10](ANDROID_ARM64_JALON_10_COMPTE_RENDU.md).

**Import ASEPRITE/PNG, export ASEPRITE/PNG, annulation et réouverture après
redémarrage validés sur la XP-Pen MDP1221.** Deux fournisseurs Android ont été
utilisés. Les captures ont été inspectées : contenu, transparence et calques
ASEPRITE sont conservés. Les manipulations passent par les véritables interfaces
Aseprite et DocumentsUI, pilotées par adb ; aucune nouvelle validation physique
du stylet ou de la pression n’est revendiquée dans ce jalon.

## Inspection et point d’intégration

- `src/main/android_main.cpp` possède NativeActivity et son thread GUI dédié.
  Le thread Android reçoit les callbacks ; `app_main()` et l’UI tournent sur
  le thread GUI. Le démarrage crée déjà `documents/`, `runtime/` et `user/`.
  La destruction joint le thread GUI après sa demande de fermeture.
- `laf/os/android/input.cpp` emploie déjà JNI pour KeyCharacterMap.
  LAF possède `os::Event::Callback` et sa file synchronisée, utilisés pour
  ramener les résultats SAF au thread GUI sans appeler les widgets depuis Java.
- `src/app/commands/cmd_open_file.cpp` accepte un paramètre `filename` POSIX,
  puis utilise FileOp, OpenFileJob, postLoad et les récents. C’est le point de
  raccordement après la copie d’un import.
- `src/app/commands/cmd_save_file.cpp` et son header gèrent Save/Save As et
  l’état enregistré du document. L’export externe utilise directement
  `FileOp::createSaveDocumentOperation()` et `Job`, sans appeler `markAsSaved()`.
- `src/app/file/file.cpp`, `src/app/file/file.h`, les encodeurs ASE/PNG,
  le sélecteur interne et `src/app/recent_files.cpp` continuent à recevoir des
  chemins ordinaires. Aucune URI n’est transmise comme nom de document.
- `src/app/app_menus.cpp` permet d’ajouter les trois entrées à File uniquement
  sur Android, sans remplacer le menu Open/Save privé.

## Fichiers créés et modifiés

Créés :

| Fichier | Rôle |
|---|---|
| `android/app/src/main/java/org/aseprite/android/SafBridge.java` | Fragment sans vue, intents, résultats, permissions, noms et copies de descripteurs |
| `src/app/android/saf_bridge.h` | Interface C++ du pont asynchrone |
| `src/app/android/saf_bridge.cpp` | Chargement de classe, RegisterNatives, tickets/générations et callbacks LAF |
| `src/app/android/saf_commands.cpp` | Commandes d’import et d’export utilisant OpenFile/FileOp existants |
| `ANDROID_ARM64_JALON_11_COMPTE_RENDU.md` | Ce rapport |

Modifiés :

| Fichier | Modification |
|---|---|
| `android/app/build.gradle.kts` | Génération BuildConfig pour les diagnostics Debug Java |
| `android/app/src/main/AndroidManifest.xml` | `hasCode=true` pour le helper Java ; NativeActivity reste l’Activity principale |
| `src/main/android_main.cpp` | Attache/détache le pont SAF avec l’Activity |
| `src/app/CMakeLists.txt` | Sources du pont et commandes uniquement dans la branche Android |
| `src/app/commands/commands_list.h` | Trois commandes gardées par LAF_ANDROID |
| `src/app/app_menus.cpp` | Trois entrées Android à la fin du menu File |
| `data/strings/en.ini` | Déclarations nécessaires à la génération des identifiants de commandes |
| `android/README.md` | Utilisation et sémantique des copies privées/externes |

Aucun changement du sous-module LAF, des formats, de la pression, du rendu,
de l’échelle, de l’IME ou des gestes. Le manifeste compilé ne demande **aucune
permission**, vérifié avec `aapt dump permissions`.

## Pont Android et JNI

NativeActivity conserve son nom et son point d’entrée natif. Un
`android.app.Fragment` sans vue reçoit `onActivityResult()` ; il ne remplace
pas l’Activity. Ce choix évite d’introduire une Activity AndroidX pour ce jalon.
L’API Fragment du framework est dépréciée : cette dette est explicite.
Voir la [référence Android Fragment](https://developer.android.com/reference/android/app/Fragment.html).

Flux d’import :

```text
File → Open External → requestSaf sur le thread GUI
→ JNI → runOnUiThread → ACTION_OPEN_DOCUMENT
→ Fragment.onActivityResult → worker Java
→ ContentResolver.openFileDescriptor(uri, "r") → fichier privé
→ JNI complete → os::Event::Callback → OpenFile(filename POSIX)
```

Flux d’export :

```text
File → Export External ASEPRITE / PNG (current frame)
→ FileOpROI + encodeur existant dans un Job → fichier privé temporaire
→ JNI → ACTION_CREATE_DOCUMENT + EXTRA_TITLE
→ worker Java → ContentResolver.openFileDescriptor(uri, "wt")
→ copie des octets → fermeture du flux → callback LAF → résultat dans l’UI
```

Les URI restent entièrement en Java. Seuls ticket, statut, chemin privé et
message traversent le retour JNI. Les chaînes de chemins transitent en octets
UTF-8 pour éviter les différences du modified UTF-8 JNI.

Une seule requête SAF est active à la fois. Les copies utilisent un executor,
pas le thread Android principal. Une génération native invalide les callbacks
d’une Activity détruite. Le Fragment demande l’annulation de la copie et arrête
son executor à sa destruction ; aucun résultat ancien ne réouvre un document
dans une nouvelle instance. Un résultat restauré sans ticket est ignoré : il
n’y a pas de reprise automatique d’une opération après destruction du processus.

## Noms, MIME, permissions et copies de travail

| Opération | MIME |
|---|---|
| Open External | `*/*` avec CATEGORY_OPENABLE, pour ne pas exclure les ASE classés différemment selon le fournisseur |
| Export ASEPRITE | `application/octet-stream` |
| Export PNG | `image/png` |

Le moteur Aseprite valide/décode le fichier sélectionné ; le helper Java ne
parse aucun format. L’export ASE conserve les calques et les frames ; PNG
exporte seulement la frame courante, avec les avertissements habituels du moteur.

`OpenableColumns.DISPLAY_NAME` fournit le nom. L’import crée
`<filesDir>/documents/import-<UUID>/<nom>` ; slash, antislash et NUL sont remplacés,
les noms vides ou `.`/`..` reçoivent un nom de secours. Les doublons n’écrasent
aucun document. Une copie partielle est synchronisée puis renommée localement
avant OpenFile. Le nom retourné par le fournisseur est utilisé dans le message
de résultat, même s’il diffère du titre proposé.

L’export encode dans `<documents>/.saf-export-XXXXXX/`, puis supprime cette copie
temporaire à la fin ou à l’annulation. Il exporte un instantané ; aucun pointeur
vers le document éditable n’est conservé en attendant le fournisseur.

**Option B : Save enregistre uniquement la copie privée.** Pour écrire dehors,
utiliser de nouveau Export External. Un export externe ne change ni le nom privé
du document, ni son état modifié. Les récents ne contiennent que les copies POSIX.
La configuration observée après arrêt normal contient les chemins `import-…`
et aucune URI `content://`.

L’import demande READ, WRITE et PERSISTABLE. Au retour, seuls les bits READ/WRITE
réellement accordés sont passés à `takePersistableUriPermission`, et seulement si
PERSISTABLE a été offert. Un refus de persistance laisse poursuivre la copie avec
le droit temporaire. Les deux fournisseurs testés ont accepté `flags=3`.
Ces droits ne constituent pas une association de sauvegarde automatique avec
l’original. Comportement conforme aux
[règles SAF et permissions persistantes Android](https://developer.android.com/training/data-storage/shared/documents-files).

## Compilation et installation

Commande exacte depuis la racine :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4
```

Dernier build de code : **BUILD SUCCESSFUL in 3s**, 40 tâches, 7 exécutées,
33 à jour. Journal : `android/build/jalon11-assemble-6.log`. Aucune erreur
restante de compilation ou d’édition de liens.

APK : **`android/app/build/outputs/apk/debug/app-debug.apk`**.

```bash
/home/golden/Android/Sdk/platform-tools/adb -d install -r android/app/build/outputs/apk/debug/app-debug.apk
/home/golden/Android/Sdk/platform-tools/adb -d shell am start -W -n org.aseprite.android/android.app.NativeActivity
```

Installation : **Success**. Appareil autorisé : XP-Pen MDP1221,
`XCD1205AF825A05168`, Android API 34, arm64-v8a.

Le premier essai sur tablette avait bien écrit 1 802 octets, puis rencontré :

```text
java.lang.UnsatisfiedLinkError: No implementation found for void org.aseprite.android.SafBridge.complete(long, int, byte[], byte[]) (tried Java_org_aseprite_android_SafBridge_complete and Java_org_aseprite_android_SafBridge_complete__JI_3B_3B) - is the library loaded, e.g. System.loadLibrary?
```

Correction : `RegisterNatives` lie explicitement la méthode du helper à la
bibliothèque déjà chargée par NativeActivity. Les essais suivants utilisent
ce correctif. Aucun crash fatal/assertion observé pour les PID 11423 et 12253.
Les premières itérations de compilation ont également corrigé un include Site,
le constructeur Separator, les identifiants générés et l’appel Alert ; aucun
module n’a été désactivé pour contourner ces erreurs.

## Validation réelle sur tablette

Fournisseurs utilisés via DocumentsUI :

1. **Téléchargements** — `com.android.providers.downloads.documents` : ASE et PNG.
2. **MDP1221 → Documents** — `com.android.externalstorage.documents` : PNG.

Le second fournisseur a été parcouru exclusivement dans DocumentsUI, sans
accès POSIX direct au stockage partagé. Drive était disponible mais n’a pas été
testé ; aucun code particulier à un fournisseur n’a été ajouté.

| Test | Résultat observé |
|---|---|
| Export privé jalon 10 vers `test-jalon11.aseprite` dans Téléchargements | 1 802 octets écrits ; premier retour JNI corrigé ensuite |
| Import externe de ce fichier | 1 802 octets, UI 256×256, deux calques, traits et transparence présents |
| Édition de la copie importée | Trait ajouté via l’éditeur ; indicateur modifié présent |
| Annulation Open External | Retour dans le même document modifié, commandes toujours utilisables |
| Annulation Export ASEPRITE | Même document modifié ; aucun export temporaire restant après les opérations terminées |
| Export externe de la copie éditée | `test-jalon11-edited.aseprite`, 1 941 octets ; succès dans l’UI, indicateur modifié conservé |
| Export PNG | Avertissement habituel sur les calques accepté ; `test-jalon11.png`, 2 375 octets |
| Save normal sur la copie importée | Fichier privé passé à 1 941 octets ; indicateur modifié effacé |
| Import PNG depuis Téléchargements | 256×256, un calque, couleurs et transparence vérifiées à l’écran |
| PNG via le second fournisseur | Export puis import, 2 375 octets identiques |
| Arrêt normal Ctrl+Q | `app_main returned: 0`, destruction Activity et jonction GUI |
| Nouveau processus après arrêt normal | Ancien processus en cache arrêté par `am kill`, PID 11423 → 12253 ; lancement COLD, Status ok, TotalTime 949 ms |
| Import de l’ASE édité après redémarrage | 1 941 octets, deux calques et contenu modifié préservés visuellement |
| Open privé normal | Sélecteur interne toujours disponible ; `test-jalon10.aseprite` rouvert avec succès |
| Original externe réimporté après Save privé | Toujours 1 802 octets, SHA-256 initial identique : Save privé n’a pas réécrit le fournisseur |

Les ouvertures/fermetures des pickers détruisent puis recréent la fenêtre native ;
le rendu et les entrées reviennent à chaque retour testé. L’Activity termine
en **RESUMED**, `finishing=false`. Échantillon au repos : ticks CPU 814 → 814
sur 10,055 secondes (`android/build/jalon11-idle.json`). Pas de boucle active
observée sur cette mesure.

## Preuves de fichiers et journaux

Racine privée observée : `/data/user/0/org.aseprite.android/files/documents/`.
Elle est dérivée par les APIs Android, pas codée en dur.

| Copie sous cette racine | Taille |
|---|---|
| `import-ea7a9ff8-4050-4b76-a814-0d206fec23fe/test-jalon11.aseprite` après Save privé | 1 941 |
| `import-e44f57ac-81c7-4962-8992-6f0221f6855d/test-jalon11-edited.aseprite` réimporté après redémarrage | 1 941 |
| `import-7215c511-7584-4ec4-a488-892d6f0f0835/test-jalon11.aseprite` original réimporté | 1 802 |
| `import-7a7d78bd-2fbb-4731-8df6-25bc3b6b435a/test-jalon11.png` depuis Téléchargements | 2 375 |
| `import-194023fd-dcd9-4ff1-af4b-a233958d7bba/test-jalon11.png` depuis Documents | 2 375 |

Comparaisons SHA-256 effectuées sur les copies privées lues avec adb/run-as :

- Original initial et original réimporté :
  `5548991f195d42949573ddf3d9a59ec5a97d829a5e51010fcf2c451e3d1b1507`.
- Copie éditée privée et export ASE réimporté :
  `e856c2a7a8295882b61994299cb6aa766230fdb55830b705aac49da9dbec0344`.
- PNG réimportés depuis les deux fournisseurs :
  `edc4d746e90124f5be20648c0a1c3d63ab155503763184b51216061cf0564d95`.

Preuves locales : `android/build/jalon11-file-evidence.json`,
`jalon11-saf-results.log`, `jalon11-logcat.txt`, `jalon11-activity.txt`,
`jalon11-prefs.txt`, `jalon11-reimported.png`.

Extraits réels, sans URI complète ni contenu de fichier :

```text
00:26:43.440 SAF display name=test-jalon11-edited.aseprite
00:26:43.462 SAF export bytes=1941 private=/data/user/0/org.aseprite.android/files/documents/.saf-export-go8IhT/test-jalon11.aseprite
00:26:43.462 SAF completion status=0
00:27:44.382 SAF result scheme=content authority=com.android.externalstorage.documents
00:27:44.404 SAF export bytes=2375 private=/data/user/0/org.aseprite.android/files/documents/.saf-export-6V3cK1/test-jalon11.png
00:28:44.084 SAF persisted permissions flags=3
00:28:44.095 SAF display name=test-jalon11-edited.aseprite
00:28:44.122 SAF import bytes=1941 private=/data/user/0/org.aseprite.android/files/documents/import-e44f57ac-81c7-4962-8992-6f0221f6855d/test-jalon11-edited.aseprite
```

## Captures

Sous `android/build/`, non versionnées comme les artefacts des jalons précédents :

- `jalon11-file-menu.png` : entrées Android séparées.
- `jalon11-open-ase.png` : premier import ASE réussi.
- `jalon11-import-edited.png` : document importé puis édité.
- `jalon11-open-cancel.png`, `jalon11-export-cancel.png` : annulations.
- `jalon11-ase-export-success.png` : retour réussi après le correctif JNI.
- `jalon11-png-options.png` : avertissement existant sur les calques PNG.
- `jalon11-create-png-picker.png`, `jalon11-png-visible-provider.png` : choix et fichier externe.
- `jalon11-png-export-success.png`, `jalon11-png-open.png` : export/import PNG.
- `jalon11-documents-provider-file.png`, `jalon11-second-provider-open.png` : second fournisseur.
- `jalon11-private-save.png`, `jalon11-private-selector.png`, `jalon11-private-reopen.png` : flux privé préservé.
- `jalon11-restart-home.png`, `jalon11-ase-reopened-restart.png` : arrêt/reprise et calques.
- `jalon11-original-external-unchanged.png` : réimport de l’original inchangé.

Les autres captures intermédiaires restent dans le répertoire de build.
`jalon11-ase-exported.png` appartient au premier essai ayant crashé : elle montre
le lanceur Android et **ne constitue pas une preuve de retour réussi**.

## Limites et suite

Aucun blocage restant observé sur les scénarios requis. Les URI ne sont jamais
des chemins POSIX ; aucune permission de stockage général n’a été ajoutée.

- Annulation utilisateur testée ; fournisseur disparu, disque plein, droits
  refusés, copie interrompue et résultat sans URI sont traités par les branches
  d’erreur, mais n’ont pas été provoqués sur cette tablette.
- Une écriture fournisseur échouée peut laisser un fichier externe partiel.
  Le message l’indique ; le document privé reste intact. L’écriture distante
  n’est pas transactionnelle et la fermeture du flux ne prouve pas une
  synchronisation avec un serveur cloud.
- Pas de reprise des copies lors d’une destruction du processus. L’abandon des
  tickets est implémenté ; la destruction complète de l’Activity pendant une
  copie longue n’a pas été testée. Une mort brutale peut laisser un fichier de
  travail temporaire, sans écraser le document source.
- Chaque import crée une copie distincte. Pas de lien document/URI, de
  sauvegarde automatique dans l’original, ni de gestion UI des grants persistés.
- Le sélecteur externe accepte davantage de types que les trois formats visés ;
  les formats non pris en charge restent soumis aux erreurs normales d’Aseprite.
  L’extension `.ase` suit le décodeur existant mais n’a pas eu un test séparé.
- Libellés Android en anglais. L’IME Aseprite reste absente ; le clavier système
  visible dans DocumentsUI appartient au fournisseur Android.

Prochain jalon recommandé : **saisie de texte Android/IME dans les champs
Aseprite**, avec une validation ciblée des noms de fichiers et dialogues.
Un renforcement SAF ultérieur pourra tester les copies longues, échecs et
destructions pendant les transferts avant d’envisager une association URI pour
la sauvegarde directe. Aucun de ces travaux n’a été commencé ici.

## Commits

Poussés sur `github/android-port` (`Golden76z/aseprite`) :

1. `3162e85db` — pont SAF asynchrone avec NativeActivity.
2. `30bee14c3` — commandes externes et copies privées utilisant les encodeurs.
3. `43121ba90` — correction du retour JNI par RegisterNatives.

Le rapport et le README font l’objet du commit de validation suivant.
