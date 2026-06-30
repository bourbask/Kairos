# Kairos (Καιρός) — Assistant général personnel

## Origine du nom

**Kairos** = dieu grec de l'opportunité, de l'instant favorable (« le moment juste »).

Backronym : **K**ernel d'**A**utomatisation et d'**I**ntelligence **R**ationnelle pour un **O**S **S**ouverain

Choisi pour :
- Sonorité rare → détectable comme wake word vocal (2 syllabes, attaque K dur)
- Sens philosophique fort (saisir les opportunités au bon moment)
- Facile en français comme en anglais
- Court, identifiable dans un terminal (`$ kairos`, `~/.kairos/`)

---

## Vision

Kairos est un **assistant général personnel** qui orchestre la vie numérique quotidienne. Il s'exécute sur VPS, planifie les traitements lourds la nuit, et produit des fichiers de briefing lus le matin.

L'analyse business complète (pourquoi, pour qui, quels risques) est dans [`docs/QQOQCP.md`](docs/QQOQCP.md).

### Principes

- **Stack Rust** — performance, fiabilité, zéro runtime, apprentissage
- **Stockage SQLite** — fichier unique, zéro infra
- **Hébergement VPS OVH** (2 vCPU, 8 Go RAM, 80 Go) — toujours allumé, connecté
- **Modules indépendants** (briques) qui communiquent via fichiers ou DB
- **Sortie en fichiers Markdown** → lus au réveil
- **Traitement local** des données privées (Ollama phi3:mini sur VPS) → batch la nuit
- **API Claude** uniquement pour les données publiques (offres d'emploi, enrichissement entreprise)
- **Assistant par défaut, coach sur invitation explicite** — jamais de confrontation sans mandat
- **Règle 80/20** : 80% du temps = usage, 20% = amélioration

---

## Architecture de traitement

### Principe : deux voies étanches

```
                           ┌─────────────────────────────┐
                           │     Kairos (Rust)            │
                           │     VPS OVH                  │
                           │                               │
Données publiques ────────→│  Collecteurs API             │
(offres emploi)            │  (Adzuna, Jooble, Remotive)  │
                           │                               │
                           │  ┌───────────────────────┐   │
                           │  │ Ollama phi3:mini      │   │
Données privées ──────────→│  │ (local, 0 appel       │   │
(santé, agenda, repas)     │  │  externe)             │   │
                           │  │                        │   │
                           │  └───────────────────────┘   │
                           │         │                     │
                           │         ▼                     │
                           │  Briefing du jour             │
                           │  (fichier Markdown)           │
                           │         │                     │
                           └─────────┼─────────────────────┘
                                     │
                                     ▼
                              Lu au réveil par l'utilisateur
```

**Règle absolue** : les données privées ne quittent jamais le VPS. Le phi3:mini tourne localement sous Ollama. Si le résultat est lent (CPU-only), ça n'a pas d'importance car le briefing est généré en batch la veille.

**Seule donnée qui part vers Claude API** : les offres d'emploi publiques et les données d'entreprises (publiques par nature) pour l'enrichissement. Rien de personnel.

### Batch nocturne

Traitements déclenchés à 5h00 via cron :
1. Matching des offres (Rust, instantané)
2. Enrichissement entreprise (Rust, API publiques)
3. Analyse données privées (Ollama phi3:mini local)
4. Génération du briefing → `~/briefings/YYYY-MM-DD.md`

### Infrastructure

```bash
# VPS OVH
ssh vps-ovh
sudo apt install ollama
ollama pull phi3:mini

# Kairos en service systemd
kairos scrape       # collecte continue (cron 6h)
kairos briefing     # génération briefing du jour
kairos status       # stats (offres vues, présentées, score)
```

Le VPS (2 vCPU, 8 Go) fait tourner phi3:mini sur CPU à ~5-10 tok/s — suffisant pour du batch nocturne.

La carte GPU de la tour personnelle (à confirmer ce soir si 4 Go ou plus) pourrait permettre des modèles plus gros (qwen2.5:7b, mistral:7b) pour des analyses plus fines. À étudier si le VPS seul ne suffit pas — mais le VPS est l'architecture par défaut.

---

## Mémoire

Pas de mémoire long terme pour l'instant. La Brique 1 (emploi) stocke tout dans SQLite. Le schéma mémoire à 4 couches de Jarvis (docs/ARCHITECTURE.md §3) est la cible future mais pas le MVP.

Stockage actuel :
- **SQLite** (`data/kairos.db`) — offres d'emploi, scores, historique de présentation
- **Fichiers Markdown** (`~/briefings/`) — briefings quotidiens

Stockage futur (quand la mémoire sera nécessaire) :
- À déterminer — SQLite + embeddings locaux, ou une solution légère (DuckDB, ChromaDB, ou autre). Obsidian n'est pas retenu. Le stockage mémoire sera choisi au moment du besoin, pas avant.

---

## Modules (briques)

| Brique | Statut | Description |
|--------|--------|-------------|
| **Veille emploi** | **Codé (Rust)** | 3 collecteurs API, scoring, ranking, briefing, 18 tests ✅ |
| Planning / réveil | À définir | Gestion agenda, rappels, routine matin |
| Portefeuille investissement | À définir | IA conseil bourse, analyse, alertes |
| Finances perso (self-hosted) | Projet séparé | Projet self-hosted-finance, peut alimenter Kairos plus tard |

---

## Brique 1 — Veille emploi

### Cible

Offres tech full remote correspondant au profil, priorité marchés :
🇨🇭 Suisse · 🇧🇪 Belgique · 🇱🇺 Luxembourg · 🇳🇱 Pays-Bas · 🇩🇪 Allemagne

Critères : stack et salaire cible définis dans `config/profile.toml`, full remote.

### Sources de données

| Source | API | Free tier | Couvre |
|--------|-----|-----------|--------|
| **Adzuna** | API REST | 500 req/mois | CH, BE, LU, DE, NL, FR + 15 pays |
| **Jooble** | API REST | 100 req/j | Agrégateur 80+ pays (backup) |
| **Remotive** | API illimitée | Gratuit | Full remote tech mondial (startups) |

Pas de scraping LinkedIn / Indeed (anti-bot trop agressif).

### Enrichissement entreprise

Version simple — scraping DuckDuckGo + meta description. Améliorable plus tard (Pappers API FR, etc.).

### Architecture du code

```
kairos/
├── Cargo.toml
├── src/
│   ├── main.rs              # CLI : scrape | briefing | status
│   ├── config.rs            # Chargement profile.toml
│   ├── models.rs            # JobOffer, ScoredJob, ScoreBreakdown...
│   ├── collectors/
│   │   ├── mod.rs           # Trait Collector
│   │   ├── adzuna.rs        # Adzuna (testé)
│   │   ├── jooble.rs        # Jooble (testé)
│   │   └── remotive.rs      # Remotive
│   ├── matching.rs          # Scoring pondéré (skills 40%, remote 25%, salaire 20%, loc 15%)
│   ├── ranker.rs            # Top N quotidien
│   ├── enrichment.rs        # Enrichissement entreprise (testé)
│   ├── storage.rs           # SQLite (testé)
│   ├── llm.rs               # Bridge Ollama (phi3:mini)
│   └── briefing.rs          # Génération Markdown (testé)
├── data/
│   └── kairos.db
├── config/
│   └── profile.toml         # CV structuré
└── docs/
    ├── QQOQCP.md            # Analyse business (ex-Jarvis)
    ├── ARCHITECTURE.md      # Architecture cible + ADRs (ex-Jarvis, à adapter)
    └── ROADMAP.md           # Roadmap 24 mois (ex-Jarvis, à adapter)
```

### Flux d'exécution

1. **Collecte** (cron toutes les 6h) → API Adzuna + Jooble + Remotive → SQLite
2. **Matching** à chaque lot → score pondéré vs profil, déduplication
3. **Batch nocturne** (5h00) :
   - Ranking → top 3 non présentés
   - Enrichissement → scraping site entreprise
   - Analyse données privées → Ollama phi3:mini (si configuré)
   - Génération briefing → `~/briefings/<date>.md`

### Profil CV (config/profile.toml)

Profil CV configuré dans `config/profile.toml` (gitignored — voir `config/profile.example.toml`).

Fichier TOML avec skills, préférences salariales, pays cibles, contact.

### Output briefing

Fichier Markdown avec top 3 offres, score de matching, enrichissement entreprise, lien de candidature.

---

## Décisions d'architecture (ADRs)

### ADR-001 — VPS OVH comme hôte principal `Accepté`

**Contexte** : Le PC maison n'est pas toujours allumé. L'assistant doit être disponible 24/7.
**Décision** : Kairos tourne sur VPS OVH (2 vCPU, 8 Go, 80 Go). Le GPU de la tour perso est un accélérateur optionnel, pas un prérequis.
**Conséquences** : Coût mensuel (~5€/mois). phi3:mini sur CPU pour les données privées — assez rapide pour du batch.

### ADR-002 — Rust comme langage unique `Accepté`

**Contexte** : Plusieurs langages possibles (Python, Go, Rust). Le projet doit être fiable et performant.
**Décision** : Rust pour tout le code applicatif. Zéro interpréteur, zéro runtime, un binaire.
**Conséquences** : Temps de compilation plus long. Pas de prototypage rapide. Stabilité et performance en contrepartie.

### ADR-003 — Deux voies étanches pour la privacy `Accepté`

**Contexte** : Les données personnelles (santé, agenda, repas) ne doivent jamais quitter le VPS.
**Décision** : Architecture à deux chemins : données privées → Ollama local (phi3:mini), données publiques → Claude API. Aucune exception.
**Conséquences** : Les modèles locaux sont plus petits (phi3:mini). La qualité est moindre pour l'analyse privée, mais la règle est absolue.

### ADR-004 — SQLite comme stockage unique v0 `Accepté`

**Contexte** : Besoin d'un stockage persistant, zéro maintenance, zéro infra.
**Décision** : SQLite via rusqlite. Fichier unique, pas de serveur, pas de config.
**Conséquences** : Pas de concurrence d'écriture (pas un problème pour mono-utilisateur). Migration future possible vers un store plus riche si besoin.

### ADR-005 — Pas de mémoire long terme avant la v1 `Accepté`

**Contexte** : La mémoire RAG complexifie le MVP sans valeur immédiate pour la brique emploi.
**Décision** : Kairos v0 = SQLite + fichiers Markdown. La mémoire (embeddings, RAG, TTL) sera ajoutée quand une brique le nécessite.
**Conséquences** : Pas de contexte historique pour les sessions. Les offres d'emploi sont persistées mais pas "comprises" dans le temps long.

### ADR-006 — Modèle local = phi3:mini par défaut `Accepté`

**Contexte** : Le VPS n'a pas de GPU. Le modèle local doit tourner sur CPU avec <8 Go RAM.
**Décision** : phi3:mini (3.8B) sous Ollama pour toutes les requêtes sur données privées. Si la carte GPU de la tour est disponible via WireGuard, possibilité d'utiliser un modèle 7B en Q4.
**Conséquences** : Génération lente (5-10 tok/s) mais batch nocturne — pas critique.

### ADR-007 — Étape zéro : objectifs écrits avant déploiement `Accepté`

**Contexte** : Kairos optimise dans une direction. Sans direction écrite, il optimise dans la mauvaise — plus vite.
**Décision** : Avant tout déploiement du daemon sur le VPS, l'utilisateur écrit 3-5 objectifs personnels pour les 6 prochains mois.
**Conséquences** : Blocage de 30-60 min. Non-négociable. (Voir docs/QQOQCP.md pour le détail.)

---

## Phases de déploiement

Adapté de la roadmap complète dans docs/ROADMAP.md — ici la version Kairos resserrée.

| Phase | Objectif | Critère de sortie |
|-------|----------|-------------------|
| **Étape zéro** | Objectifs écrits (ADR-007) | 3-5 objectifs perso rédigés |
| **v0.1** | Brique emploi fonctionnelle | Briefing généré et lu ≥ 5 jours consécutifs |
| **v0.2** | Collecte automatique + cron VPS | Tourne 24/7, briefing dispo au réveil |
| **v0.3** | Intégration Ollama (analyse privée) | Données perso intégrées au briefing |
| **v1.0** | Nouvelle brique (planning/mémoire) | 2 briques actives, usage ≥ 3×/semaine |

---

## Prochaines étapes immédiates

1. Vérifier le GPU de la tour ce soir (4 Go ou plus ?)
2. Mettre les API keys (Adzuna, Jooble) dans des variables d'env
3. Déployer sur le VPS OVH
4. Configurer le cron de collecte
5. Lire un premier briefing
6. Itérer
