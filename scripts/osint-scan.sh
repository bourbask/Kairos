#!/usr/bin/env bash
# Garde de publication : refuse tout ajout qui exposerait une donnée personnelle,
# un secret ou un détail d'environnement dans le dépôt public.
#
#   osint-scan.sh --staged        lignes ajoutées et fichiers de l'index (pre-commit)
#   osint-scan.sh --range A..B    lignes ajoutées et messages des commits A..B (pre-push, CI)
#   osint-scan.sh --text FICHIER  un texte libre : message de commit, titre ou corps de PR ("-" = entrée standard)
#
# Contrôles techniques, rapides, sans aucune donnée personnelle embarquée :
#   - motifs génériques : adresses IP privées, emails, chemins personnels,
#     fichiers qui ne doivent jamais être versionnés ;
#   - secrets, par gitleaks quand il est installé.
# Le sens (une donnée personnelle formulée autrement) est jugé à la publication
# par scripts/revue-perso.sh.
# Exceptions ponctuelles : private/osint-allowlist.txt (texte exact ; une ligne qui le
# contient n'est pas signalée).
# Les sorties donnent l'emplacement et la règle, jamais la valeur trouvée : en CI
# les journaux sont publics.
# Sortie 0 = rien trouvé, 1 = refus, 2 = usage.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
# Exceptions locales du clone principal, partagées par ses worktrees.
main=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
allow=${OSINT_ALLOWLIST:-$main/private/osint-allowlist.txt}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
found=0

say() { printf '%s\n' "$*" >&2; }
fail() { say "  ✗ $1"; found=1; }

# Motifs génériques (aucun n'identifie quelqu'un : ils sont publics).
generic=(
  'ip-privee|(^|[^0-9.])(10\.[0-9]{1,3}|192\.168|172\.(1[6-9]|2[0-9]|3[01])|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7]))\.[0-9]{1,3}(\.[0-9]{1,3})?([^0-9.]|$)'
  'email|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}'
  'chemin-personnel|/home/[a-z_][a-z0-9_-]*/'
)
# Adresses génériques tolérées (exemples, adresses de service sans identité).
email_ok='@(example\.(com|org|net)|users\.noreply\.github\.com|github\.com)|noreply@|this@|@[a-z]\.[a-z]{2,3}([^a-z]|$)'
# Fichiers jamais versionnés.
forbidden_files='(^|/)(\.env|[^/]*\.env)$|(^|/)profile\.toml$|\.(db|db-wal|db-shm|jks|keystore|p12|pem|key)$|(^|/)private/'

# Lignes ajoutées « fichier:ligne:texte » d'un diff unifié lu sur l'entrée standard.
added_lines() {
  awk '
    /^\+\+\+ / { f = substr($0, 5); sub(/^b\//, "", f); next }
    /^@@/ { split($3, a, ","); n = substr(a[1], 2) + 0; next }
    /^\+/ { print f ":" n ":" substr($0, 2); n++; next }
    /^ / { n++ }
  '
}

# Contrôles 1 et 2 sur un fichier « emplacement:texte » (une entrée par ligne).
check_lines() {
  local lines=$1 label=$2
  [ -s "$lines" ] || return 0
  local scan=$lines
  if [ -s "$allow" ]; then
    grep -viF -f <(grep -v '^[[:space:]]*\(#\|$\)' "$allow") "$lines" > "$work/allowed" || true
    scan=$work/allowed
  fi
  local rule pat
  for g in "${generic[@]}"; do
    rule=${g%%|*}; pat=${g#*|}
    grep -nE "$pat" "$scan" > "$work/hits-g" || true
    # « /home/user/ » : exemple de documentation, sans identité.
    if [ "$rule" = chemin-personnel ]; then grep -v '/home/user/' "$work/hits-g" > "$work/hits-g2" || true; mv "$work/hits-g2" "$work/hits-g"; fi
    if [ "$rule" = email ]; then grep -viE "$email_ok" "$work/hits-g" > "$work/hits-g2" || true; mv "$work/hits-g2" "$work/hits-g"; fi
    while IFS= read -r l; do fail "$label $(echo "$l" | cut -d: -f2-3) : $rule"; done < "$work/hits-g"
  done
}

check_files() {
  { grep -E "$forbidden_files" || true; } | { grep -v '\.example$' || true; } > "$work/files"
  while IFS= read -r f; do fail "fichier interdit : $f"; done < "$work/files"
}

gitleaks_run() {
  if ! command -v gitleaks >/dev/null; then
    [ -n "${CI:-}" ] && { fail "gitleaks absent"; return; }
    say "  ! gitleaks absent : secrets non vérifiés"
    return
  fi
  gitleaks "$@" --no-banner --redact --exit-code 1 >/dev/null 2>"$work/gl" || fail "gitleaks : secret détecté (gitleaks $* --redact pour le détail)"
}

case "${1:-}" in
  --staged)
    git diff --cached --name-only --diff-filter=AMR | check_files
    git diff --cached -U0 --no-color --diff-filter=AMR | added_lines > "$work/lines"
    check_lines "$work/lines" ""
    gitleaks_run git --pre-commit --staged "$root"
    ;;
  --range)
    range=${2:?plage A..B attendue}
    git diff --name-only --diff-filter=AMR "$range" | check_files
    git diff -U0 --no-color --diff-filter=AMR "$range" | added_lines > "$work/lines"
    check_lines "$work/lines" ""
    git log --format='%H%n%B' "$range" | awk 'length($0)==40 && $0 ~ /^[0-9a-f]+$/ { c = substr($0,1,9); next } { print "commit " c ":" NR ":" $0 }' > "$work/msgs"
    check_lines "$work/msgs" "message"
    gitleaks_run git --log-opts="$range" "$root"
    ;;
  --text)
    src=${2:--}
    { if [ "$src" = - ]; then cat; else cat "$src"; fi; } | awk '{ print "texte:" NR ":" $0 }' > "$work/lines"
    check_lines "$work/lines" ""
    gitleaks_run stdin < <(cut -d: -f3- "$work/lines")
    ;;
  *)
    sed -n '2,9p' "$0" >&2
    exit 2
    ;;
esac

if [ "$found" -ne 0 ]; then
  say "Publication refusée : retirer ces éléments (ou les remplacer par des exemples fictifs)."
  say "Faux positif avéré : ajouter le texte exact à private/osint-allowlist.txt."
  exit 1
fi
