# Déploiement Kairos — Docker (recommandé)

> Kairos isolé dans des conteneurs ; planification **interne** (supercronic) ; conteneurs non-root.
> Alternative non-Docker (binaire + systemd) : voir `../systemd/` et `../README.md`.

## Composants

- `Dockerfile` — build multi-stage du binaire Rust → image `debian-slim` (+ `supercronic`).
- `docker-compose.yml` :
  - `ollama` — service persistant, non exposé (réseau interne only) ;
  - `radicale` — serveur du module calendar ;
  - `scheduler` — toujours actif, lance `scrape`/`calendar sync`/`briefing` via `supercronic` ;
  - `kairos` — profil `tools`, pour les exécutions manuelles ;
  - `kairos-secondary` / `scheduler-secondary` — profil supplémentaire isolé (même image, sa propre
    config/DB/notif via `kairos-secondary.env` — cf. section dédiée ci-dessous).
- `supercronic.crontab` — planning interne (scrape, calendar sync, briefing ; fuseau `TZ`).
- `kairos.env` (optionnel, gitignoré) — secrets ; copier depuis `../kairos.env.example`.

> L'envoi écarte tout fichier ignoré par le dépôt. Les configurations réelles de
> `config/` (profil, modules, modèle) et les secrets appartiennent donc à l'hôte
> cible et n'y sont jamais écrasés par celles du poste qui déploie : elles se
> créent et se modifient sur place, depuis les fichiers d'exemple. Le corollaire
> est qu'un nouveau champ de configuration se répercute à la main.

## Mise en place (sur le serveur, dans `~/kairos/deploy/docker`)

```bash
cd ~/kairos/deploy/docker

# 1. (optionnel) secrets — les collecteurs sans clé fonctionnent quand même
cp ../kairos.env.example kairos.env && "$EDITOR" kairos.env   # JOOBLE_API_KEY, etc.

# 2. build + Ollama + modèle
docker compose build
docker compose up -d ollama
docker compose exec ollama ollama pull phi3:mini             # ~2,2 Go (une fois)

# 3. test manuel
docker compose run --rm kairos scrape
docker compose run --rm kairos briefing
cat ~/kairos/briefings/$(date +%F).md

# 4. démarrer le planificateur (remplace le cron hôte)
docker compose up -d scheduler
docker compose logs scheduler        # doit lister les 2 jobs chargés
```

## Calendar (module optionnel)

Serveur du module calendar. Créer la config d'auth locale, démarrer
le service, puis renseigner `CALENDAR_*` dans `kairos.env` (URL = collection directe,
pas le principal) :

```bash
cd radicale/config && cp config.example config
# créer le fichier d'auth (cf. config.example)
docker compose up -d radicale
docker compose run --rm kairos calendar sync   # vérifie la config
```

Adresse de bind du serveur configurable via `RADICALE_BIND_ADDR` (défaut : boucle locale).
Les spécificités d'hôte (exposition réseau, client) restent hors du dépôt public.

## Notifications (module optionnel)

Alertes push optionnelles pour les offres fortes (score > 0.9), servies par un service
auto-hébergé déclaré dans un `docker-compose.override.yml` local à l'hôte (non versionné).
Configuration via `ALERT_URL`, `ALERT_TOPIC` et `ALERT_TOKEN` dans `kairos.env`.

## Tentacules (mémoire, agenda, finances)

Services MCP (HTTP, chemin `/mcp`) : `memoire` (base dédiée, volume `memoire-data`) et `agenda`
(`kairos.db` en lecture-écriture). Ils refusent de démarrer sans jeton.

`agenda` reprend les variables `CALENDAR_URL`, `CALENDAR_WORK_URL`, `CALENDAR_USERNAME` et `CALENDAR_PASSWORD`
de `kairos.env` (celles de la synchronisation de nuit) pour resynchroniser à la demande de l'application
(au plus une fois par minute) et y ajouter des événements. Absentes : lecture de la base locale seule, ajout refusé.

Générer un jeton par service (`MEMOIRE_TOKEN`, `AGENDA_TOKEN`, `FINANCES_TOKEN`, `ALIMENTATION_TOKEN`) et les renseigner dans `kairos.env` :

```bash
openssl rand -hex 32   # -> MEMOIRE_TOKEN ; relancer pour AGENDA_TOKEN, FINANCES_TOKEN, puis ALIMENTATION_TOKEN
```

Publication : `${TENTACLES_BIND_ADDR:-127.0.0.1}` (défaut : boucle locale), ports `MEMOIRE_BIND_PORT` (8091) et
`AGENDA_BIND_PORT` (8092). `TENTACLES_BIND_ADDR` est une variable de substitution de compose : elle se définit dans
`deploy/docker/.env` (gitignoré) ou dans l'environnement du shell, **pas** dans `kairos.env`
(qui n'alimente que l'intérieur des conteneurs). L'adresse d'écoute doit exister avant le démarrage des
conteneurs : démarrer le réseau privé avant Docker.

