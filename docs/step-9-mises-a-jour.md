# Etape 9 - Mise a jour integree

L'app se met a jour elle-meme depuis les releases GitHub que la CI publie a
chaque push (une release par build, voir `.github/workflows/build.yml`).

## Parcours

1. **Verification** : au demarrage (au plus toutes les 6 h, reglage
   "Verifier au demarrage") ou via Reglages -> Mises a jour -> "Rechercher une
   mise a jour". L'app lit `GET /repos/<depot>/releases` (API publique, sans
   compte) et garde les releases dont le numero de build (fin du tag
   `v0.2.0-debug.<n>`, egal au `versionCode`) depasse celui installe.
2. **Dialogue** : version proposee, puis le changelog de *toutes* les
   versions manquees, de la plus recente a la plus ancienne. "Plus tard" :
   le dialogue ne se rouvrira pas seul pour cette version (elle reste
   accessible depuis les reglages, signalee par un badge sur l'icone).
3. **Telechargement** : barre de progression (taille, pourcentage),
   annulable. L'APK est verifie avant installation : SHA-256 publie par
   GitHub pour la piece jointe (`digest`), nom de paquet et `versionCode`
   superieur.
4. **Installation** : l'APK est confie a l'installateur Android
   (`PackageInstaller`) qui demande confirmation - et, la premiere fois,
   l'autorisation "Installer des applis inconnues" pour Termaterial.
   L'installation ferme l'app et ses sessions. Les builds de la CI sont tous
   signes avec la meme cle debug, donc s'installent en mise a jour.

## Changelog

`scripts/release-notes.sh` ecrit les notes de chaque release : les sujets
des commits depuis la release precedente (prefixes `feat(app):`, `fix:`...
retires, commits `chore` exclus), puis un marqueur
`<!-- termaterial:changelog-end -->` (invisible sur GitHub) et les details du
build. L'app n'affiche que ce qui precede le marqueur ; les releases
anterieures a ce format apparaissent sans notes.

## Code

- `app/.../update/AppRelease.kt` : modele et lecture du JSON de GitHub
  (`ReleaseParser`, teste dont sur une vraie reponse de l'API du depot).
- `app/.../update/UpdateManager.kt` : verification, telechargement,
  verification, etat expose a l'UI ; au niveau du processus, donc un
  telechargement survit a la recreation de l'Activity.
- `app/.../update/ApkInstaller.kt` : session `PackageInstaller` et
  `UpdateInstallReceiver` (confirmation systeme, resultat).
- `app/.../ui/screens/UpdateDialog.kt` : dialogue Material 3.
- `shell/.../HttpDownload.kt` : client HTTPS partage avec l'installation du
  bootstrap (redirections suivies a la main, SHA-256, annulation), teste
  contre un petit serveur local.

Le depot suivi est celui ou la CI construit (`GITHUB_REPOSITORY`, donc un
fork se met a jour depuis lui-meme), `Karelisio/Termaterial` pour un build
local. Necessite la permission `REQUEST_INSTALL_PACKAGES`.

## Limites

- La premiere version qui contient cette fonction doit etre installee a la
  main (ou via Obtainium) ; les suivantes peuvent l'etre depuis l'app.
- API GitHub sans compte : 60 requetes/heure par adresse IP, largement
  suffisant ici (message clair si la limite est atteinte).
- Non teste sur appareil (pas d'emulateur ici) : verification, parsing,
  telechargement et changelog sont couverts par des tests ; le passage par
  l'installateur Android reste a confirmer sur telephone.
