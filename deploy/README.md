# Déploiement Kairos — Sprint 0

> **Périmètre : brique 1 (veille emploi) uniquement.** Pas de daemon serveur. Le briefing
> est généré chaque matin par timer.

## Contenu

```
deploy/
├── README.md                 # ce fichier
├── kairos.env.example        # secrets + chemins (→ /etc/kairos/kairos.env)
├── deploy.sh                 # build + copie + rendu des units (depuis la machine de dev)
└── systemd/
    ├── kairos-scrape.service # collecte (oneshot)
    ├── kairos-scrape.timer   # collecte quotidienne
    ├── kairos-briefing.service
    └── kairos-briefing.timer # briefing quotidien
```

## Pré-requis

- Serveur accessible en SSH.
- Rust installé **en local** (le build release se fait sur la machine de dev).
- Ollama + un petit modèle local sur le serveur.

## Étapes

1. **Ollama** (sur le serveur)
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
   dans `/tmp/kairos-units/` sur le serveur. Il **affiche** ensuite les commandes `sudo` à lancer.

3. **Secrets** (sur le serveur) — cf. `kairos.env.example`
   ```bash
   sudo mkdir -p /etc/kairos
   sudo cp <...>/kairos.env /etc/kairos/kairos.env   # rempli à partir de l'exemple
   sudo chmod 600 /etc/kairos/kairos.env
   sudo chown <user> /etc/kairos/kairos.env
   ```

4. **Activer les timers** (sur le serveur)
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

## Notes

- Timers en `OnCalendar` + `Persistent=true` → rattrapent le run si le serveur était éteint.
- Réseau : le batch n'écoute sur aucun port entrant ; accès distant éventuel à restreindre.
- Le briefing rend les sections planning/calendar seulement si des données existent — ces briques
  sont **gelées** et non alimentées en Sprint 0 (pas de cron de synchro, pas de seed de routine).
- Sauvegarde : quotidienne à configurer (`sqlite3 VACUUM INTO`).
