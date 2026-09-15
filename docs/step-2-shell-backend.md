# Etape 2 - Shell backend (bootstrap + proot)

## Ce qui a ete fait

Nouveau module `shell` (`io.termaterial.shell`), qui depend de `terminal-emulator`
et n'a aucun lien avec l'UI :

- **`TermaterialPaths`** - constantes de chemins : les chemins reels
  (`<filesDir>/usr`, `<filesDir>/usr-staging`, `<filesDir>/home`) et les
  chemins "virtuels" attendus par les binaires du bootstrap Termux
  (`/data/data/com.termux/files/usr`, `.../home`).
- **`BootstrapArch`** - associe `Build.SUPPORTED_ABIS` au nom d'architecture
  utilise par les releases termux-packages (`aarch64`, `arm`, `x86_64`,
  `i686`) et au nom de fichier de l'archive (`bootstrap-<arch>.zip`).
- **`BootstrapInstaller`** - classe separee de `TerminalSession` comme demande :
  - `isInstalled()` verifie un fichier marqueur (`.TERMATERIAL_BOOTSTRAP_VERSION`)
    contenant le tag de release installe ;
  - `install(forceReinstall)` retourne un `Flow<BootstrapProgress>`
    (`CheckingExistingInstallation` / `Downloading(bytesRead, totalBytes)` /
    `Extracting(entriesDone)` / `Installed` / `Failed`) exploitable directement
    par un ecran de progression Compose (Etape 3) ;
  - telecharge `bootstrap-<arch>.zip` depuis les releases GitHub de
    `termux/termux-packages` (tag configure dans `BOOTSTRAP_RELEASE_TAG`),
    l'extrait dans un dossier de staging, recree les symlinks listes dans
    `SYMLINKS.txt` (meme format que termux-app : `cible<fleche-gauche>lien`),
    positionne les bits d'execution sur `bin/`, `libexec`, `lib/apt/apt-helper`
    et `lib/apt/methods`, puis bascule le staging vers le dossier final de
    facon atomique (`renameTo`) ;
  - `forceReinstall = true` permet de reinstaller/mettre a jour plus tard
    (mentionne dans la demande).
