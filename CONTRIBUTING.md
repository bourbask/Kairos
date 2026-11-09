# Contribuer

## Branches

| Branche | Rôle | Cible de sa PR |
|---|---|---|
| `main` | production : chaque commit est une release taguée | — |
| `develop` | intégration continue | `main` (PR de release uniquement) |
| `feat/<sujet>` | nouvelle fonctionnalité | `develop` ou `integration/<sujet>` |
| `fix/<sujet>` | correction de bug | `develop` ou `integration/<sujet>` |
| `chore/<sujet>`, `docs/<sujet>`, `refactor/<sujet>` | outillage, documentation, restructuration | `develop` ou `integration/<sujet>` |
| `integration/<sujet>` | branche de fusion regroupant plusieurs branches de travail | `develop` |

- `<sujet>` : court, en minuscules, mots séparés par des tirets (`feat/app-mobile-maison`).
- Rien n'est poussé directement sur `main` ni sur `develop` : tout passe par une PR, CI verte.
- Seule `develop` peut ouvrir une PR vers `main`.
- Les branches de travail et de fusion sont supprimées une fois fusionnées.
- Intégration par merge, jamais par rebase. Avant d'ouvrir une PR : `git pull origin <cible>`.

## Garde de publication

Dépôt public : rien de personnel, de secret ni d'environnement ne doit y entrer.

- Sur chaque clone, une fois : `make hooks`. Les hooks de `.githooks/` passent
  `scripts/osint-scan.sh` sur chaque commit (contenu et message) et chaque push.
- Le scan refuse : les termes d'une liste privée locale (`private/osint-denylist.txt`,
  jamais versionnée), les adresses IP privées, les emails, les chemins personnels,
  les fichiers interdits (`.env`, profils, bases, clés) et les secrets (gitleaks).
- Aucune donnée réelle d'une personne, même dans la documentation ou les tests :
  exemples fictifs uniquement.
- Faux positif avéré : ajouter le texte exact à `private/osint-allowlist.txt`.
- La CI rejoue le scan sur chaque PR (commits, messages, titre et description).

## Worktrees

Un worktree par branche de travail, dans `.worktrees/`, nommé d'après la branche
avec `/` remplacé par `-` : la branche `feat/app-mobile-maison` vit dans
`.worktrees/feat-app-mobile-maison`. Le worktree est supprimé avec sa branche.

## Commits

Préfixes : `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`, avec la portée
entre parenthèses (`feat(mobile): …`). Un `!` après la portée signale un changement
incompatible (`feat(tete)!: …`).

## Versions et releases

Le numéro de version n'est écrit nulle part dans le code : il vient du titre de la PR
de release et devient le tag `vX.Y.Z`. L'APK de release le reçoit à la construction
(nom de version, et code de version `X*10000 + Y*100 + Z`) ; un build local prend le
dernier tag. Le champ `version` des `Cargo.toml` n'est pas tenu à jour.

Règle de montée de version ([Semantic Versioning](https://semver.org/lang/fr/)), telle
qu'appliquée depuis la 0.2.0 :

| Changement depuis la dernière release | Avant 1.0.0 | À partir de 1.0.0 |
|---|---|---|
| incompatible (`!`) | `0.X.0` | `X.0.0` |
| nouvelle fonctionnalité compatible (`feat`) | `0.X.0` | `0.X.0` |
| correction seule (`fix`) | `0.0.X` | `0.0.X` |

Publier une release (deux PR en tout : les branches de travail, puis celle-ci) :

1. PR `develop` → `main` intitulée exactement `Release X.Y.Z`, dont la description
   contient les notes de version (`## Nouveautés`, `## Corrections`, en listes). La CI
   vérifie le titre, la présence des notes, que le tag n'existe pas et que la version
   dépasse la précédente.
2. Fusion en merge commit : tag `vX.Y.Z`, APK signé construit et publié dans la release,
   avec la description de la PR comme notes.
3. Déploiement depuis `main` (le script dépose l'APK du dernier tag sur le serveur).

Le serveur se déploie depuis `main` uniquement.
