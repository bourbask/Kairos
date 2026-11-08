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

## Worktrees

Un worktree par branche de travail, dans `.worktrees/`, nommé d'après la branche
avec `/` remplacé par `-` : la branche `feat/app-mobile-maison` vit dans
`.worktrees/feat-app-mobile-maison`. Le worktree est supprimé avec sa branche.

## Commits

Préfixes : `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`, avec la portée
entre parenthèses (`feat(mobile): …`). Un `!` après la portée signale un changement
incompatible (`feat(tete)!: …`).

## Versions et releases

Version unique du projet : le champ `version` du `Cargo.toml` racine. L'application
Android la reprend (nom de version, et code de version `X*10000 + Y*100 + Z`).

Règle de montée de version ([Semantic Versioning](https://semver.org/lang/fr/)) :

| Changement depuis la dernière release | Avant 1.0.0 | À partir de 1.0.0 |
|---|---|---|
| incompatible (`!`) | `0.X.0` | `X.0.0` |
| nouvelle fonctionnalité compatible (`feat`) | `0.0.X` | `0.X.0` |
| correction seule (`fix`) | `0.0.X` | `0.0.X` |

Publier une release :

1. Sur une branche `chore/release-X.Y.Z` depuis `develop`, monter `version` dans le
   `Cargo.toml` racine (et `Cargo.lock`), PR vers `develop`.
2. PR `develop` → `main` intitulée exactement `Release X.Y.Z`. La CI vérifie le titre,
   la version et qu'elle dépasse la précédente.
3. À la fusion : tag `vX.Y.Z`, APK signé construit et publié dans la release GitHub.
4. Le serveur se déploie depuis `main` uniquement.
