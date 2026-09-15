# Etape 2 - Shell backend (bootstrap + exec direct)

> Cette etape a ete revisee une fois en cours de route : la premiere version
> utilisait `proot`, mais en telechargeant et en inspectant le vrai
> `bootstrap-aarch64.zip` de termux-packages, il s'est avere que `proot`
> n'est pas inclus dans le bootstrap publie officiellement. La section
> "Ce qui a change" ci-dessous explique pourquoi et ce qui a ete fait a la
> place, valide avec vous.

## Ce qui a ete fait

Nouveau module `shell` (`io.termaterial.shell`), qui depend de `terminal-emulator`
et n'a aucun lien avec l'UI :

- **`TermaterialPaths`** - chemins reels dans le stockage prive de l'app :
  `<filesDir>/usr` (prefix final), `<filesDir>/usr-staging` (extraction),
  `<filesDir>/home`.
- **`BootstrapArch`** - associe `Build.SUPPORTED_ABIS` au nom d'architecture
  utilise par les releases termux-packages (`aarch64`, `arm`, `x86_64`,
  `i686`) et au nom de fichier de l'archive (`bootstrap-<arch>.zip`).
- **`BootstrapInstaller`** - classe separee de la session shell, comme demande :
  - `isInstalled()` verifie un fichier marqueur (`.TERMATERIAL_BOOTSTRAP_VERSION`)
    contenant le tag de release installe ;
  - `install(forceReinstall)` retourne un `Flow<BootstrapProgress>`
    (`CheckingExistingInstallation` / `Downloading(bytesRead, totalBytes)` /
    `Extracting(entriesDone)` / `Installed` / `Failed`) exploitable directement
    par un ecran de progression Compose (Etape 3) ;
  - telecharge `bootstrap-<arch>.zip` depuis les releases GitHub de
    `termux/termux-packages` (tag configure dans `BOOTSTRAP_RELEASE_TAG`),
    l'extrait dans un dossier de staging, recree les symlinks listes dans
    `SYMLINKS.txt` (`cible<fleche-gauche>lien`), positionne les bits
    d'execution sur `bin/`, `libexec`, `lib/apt/apt-helper` et
    `lib/apt/methods`, puis bascule le staging vers le dossier final de
    facon atomique (`renameTo`) ;
  - `forceReinstall = true` permet de reinstaller/mettre a jour plus tard.
- **`BootstrapShellSessionFactory`** - construit une `TerminalSession` (module
  `terminal-emulator` de l'Etape 1) qui lance directement `bash --login` du
  bootstrap extrait, avec un environnement corrige (voir plus bas).

## Ce qui a change : pourquoi pas proot au final

La demande initiale precisait d'utiliser `proot` (present dans le bootstrap
Termux) pour lancer le shell. En verifiant reellement le contenu d'un
bootstrap publie (`git ls-remote` sur les tags de `termux-packages` pour
trouver le tag courant, puis telechargement direct de
`bootstrap-aarch64.zip` depuis les releases GitHub - accessible depuis ce
bac a sable, contrairement a `dl.google.com`), deux choses se sont averees :

1. **Ce zip ne contient pas de binaire `proot`.** En lisant
   `scripts/generate-bootstraps.sh` de `termux-packages`, `proot` n'est
   `pull_package`-e que dans une variante speciale
   (`BOOTSTRAP_ANDROID10_COMPATIBLE=true`), qui n'est pas celle que le
   workflow GitHub Actions programme (`bootstrap_archives.yml`) publie
   chaque semaine. Le bootstrap reellement telechargeable ne l'a donc
   jamais eu.
2. **Les binaires du bootstrap n'ont pas besoin de proot pour s'executer.**
   `readelf -l bin/bash` montre `Requesting program interpreter:
   /system/bin/linker64` : ce sont des executables ELF Android normaux,
   lances directement par le vrai linker du systeme - exactement comme le
   fait le vrai Termux (qui n'a plus utilise proot pour son propre
   environnement depuis 2021). Le seul probleme reel est que
   `readelf -d bin/bash` montre un `RUNPATH` fige a
   `/data/data/com.termux/files/usr/lib` - un chemin qui n'existe pas ici
   puisque Termaterial n'utilise pas ce nom de paquet.

Le linker dynamique Android consulte `LD_LIBRARY_PATH` **avant** le
`RUNPATH` du binaire. Il suffit donc de positionner `LD_LIBRARY_PATH` sur le
vrai dossier `lib` de l'app pour que `bash` trouve ses bibliotheques
(`libreadline.so.8`, `libandroid-support.so`, `libiconv.so`, ...) sans
bind mount, sans faux chroot, et sans la latence d'interception d'appels
systeme de proot.

`BootstrapShellSessionFactory.buildShellEnvironment` (teste unitairement)
fixe donc :

```
HOME=<homeReel>
PREFIX=<prefixReel>
PATH=<prefixReel>/bin
LD_LIBRARY_PATH=<prefixReel>/lib
LANG=en_US.UTF-8
TERM=xterm-256color
COLORTERM=truecolor
TMPDIR=<prefixReel>/tmp
```