- **`ProotShellSessionFactory`** - construit une `TerminalSession` (module
  `terminal-emulator` de l'Etape 1) dont le "shell" est en realite
  `proot` (deja present dans le bootstrap, comme demande), qui execute
  `bash --login` a l'interieur.

Un fichier `.gitignore` racine ignore deja `local.properties`/`build/` ; rien
de plus a ajouter pour ce module.

## Pourquoi proot pour lancer le propre shell de l'app (et pas seulement pour des distros invitees) ?

En lisant les scripts de build de `termux/termux-packages`
(`scripts/generate-bootstraps.sh`), deux faits importants ressortent :

1. **Les binaires du bootstrap ont le chemin `/data/data/com.termux/files/usr`
   code en dur** (shebang des scripts, RPATH/RUNPATH des binaires ELF,
   metadonnees dpkg). Comme Termaterial n'utilise pas le nom de paquet
   `com.termux`, ce chemin n'existe pas sur l'appareil. `proot` sert ici a
   *remapper* ce chemin virtuel vers le vrai dossier prive de l'app, via des
   bind mounts (`-b <reel>:<virtuel>`) - **pas** a executer une distribution
   Linux etrangere (usage habituel de `proot-distro` dans le vrai Termux).
2. Le script `generate-bootstraps.sh` construit en fait **deux variantes** de
   bootstrap : la variante "classique" (execution directe des binaires,
   utilisee par le vrai Termux depuis quelques annees, bootstrap precompile
   dans l'APK) et une variante **"Android 10 compatible"** qui, elle,
   `pull_package proot` explicitement - preuve que Termux lui-meme utilise
   `proot` pour contourner la meme contrainte que nous rencontrons ici
   (voir section suivante).

C'est ce qui valide et motive le choix de l'enonce de la tache ("environnement
chroote sans root").

## Point d'attention reel : restriction d'execution Android 10+ (W^X)

Depuis Android 10 (API 29), un fichier ecrit par l'app dans son propre
dossier prive (`/data/data/<pkg>/files/...`) **ne peut generalement plus etre
execute** (durcissement W^X applique par le systeme). C'est precisement pour
cette raison que le vrai Termux a arrete de telecharger son bootstrap au
premier lancement (ce que cette Etape 2 fait, comme demande) et l'embarque
desormais **au moment du build** dans `app/src/main/jniLibs/<abi>/` (dossier
natif de l'APK, exempte de cette restriction), sous la forme d'un
`libtermux-bootstrap.so` contenant le zip en tant que donnees, plus un
`proot` lui-meme place dans `jniLibs` pour la variante "Android 10
compatible".

Consequence concrete pour Termaterial : sur un appareil recent, `proot`
telecharge et extrait a l'execution (comme specifie) risque de ne pas pouvoir
etre lance directement par `execve()` sur certaines versions/configurations
Android. Deux options pour la suite, a valider avec vous :

1. **Vendoriser `proot` dans `jniLibs` au moment du build** (comme le fait
   Termux) : ajouter une tache Gradle qui recupere le binaire `proot` officiel
   par ABI et le place sous `app/src/main/jniLibs/<abi>/libproot.so` -
   `proot` serait alors dans le dossier natif exempte de la restriction, et
   resterait charge d'executer tout le reste (bash, apt, etc.) a l'interieur
   de son bac a sable. C'est la solution robuste et perenne, alignee sur ce
   que fait le vrai Termux.
2. Ne rien changer pour l'instant et traiter ce point avec les tests reels
   sur appareil/CI (Etape 6), en gardant a l'esprit qu'un correctif sera
   necessaire si l'exec echoue sur Android 10+.

Le code actuel suit la lettre de la demande (bootstrap + proot telecharges et
extraits au premier lancement) ; le point ci-dessus est documente pour qu'on
puisse decider ensemble plutot que de le corriger silencieusement.

## Bind mounts et variables d'environnement

`ProotShellSessionFactory.buildProotArgv` (teste unitairement) construit :

```
<prefixReel>/bin/proot
  --link2symlink --kill-on-exit --sysvipc -0
  -b /dev -b /proc
  [-b <stockage-externe>:/sdcard]      si accessible
  [-b /apex]                            si API >= 29 (resolution DNS)
  -b <prefixReel>:/data/data/com.termux/files/usr
  -b <homeReel>:/data/data/com.termux/files/home
  -w /data/data/com.termux/files/home
  /data/data/com.termux/files/usr/bin/bash --login
```

`buildShellEnvironment` fixe `HOME`, `PREFIX`, `PATH`, `LD_LIBRARY_PATH`,
`LANG`, `TERM=xterm-256color`, `COLORTERM=truecolor`, `TMPDIR`, toutes
pointant vers les chemins virtuels (coherent avec ce que les scripts du
bootstrap - `profile.d`, `apt`, etc. - attendent).

Notez que `cwd` passe a `TerminalSession` est le dossier **reel** (`proot`
ne remappe le chemin que pour le processus qu'il execute ensuite, pas pour le
`chdir()` fait par notre propre code juste avant l'`exec` de `proot` lui-meme).

## Permissions

Ni `BootstrapInstaller` ni `ProotShellSessionFactory` ne necessitent de
permission de stockage : tout se passe dans le stockage prive de l'app
(`context.filesDir`), conformement a l'Etape 5 de la demande. Le bind mount
optionnel `/sdcard` degrade proprement (simplement absent) si
`Environment.getExternalStorageDirectory()` n'est pas accessible - aucune
permission n'est demandee ici ; la gestion des permissions runtime et des
symlinks `~/storage/*` (acces au stockage partage) est prevue pour l'Etape 5.

## Tests

`shell/src/test` contient des tests JUnit purs (pas de dependance Android,
`BootstrapArch`, `buildProotArgv`, `buildShellEnvironment` sont des fonctions
pures) :

```
./gradlew :shell:testDebugUnitTest
```

Comme pour l'Etape 1, je n'ai pas pu executer cette commande dans ce bac a
sable (meme limitation reseau que documentee dans
`docs/step-1-terminal-engine.md`) ; le workflow CI GitHub Actions
(`.github/workflows/build.yml`) a ete mis a jour pour compiler et tester ce
nouveau module a chaque push.

## Pas encore fait (etapes suivantes)

- Ecran de progression Compose consommant `BootstrapInstaller.install()` et
  demande des permissions de stockage necessaires (Etape 3 / Etape 5).
- Symlinks `~/storage/*` vers le stockage partage (Etape 5).
- Foreground service gardant la session active en arriere-plan (Etape 5).
- Decision sur le vendoring de `proot` en `jniLibs` (voir plus haut).
