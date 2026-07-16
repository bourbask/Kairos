# Déploiement Kairos — Docker (recommandé)

> Kairos isolé dans des conteneurs ; planification **interne** (supercronic) ; conteneurs non-root.
> Alternative non-Docker (binaire + systemd) : voir `../systemd/` et `../README.md`.

## Composants

- `Dockerfile` — build multi-stage du binaire Rust → image `debian-slim` (+ `supercronic`).
- `docker-compose.yml` :
  - `ollama` — service persistant, non exposé (réseau interne only) ;
  - `radicale` — serveur du module calendar ;
  - `ntfy` — serveur de notifications push (alerte offre forte) ;
  - `scheduler` — toujours actif, lance `scrape`/`calendar sync`/`briefing` via `supercronic` ;
  - `kairos` — profil `tools`, pour les exécutions manuelles.
- `supercronic.crontab` — planning interne (scrape 04:30, calendar sync 04:45, briefing 05:00 ; fuseau `TZ`).
- `kairos.env` (optionnel, gitignoré) — secrets ; copier depuis `../kairos.env.example`.

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

Serveur ntfy self-hosted pour l'alerte "offre forte" (score > 0.9), séparée du digest Discord.

```bash
docker compose up -d ntfy
```

Auth activée (`NTFY_AUTH_DEFAULT_ACCESS=deny-all`) — un user + un token à créer une fois :

```bash
docker compose exec ntfy ntfy user add --role=user kairos     # mot de passe demandé (peu importe, non utilisé)
docker compose exec ntfy ntfy access kairos <topic> rw        # <topic> = nom choisi, ex. kairos-alertes
docker compose exec ntfy ntfy token add kairos                 # affiche le token à copier
```

Renseigner dans `kairos.env` :

```
NTFY_TOPIC=<topic choisi ci-dessus>
NTFY_TOKEN=<token généré ci-dessus>
```

`NTFY_URL` est déjà fixé en interne (`http://ntfy`) dans `docker-compose.yml` — ne pas le redéfinir dans `kairos.env`.

Côté téléphone (app [ntfy](https://ntfy.sh/) — Android/iOS/web) : ajouter un serveur self-hosted
pointant sur l'adresse tailnet du VPS (`http://<IP-tailnet>:8090` ou nom MagicDNS), s'authentifier avec
le même token (champ "Access token" dans les paramètres du serveur), puis s'abonner au topic. Aucune
configuration réseau supplémentaire si le téléphone est déjà un nœud du tailnet.

Adresse/port de bind du serveur configurables via `NTFY_BIND_ADDR`/`NTFY_BIND_PORT`
(défaut : boucle locale, port `8090` — le port 80 est déjà pris par Traefik sur cet hôte) — même
convention que `RADICALE_BIND_ADDR`.

## Notes

- **Planification interne** : le conteneur `scheduler` (supercronic) déclenche les batchs (scrape + calendar sync + briefing).
- Ollama n'expose **aucun port** (réseau interne compose uniquement).
- phi3:mini tourne sur CPU (batch nocturne) ; ajouter un `mem_limit` sur `ollama` si besoin.
- `scrape`/`briefing` **n'utilisent pas** le LLM (matching = règles Rust, enrichissement = scraping). phi3:mini sert au futur `prompt`/analyse privée.
- Reboot VPS : `restart: unless-stopped` relance `ollama` + `scheduler`.
- Teardown : `docker compose down -v` (supprime volumes DB + modèles).
- Fuseau : la variable `TZ` des conteneurs pilote le fuseau de planification.
