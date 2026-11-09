#!/usr/bin/env bash
# Relecture du sens avant publication : un modèle indépendant lit ce qui va devenir
# public (lignes ajoutées, messages de commit, titre et corps d'une PR ou d'une
# release) et juge si cela révèle une donnée personnelle — de l'auteur ou d'un tiers.
# Ce n'est pas une liste de mots : une ville citée dans un exemple neutre passe,
# un trajet domicile-travail ne passe pas.
#
#   revue-perso.sh FICHIER   (ou "-" : entrée standard)
#
# Sortie 0 = rien de personnel, 1 = risque (zones listées sans recopier la donnée),
# 3 = relecture impossible (modèle indisponible) : à traiter comme un refus.
# Un contenu déjà jugé sans risque n'est pas relu deux fois (empreinte en cache local).
set -euo pipefail

src=${1:--}
text=$(if [ "$src" = - ]; then cat; else cat "$src"; fi)
[ -z "${text//[[:space:]]/}" ] && exit 0

max=150000
truncated=""
if [ ${#text} -gt $max ]; then
  text=${text:0:$max}
  truncated="(Contenu tronqué à $max caractères : signale-le dans ta réponse s'il manque de quoi juger.)"
fi

cache_dir="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || echo "${TMPDIR:-/tmp}")/revue-perso"
mkdir -p "$cache_dir"
key=$(printf '%s' "$text" | sha256sum | cut -c1-32)
[ -f "$cache_dir/$key" ] && exit 0

command -v claude >/dev/null || { echo "revue-perso : modèle indisponible, publication refusée" >&2; exit 3; }

prompt=$(cat <<'P'
Tu relis, avant publication sur un dépôt GitHub PUBLIC, le texte fourni (lignes de code ou de doc ajoutées, messages de commit, titre et description de PR ou de release).

Contexte : ce dépôt EST un assistant personnel auto-hébergé. Décrire ses fonctionnalités, ses modules (finances, alimentation, agenda, médias, documents, emploi…), son architecture générique, ses choix techniques et de sécurité en termes généraux N'EST PAS une donnée personnelle.

Question : ce texte révèle-t-il un FAIT RÉEL et PRÉCIS sur une personne réelle (l'auteur ou un tiers) ? Exemples à signaler :
- identité réelle (nom, prénom rattaché à une personne, pseudo), coordonnées (téléphone, email personnel, adresse postale) ;
- lieu réel de vie ou de travail, trajet réel, employeur ou clients réels, collègues ou proches réels ;
- valeurs réelles de sa vie : salaire ou prétentions réels, montants réels de comptes ou dépenses, contenu réel de son domicile, jours ou horaires réels de sa semaine, santé ;
- identifiants réels de son infrastructure : nom de domaine, nom de machine, hébergeur ou fournisseur nommés, adresses IP, ports exposés, faiblesse de sécurité de SON installation.
À NE PAS signaler : une fonctionnalité décrite en général (« suivre l'épargne », « jours sur site configurables »), des exemples manifestement fictifs (« Exemple », example.com, « Série Alpha », montants ronds d'illustration), des noms de lieux ou de marques sans lien avec une personne réelle, des identifiants de code, des bibliothèques, l'auteur Git public.
Dans le doute sur un exemple : signale-le seulement s'il paraît tiré de la vie réelle (précision invraisemblable pour une illustration).

Réponds UNIQUEMENT par un objet JSON sur une ligne :
{"verdict":"ok"} ou {"verdict":"risque","zones":[{"emplacement":"fichier:ligne ou message/titre/corps","nature":"catégorie de donnée, sans la recopier"}]}
Ne recopie JAMAIS la donnée elle-même dans ta réponse.
P
)

out=$(printf '%s\n%s\n\n--- TEXTE À RELIRE ---\n%s\n' "$prompt" "$truncated" "$text" \
  | timeout 170 claude -p --model sonnet --tools "" --no-session-persistence --output-format text 2>/dev/null) \
  || { echo "revue-perso : relecture impossible (modèle injoignable ou délai dépassé), publication refusée" >&2; exit 3; }

json=$(printf '%s' "$out" | grep -o '{.*}' | tail -1)
verdict=$(printf '%s' "$json" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("verdict",""))' 2>/dev/null || true)
case "$verdict" in
  ok)
    : > "$cache_dir/$key"
    exit 0 ;;
  risque)
    echo "Relecture : données personnelles possibles — anonymiser ces passages avant de publier :" >&2
    printf '%s' "$json" | python3 -c 'import sys,json
for z in json.load(sys.stdin).get("zones", []):
    print("  ✗", z.get("emplacement","?"), "—", z.get("nature","?"))' >&2
    exit 1 ;;
  *)
    echo "revue-perso : réponse illisible du relecteur, publication refusée" >&2
    exit 3 ;;
esac
