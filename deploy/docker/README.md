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
  - `kairos-secondary` / `scheduler-secondary` — second profil isolé (même image, sa propre
    config/DB/notif via `kairos-secondary.env` — cf. section dédiée ci-dessous).
- `supercronic.crontab` — planning interne (scrape 04:30, calendar sync 04:45, briefing 05:00 ; fuseau `TZ`).
- `kairos.env` (optionnel, gitignoré) — secrets ; copier depuis `../kairos.env.example`.

> L'envoi écarte tout fichier ignoré par le dépôt. Les configurations réelles de
> `config/` (profil, modules, modèle) et les secrets appartiennent donc à l'hôte
> cible et n'y sont jamais écrasés par celles du poste qui déploie : elles se
> créent et se modifient sur place, depuis les fichiers d'exemple. Le corollaire
> est qu'un nouveau champ de configuration se répercute à la main.

## Mise en place (sur le VPS, dans `~/kairos/deploy/docker`)

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
(lit `kairos.db`, connexion en lecture seule). Ils refusent de démarrer sans jeton.

Générer un jeton par service (`MEMOIRE_TOKEN`, `AGENDA_TOKEN`, `FINANCES_TOKEN`, `ALIMENTATION_TOKEN`) et les renseigner dans `kairos.env` :

```bash
openssl rand -hex 32   # -> MEMOIRE_TOKEN ; relancer pour AGENDA_TOKEN, FINANCES_TOKEN, puis ALIMENTATION_TOKEN
```

Publication : `${TENTACLES_BIND_ADDR:-127.0.0.1}` (défaut : boucle locale), ports `MEMOIRE_BIND_PORT` (8091) et
`AGENDA_BIND_PORT` (8092). `TENTACLES_BIND_ADDR` est une variable de substitution de compose : elle se définit dans
`deploy/docker/.env` (gitignoré) ou dans l'environnement du shell, **pas** dans `kairos.env`
(qui n'alimente que l'intérieur des conteneurs). Au démarrage de l'hôte, le bind échoue si l'adresse du
réseau privé n'est pas encore montée : le réseau privé doit démarrer avant Docker (ou relancer les services).

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

## Second profil (module optionnel)

Fait tourner un second profil isolé (config, base de données, canal de digest distinct)
sur la même image, sans rien dupliquer côté code.

```bash
# 1. config du second profil (jamais commitée — son nom réel ne doit apparaître nulle part
#    dans le repo, cf. règles anti-OSINT du projet)
cp ../../config/profile.example.toml ../../config/profile-secondary.toml
"$EDITOR" ../../config/profile-secondary.toml

# 2. secrets/config déploiement du second profil
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
- Reboot VPS : `restart: unless-stopped` relance `ollama` + `scheduler`.
- Teardown : `docker compose down -v` (supprime volumes DB + modèles).
- Fuseau : la variable `TZ` des conteneurs pilote le fuseau de planification.