L'image `kairos:local` doit exister (construite par `scheduler`) ; `agenda` exige que `kairos.db` existe
(`kairos` doit avoir tourné une fois).

```bash
docker compose build scheduler
docker compose up -d memoire agenda
```

### Finances

`finances` est un tentacule sans état (aucun volume, aucune dépendance au moteur local) qui interroge une API de
finances personnelles externe au compose. Variables de `kairos.env` : `FINANCES_TOKEN` (jeton d'accès propre au tentacule, obligatoire ; généré avec `openssl rand -hex 32`),
`FINANCES_API_URL` (adresse privée de l'API, joignable depuis le conteneur `finances` et pas seulement depuis l'hôte : une adresse de boucle locale de l'hôte ne l'est pas ; obligatoire, refus de démarrer sans),
`FINANCES_API_TOKEN` (non généré ici : copié à l'identique du jeton propre à l'API de finances) et, facultatif, `FINANCES_DASHBOARD_URL`. Aucun couplage réseau dans ce
compose : l'adresse vient uniquement de la configuration. Port publié : `FINANCES_BIND_PORT` (8094).

Module alimentation : stock du logement, commandes de courses, repas, préférences. Base SQLite dans le volume
nommé `alimentation-data` ; fiches produits lues dans la base ouverte Open Food Facts (sortie HTTPS). Variable de
`kairos.env` : `ALIMENTATION_TOKEN` (obligatoire, `openssl rand -hex 32`). Port publié : `ALIMENTATION_BIND_PORT` (8098).
Calendrier des repas (optionnel, les trois ou aucune) : `ALIMENTATION_CALDAV_URL` (adresse de la collection CalDAV),
`ALIMENTATION_CALDAV_USER`, `ALIMENTATION_CALDAV_PASS`. Absentes : le plan de la semaine est enregistré sans agenda.

Module marchés : veille de marchés de prédiction en lecture seule. Base SQLite dans le volume `marches-data`. Variables de `kairos.env` : `MARCHES_TOKEN` (obligatoire, `openssl rand -hex 32`), `MARCHES_API_URL` (obligatoire, adresse de la source), `MARCHES_KEYWORDS` (liste séparée par des virgules), `MARCHES_POLL_MINUTES` (optionnel, 30 par défaut). Port publié : `MARCHES_BIND_PORT` (8099).

Déclarer ensuite le tentacule dans `config/tete.toml` (modèle : `config/tete.example.toml`). L'image `kairos:local`
doit exister :

```bash
docker compose build scheduler
docker compose up -d finances
```

## Tête (processus principal)

`tete` est le processus qui relie les tentacules, le moteur de langage et une passerelle de messagerie :
conversation, briefs du matin et du soir, suggestions, résumés nocturnes. Il tourne en continu (`mem_limit: 64m`)
et joint les tentacules par leur nom de service compose.

Contrat de la passerelle (HTTP, JSON `{"text": "..."}`) :
- entrant : la passerelle fait `POST /message` sur la tête avec `Authorization: Bearer <jeton entrant>` ; réponse `202`.
- sortant : la tête fait `POST` sur `TETE_OUTBOUND_URL` avec `Authorization: Bearer <jeton sortant>`.
- la passerelle filtre l'expéditeur et le canal (seul l'utilisateur configuré passe), ignore l'arriéré
  à son démarrage et découpe les messages trop longs. La tête n'accepte que les appels portant le jeton entrant.

Jetons (obligatoires, refus de démarrer si le jeton entrant est vide), à renseigner dans `kairos.env` :

```bash
openssl rand -hex 32   # -> TETE_INBOUND_TOKEN ; relancer pour TETE_OUTBOUND_TOKEN
```

