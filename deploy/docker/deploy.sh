#!/usr/bin/env bash
# Déploiement Docker de Kairos sur un hôte distant, depuis la machine de dev.
# Envoie le working tree courant (fichiers connus de git, ignorés exclus), reconstruit l'image, (re)démarre les services.
#
# Usage : VPS_HOST=user@hote [KAIROS_DIR=kairos] [KAIROS_SERVICES="…"] ./deploy/docker/deploy.sh
#
# Services (re)démarrés : KAIROS_SERVICES s'il est fourni (liste séparée par des
# espaces) ; sinon ollama, scheduler et les tentacules, plus la tête seulement
# si sa configuration (config/tete.toml) existe sur l'hôte et le partage seulement
# si son fichier (deploy/docker/partage.env) y existe, le module politique seulement
# si son dossier de données (POLITIQUE_DONNEES_HOTE dans deploy/docker/.env) y est
# déclaré — sans eux, ils redémarreraient en boucle.
#
# APK de l'application : si `gh` est disponible et authentifié, l'APK de la
# release fusionnée (PR « Release X.Y.Z » dont HEAD est le commit de fusion, sinon dernier tag), attendu au besoin, est déposé dans <KAIROS_DIR>/apk sur
# l'hôte (servi par la tête). Sinon l'étape est sautée, le déploiement continue.
set -euo pipefail

VPS_HOST="${VPS_HOST:?export VPS_HOST=user@hote}"
KAIROS_DIR="${KAIROS_DIR:-kairos}"                                   # dossier distant (relatif au home)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_SERVICES="ollama scheduler memoire agenda finances alimentation marches"

if [ -n "${KAIROS_SERVICES:-}" ]; then
    # Interpolée dans une commande distante : noms de services uniquement.
    if [[ ! "$KAIROS_SERVICES" =~ ^[a-z0-9_\ -]+$ ]]; then
        echo "KAIROS_SERVICES invalide : noms de services séparés par des espaces." >&2
        exit 1
    fi
fi

echo "==> [1/4] Envoi du working tree vers l'hôte"
ssh "$VPS_HOST" "mkdir -p '$KAIROS_DIR'"
# Liste établie par git (suivis + non suivis non ignorés) : configuration et
# secrets propres à une machine restent sur leur hôte, les fichiers d'exemple
# suivent. Un fichier suivi supprimé localement est sauté.
git -C "$ROOT" ls-files -z --cached --others --exclude-standard \
  | while IFS= read -r -d '' f; do if [ -e "$ROOT/$f" ]; then printf '%s\0' "$f"; fi; done \
  | tar czf - -C "$ROOT" --null -T - \
  | ssh "$VPS_HOST" "tar xzf - -C '$KAIROS_DIR'"

