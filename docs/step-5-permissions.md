# Etape 5 - Permissions Android & foreground service

## Ce qui a ete fait

### Stockage

Rien a demander : `BootstrapInstaller`/`BootstrapShellSessionFactory` (Etape 2) n'utilisent que
le stockage prive de l'app (`context.filesDir`), donc aucune permission de stockage n'est
necessaire, meme sur API < 30 - conforme a ce que la demande notait deja
("utiliser le stockage prive de l'app evite la plupart des permissions de stockage").

### POST_NOTIFICATIONS (Android 13+)

Demandee au runtime dans `MainActivity.onCreate()` via
`ActivityResultContracts.RequestPermission()` (`registerForActivityResult` - doit etre appele
avant que l'Activity atteigne l'etat STARTED, donc comme propriete de la classe, pas dans un
composable). Aucune gestion speciale du refus n'est necessaire : `NotificationManager.notify()`
ne fait simplement rien si la permission est refusee (pas d'exception), et un service au premier
plan (`startForeground()`) demarre et garde sa priorite meme sans cette permission - seule la
notification reste invisible. L'app degrade donc deja proprement sans code special ; c'est
documente explicitement dans `MainActivity.kt` pour que ce ne soit pas pris pour un oubli.

### Foreground Service

**`service/TerminalSessionService.kt`** - garde le processus de l'app actif (et donc les threads
d'E/S de chaque `TerminalSession`, qui tournent independamment du cycle de vie de l'Activity)
meme quand l'app passe en arriere-plan, avec une notification persistante (requis par Android
pour les services longue duree), comme demande.

Choix d'architecture : le service ne possede pas les sessions lui-meme (elles restent dans l'etat
Compose de `MainActivity`, construit aux Etapes 3-4) - une notification persistante n'a besoin que
du *nombre* de sessions et du titre actif pour s'afficher, pas des sessions elles-memes. Dupliquer
la possession des sessions dans le service aurait ete un changement d'architecture plus important
sans benefice reel a ce stade (voir "Limites connues" plus bas si la persistance entre redemarrages
du process devient necessaire).

- `MainActivity` demarre le service (`ContextCompat.startForegroundService`, requis depuis l'API 26
  pour un service qui va immediatement passer au premier plan) et s'y lie (`bindService`) des
  qu'un premier onglet existe.
- Le service expose `updateStatus(sessionCount, activeTitle)` via un `Binder` local ;
  `MainActivity` l'appelle a chaque changement du nombre d'onglets ou du titre de l'onglet actif.
- Type de foreground service : `specialUse` (le seul type generique disponible depuis l'API 34
  pour ce genre d'usage - "garder un shell interactif actif en arriere-plan" ne correspond a
  aucun type specifique comme `mediaPlayback` ou `location`), avec la `<property>` de justification
  requise par le manifeste. `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`
  sur API 34+, variante sans type explicite en dessous (l'attribut de manifeste `specialUse`
  n'existe pas avant l'API 34 et est ignore silencieusement, comportement standard Android).
- Permissions manifeste (non demandees au runtime, ce sont des permissions "normal") :
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`.

## Tests / verification

Comme pour les etapes precedentes, aucune commande Gradle Android n'a pu etre executee dans ce
bac a sable. **Point important** : les 5 premiers runs CI avaient en fait echoue depuis l'Etape 1
pour une raison sans rapport avec le code du projet - `android-actions/setup-android@v3` (utilise
dans `.github/workflows/build.yml`) est desormais casse (tente d'installer un paquet SDK legacy
qui n'existe plus). Corrige dans un commit separe avant cette etape (installation manuelle du SDK
via `sdkmanager`) - a verifier au prochain push que la CI passe enfin reellement, ce qui donnera
la premiere confirmation de compilation de tout ce qui a ete ecrit depuis l'Etape 1.

## Limites connues

- Le service ne persiste pas la liste des onglets sur disque : si le systeme tue completement le
  processus (rare une fois le foreground service actif, mais possible sous pression memoire
  extreme), les onglets sont perdus au redemarrage plutot que restaures. Le vrai Termux persiste
  ses sessions via son propre `TermuxService` qui les possede directement ; notre choix
  d'architecture (sessions dans l'etat Compose de l'Activity) rendrait cet ajout possible plus
  tard mais represente un changement plus consequent, hors scope ici.
- `updateStatus(0, ...)` appelle `stopSelf()`, mais tant que `MainActivity` reste liee
  (`bindService`), le service ne s'arrete reellement qu'a la deconnexion (`onDestroy` de
  l'Activity) - en pratique les onglets ne restent jamais vides longtemps (rouverture automatique,
  Etape 4), donc ce chemin est surtout theorique pour l'instant.
- Le point Android 10+ laisse ouvert a l'Etape 2 (exec du bootstrap telecharge dans le dossier
  prive de l'app) reste entier et devra etre verifie/traite a l'Etape 6.
