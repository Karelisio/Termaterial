# Termaterial

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
- [ ] Étape 2 — Shell backend (bootstrap + proot)
- [ ] Étape 3 — UI Compose Material You
- [ ] Étape 4 — Fonctionnalités (onglets, réglages, barre de touches)
- [ ] Étape 5 — Permissions Android & foreground service
- [ ] Étape 6 — Build & vérification APK debug

## Structure

```
terminal-emulator/   Moteur d'émulation ANSI/VT100 (module Android library, Java)
terminal-view/       Vue de rendu du terminal (module Android library, Java)
```

## Licences

Ce dépôt combine du code sous plusieurs licences :

- `terminal-emulator` et `terminal-view` : **Apache License 2.0**, adaptés de
  termux-app (lui-même dérivé de
  [jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)).
  Voir le fichier `LICENSE` et `NOTICE.md` dans chaque module.
- Le reste du code (UI Compose, backend proot/bootstrap, etc., à venir dans
  les étapes suivantes) est écrit spécifiquement pour ce projet.

Aucun autre module de termux-app (bootstrap installer, plugins, app shell)
n'a été importé : seuls le moteur d'émulation et la vue de rendu le sont.
