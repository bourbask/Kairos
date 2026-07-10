#!/usr/bin/env bash
# Déploiement Docker de Kairos sur un hôte distant, depuis la machine de dev.
# Envoie le working tree courant (pas git), reconstruit l'image, (re)démarre les services.
#
# Usage : VPS_HOST=user@hote [KAIROS_DIR=kairos] ./deploy/docker/deploy.sh
set -euo pipefail

VPS_HOST="${VPS_HOST:?export VPS_HOST=user@hote}"
KAIROS_DIR="${KAIROS_DIR:-kairos}"                                   # dossier distant (relatif au home)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "==> [1/3] Envoi du working tree vers l'hôte"
ssh "$VPS_HOST" "mkdir -p '$KAIROS_DIR'"
tar czf - -C "$ROOT" \
    --exclude=target --exclude=.git --exclude=data --exclude=briefings \
    --exclude=logs --exclude=private . \
  | ssh "$VPS_HOST" "tar xzf - -C '$KAIROS_DIR'"

echo "==> [2/3] Build de l'image"
ssh "$VPS_HOST" "cd '$KAIROS_DIR/deploy/docker' && docker compose build"

echo "==> [3/3] (Re)démarrage des services"
ssh "$VPS_HOST" "cd '$KAIROS_DIR/deploy/docker' && docker compose up -d ollama scheduler"

cat <<'EOF'

Déployé.
- Secrets : deploy/docker/kairos.env sur l'hôte (hors repo).
- Premier lancement seulement : docker compose exec ollama ollama pull <modèle>
- Exécutions manuelles : docker compose run --rm kairos <scrape|briefing|status>
EOF
