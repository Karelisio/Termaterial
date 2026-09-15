# Termaterial

![Build](https://github.com/Karelisio/Termaterial/actions/workflows/build.yml/badge.svg)

Terminal Android natif inspiré de [Termux](https://github.com/termux/termux-app),
avec une interface Material You (Material 3, dynamic color) au lieu de
l'interface classique de Termux.

- Kotlin + Jetpack Compose + Material 3
- Gradle (Kotlin DSL)
- `minSdk` 26 (Android 8.0), dynamic color sur API 31+ avec palette
  terminal fixe (vert/ambre) en fallback en dessous

## État du projet

Le projet est construit étape par étape :

- [x] **Étape 1 — Moteur terminal** : modules `terminal-emulator` et
      `terminal-view`, adaptés depuis
      [termux/termux-app](https://github.com/termux/termux-app) pour
      compiler comme modules Gradle indépendants. Voir
      [`docs/step-1-terminal-engine.md`](docs/step-1-terminal-engine.md).
- [x] **Étape 2 — Shell backend** : module `shell` (`BootstrapInstaller`,
      `BootstrapShellSessionFactory`) qui télécharge le bootstrap Termux
      officiel et lance `bash` directement (pas de `proot` — vérification
      empirique du bootstrap réel a montré qu'il n'était pas nécessaire ; le
      `RUNPATH` figé des binaires est corrigé via `LD_LIBRARY_PATH`). Voir
      [`docs/step-2-shell-backend.md`](docs/step-2-shell-backend.md) — inclut
      un point d'attention important sur Android 10+ à trancher avant
      l'Étape 6.
- [x] **Étape 3 — UI Compose Material You** : module `app`, thème
      `TermaterialTheme` (dynamic color API 31+, palette terminal
      vert/ambre en fallback), `TerminalScreen` (TopAppBar + `TerminalView`
      via `AndroidView`), `BootstrapProgressScreen`, `SettingsBottomSheet`
      (placeholder). Voir
      [`docs/step-3-ui-compose.md`](docs/step-3-ui-compose.md).
- [x] **Étape 4 — Fonctionnalités** : onglets multi-sessions (une
      `TerminalView` partagée, `attachSession` pour changer d'onglet),
      écran de réglages complet (police, taille, thème de couleurs
      terminal réel via `TerminalColorSchemeApplier`, dynamic color
      on/off), barre de touches spéciales (Ctrl/Alt en bascule, Tab, Esc,
      flèches). Voir [`docs/step-4-features.md`](docs/step-4-features.md).
- [x] **Étape 5 — Permissions Android & foreground service** :
      `POST_NOTIFICATIONS` demandée au runtime (dégrade proprement si
      refusée), `TerminalSessionService` (foreground, type `specialUse`,
      notification persistante) démarré dès qu'un onglet existe. Voir
      [`docs/step-5-permissions.md`](docs/step-5-permissions.md).
- [x] **Étape 6 — Build & vérification** : la CI compile les 4 modules et
      produit un APK debug (`terminal-emulator`, `terminal-view`, `shell`,
      `app`) à chaque push — voir le badge ci-dessus. Reste un point ouvert
      avant de considérer le projet totalement terminé : le comportement
      réel sur Android 10+ (voir `docs/step-2-shell-backend.md`), qui ne
      peut être vérifié que sur un appareil/émulateur réel.

## Structure

```
terminal-emulator/   Moteur d'émulation ANSI/VT100 (module Android library, Java)
terminal-view/       Vue de rendu du terminal (module Android library, Java)
shell/               Bootstrap Termux + lancement direct du shell (module Android library, Kotlin)
app/                 Application Compose / Material You (module Android application, Kotlin)
```

## Licences

Ce dépôt combine du code sous plusieurs licences :

- `terminal-emulator` et `terminal-view` : **Apache License 2.0**, adaptés de
  termux-app (lui-même dérivé de
  [jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)).
  Voir le fichier `LICENSE` et `NOTICE.md` dans chaque module.
- Le reste du code (UI Compose, backend shell/bootstrap, etc.) est écrit
  spécifiquement pour ce projet.

Aucun autre module de termux-app (bootstrap installer, plugins, app shell)
n'a été importé : seuls le moteur d'émulation et la vue de rendu le sont.