# Remplace l'APK de l'hôte par celui de la release ; tout échec est rapporté, jamais fatal.
deploy_apk() {
    local version tmp apk
    # Créé ici par l'utilisateur ssh : sinon le compose le créerait en root au démarrage.
    ssh "$VPS_HOST" "mkdir -p '$KAIROS_DIR/apk'" || true
    if ! command -v gh >/dev/null 2>&1; then
        echo "    APK ignoré : gh indisponible (l'application garde la version déjà déposée)."; return 0
    fi
    # Version de la release fusionnée à l'instant : le tag et l'APK sont créés par la CI
    # après la fusion, donc souvent absents au moment du déploiement. Le titre de la PR de
    # release dont le commit de fusion est HEAD fait foi ; à défaut, le dernier tag connu.
    local head
    head="$(git -C "$ROOT" rev-parse HEAD)"
    version="$(cd "$ROOT" && gh pr list --base main --state merged --limit 10 --json title,mergeCommit \
        -q ".[] | select(.mergeCommit.oid == \"$head\") | .title" 2>/dev/null \
        | sed -n 's/^Release \([0-9]*\.[0-9]*\.[0-9]*\)$/\1/p' | head -n1)"
    if [ -z "$version" ]; then
        version="$(cd "$ROOT" && git fetch -q --tags 2>/dev/null; git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null | sed 's/^v//')"
    fi
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "    APK ignoré : aucune version de release pour la branche déployée."; return 0
    fi
    # L'APK est construit par la CI après la fusion : attente bornée (20 min) de sa publication.
    local i
    for i in $(seq 1 40); do
        if (cd "$ROOT" && gh release view "v$version" --json assets -q '.assets[].name' 2>/dev/null | grep -q '\.apk$'); then break; fi
        [ "$i" = 1 ] && echo "    APK v$version pas encore publié : attente de la CI…"
        sleep 30
    done
    tmp="$(mktemp -d)"
    if ! (cd "$ROOT" && gh release download "v$version" -p '*.apk' -D "$tmp") >/dev/null 2>&1; then
        echo "    APK ignoré : release v$version sans APK, ou gh non authentifié."
        rm -rf "$tmp"; return 0
    fi
    apk="$(find "$tmp" -maxdepth 1 -name '*.apk' | head -n1)"
    # Dépôt sous un nom temporaire puis renommage : la tête ne sert jamais un fichier partiel.
    # Un .part laissé par un envoi interrompu est retiré avant le dépôt.
    if ssh "$VPS_HOST" "mkdir -p '$KAIROS_DIR/apk' && cd '$KAIROS_DIR/apk' \
            && rm -f .kairos-v*.apk.part \
            && cat > .kairos-v$version.apk.part && mv .kairos-v$version.apk.part kairos-v$version.apk \
            && find . -maxdepth 1 -name 'kairos-v*.apk' ! -name 'kairos-v$version.apk' -delete" < "$apk"; then
        echo "    APK v$version déposé."
    else
        echo "    APK ignoré : dépôt sur l'hôte impossible."
    fi
    rm -rf "$tmp"
}

echo "==> [2/4] APK de l'application"
deploy_apk || echo "    APK ignoré."

echo "==> [3/4] Build de l'image"
ssh "$VPS_HOST" "cd '$KAIROS_DIR/deploy/docker' && docker compose build"

echo "==> [4/4] (Re)démarrage des services"
# Les services propres à l'hôte (docker-compose.override.yml, non versionné) sont démarrés par l'opérateur.
if [ -n "${KAIROS_SERVICES:-}" ]; then
    SERVICES="$KAIROS_SERVICES"
else
    SERVICES="$BASE_SERVICES"
    if ssh "$VPS_HOST" "test -f '$KAIROS_DIR/config/tete.toml'"; then
        SERVICES="$SERVICES tete"
    else
        echo "    Tête non démarrée : config/tete.toml absent sur l'hôte."
    fi
    if ssh "$VPS_HOST" "test -f '$KAIROS_DIR/deploy/docker/partage.env'"; then
        SERVICES="$SERVICES partage"
    else
        echo "    Partage non démarré : deploy/docker/partage.env absent sur l'hôte."
    fi
    if ssh "$VPS_HOST" "grep -qs '^POLITIQUE_DONNEES_HOTE=.' '$KAIROS_DIR/deploy/docker/.env'"; then
        SERVICES="$SERVICES politique"
    else
        echo "    Politique non démarré : POLITIQUE_DONNEES_HOTE absent de deploy/docker/.env sur l'hôte."
    fi
fi
echo "    Services : $SERVICES"
ssh "$VPS_HOST" "cd '$KAIROS_DIR/deploy/docker' && docker compose up -d $SERVICES"

cat <<'EOF'

Déployé.
- Secrets : deploy/docker/kairos.env sur l'hôte (hors repo).
- Premier lancement seulement : docker compose exec ollama ollama pull <modèle>
- Exécutions manuelles : docker compose run --rm kairos <scrape|briefing|status>
EOF
