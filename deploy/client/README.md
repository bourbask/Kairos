# Client MCP (assistant de développement)

Branche un client MCP aux tentacules `kairos-memoire` et `kairos-agenda`, et
archive chaque session terminée dans la mémoire.

## Variables d'environnement

| Variable | Rôle |
|---|---|
| `KAIROS_MEMOIRE_URL` | URL complète du tentacule mémoire, **suffixe `/mcp` inclus** (le hook n'ajoute rien) |
| `KAIROS_MEMOIRE_TOKEN` | jeton du tentacule mémoire |
| `KAIROS_AGENDA_URL` | URL complète du tentacule agenda, suffixe `/mcp` inclus |
| `KAIROS_AGENDA_TOKEN` | jeton du tentacule agenda |

À définir dans l'environnement du client (jamais dans un fichier versionné).

## Serveurs MCP

Copier le contenu de `mcp.example.json` dans la configuration MCP du client
(les `${...}` sont développés depuis l'environnement).

## Hook de fin de session

Ajouter, dans les réglages utilisateur du client, un hook sur l'événement
`SessionEnd` qui exécute le script (chemin absolu) :

```json
{
  "hooks": {
    "SessionEnd": [
      { "hooks": [ { "type": "command", "command": "/chemin/vers/deploy/client/session-end-hook.sh" } ] }
    ]
  }
}
```

Le script lit sur stdin `transcript_path`, ne garde que le texte des messages
utilisateur et assistant (pas les sorties d'outils), tronque à 1 400 000 octets
en conservant la fin, puis appelle `conversation_ingest`. Dépendances : `bash`,
`jq`, `curl`, `iconv`. Il sort toujours en 0 ; les échecs sont journalisés dans
`${XDG_STATE_HOME:-~/.local/state}/kairos/hook.log`.

`KAIROS_HOOK_DRY_RUN=1` imprime la requête (jeton masqué) au lieu de l'envoyer.

## Test

```bash
bash deploy/client/test-session-end-hook.sh
```
