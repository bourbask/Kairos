# Déploiement Kairos — Sprint 0

> **Périmètre : brique 1 (veille emploi) uniquement.** Pas de daemon serveur, pas de mobile
> (gel dur R-U01 — cf. `docs/recalibration/`). Objectif : le briefing est généré par timer
> chaque matin et **lu ≥ 5 jours consécutifs** (gate O-01), avant d'envisager la suite.

## Contenu

```
deploy/
├── README.md                 # ce fichier
├── kairos.env.example        # secrets + chemins (→ /etc/kairos/kairos.env)
├── deploy.sh                 # build + copie + rendu des units (depuis la machine de dev)
└── systemd/
    ├── kairos-scrape.service # collecte (oneshot)
    ├── kairos-scrape.timer   # 04:30 quotidien
    ├── kairos-briefing.service
    └── kairos-briefing.timer # 05:00 quotidien
```

## Pré-requis

- Serveur accessible en SSH ; accès distant à restreindre.
- Rust installé **en local** (le build release se fait sur la machine de dev).
- Ollama + `phi3:mini` sur le VPS.

## Gate bloquant (ADR-007)

- [ ] `private/OBJECTIVES.md` rempli **par toi** (3-5 objectifs SMART). Non négociable avant déploiement.

## Étapes

1. **Ollama** (sur le VPS)
   ```bash
   curl -fsSL https://ollama.com/install.sh | sh
   ollama pull phi3:mini
   curl -s localhost:11434/api/tags   # doit lister phi3:mini
   ```

2. **Déploiement** (depuis la machine de dev)
   ```bash
   VPS_HOST=user@hote ./deploy/deploy.sh
   ```
   Le script build, copie le binaire + `config/profile.toml`, et dépose les units rendues
   dans `/tmp/kairos-units/` sur le VPS. Il **affiche** ensuite les commandes `sudo` à lancer.

3. **Secrets** (sur le VPS) — cf. `kairos.env.example`
   ```bash
   sudo mkdir -p /etc/kairos
   sudo cp <...>/kairos.env /etc/kairos/kairos.env   # rempli à partir de l'exemple
   sudo chmod 600 /etc/kairos/kairos.env
   sudo chown <user> /etc/kairos/kairos.env
   ```

4. **Activer les timers** (sur le VPS)
   ```bash
   sudo cp /tmp/kairos-units/*.service /tmp/kairos-units/*.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now kairos-scrape.timer kairos-briefing.timer
   ```

5. **Vérifier**
   ```bash
   systemctl list-timers 'kairos-*'
   sudo systemctl start kairos-scrape.service            # test immédiat
   journalctl -u kairos-briefing.service -n 50 --no-pager
   cat ~/briefings/$(date +%F).md
   ```

## Mesure O-01 (gate de sortie de Sprint 0)

- [ ] Briefing généré par timer **et lu ≥ 5 jours consécutifs**.
- [ ] ≥ 1 offre pertinente/semaine dans le top 3.

Tant que ce gate n'est pas atteint : **gel dur**, aucune nouvelle feature, aucun code hors correctif.

## Notes

- Timers en `OnCalendar` + `Persistent=true` → rattrapent le run si le VPS était éteint.
- Réseau : le batch n'écoute sur aucun port entrant ; accès distant éventuel à restreindre.
- Le briefing rend les sections planning/calendar seulement si des données existent — ces briques
  sont **gelées** et non alimentées en Sprint 0 (pas de cron CalDAV, pas de seed de routine).
- Sauvegarde : `sqlite3 VACUUM INTO` prévu (procédure interne).
