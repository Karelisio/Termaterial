# Etape 7 - Reprise : audit, corrections, ameliorations

Audit complet du depot apres les etapes 1 a 6, avec cette fois un SDK
Android/NDK utilisable dans le bac a sable : build et tests unitaires
executes localement (183 tests), et le vrai bootstrap Termux
(`bootstrap-2026.09.13-r1+apt.android-7`) telecharge et analyse
statiquement (fichiers texte, liens, `readelf`/`strings` des binaires). Les
binaires Android eux-memes n'ont pas pu etre executes ici (pas
d'emulateur) : tout ce qui suit reste a confirmer sur appareil.

## Environnement Termux (module `shell`)

Le bootstrap a `/data/data/com.termux` (le dossier prive de Termux, jamais
accessible pour une autre application) fige partout, pas seulement dans les
binaires :

- **Scripts** : 79 commandes de `bin/` sont des scripts commencant par
  `#!/data/data/com.termux/files/usr/bin/sh` (ou `bash`) - `pkg`, `top`,
  `df`, `zcat`, `ping`, `login`, `apt-key`, `termux-change-repo`... Toutes
  echouaient ("bad interpreter").
- **Liens symboliques absolus** : les cles de signature des depots
  (`etc/apt/trusted.gpg.d/*.gpg`) pointent vers
  `/data/data/com.termux/files/usr/share/termux-keyring/` : une fois le
  probleme des certificats regle, `apt update` aurait echoue sur la
  verification des signatures.
- **apt** : en plus de `Dir`/`Dir::Etc`/`CaInfo` deja corriges, la liste des
  chemins com.termux de `libapt-pkg.so` montre `Dir::Bin::apt-key` (lance
  pour verifier chaque signature), les decompresseurs (`Dir::Bin::xz`...),
  `DPkg::Path` (le `PATH` donne a dpkg, qui refuse de demarrer sans `sh`) et
  `Dir::Cache` (Termux le garde dans `<dataDir>/cache/apt`, chemin utilise
  aussi par `pkg`).

Correctifs :

- `TermuxPathRewriter` + `BootstrapFixups` : a l'installation, reecriture de
  `/data/data/com.termux` vers le vrai dossier de donnees de l'app dans les
  fichiers texte (scripts, `etc/profile`, pkg-config...) et les liens
  symboliques absolus. Les binaires ELF ne sont jamais modifies, ni la base
  dpkg (`var/lib/dpkg`, hors scripts de maintenance) qui doit rester alignee
  sur les chemins des archives `.deb`. Verifie sur le bootstrap aarch64 :
  186 fichiers texte et 20 liens corriges, aucune reference restante hors de
  la base dpkg, aucun binaire modifie, permissions conservees, ~0,5 s.
- Installations existantes : appliques automatiquement au prochain
  lancement (marqueur versionne `.TERMATERIAL_FIXUPS_VERSION`), sans
  retelechargement.
- `APT_CONFIG` complete avec les cles ci-dessus.
- bash n'est plus lance en `--login --noprofile` (qui ne lisait **ni**
  `~/.bashrc` **ni** `~/.bash_profile`) mais en shell interactif avec un
  rcfile genere (`termaterial-bashrc`) qui rejoue la sequence de login
  Termux avec les vrais chemins : `$PREFIX/etc/profile` (corrige) ->
  `profile.d/`, `bash.bashrc` (prompt Termux, historique, command-not-found,
  bash-completion) -> fichiers personnels.
