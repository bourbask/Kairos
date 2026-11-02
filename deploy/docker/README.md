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