et la session est creee avec `shellPath = cwd = <prefixReel>/bin/bash`,
`args = [bash, --login]`.

### Limite connue : scripts avec shebang code en dur

`LD_LIBRARY_PATH` resout le probleme pour les executables ELF (`bash`,
`apt`, `dpkg`, coreutils...). Il reste possible que de rares scripts du
bootstrap (scripts de maintenance dpkg, post/pre-install de certains
paquets) commencent par un shebang litteral
`#!/data/data/com.termux/files/usr/bin/bash` : le noyau resoudrait ce
chemin pour de vrai et echouerait puisqu'il n'existe pas. Cela ne bloque
pas l'usage courant du terminal (execution interactive de commandes,
coreutils, edition de fichiers) ; a verifier et corriger au cas par cas une
fois testable sur un vrai appareil/emulateur (Etape 6), par exemple en
patchant le shebang de ces scripts specifiques lors de l'extraction si un
cas concret se presente.

## Restriction d'execution Android 10+ (W^X) - confirmee, puis contournee

Confirme sur un vrai appareil (Etape 6) : `exec("/data/user/0/io.termaterial.app/files/usr/bin/bash"): Permission denied`,
exactement la restriction anticipee ici. Cette contrainte ne depend pas de
proot : depuis Android 10 (API 29), un fichier ecrit par l'app dans son
propre dossier prive (`/data/data/<pkg>/files/...`) ne peut generalement
plus etre execute (durcissement W^X). Elle s'applique de la meme facon a
`bash` execute directement qu'elle se serait appliquee a `proot`.

**Contournement retenu** : cette restriction n'est pas liee a la version
d'Android de l'appareil, mais au `targetSdkVersion` **declare par l'app**
(changement de comportement documente par Google comme s'appliquant "aux
apps ciblant l'API 29+"). `app/build.gradle.kts` fixe donc volontairement
`targetSdk = 28` (au lieu des 35 partages par les autres modules via
`gradle.properties`) : l'app garde le comportement historique (autorisee a
executer les fichiers qu'elle a elle-meme extraits) meme sur un appareil
recent. Verifie efficace sur le meme appareil qui reproduisait l'erreur.

Limites de ce choix, assumees pour une app sideloadee (pas de publication
Play Store envisagee, qui imposerait de toute facon un `targetSdk` bien
plus recent) :
- Perd certains comportements/protections par defaut lies aux
  `targetSdk` recents (globalement dans le sens "plus permissif", ce qui
  ne pose pas de probleme ici).
- Si un jour un `targetSdk` recent redevient necessaire (publication,
  exigence d'une lib tierce...), il faudra alors la solution plus lourde :
  embarquer `bash` (et ses `.so`) - voire `proot` - dans
  `app/src/main/jniLibs/<abi>/` au moment du build, comme le fait le vrai
  Termux aujourd'hui. Explore et abandonnee pour l'instant : ni le
  bootstrap zip officiel ni le depot `termux/proot` ne publient de binaire
  precompile telechargeable depuis les canaux accessibles (le CDN de
  paquets Termux est bloque depuis le bac a sable de dev, et `termux/proot`
  n'a pas de pipeline de release GitHub - seul `termux-packages`, egalement
  bloque, le compile) ; construire `proot` depuis les sources (avec sa
  dependance `libtalloc` et son composant `loader`) est un chantier de
  compilation croisee NDK consequent, a envisager seulement si le
  contournement `targetSdk` s'avere insuffisant.

## Permissions

Ni `BootstrapInstaller` ni `BootstrapShellSessionFactory` ne necessitent de
permission de stockage : tout se passe dans le stockage prive de l'app
(`context.filesDir`), conformement a l'Etape 5 de la demande. La gestion
des permissions runtime et des symlinks `~/storage/*` (acces au stockage
partage) est prevue pour l'Etape 5.

## Tests

`shell/src/test` contient des tests JUnit purs (pas de dependance Android) :

```
./gradlew :shell:testDebugUnitTest
```

Comme pour l'Etape 1, je n'ai pas pu executer les commandes Gradle Android
dans ce bac a sable (meme limitation reseau que documentee dans
`docs/step-1-terminal-engine.md`, `dl.google.com`/`maven.google.com` restent
bloques) ; le workflow CI GitHub Actions (`.github/workflows/build.yml`)
compile et teste ce module a chaque push. En revanche, `github.com` (releases
incluses) est accessible depuis ce bac a sable, ce qui a permis de verifier
empiriquement le contenu reel du bootstrap (section ci-dessus) plutot que de
se fier uniquement a la lecture du code source de termux-packages.

## Pas encore fait (etapes suivantes)

- Ecran de progression Compose consommant `BootstrapInstaller.install()` et
  demande des permissions de stockage necessaires (Etape 3 / Etape 5).
- Symlinks `~/storage/*` vers le stockage partage (Etape 5).
- Foreground service gardant la session active en arriere-plan (Etape 5).
- Decision sur l'embarquement de `bash`/`jniLibs` pour Android 10+ (voir
  plus haut), a prendre avant de considerer l'Etape 6 terminee.
