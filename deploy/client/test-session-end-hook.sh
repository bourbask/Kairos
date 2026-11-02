#!/usr/bin/env bash
# Fuseau fixé pour des horodatages reproductibles ; le hook suit le fuseau du poste.
export TZ=UTC
# Test du hook de fin de session (jq + bash uniquement).
set -u
cd "$(dirname "$0")" || exit 1
HOOK=./session-end-hook.sh
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0
ok() { if [ "$1" = 0 ]; then echo "ok   $2"; else echo "FAIL $2"; fail=1; fi; }
run() { # $1 = transcript ; env supplémentaire hérité
  printf '{"session_id":"s1","transcript_path":"%s"}' "$1" | XDG_STATE_HOME="$T/state" bash "$HOOK"
}
export KAIROS_MEMOIRE_URL=http://127.0.0.1:9/mcp KAIROS_MEMOIRE_TOKEN=jeton-secret

out=$(KAIROS_HOOK_DRY_RUN=1 run "$PWD/fixtures/transcript.jsonl")
echo "$out" | grep -q 'bonjour, parle-moi' ; ok $? "texte utilisateur"
echo "$out" | grep -q 'réponse assistant' ; ok $? "texte assistant"
echo "$out" | grep -q SECRET_TOOL_OUTPUT ; [ $? -ne 0 ] ; ok $? "sortie d'outil exclue"
echo "$out" | grep -q SECRET_SHELL_OUTPUT ; [ $? -ne 0 ] ; ok $? "sortie de commande shell exclue"
echo "$out" | grep -q '"name":"conversation_ingest"' ; ok $? "nom de l'outil"
echo "$out" | grep -q jeton-secret ; [ $? -ne 0 ] ; ok $? "jeton masqué"
body=$(echo "$out" | tail -n1)
[ "$(echo "$body" | jq -r .params.arguments.session_id)" = "s1" ] ; ok $? "session_id transmis"
[ "$(echo "$body" | jq -r .params.arguments.started_at)" = "2026-10-29 08:00:00" ] ; ok $? "début en heure locale"
[ "$(echo "$body" | jq -r .params.arguments.ended_at)" = "2026-10-29 08:10:30" ] ; ok $? "fin en heure locale"

# transcription gonflée à ~3 Mo, avec caractères multi-octets
big="$T/big.jsonl"
for i in $(seq 1 3000); do
  jq -cn --arg t "$(head -c 1000 /dev/zero | tr '\0' 'é')" '{type:"user",timestamp:"2026-10-29T08:00:00Z",message:{content:$t}}'
done > "$big"
KAIROS_HOOK_DRY_RUN=1 run "$big" | tail -n1 | jq -j .params.arguments.transcript > "$T/tr"
[ "$(wc -c < "$T/tr")" -le 1400000 ] ; ok $? "troncature <= 1 400 000 octets"
iconv -f UTF-8 -t UTF-8 "$T/tr" >/dev/null 2>&1 ; ok $? "UTF-8 valide après troncature"

# transcription pleine de caractères à échapper : le corps reste sous 2 MiB
esc="$T/esc.jsonl"
for i in $(seq 1 1500); do
  jq -cn --arg t "$(head -c 1000 /dev/zero | tr '\0' '"')" '{type:"user",timestamp:"2026-10-29T08:00:00Z",message:{content:$t}}'
done > "$esc"
KAIROS_HOOK_DRY_RUN=1 run "$esc" | tail -n1 > "$T/escbody"
[ "$(wc -c < "$T/escbody")" -lt 2097152 ] ; ok $? "corps < 2 MiB avec échappements"
jq -e .params.arguments.transcript "$T/escbody" >/dev/null ; ok $? "corps JSON valide après réduction"

# serveur injoignable : sortie 0 + journal
run "$PWD/fixtures/transcript.jsonl" >/dev/null 2>&1 ; ok $? "exit 0 si échec"
[ "$(wc -l < "$T/state/kairos/hook.log")" -ge 1 ] ; ok $? "échec journalisé"
grep -q jeton-secret "$T/state/kairos/hook.log" ; [ $? -ne 0 ] ; ok $? "jeton absent du journal"

# faux curl : trace argv, lit stdin, écrit le jeton sur stderr (jeton avec / et &)
mkdir "$T/bin"
cat > "$T/bin/curl" <<'F'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$FAKE_ARGS"
cat >/dev/null
echo "boom $KAIROS_MEMOIRE_TOKEN fin" >&2
printf 000
exit 7
F
chmod +x "$T/bin/curl"
rm -rf "$T/state"; tok='a/b&c.d*e'
FAKE_ARGS="$T/args" KAIROS_MEMOIRE_TOKEN="$tok" PATH="$T/bin:$PATH" run "$PWD/fixtures/transcript.jsonl" >/dev/null 2>&1 ; ok $? "exit 0 (faux curl)"
grep -qF "$tok" "$T/args" ; [ $? -ne 0 ] ; ok $? "jeton absent de argv de curl"
grep -qF "$tok" "$T/state/kairos/hook.log" ; [ $? -ne 0 ] ; ok $? "jeton (/ et &) masqué dans le journal"
grep -qF 'boom *** fin' "$T/state/kairos/hook.log" ; ok $? "masque *** présent"
exit $fail
