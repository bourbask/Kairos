#!/usr/bin/env bash
# Hook de fin de session : archive la conversation (texte utilisateur et
# assistant uniquement) dans la mémoire. Ne bloque jamais la fermeture.
set -u
MAX=1400000
LOG="${XDG_STATE_HOME:-$HOME/.local/state}/kairos/hook.log"

log() {
  mkdir -p "$(dirname "$LOG")" 2>/dev/null
  printf '%s %s\n' "$(date '+%F %T')" "$*" >>"$LOG" 2>/dev/null
}
trap 'exit 0' EXIT

T=$(mktemp -d) || exit 0
trap 'rm -rf "$T"; exit 0' EXIT

in=$(cat)
path=$(printf '%s' "$in" | jq -r '.transcript_path // empty' 2>/dev/null)
[ -n "$path" ] && [ -r "$path" ] || { log "transcript illisible"; exit 0; }
url="${KAIROS_MEMOIRE_URL:-}"; token="${KAIROS_MEMOIRE_TOKEN:-}"
if [ -z "${KAIROS_HOOK_DRY_RUN:-}" ] && { [ -z "$url" ] || [ -z "$token" ]; }; then
  log "KAIROS_MEMOIRE_URL / KAIROS_MEMOIRE_TOKEN non définies"; exit 0
fi

# Texte seul : contenus chaîne et blocs `text` ; les résultats/appels d'outils sont ignorés.
jq -r 'select(.type=="user" or .type=="assistant")
  | (.message.content | if type=="string" then . else ([.[]? | select(.type=="text") | .text] | join("\n")) end) as $t
  | select($t != "") | "\(.type): \($t)\n"' "$path" >"$T/full" 2>/dev/null
# Fin conservée ; iconv -c écarte un éventuel caractère multi-octet coupé en tête.
command -v iconv >/dev/null || { log "iconv absent"; exit 0; }
tail -c "$MAX" "$T/full" | iconv -f UTF-8 -t UTF-8 -c >"$T/tr"
[ -s "$T/tr" ] || { log "transcription vide"; exit 0; }

ts=$(jq -r 'select(.type=="user" or .type=="assistant") | .timestamp // empty' "$path" 2>/dev/null)
loc() { TZ=Europe/Paris date -d "$1" '+%F %T' 2>/dev/null; }
start=$(loc "$(printf '%s\n' "$ts" | head -n1)"); end=$(loc "$(printf '%s\n' "$ts" | tail -n1)")
[ -n "$start" ] && [ -n "$end" ] || { log "horodatages illisibles"; exit 0; }

jq -cn --rawfile t "$T/tr" --arg d "$(hostname)" --arg s "$start" --arg e "$end" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"conversation_ingest",arguments:{channel:"client-mcp",device:$d,started_at:$s,ended_at:$e,transcript:$t}}}' >"$T/body"

if [ -n "${KAIROS_HOOK_DRY_RUN:-}" ]; then
  echo "POST ${url:-<url>}"
  echo "Authorization: Bearer ***"
  cat "$T/body"
  exit 0
fi

# En-tête d'authentification passé par stdin (printf est interne) : jamais dans argv.
code=$(printf 'header = "Authorization: Bearer %s"\n' "$token" | curl -sS --max-time 10 -o "$T/resp" -w '%{http_code}' -X POST "$url" -K - \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' --data-binary @"$T/body" 2>"$T/err")
if [ "$code" != 200 ] || grep -Eq '"isError": ?true|"error": ?\{' "$T/resp"; then
  err=$(head -c 200 "$T/err" | tr '\n' ' ')
  log "échec envoi (http $code) ${err//"$token"/***}"
fi
exit 0
