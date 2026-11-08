#!/usr/bin/env bash
# Déploiement Docker de Kairos sur un hôte distant, depuis la machine de dev.
# Envoie le working tree courant (pas git), reconstruit l'image, (re)démarre les services.
#
# Usage : VPS_HOST=user@hote [KAIROS_DIR=kairos] [KAIROS_SERVICES="…"] ./deploy/docker/deploy.sh
#
# Services (re)démarrés : KAIROS_SERVICES s'il est fourni (liste séparée par des
# espaces) ; sinon ollama, scheduler et les tentacules, plus la tête seulement
# si sa configuration (config/tete.toml) existe sur l'hôte — sans elle, la tête
# redémarrerait en boucle.
set -euo pipefail

VPS_HOST="${VPS_HOST:?export VPS_HOST=user@hote}"
KAIROS_DIR="${KAIROS_DIR:-kairos}"                                   # dossier distant (relatif au home)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_SERVICES="ollama scheduler memoire agenda finances alimentation"

if [ -n "${KAIROS_SERVICES:-}" ]; then
    # Interpolée dans une commande distante : noms de services uniquement.
    if [[ ! "$KAIROS_SERVICES" =~ ^[a-z0-9_\ -]+$ ]]; then
        echo "KAIROS_SERVICES invalide : noms de services séparés par des espaces." >&2
        exit 1
    fi
fi

echo "==> [1/3] Envoi du working tree vers l'hôte"
ssh "$VPS_HOST" "mkdir -p '$KAIROS_DIR'"
# --exclude-vcs-ignores écarte tout ce que le dépôt ignore : configuration et
# secrets propres à une machine restent sur leur hôte, les fichiers d'exemple
# suivent. Sans lui, la configuration locale de l'opérateur écrase celle de
# l'hôte cible à chaque envoi, y compris ses jetons.
tar czf - -C "$ROOT" --exclude-vcs-ignores \
    --exclude=target --exclude=.git --exclude=data --exclude=briefings \
    --exclude=logs --exclude=private . \
  | ssh "$VPS_HOST" "tar xzf - -C '$KAIROS_DIR'"

echo "==> [2/3] Build de l'image"
ssh "$VPS_HOST" "cd '$KAIROS_DIR/deploy/docker' && docker compose build"

echo "==> [3/3] (Re)démarrage des services"
# Les services propres à l'hôte (docker-compose.override.yml, non versionné) sont démarrés par l'opérateur.
if [ -n "${KAIROS_SERVICES:-}" ]; then
    SERVICES="$KAIROS_SERVICES"
elif ssh "$VPS_HOST" "test -f '$KAIROS_DIR/config/tete.toml'"; then
    SERVICES="$BASE_SERVICES tete"
else
    SERVICES="$BASE_SERVICES"
    echo "    Tête non démarrée : config/tete.toml absent sur l'hôte."
fi
echo "    Services : $SERVICES"
ssh "$VPS_HOST" "cd '$KAIROS_DIR/deploy/docker' && docker compose up -d $SERVICES"

cat <<'EOF'

Déployé.
- Secrets : deploy/docker/kairos.env sur l'hôte (hors repo).
- Premier lancement seulement : docker compose exec ollama ollama pull <modèle>
- Exécutions manuelles : docker compose run --rm kairos <scrape|briefing|status>
EOF
