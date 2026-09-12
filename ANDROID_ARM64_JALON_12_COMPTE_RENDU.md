# Jalon 12 — clavier logiciel Android dans les champs Aseprite

13 septembre 2026. Base : Aseprite `a93e75ac2`, LAF `39c6714`, rapports
précédents, notamment [jalon 11](ANDROID_ARM64_JALON_11_COMPTE_RENDU.md).

**Saisie de base avec Gboard validée sur la XP-Pen MDP1221** : nom de fichier,
chiffres, ponctuation, Backspace et texte accentué. Le clavier s’ouvre pour les
champs Aseprite ; les widgets restent ceux d’Aseprite. La sauvegarde privée et
la réouverture fonctionnent. NativeActivity reste l’Activity principale.

Les touches du **véritable Gboard de la tablette** ont été actionnées par taps
adb. La saisie du nom de sauvegarde n’utilise pas `adb input text` ni un clavier
de test. Les tests de régression matériels utilisent séparément les événements
injectés. Pas de nouvelle séance physique de dessin au doigt/stylet revendiquée.

## Contrat existant inspecté

- `laf/os/system.h` : `setTextInput(bool, screenCaretPos)` active initialement
  le traitement des touches mortes. L’API ne transmet ni contenu, ni sélection,
  ni identité de champ, ni plage de composition.
- `laf/os/event.h` : pas de type TextInput/Composition. Les événements clavier
  portent séparément `scancode`, `unicodeChar`, modificateurs et indicateur de
  touche morte.
- `laf/os/win/window.cpp`, branche `WM_IME_CHAR` : le texte IME arrive déjà
  comme **KeyDown Unicode sans scancode physique**, après assemblage des
  surrogates. Le pont Android réutilise exactement ce contrat.
- `laf/os/osx/view.mm`, `laf/os/skia/skia_window_osx.mm` et
  `laf/os/x11/window.h` : activation du texte/touches mortes, conversion en
  événements LAF ; pas de contrat commun de texte environnant à réutiliser.
- `src/ui/manager.cpp` : transforme ces événements en `KeyMessage` puis les
  distribue selon le focus existant.
- `src/ui/entry.cpp` : insertion Unicode par `TextCmdProcessor`, sélection,
  suppression et navigation existantes. `setCaretPos()` pouvait activer la
  saisie même hors focus ; les champs numériques n’utilisent pas forcément le
  drapeau de traduction des touches mortes.
- `src/ui/textedit.cpp` : appelle également `System::setTextInput()` ;
  `TextBox` sert à l’affichage et n’est pas remplacé par une vue Android.
- `laf/os/skia/skia_system.h` délègue `setTextInput()` à la fenêtre ;
  `laf/os/android/window.h` avait une implémentation vide.
- `src/main/android_main.cpp`, `laf/os/android/input.cpp` et le pont SAF
  existant fournissent le découpage Android/main looper, GUI Aseprite, JNI et
  callbacks `EventQueue` utilisé ici.

## Architecture implémentée

Une **View Java de 1×1 pixel, sans dessin**, ajoutée au contenu de NativeActivity,
sert uniquement de cible de focus pour `InputMethodManager`. Elle expose une
`BaseInputConnection` dédiée ; aucun `EditText`, aucune nouvelle Activity, aucun
rendu Android de l’interface Aseprite.

