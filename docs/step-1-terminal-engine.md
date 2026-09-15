# Étape 1 — Moteur terminal

## Ce qui a été fait

- Les modules `terminal-emulator` et `terminal-view` de
  [termux/termux-app](https://github.com/termux/termux-app) (dernier commit
  sur `master` au moment de l'import) ont été copiés dans ce dépôt, sans le
  reste du projet Termux (pas de `app/`, `termux-shared/`, bootstrap installer
  ou plugins).
- `terminal-emulator` : moteur d'émulation ANSI/VT100/xterm pur Java
  (~7800 lignes, paquet `com.termux.terminal`) + une petite bibliothèque
  native JNI (`src/main/jni/termux.c`, ~220 lignes) qui ouvre le
  pseudo-terminal (`/dev/ptmx`) et lance le sous-processus (`fork`/`exec`).
  Les tests unitaires JUnit d'origine (`src/test/...`) sont conservés.
- `terminal-view` : la `View` Android qui affiche le buffer du terminal et
  gère les gestes/le clavier/la sélection de texte (paquet `com.termux.view`,
  ~2900 lignes), avec ses ressources (`drawable/text_select_handle_*`,
  `strings.xml`).
- Les fichiers `build.gradle` (Groovy) d'origine ont été réécrits en
  `build.gradle.kts` (Kotlin DSL), avec un catalogue de versions
  `gradle/libs.versions.toml` pour centraliser les versions (AGP 8.7.3,
  androidx.annotation 1.9.0, JUnit 4.13.2). Le plugin `maven-publish` (utilisé
  par Termux pour publier sur Maven/JitPack) a été retiré car inutile ici.
- `minSdk` fixé à 26 (Android 8.0) et `compileSdk`/`targetSdk` à 35, valeurs
  définies une seule fois dans `gradle.properties` racine et lues par les
  deux modules — cohérent avec le reste du projet à venir (Étape 3+).
- Les packages Java d'origine (`com.termux.terminal`, `com.termux.view`) et
  les namespaces Gradle (`com.termux.emulator`, `com.termux.view`) ont été
  conservés tels quels : renommer ~50 fichiers pour un projet qui n'a pas
  encore d'UI aurait ajouté du risque sans bénéfice réel à ce stade. Le nom
  du module/package de l'application elle-même (Étape 3) sera bien le nôtre
  (`io.termaterial...` ou équivalent), indépendant de ces deux libs.
- Un `NOTICE.md` + `LICENSE` (Apache License 2.0) ont été ajoutés dans
  chacun des deux modules : contrairement au reste de termux-app (GPLv3),
  `terminal-emulator`/`terminal-view` sont sous licence Apache 2.0 (exception
  documentée dans le `LICENSE.md` racine de termux-app, héritée de
  jackpal/Android-Terminal-Emulator). C'est ce qui permet de les réutiliser
  ici sans placer tout Termaterial sous GPLv3.
- Le wrapper Gradle a été généré (`./gradlew`, Gradle 8.9, compatible avec
  AGP 8.7.3).

## Structure ajoutée

```
settings.gradle.kts
build.gradle.kts
gradle.properties
gradle/libs.versions.toml
gradle/wrapper/...
terminal-emulator/
  build.gradle.kts
  proguard-rules.pro
  LICENSE, NOTICE.md
  src/main/AndroidManifest.xml
  src/main/java/com/termux/terminal/*.java   (moteur ANSI/VT100)
  src/main/jni/{Android.mk,termux.c}         (pty + fork/exec natif)
  src/test/java/com/termux/terminal/*.java   (tests JUnit d'origine)
terminal-view/
  build.gradle.kts
  proguard-rules.pro
  LICENSE, NOTICE.md
  src/main/AndroidManifest.xml
  src/main/java/com/termux/view/**/*.java    (rendu + gestes + sélection)
  src/main/res/{drawable,values}/...
```

## ⚠️ Limitation de vérification dans cet environnement

Je n'ai **pas pu exécuter `./gradlew assembleDebug` ni même `./gradlew help`**
dans ce bac à sable : le proxy réseau de cet environnement bloque
`dl.google.com` et `maven.google.com` (redirige vers `dl.google.com`, aussi
bloqué), qui sont les seuls dépôts qui hébergent :

- le plugin Android Gradle (`com.android.library`) lui-même,
- toutes les bibliothèques `androidx.*` (y compris `androidx.annotation`,
  utilisée ici),
- les composants du SDK/NDK Android (aucun SDK Android n'est installé dans ce
  conteneur).

`repo1.maven.org` (Maven Central) et `services.gradle.org` (distributions
Gradle) sont en revanche accessibles — c'est ce qui a permis de générer le
wrapper Gradle localement.

Concrètement : la configuration Gradle (fichiers `.kts`, catalogue de
versions, structure des modules) est correcte à la lecture, le code Java
copié est celui d'origine de termux-app (non modifié à part le
retrait des annotations `@TargetApi`/imports superflus — aucun ici), mais
je n'ai **aucun moyen de confirmer par une compilation réelle** que tout
s'assemble tant que je n'ai pas accès à `dl.google.com`/au SDK Android.

**À faire de votre côté (ou en CI GitHub Actions, qui a accès à ces
serveurs) pour valider cette étape :**

```
./gradlew :terminal-emulator:assembleDebug :terminal-view:assembleDebug
./gradlew :terminal-emulator:testDebugUnitTest
```

Si vous avez un moyen de me donner accès à `dl.google.com`/`maven.google.com`
dans cet environnement (ou si vous préférez que j'ajoute un workflow GitHub
Actions qui le fait automatiquement à chaque push), dites-le-moi et je peux
le mettre en place pour les étapes suivantes.
