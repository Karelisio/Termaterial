# Etape 3 - UI Compose Material You

## Ce qui a ete fait

Nouveau module `app` (application Android, `applicationId` `io.termaterial.app`),
qui assemble les modules des etapes precedentes :

- **`MainActivity.kt`** - une seule Activity Compose (`ComponentActivity`),
  `configChanges` geres nous-memes (rotation, clavier...) pour ne pas
  recreer l'Activity et donc ne pas perdre la session shell en cours
  (comme le fait le vrai Termux). `TermaterialApp()` orchestre :
  1. collecte du `Flow<BootstrapProgress>` de `BootstrapInstaller`
     (Etape 2) via `collectAsState` ;
  2. tant que ce n'est pas termine : `BootstrapProgressScreen` (ecran de
     premier lancement demande a l'Etape 2/5) ;
  3. une fois installe : creation de la `TerminalSession` via
     `BootstrapShellSessionFactory` et affichage de `TerminalScreen`.
- **`ui/theme/Theme.kt`** - `TermaterialTheme` : `dynamicLightColorScheme`/
  `dynamicDarkColorScheme` (Material 3) sur API 31+ si activee, sinon
  palette terminal fixe (`TerminalPalette.Green` par defaut,
  `TerminalPalette.Amber` disponible - le choix utilisateur sera cable en
  Etape 4). Suit `isSystemInDarkTheme()` pour le theme clair/sombre
  automatique.
- **`ui/screens/TerminalScreen.kt`** - wrappe la `TerminalView` (vue
  classique du module `terminal-view`, Etape 1) dans Compose via
  `AndroidView`, derriere un `Scaffold` + `TopAppBar` Material 3 (titre =
  titre du terminal courant, action reglages).
- **`ui/screens/SettingsBottomSheet.kt`** - `ModalBottomSheet` Material 3,
  ouverte depuis l'icone reglages ; contenu reel prevu Etape 4.
- **`ui/screens/BootstrapProgressScreen.kt`** - ecran de progression
  (`CircularProgressIndicator`/`LinearProgressIndicator` selon l'etat :
  verification / telechargement avec Mo telecharges / extraction / echec
  avec bouton "Reessayer").
- **`terminal/AppTerminalClient.kt`** - implementation de
  `TerminalSessionClient` (module `terminal-emulator`) et
  `TerminalViewClient` (module `terminal-view`) : rafraichissement de la
  vue, copier/coller via `ClipboardManager`, titre, fin de session. La
  gestion des touches (Ctrl/Alt/Maj/Fn, barre de touches speciales) renvoie
  `false`/pas d'etat pour l'instant - cablage reel prevu Etape 4.
- Icone de lancement adaptative (XML vectoriel, pas de PNG) : fond sombre +
  glyphe de prompt terminal `>_` en vert.
- Theme XML minimal (`Theme.Termaterial`, parent `android:Theme.Material.Light.NoActionBar`)
  uniquement pour la couleur de fond avant que Compose ne prenne la main -
  aucune dependance AppCompat/Material Components XML necessaire, toute
  l'UI est en Compose.

## Point technique a connaitre : `setTextSize` avant `attachSession`

En lisant `TerminalView.java` (Etape 1), `mRenderer` (qui porte la police et
la taille de texte) n'est cree que par `setTextSize()` - sans cet appel,
`attachSession()` provoquerait un `NullPointerException` des la premiere
mise en page (`updateSize()` deferencerait `mRenderer` = `null`). Corrige
dans `TerminalScreen.kt` en appelant `setTextSize(...)` avant
`attachSession(...)` avec une taille par defaut (14sp) - le choix de taille
et de police monospace deviendra reglable en Etape 4.

## Dependances ajoutees

`gradle/libs.versions.toml` : BOM Compose (`2024.12.01`), `material3`,
`activity-compose`, `lifecycle-runtime-ktx`/`lifecycle-viewmodel-compose`,
`material-icons-extended`, plus le plugin `org.jetbrains.kotlin.plugin.compose`
(obligatoire avec Kotlin 2.0+ pour le compilateur Compose, separe de l'AGP).

## Tests / verification

Comme pour les etapes precedentes, je n'ai pas pu executer
`./gradlew :app:assembleDebug` dans ce bac a sable (memes blocages reseau
`dl.google.com`/`maven.google.com`, cf. `docs/step-1-terminal-engine.md`) ;
le CI GitHub Actions (deja preconfiguree a l'Etape 1 pour se declencher des
que `app/build.gradle.kts` existe) va desormais construire et publier l'APK
debug en artefact a chaque push. Je n'ai donc pas pu verifier visuellement
le rendu (dynamic color, wrapping de la TerminalView, etc.) - a confirmer
au premier lancement reel (Etape 6).

## Pas encore fait (etapes suivantes)

- Onglets multi-sessions, contenu reel de l'ecran de reglages (police,
  taille, theme, dynamic color on/off), barre de touches speciales (Etape 4).
- Permissions runtime, foreground service (Etape 5).
- Build/verification APK debug sur un environnement avec acces au SDK
  Android (Etape 6) - y compris le point Android 10+ laisse ouvert a
  l'Etape 2.