- Environnement : `SHELL`, variables Android (`ANDROID_ROOT`,
  `BOOTCLASSPATH`... necessaires a `am`, `pm`, `cmd`), `SSL_CERT_FILE` et
  `CURL_CA_BUNDLE` (meme probleme de bundle CA qu'apt, pour curl & co).
- La "seconde etape" du bootstrap (scripts `postinst`, normalement lancee
  par l'app Termux) est marquee comme faite : ses scripts ne font
  qu'enregistrer des `update-alternatives` (`pager`, `editor`) dont le
  dossier d'administration compile est inaccessible ici.

## Installateur

- **Perte de donnees** : changer `BOOTSTRAP_RELEASE_TAG` dans une future
  version declenchait une reinstallation automatique qui effacait `usr/`
  (tous les paquets installes). `isInstalled()` ne compare plus le tag.
- **Perte de donnees** : l'ancien `usr/` etait supprime *avant* le
  telechargement ; un echec reseau detruisait l'installation. Tout est
  maintenant prepare dans `usr-staging`, l'ancien dossier n'est remplace
  qu'a la fin.
- **Perte de donnees** : `File.deleteRecursively()` de Kotlin suit les liens
  symboliques vers des dossiers et supprime leur contenu (verifie par un
  test). Remplace par une suppression qui ne suit jamais les liens.
- Protection "zip slip", verification SHA-256 de l'archive (empreintes des 4
  architectures pour le tag epingle), redirections HTTP relatives.
- "Reessayer" apres un echec ne force plus une reinstallation complete.

## Application

- **Sessions perdues** : les onglets vivaient dans l'etat Compose de
  l'Activity. Toute recreation de l'Activity (Retour sous Android 11 et
  avant, redimensionnement en multi-fenetre, changement de taille
  d'affichage, "Ne pas conserver les activites") laissait le shell tourner
  sans moyen d'y revenir. Nouveau `TerminalSessionManager` au niveau du
  processus ; le service de premier plan l'observe directement (plus de
  binding).
- **Boucle de reouverture** : un shell qui se terminait fermait son onglet
  immediatement, la sortie etait illisible et, si le shell plantait au
  demarrage, un nouvel onglet etait rouvert en boucle. L'onglet reste
  maintenant affiche avec `[Process completed (code N) - press Enter]`,
  Entree le ferme (comme Termux).
- **Collage dangereux** : le texte colle etait ecrit brut ; un collage
  multi-lignes executait chaque ligne. Passe par `TerminalEmulator.paste()`
  (bracketed paste, `\n` -> `\r`).
- **Ctrl/Alt** restaient actifs indefiniment. Un appui = touche suivante
  seulement, appui long = verrouille. `TerminalView` ne lit chaque
  modificateur qu'une fois par touche (`onKeyDown()` transmet la valeur a
  `inputCodePoint()` qui court-circuite sa propre lecture), le meme contrat
  que les touches supplementaires de Termux.
- **Crash garanti sur x86** : l'APK contenait `lib/x86/` (une lib AndroidX)
  mais pas `libtermux.so` pour x86. Ajoute (et verifie par la CI).
- Renderer du terminal recree a chaque recomposition ; `scaledDensity`
  deprecie remplace.

Nouveautes : barre de touches a la Termux sur deux rangees
(`ESC / - HOME ↑ END PGUP` / `TAB CTRL ALT ← ↓ → PGDN`, repetition a
l'appui long), pincer pour zoomer, action "Quitter" dans la notification,
"Reinstaller l'environnement Termux" dans les reglages, onglets numerotes et
onglet termine barre.

## CI et distribution

- La CI ne tournait que sur `main` et `claude/**` : toutes les branches.
- La release debug avait un tag fixe par branche, cree sans `--target`
  (donc pointant sur le commit initial de `main`) et dont seul l'APK etait
  remplace : un outil comme Obtainium, qui compare le tag a la version
  installee, ne pouvait jamais voir de mise a jour. Desormais une release
  par build, tag `v<versionName>` (`v0.2.0-debug.<numero de run>`),
  `versionCode` = numero de run (chaque build s'installe en mise a jour),
  les 10 dernieres sont conservees.
- Cache du SDK/NDK et de Gradle.

## Limite majeure restante : installer des paquets

`apt update` devrait maintenant aboutir, mais `apt install` / `pkg install`
ne peuvent pas fonctionner avec l'architecture actuelle (paquets Termux
executes directement sous un autre nom de paquet que `com.termux`) :

1. Les `.deb` Termux contiennent des chemins absolus
   (`./data/data/com.termux/files/usr/...`) : dpkg essaie d'extraire dans le
   dossier d'une autre app et echoue.
2. Leurs scripts de maintenance ont les memes shebangs figes.
3. Chaque binaire installe a ses propres chemins compiles (perl et son
   `@INC`, `git` et son `exec-path`, vim et son runtime, ssh...) : une liste
   sans fin de cas particuliers.

Options :

- **A. `applicationId = com.termux`** : tous les chemins deviennent valides,
  tout fonctionne comme dans Termux (et la plupart des contournements
  ci-dessus deviennent inutiles). Mais l'app remplace Termux : impossible de
  les installer cote a cote, ni d'utiliser les plugins Termux:API &co
  (signature differente). C'est le choix de forks comme Termux Monet.
- **B. proot** : garder `io.termaterial.app` et lancer le shell sous proot
  avec `-b <dataDir>:/data/data/com.termux`. Compatibilite complete des
  chemins, cohabitation avec Termux, et permettrait meme de remonter le
  `targetSdk` (le chargeur de proot contourne W^X). Couts : proot (+ talloc)
  a compiler pour chaque ABI ou a telecharger depuis le depot Termux, et un
  surcout a l'execution (ptrace).
- **C. Continuer les rustines** (dpkg `--instdir` + lien, reecriture apres
  chaque installation...) : fragile, toujours incomplet a cause du point 3.

Recommandation : **A** si cohabiter avec Termux n'est pas necessaire,
sinon **B**.

**Decision : B (proot)**, voir [`step-8-proot.md`](step-8-proot.md).
