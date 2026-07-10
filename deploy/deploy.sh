#!/usr/bin/env bash
# Déploiement Kairos — Sprint 0 (brique 1 batch uniquement).
# À lancer depuis la machine de dev. Fait : build release, copie binaire + profil,
# rend les units systemd (USER/dir), les dépose dans /tmp sur le VPS.
# Les étapes `sudo` finales sont AFFICHÉES (pas exécutées) — à lancer sur le VPS.
#
# Usage:
#   VPS_HOST=user@hote [KAIROS_DIR=/home/user/kairos] ./deploy/deploy.sh
set -euo pipefail

VPS_HOST="${VPS_HOST:?export VPS_HOST=user@hote}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Détection de l'utilisateur distant"
REMOTE_USER="$(ssh "$VPS_HOST" 'whoami')"
KAIROS_DIR="${KAIROS_DIR:-/home/$REMOTE_USER/kairos}"
echo "    VPS=$VPS_HOST  user=$REMOTE_USER  dir=$KAIROS_DIR"

echo "==> [1/4] Build release (local)"
cargo build --release --manifest-path "$ROOT/Cargo.toml"

echo "==> [2/4] Arborescence distante"
ssh "$VPS_HOST" "mkdir -p '$KAIROS_DIR/config' '$KAIROS_DIR/data' /tmp/kairos-units"

echo "==> [3/4] Copie binaire + profil"
scp "$ROOT/target/release/kairos" "$VPS_HOST:$KAIROS_DIR/kairos"
if [ -f "$ROOT/config/profile.toml" ]; then
  scp "$ROOT/config/profile.toml" "$VPS_HOST:$KAIROS_DIR/config/profile.toml"
else
  echo "    ⚠️  config/profile.toml absent en local (gitignoré) — copie-le à la main."
fi

echo "==> [4/4] Rendu + copie des units systemd"
tmp="$(mktemp -d)"
for f in "$ROOT"/deploy/systemd/*.service "$ROOT"/deploy/systemd/*.timer; do
  sed -e "s|__USER__|$REMOTE_USER|g" -e "s|__KAIROS_DIR__|$KAIROS_DIR|g" \
      "$f" > "$tmp/$(basename "$f")"
done
scp "$tmp"/* "$VPS_HOST:/tmp/kairos-units/"
rm -rf "$tmp"

cat <<EOF

======================================================================
Copie faite. Étapes SUDO à exécuter SUR LE VPS (une fois) :

  ssh $VPS_HOST

  # 1. Secrets (voir deploy/kairos.env.example) :
  sudo mkdir -p /etc/kairos
  sudo cp $KAIROS_DIR/kairos.env /etc/kairos/kairos.env   # après l'avoir rempli
  sudo chmod 600 /etc/kairos/kairos.env
  sudo chown $REMOTE_USER /etc/kairos/kairos.env

  # 2. Units systemd :
  sudo cp /tmp/kairos-units/*.service /tmp/kairos-units/*.timer /etc/systemd/system/
  sudo systemctl daemon-reload
  sudo systemctl enable --now kairos-scrape.timer kairos-briefing.timer

  # 3. Vérifier :
  systemctl list-timers 'kairos-*'
  systemctl start kairos-scrape.service   # test manuel immédiat
  journalctl -u kairos-scrape.service -n 50 --no-pager

Rappels : Ollama+phi3 installés ? OBJECTIVES.md rempli (gate ADR-007) ?
======================================================================
EOF
