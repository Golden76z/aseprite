# Compte rendu — Jalon 3 : compatibilité de compilation Android

Date : 12 septembre 2026.

Références : [audit](ANDROID_ARM64_AUDIT.md),
[jalon 1](ANDROID_ARM64_JALON_1_COMPTE_RENDU.md),
[jalon 2](ANDROID_ARM64_JALON_2_COMPTE_RENDU.md).
Point de départ : Aseprite `3c84b37bf`, LAF `dcec8d1`.

## Résultat

**La cible Android ARM64 complète compile et atteint avec succès l’édition de
liens de `libaseprite.so`. Dernier résultat Gradle : code de sortie 0,
`BUILD SUCCESSFUL in 1m 49s`.**

Le jalon s’arrête au critère A demandé : arrivée à l’édition de liens. Aucun
comportement Android d’exécution n’est ajouté. La NativeActivity reste le stub
du premier jalon ; aucun APK n’a été construit ni lancé.

## Deux corrections, limitées aux branches Android

### Identification du système

Dans `src/updater/user_agent.cpp`, ajout d’une branche `#elif LAF_ANDROID` à
`getFullOSString()`, qui renvoie simplement `Android`.

`laf/base/platform.h` identifie déjà Android avec `Platform::OS::Android` et
`LAF_ANDROID`. En revanche, `base::get_platform()` ne renseigne pas encore sa
version. La nouvelle chaîne n’invente donc aucune version ni distribution Linux.
Les branches Windows, macOS et le repli Linux restent inchangés.

### Déclarations SkSL du Skia raster existant

Après cette correction, la compilation a réellement atteint et échoué dans
`src/app/ui/editor/brush_preview.cpp` sur ces diagnostics :

```text
src/app/ui/editor/brush_preview.cpp:511:13: error: unknown type name 'SkRuntimeBlendBuilder'
src/app/ui/editor/brush_preview.cpp:511:43: error: use of undeclared identifier 'make_blender'
.deps/skia/include/core/SkRefCnt.h:151:12: error: member access into incomplete type 'SkBlender'
.deps/skia/include/core/SkRefCnt.h:142:12: error: member access into incomplete type 'SkBlender'
```

Seul le préfixe absolu du dépôt a été retiré des diagnostics ci-dessus.

L’inspection de `src/app/util/shader_helpers.h` et `.cpp` a montré que les
déclarations et définitions requises étaient protégées par `SK_ENABLE_SKSL`.
La branche Android de `laf/cmake/FindSkia.cmake` omettait cette définition.

Avant modification, l’archive ARM64 existante a été inspectée avec `llvm-nm` :
elle contient notamment `SkRuntimeEffect::MakeForBlender`, les constructeurs et
le destructeur de `SkRuntimeBlendBuilder`, et `SkRuntimeBlendBuilder::makeBlender()`.
Son graphe Ninja contient également `src/core/SkRuntimeEffect.cpp`.

La correction ajoute donc **uniquement `SK_ENABLE_SKSL=1` dans la branche CMake
Android**, en conservant `SK_SUPPORT_GPU=0`. Elle aligne les déclarations utilisées
par Aseprite sur le contenu réel de l’archive Skia raster déjà construite.
Les définitions desktop sont inchangées.

Aucun code d’aperçu de brosse, de shader ou de présentation n’a été réécrit.
Skia n’a pas été reconstruit et aucun module applicatif n’a été désactivé.

## Commandes exactes et progression

Depuis la racine du dépôt, avec les dépendances du premier jalon déjà préparées :

```bash
android/gradlew -p android ':app:buildCMakeDebug[arm64-v8a]' \
  --console=plain --max-workers=4
```

Les deux exécutions de cette session ont été journalisées ainsi :

```bash
# Après la correction de user_agent.cpp : échec sur les déclarations SkSL.
android/gradlew -p android ':app:buildCMakeDebug[arm64-v8a]' \
  --console=plain --max-workers=4 > android/build/jalon3-build-1.log 2>&1

# Après ajout de SK_ENABLE_SKSL=1 dans la branche Android : réussite.
android/gradlew -p android ':app:buildCMakeDebug[arm64-v8a]' \
  --console=plain --max-workers=4 > android/build/jalon3-build-2.log 2>&1
```

| Étape | Résultat exact |
|---|---|
| Première compilation complète | Code 1 ; `BUILD FAILED in 1m 4s` ; quatre diagnostics SkSL ci-dessus |
| Compilation de `user_agent.cpp` | Réussie ; objet ELF AArch64 vérifié |
| Deuxième compilation complète | Code 0 ; `BUILD SUCCESSFUL in 1m 49s` ; 4 tâches exécutées |
| Générateur hôte et configuration Android | Réussis dans les deux exécutions |
| Édition de liens de `libaseprite.so` | Atteinte et réussie |
| Erreurs de compilation restantes | Aucune dans la dernière exécution |
| Erreurs d’édition de liens | Aucune |

Les journaux restent locaux dans `android/build/`, ignoré par Git.
Des avertissements existants persistent, notamment des appels dépréciés et des
valeurs de retour ignorées ; ils n’ont pas interrompu la compilation.

## Bibliothèque produite et vérifiée

```text
android/app/.cxx/Debug/3x1d695f/arm64-v8a/lib/libaseprite.so
```

Vérifications effectuées avec `file`, `llvm-readelf -h` et `llvm-nm -D` :

- bibliothèque partagée ELF64, little-endian, machine AArch64 ;
- cible Android API 26, NDK r28c (`13676358`) ;
- informations de débogage présentes, bibliothèque non strippée ;
- symboles exportés `ANativeActivity_onCreate` et `app_main(int, char**)` présents ;
- entrée de liaison `lib/libaseprite.so` enregistrée dans `.ninja_log`.

Le dossier `3x1d695f` est celui généré par Gradle dans cette session ; il peut
différer ailleurs. La commande Gradle reste indépendante de ce nom.
La bibliothèque produite est un artefact local ignoré par Git, pas un fichier
ajouté au commit.

Les commandes de compilation de l’aperçu de brosse et des utilitaires de shader
ont été contrôlées : cible `aarch64-none-linux-android26`, `LAF_ANDROID`, absence
de `LAF_LINUX`, `SK_ENABLE_SKSL=1` et `SK_SUPPORT_GPU=0`.

La réussite porte sur la construction native. Le chargement sur appareil,
l’exécution de l’éditeur et le fonctionnement des shaders n’ont pas été testés.
Les tests de file du jalon 2 n’ont pas été relancés : leur implémentation est
inchangée. Aucune validation desktop complète n’est revendiquée.

## Fichiers modifiés

```text
src/updater/user_agent.cpp
laf/cmake/FindSkia.cmake
laf                         # Référence du sous-module dans le dépôt principal
android/README.md
```

Fichier créé :

```text
ANDROID_ARM64_JALON_3_COMPTE_RENDU.md
```

L’audit et les comptes rendus précédents sont conservés comme références historiques.
Les deux seules corrections de code/configuration sont les branches Android
détaillées ci-dessus. Aucun changement supplémentaire de plateforme n’a été
nécessaire pour atteindre la liaison ; aucun correctif spéculatif n’est proposé.

## GitHub

Branche : `android-port` dans `Golden76z/aseprite` et `Golden76z/laf`.
Le commit LAF de ce jalon est
[`c3e6752`](https://github.com/Golden76z/laf/commit/c3e6752).
Le commit principal contenant ce compte rendu enregistre la correction
`user_agent.cpp`, la documentation et cette nouvelle référence LAF.