Cette nécessité d’une View et d’une InputConnection correspond au contrat
[InputMethodManager](https://developer.android.com/reference/android/view/inputmethod/InputMethodManager)
et [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection).

```text
Entry / TextEdit → System::setTextInput()
→ SkiaSystem → WindowAndroid → AndroidTextInput
→ JNI → ImeBridge.update() sur le main looper → Gboard

Gboard commitText → JNI receive(UTF-16)
→ os::Event::Callback sur le thread GUI
→ KeyDown(scancode=kKeyNil, unicodeChar=point de code)
→ EventQueue → ui::Manager → KeyMessage → champ Aseprite existant
```

Les caractères ne sont pas traduits en codes de touches physiques : aucun
scancode de lettre, aucune fausse paire appui/relâchement pour le texte validé.
UTF-16 est converti en points de code, avec assemblage des paires de surrogates
et remplacement défensif d’un surrogate isolé par U+FFFD. La conversion UTF-8
utilisée pour les documents reste celle des widgets Aseprite.

Pour Backspace, Delete, Enter et flèches, le pont génère les événements
KeyDown/KeyUp correspondants, sans caractère. Les touches matérielles restent
dans `InputAndroid::key()` ; elles ne repassent pas par le prétraitement IME qui
avait absorbé Ctrl. Back reste proposé à Android pour masquer le clavier.

## Focus, composition et sélection

- Les champs éditables Android activent le clavier, y compris les numériques.
  Déplacer le curseur d’un Entry non focalisé ne l’active plus.
- Un tap sur le champ déjà focalisé peut rouvrir le clavier après Back.
- La perte du focus invalide la connexion ; la fermeture est postée sur le
  looper et annulée si un autre champ prend immédiatement le focus. La View
  devient non focalisable et le focus revient au contenu NativeActivity.
- Les appels Java utilisent le main looper. Les widgets ne sont jamais
  manipulés depuis ce thread. Les retours natifs sont filtrés par génération
  de session/Activity avant distribution GUI.
- La View n’est conservée statiquement que par une WeakReference. À son
  détachement, la connexion et l’écoute des insets sont invalidées. Le pont
  natif libère ses références globales lors de la destruction de l’Activity.

**Composition limitée, sans préédition inline dans Aseprite.**
`setComposingText()` conserve uniquement la composition provisoire côté Java.
`commitText()` la remplace par le texte final ; `finishComposingText()` valide
le texte provisoire restant. Une ancienne connexion/focus ne peut pas rejouer
son texte dans un autre champ : la composition non validée est abandonnée à
sa fermeture. Les commits Gboard observés pendant ces essais étaient directs ;
aucune séquence de composition CJK n’a été validée sur appareil.

Il n’existe **aucune seconde copie éditable du champ**. Les requêtes de texte
environnant/sélection renvoient « indisponible » (`null`, indices initiaux -1),
et les requêtes de sélection absolue/reconversion ne sont pas prises en charge.
Le curseur et la sélection restent ceux d’Aseprite, modifiés par ses taps,
touches et commandes habituels. Les suppressions environnantes unitaires sont
traduites en Backspace/Delete ; les plages plus longues sont refusées.
Les positions de curseur non standard demandées par `commitText()` ne sont
pas appliquées : le curseur suit l’insertion normale du widget.

## Place occupée par le clavier

`adjustResize` testé seul n’a pas redimensionné la surface native ; il a aussi
fait apparaître la barre d’état. Cet essai a été retiré.

L’implémentation finale lit `WindowInsets.Type.ime()` sur le decor Android,
avant consommation des insets par ses enfants. Elle réduit la zone de rendu
disponible, sans changer le buffer ANativeWindow ni l’échelle de densité :

| Mesure sur la XP-Pen API 34 | Clavier fermé | Gboard ouvert |
|---|---|---|
| Framebuffer natif | 2160×1440 | 2160×1440 |
| Format / stride | RGBA8888 / 2160 | RGBA8888 / 2160 |
| Inset inférieur IME | 0 | 842 pixels |
| Zone physique présentée | 2160×1440 | 2160×598 |
| Surface Skia/UI | 944×629 | 944×261 |
| rowBytes | 3776 | 3776 |

Le facteur de densité est toujours calculé depuis l’écran complet, à 366 dpi,
pour éviter que l’apparition du clavier réduise la taille des caractères.
La copie raster nearest-neighbor utilise la hauteur disponible ; l’entrée
tactile applique la transformation inverse avec les mêmes dimensions arrondies.
L’origine reste en haut à gauche. Pas de compensation arbitraire ni de changement
GPU/DPI général.

Le sélecteur privé gardait une ancienne taille plus grande que la zone disponible.
Une branche Android de `Manager::onResize()` limite désormais les dimensions et
la position des dialogues à cette zone, puis laisse leur mise en page existante
réduire le contenu flexible. Le dialogue peut conserver cette taille compacte
après fermeture du clavier. Les scènes/documents ne changent pas de dimensions.

Les insets IME sont pris en charge à partir de l’API 30. Le comportement des
API 26–29 n’a pas été testé et conserve la limitation de clavier superposé.

## Fichiers créés et modifiés

Créés :

- `android/app/src/main/java/org/aseprite/android/ImeBridge.java`.
- `laf/os/android/text_input.h` et `laf/os/android/text_input.cpp`.
- `ANDROID_ARM64_JALON_12_COMPTE_RENDU.md`.

Modifiés :

| Fichier | Raison |
|---|---|
| `laf/os/CMakeLists.txt` | Source IME Android uniquement |
| `laf/os/android/window.h`, `window.cpp` | Implémentation de setTextInput |
| `laf/os/android/system.h`, `system.cpp` | Zone disponible et transformation tactile avec inset |
| `laf/os/skia/skia_window_android.cpp` | Présentation dans la hauteur disponible |
| `laf/os/android/input.cpp` | Conservation du chemin matériel sans interception des raccourcis par Gboard |
| `src/main/android_main.cpp` | Vie du pont JNI et réinitialisation de navigation au démarrage d’une nouvelle instance |
| `src/ui/entry.cpp` | Activation limitée au focus éditable, numérique, retap après Back |
| `src/ui/manager.cpp` | Dialogues Android contenus dans la zone disponible |
| `src/app/file_system.cpp` | Suppression de références statiques vers la racine libérée |
| `src/app/ui/file_selector.h`, `file_selector.cpp` | Réinitialisation de l’historique de pointeurs entre deux vies App |
| `android/README.md`, référence du sous-module `laf` | Documentation et version du backend |

Le manifeste et les permissions ne changent pas. Aucun geste, presse-papiers,
reconnaissance manuscrite, SAF supplémentaire, clavier personnalisé ou GPU ajouté.

## Compilation et installation

Depuis la racine du dépôt :

```bash
android/gradlew -p android :app:assembleDebug --console=plain --max-workers=4
/home/golden/Android/Sdk/platform-tools/adb -d install -r android/app/build/outputs/apk/debug/app-debug.apk
/home/golden/Android/Sdk/platform-tools/adb -d shell am start -W -n org.aseprite.android/android.app.NativeActivity
```

Dernier build de code : **BUILD SUCCESSFUL in 5s**, 40 tâches, 7 exécutées,
33 à jour. Journal : `android/build/jalon12-assemble-9.log`.
Toutes les itérations ont compilé ; les corrections intermédiaires répondent
aux problèmes observés sur appareil. Les avertissements de dépréciation
existants ne bloquent pas le build.

APK : **`android/app/build/outputs/apk/debug/app-debug.apk`**.
Installation finale : **Success** (`jalon12-install-9.log`).
Premier lancement après installation : **Status ok, COLD, 1291 ms**.

Appareil : XP-Pen MDP1221, série `XCD1205AF825A05168`, API 34, arm64-v8a.
Clavier : `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`,
Gboard français AZERTY déjà installé, sans modification de ses langues.

Tests hôte existants : **4/4 réussis**, file d’événements, raster, densité et
pression. Journal `android/build/jalon12-host-tests.log`. Ces tests ne remplacent
pas les essais IME sur tablette et ne valident pas la composition multilingue.

## Résultats sur appareil

| Essai | Résultat vérifié |
|---|---|
| Save As, nom entièrement saisi avec Gboard | `pixel-test-01.aseprite` ; lettres, deux tirets, 0/1 et point présents |
| Correction Backspace logiciel | Saisie volontaire `pixelx`, suppression du x, poursuite du nom sans double insertion |
| Done sur le nom | Quitte l’Entry et focalise OK selon la navigation normale Aseprite ; tap OK effectue la sauvegarde |
| Fichier sur disque | `/data/user/0/org.aseprite.android/files/documents/pixel-test-01.aseprite`, 1 802 octets initialement |
| New Sprite | Largeur 256 remplacée par 512 avec le pavé chiffres de Gboard ; hauteur 256 conservée, contrôles accessibles |
| Passage largeur → hauteur | Focus transféré au champ visuellement touché |
| Champ supplémentaire | Nom de calque dans Layer Properties |
| Unicode accentué | `été` via appuis longs sur e dans Gboard, affichage correct dans le champ et le calque |
| Unicode enregistré | Octets UTF-8 de `été` présents dans l’ASEPRITE après Save ; 1 800 octets après renommage du calque, réouverture vérifiée |
| Non-Latin / emoji | Non testés avec le clavier réel ; ne pas confondre la conversion Unicode implémentée avec une validation de ces IME/glyphes |
| Matériel/injection pendant IME | `ab12`, Gauche, Backspace, Delete, `3` → **ab3**, sans duplication ; Ctrl+A fonctionne |
| Raccourcis | Ctrl+O, Ctrl+Shift+S, Ctrl+N, Ctrl+S, Ctrl+Q et Shift+P fonctionnent dans les contextes testés |
| Back puis retap | Masquage puis réouverture du clavier dans le même champ |
| Tap hors du champ | Saisie désactivée et clavier masqué |
| Fermeture du dialogue | Clavier masqué, surface complète retrouvée |
| Home → retour, clavier ouvert | Fenêtre native recréée ; champ, texte et clavier de nouveau utilisables |
| Arrêt avec IME ouverte | `app_main returned: 0`, clavier masqué, `Android activity destroyed; UI thread joined` |
| Nouvelle Activity, même processus | PID **15985** avant/après ; WARM, Status ok, 662 ms ; Open privé et clavier réutilisables |
| Touch / Pen | Taps injectés ouvrent Edit/File, PointerType Pen confirmé ; aucune nouvelle validation physique de pression |
| État final | Activity RESUMED, finishing=false ; aucun fatal dans le PID final 15985 |
| Repos | 1357 → 1357 ticks CPU sur 10,042 s, clavier fermé ; pas de boucle active observée |

Les touches injectées testent le chemin matériel ; aucune présence d’un clavier
physique externe n’est revendiquée. La validation physique doigt/stylet/pression
des jalons précédents reste la référence.

## Problèmes réellement rencontrés et corrigés

1. **Clavier superposé** : ANativeWindow reste plein écran malgré adjustResize.
   Le calcul de zone disponible utilise maintenant l’inset IME réel.
2. **Sélecteur hors écran** : une taille mémorisée dépassait la zone disponible.
   Les dialogues Android sont contraints à cette zone, sans nouveaux widgets.
3. **Raccourcis absorbés / clavier réactivé** : prétraitement des touches par
   Gboard et focus resté sur le helper. Chemin matériel préservé et restitution
   explicite du focus à la fin de la saisie.
4. **Home/retour** : un résultat d’inset pouvait arriver après destruction de la
   fenêtre native et réduire la fenêtre logique à une taille vide. Le changement
   de taille est ignoré tant que les bounds natifs ne sont pas disponibles.
5. **Relance dans le même processus** : crash réel du sélecteur, distinct du
   transport IME, révélé par l’essai de recréation complète. Trace :

```text
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xffffffffffffff78
#00 pc ffffffffffffff78 <unknown>
#01 app::FileList::regenerateList()+56
#02 app::FileList::FileList()+608
#03 app::FileSelector::FileSelector(app::FileSelectorType)+784
```

La racine statique `rootitem` pointait sur un objet détruit avec FileSystemModule.
Elle est maintenant remise à null ; l’historique de navigation qui conservait
des `IFileItem*` de cette ancienne vie est vidé avant le nouvel `app_main()`.
Le scénario arrêt → relance **sans tuer le processus** → Open + IME passe après
correction. L’ancienne trace reste conservée dans `jalon12-crash.txt` ; elle
n’est pas masquée par le constat de stabilité du build final.

## Captures et preuves locales

Sous `android/build/`, non versionnées :

- `jalon12-filename-keyboard.png` : nom complet saisi avec Gboard.
- `jalon12-filename-done.png`, `jalon12-saved-file.png` : Done puis sauvegarde.
- `jalon12-save-fit.png` : sélecteur contenu au-dessus du clavier.
- `jalon12-numeric-verified.png` : largeur 512 sur le build final.
- `jalon12-accent-final.png`, `jalon12-unicode-saved.png`,
  `jalon12-reopened-unicode.png` : texte accentué et réouverture.
- `jalon12-hardware-verified.png` : résultat exact ab3 sur le build final.
- `jalon12-home-return-final.png` : champ conservé après Home/retour.
- `jalon12-back-hide.png`, `jalon12-retap-show.png`,
  `jalon12-outside-hide.png` : transitions de focus/visibilité.
- `jalon12-recreated-selector-final.png` : nouvelle Activity dans le même PID.
- `jalon12-pen-verified.png`, `jalon12-touch-verified.png`,
  `jalon12-final-ui.png` : interaction et état final sans clavier.
- `jalon12-unicode-evidence.txt`, `jalon12-idle.json`, `jalon12-activity.txt`,
  `jalon12-native-stderr.txt`, `jalon12-logcat.txt` : preuves complémentaires.

Les captures des premiers essais, notamment `jalon12-hardware-final.png`,
peuvent montrer l’échec de relance avant correction ; ce sont les fichiers
**verified** ci-dessus qui constituent la validation finale.

Les nouveaux diagnostics IME sont limités au Debug : activation/désactivation,
show/hide, longueur de commit UTF-16, état de composition et inset. Le contenu
des textes n’est pas journalisé par le pont.

## Limites et prochain jalon

**Aucun blocage restant pour les scénarios de saisie de base testés avec Gboard
sur API 34.** Il ne s’agit pas d’une implémentation complète de l’édition IME :

- Pas de texte environnant, sélection absolue, reconversion, remplacement de
  plages arbitraires ou garantie pour tous les claviers prédictifs/CJK.
- Pas de préédition inline ; la composition encore non validée peut être
  abandonnée en quittant le champ. Le scénario CJK reste à tester.
- Les champs numériques utilisent le clavier général et son volet chiffres.
- La saisie multiligne, les emojis et les scripts non latins n’ont pas reçu
  une validation appareil spécifique ; les polices sont inchangées.
- Pas de validation API 26–29, rotation forcée pendant composition, ni mort
  brutale du processus pendant une saisie non enregistrée.
- Le redimensionnement peut déplacer le cadrage du canvas et garder un
  dialogue compact ; il ne change pas les pixels du document.

Prochain jalon recommandé : **étendre proprement le contrat LAF de texte actif
(contenu, sélection et composition), puis valider un IME multilingue**, avant
de promettre autocorrection ou reconversion. Aucun geste ou presse-papiers
n’a été commencé ici.

## Commits poussés

Sur `github/android-port` des forks Golden76z :

- LAF `a7f415d` : texte IME et zone disponible conservant la densité.
- Aseprite `0529a9710` : helper NativeActivity et activation des champs.
- LAF `97b25d0` : touches matérielles et garde sur fenêtre native absente.
- Aseprite `8fe031c81` : focus et dialogues accessibles.
- Aseprite `581dd3caa` : durée de vie du sélecteur lors d’une nouvelle instance.

Le README et ce rapport sont enregistrés dans le commit de validation suivant.