Configuration : copier `config/tete.example.toml` en `config/tete.toml` (gitignoré) et déclarer les tentacules
(URL internes du compose, jeton lu dans une variable d'environnement). Chemins surchargeables :
`TETE_CONFIG`, `TETE_DB` (défaut `data/tete.db`). Port hôte : `TETE_BIND_PORT` (8093), publié sur `${TENTACLES_BIND_ADDR:-127.0.0.1}`.

Moteur de langage : `config/llm.toml` doit déclarer les usages `conversation`, `resume_conversation`, `brief` et
`suggestion` dans `[usages]`. Tous sont sensibles : si le moteur est externe, chacun exige une concession explicite
dans `strategic_concessions`. La tête n'utilise que les usages déclarés : la voie auto-hébergée par défaut (y compris
celle renseignée par l'environnement) ne sert jamais de repli. Un usage non déclaré se dégrade proprement : réponse
fixe en conversation, brief envoyé brut, ni suggestion ni résumé.

```toml
# Seulement si le moteur est externe : chaque usage listé quitte la machine.
strategic_concessions = ["conversation", "resume_conversation", "brief", "suggestion"]

[engines.principal]
endpoint = ""
model = ""
protocol = ""    # "ollama", "openai-compatible" ou "messages"
placement = ""   # "self_hosted" ou "external"

[usages]
conversation = "principal"
resume_conversation = "principal"
brief = "principal"
suggestion = "principal"
```

Après chaque redémarrage, le premier passage de la minute peut tenter une suggestion : elle reste bornée par le
budget quotidien d'interruptions et par la politique de sortie.

```bash
docker compose build scheduler
docker compose up -d tete
```

## Application mobile

La tête sert une API pour l'application (préfixe `/app/v1`, jeton propre à chaque appareil) sur une seconde écoute,
active seulement si `TETE_APP_BIND` est renseignée. Dans `kairos.env` :

- `TETE_APP_BIND=0.0.0.0:8091` (port du conteneur, ne pas changer en Docker) ;
- `TETE_APP_PUBLIC_URL` : adresse de l'API telle que l'appareil la joint (inscrite dans le QR code d'appairage).

Publication sur l'hôte : `TETE_APP_BIND_ADDR` (défaut `127.0.0.1`) et `TETE_APP_PORT` (défaut 8100) sont des
variables de substitution du compose, comme `TENTACLES_BIND_ADDR` (shell ou fichier `.env` du compose), pas des
entrées de `kairos.env`. Avec le défaut, le téléphone ne joint pas l'API : `TETE_APP_BIND_ADDR` doit valoir l'adresse
de l'hôte sur le réseau privé, jamais celle d'une interface publique.

Appairage d'un appareil (affiche un QR code à scanner ; seule l'empreinte du jeton est conservée) :

```bash
docker compose exec tete /app/tete pair <nom>
docker compose exec tete /app/tete devices        # liste
docker compose exec tete /app/tete revoke <nom>   # révocation immédiate
```

Mise à jour de l'application : la tête sert l'APK présent dans `TETE_APP_APK_DIR` (`/app/apk` dans le conteneur,
monté en lecture seule depuis le dossier `apk/` à la racine du déploiement ; un seul fichier `kairos-vX.Y.Z.apk`,
version lue dans le nom). Sans APK, l'application ne propose aucune mise à jour. `deploy.sh` y dépose, depuis le
poste de l'opérateur, l'APK de la release `v<version>` du `Cargo.toml` racine via `gh release download` (dépôt
résolu par `gh` depuis le working tree) et retire les anciens ; sans `gh` authentifié ou sans APK dans la release,
l'étape est sautée avec un message et le déploiement continue.

Les tentacules qui exposent `cards`, `decisions` et `decide` alimentent le fil du jour et les décisions ; ces outils
ne sont jamais proposés au moteur de langage.

## Profil supplémentaire (optionnel)

Fait tourner un profil supplémentaire isolé (config, base de données, canal de digest distinct)
sur la même image, sans rien dupliquer côté code.

```bash
# 1. config du profil supplémentaire (gitignorée)
cp ../../config/profile.example.toml ../../config/profile-secondary.toml
"$EDITOR" ../../config/profile-secondary.toml

# 2. secrets/config déploiement du profil supplémentaire
cp ../kairos.env.example kairos-secondary.env
"$EDITOR" kairos-secondary.env   # au minimum NOTIFY_CHANNEL_ID (canal dédié)

# 3. test manuel
docker compose run --rm kairos-secondary scrape
docker compose run --rm kairos-secondary briefing

# 4. démarrer son propre planificateur
docker compose up -d scheduler-secondary
```

Base de données et briefings entièrement séparés de ceux du profil principal
(`data/secondary.db`, `briefings-secondary/`) — aucun risque de mélange.

**Migration requise pour le profil déjà en place** (mise à jour vers cette version) :
- `config/profile.toml` doit être migré au format `[skills] skills = [...]` (liste plate,
  remplace les anciennes catégories `backend`/`frontend`/etc.) — sans ça le binaire refuse
  de démarrer (erreur de parsing TOML).
- Relancer `docker compose run --rm kairos rescore` après le déploiement : le calcul du
  bonus salaire est maintenant relatif à `salary_min`/`salary_target` du profil (avant :
  seuils fixes) — sans ce rerun, les scores déjà en base restent calculés à l'ancienne
  formule tant qu'une offre n'est pas re-scrapée.

## Notes

- **Planification interne** : le conteneur `scheduler` (supercronic) déclenche les batchs (scrape + calendar sync + briefing).
- Ollama n'expose **aucun port** (réseau interne compose uniquement).
- phi3:mini tourne sur CPU (batch nocturne) ; ajouter un `mem_limit` sur `ollama` si besoin.
- `scrape`/`briefing` **n'utilisent pas** le LLM (matching = règles Rust, enrichissement = scraping). phi3:mini sert au futur `prompt`/analyse privée.
- Redémarrage du serveur : `restart: unless-stopped` relance `ollama` + `scheduler`.
- Teardown : `docker compose down -v` (supprime volumes DB + modèles).
- Fuseau : la variable `TZ` des conteneurs pilote le fuseau de planification.
