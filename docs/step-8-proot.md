# Etape 8 - Mode proot : installer des paquets

Suite de [`step-7-reprise.md`](step-7-reprise.md) : choix de l'option B
(proot) pour que `pkg install` / `apt install` fonctionnent, en gardant le
nom de paquet `io.termaterial.app` (cohabitation avec Termux).

## Principe

Tout ce que termux-packages construit a `/data/data/com.termux` code en dur
(chemins des `.deb`, scripts de maintenance, chemins compiles dans les
binaires). En mode proot, le shell tourne sous
[proot](https://github.com/termux/proot) avec le dossier de donnees de
l'app monte a cet endroit :

```
libproot.so --kill-on-exit --link2symlink \
    -b /data/user/0/io.termaterial.app:/data/data/com.termux \
    -w /data/data/com.termux/files/home \
    /data/data/com.termux/files/usr/bin/login
```

Tous les chemins Termux resolvent donc, comme dans Termux : dpkg extrait les
paquets au bon endroit, leurs scripts s'executent, et chaque programme
installe trouve ses fichiers. On lance le script `login` de Termux lui-meme
(termux-exec, `SHELL`, `bash -l` qui lit `$PREFIX/etc/profile` et les
fichiers de l'utilisateur). Plus besoin de `LD_LIBRARY_PATH` ni
d'`APT_CONFIG` dans ce mode.

- `--kill-on-exit` : quand le shell se termine, ses processus en arriere-plan
  aussi (sinon ils continueraient sans proot, donc sans ces chemins).
- `--link2symlink` : Android interdit les liens physiques aux applications ;
  proot les emule (utile a git, npm...).
- `TERMUX_HUSHLOGIN=1` : pas de banniere "Welcome to Termux" (elle renvoie
  vers le support de Termux, pas de cette app).

Cout : proot intercepte les appels systeme (ptrace, accelere par seccomp),
donc un peu plus lent que Termux, surtout pour les taches tres orientees
fichiers (compilation...). Les outils qui utilisent eux-memes ptrace
(`strace`, `gdb`) ne fonctionnent pas sous proot.

## D'ou vient proot

Pas compile ici : `scripts/fetch-proot.sh` (lance par la CI avant le build)
telecharge depuis le depot Termux les paquets `proot`, `libtalloc` et
`libandroid-shmem` de chaque architecture, verifie leur SHA-256 dans l'index
du depot, et les place dans `app/src/main/jniLibs/<abi>/` :

| Fichier dans l'APK      | Origine                          |
|-------------------------|----------------------------------|
| `libproot.so`           | `bin/proot`                      |
| `libproot-loader.so`    | `libexec/proot/loader`           |
| `libproot-loader32.so`  | `libexec/proot/loader32` (64 bits) |
| `libtalloc.so`          | `lib/libtalloc.so.2`             |
| `libandroid-shmem.so`   | `lib/libandroid-shmem.so`        |

Android extrait ces fichiers dans le dossier des bibliotheques natives de
l'app (`useLegacyPackaging = true`), le seul endroit d'ou une app peut
toujours executer un fichier. Seuls les en-tetes ELF sont modifies
(`patchelf`) : `DT_RUNPATH` -> `$ORIGIN` et `libtalloc.so.2` ->
`libtalloc.so` (Android n'extrait que des fichiers `lib*.so`). proot
cherche normalement son loader et son dossier temporaire sous le `$PREFIX`
de Termux : `PROOT_LOADER`, `PROOT_LOADER_32` et `PROOT_TMP_DIR` lui donnent
les vrais chemins.

Le script a ete verifie localement contre un faux depot reproduisant la
structure de celui de Termux (index, `.deb`, liens symboliques, binaires NDK
factices pour les 4 ABI) ; la CI l'execute contre le vrai depot et echoue si
un binaire attendu manque de l'APK. Les versions utilisees et les sources
(GPL-2.0 pour proot, LGPL-3.0 pour talloc, BSD-3-Clause pour
libandroid-shmem) sont listees dans les notes de chaque release.

## Reglage et repli

Reglages -> Environnement -> "Compatibilite Termux (proot)", active par
defaut, s'applique aux nouveaux onglets. Desactive, ou dans un APK construit
sans le script (build local), le shell est lance directement comme avant
(mode direct de l'etape 7, sans installation de paquets possible).

Si proot ne demarre pas sur un appareil, son message d'erreur reste affiche
dans l'onglet (`[Process completed ...]`) : desactiver le reglage permet de
retrouver un shell en attendant un correctif.

## Non verifie

Pas d'appareil ni d'emulateur ici : le fonctionnement reel de proot avec ces
chemins (et `pkg install`) reste a confirmer sur telephone.

Effet de bord connu : les fichiers de configuration du bootstrap reecrits a
l'etape 7 (`etc/profile`, `etc/bash.bashrc`...) different de ceux des
paquets ; une mise a jour de ces paquets (`pkg upgrade`) peut donc demander
s'il faut garder la version installee ou celle du paquet. Les deux
fonctionnent en mode proot.
