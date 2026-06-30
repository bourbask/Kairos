# Architecture — Kairos

**Réf.** KAI-ARCH · v0.1 | **Date** : 2026-06-30 | **Stack** : Rust 2024, VPS OVH, SQLite, Ollama

> Contrainte principale : 5-10h/semaine. Tout composant qui exige > 2 min/jour de maintenance doit être justifié ou supprimé.

---

## 1. Stack technique

| Composant | Technologie | Rôle |
|-----------|-------------|------|
| Langage | Rust 2024 | Code applicatif — performance, fiabilité, zéro runtime |
| Exécution | VPS OVH (2 vCPU, 8 GB RAM, 80 GB) | Hôte principal, H24 |
| Stockage | SQLite (`rusqlite`) | Offres d'emploi, scores, briefings, historique |
| LLM primaire | Ollama + `phi3:mini` (3.8B Q4, CPU) | Données privées, batch nocturne |
| LLM secondaire | Ollama + `mistral-nemo:12b-Q4_K_M` (GPU) | Analyses profondes (PC home, dispo variable) |
| API cloud | Claude API (`claude-sonnet-4-6`) | Données publiques uniquement (offres, enrichissement) |
| CLI | `clap` | Interface utilisateur (`scrape`, `briefing`, `status`, `prompt`) |
| HTTP | `reqwest` + `tokio` | Collecteurs API, enrichissement, Ollama |
| Cron | Cron système VPS | Batch nocturne 5h00, collecte 6h |

### Matrice LLM

| Nœud | Modèle | Hardware | Dispo | Usage |
|------|--------|----------|-------|-------|
| **VPS OVH** (primaire) | phi3:mini (3.8B Q4) | CPU-only | H24 | Batch nocturne, analyse données privées, toutes requêtes |
| **PC home** (secondaire) | mistral-nemo:12b-Q4_K_M | RTX 2070 SUPER — 8192 MB GDDR6, 2560 CUDA cores | Variable | Analyses profondes quand disponible |
| **Claude API** | claude-sonnet-4-6 | Cloud | H24 | Enrichissement entreprise, offres (données publiques) |

---

## 2. Architecture globale

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           KAIROS — VPS OVH                                  │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  COLLECTEURS (Rust, cron toutes les 6h)                              │   │
│  │  Adzuna · Jooble · Remotive                                          │   │
│  │  500 req/mois · 100 req/j · illimité                                 │   │
│  └──────────────────────────┬───────────────────────────────────────────┘   │
│                             │ offres brutes                                 │
│                             ▼                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  MATCHING (Rust, synchrone)                                          │   │
│  │  skills 40% / remote 25% / salaire 20% / localisation 15%           │   │
│  │  Déduplication · Ranking · Score composite                           │   │
│  └──────────────────────────┬───────────────────────────────────────────┘   │
│                             │ offres scorées                                │
│                             ▼                                               │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  SQLite (data/kairos.db)                                             │   │
│  │  job_offers · scores · presentation_history · briefings              │   │
│  └──────────────────────────┬───────────────────────────────────────────┘   │
│                             │                                               │
│              ┌──────────────┴──────────────┐                               │
│              │ cron 5h00 : BATCH NOCTURNE  │                               │
│              └──────────────┬──────────────┘                               │
│                             │                                               │
│              ┌──────────────▼──────────────┐                               │
│              │  ROUTEUR LLM                │                               │
│              │  health_check(PC_HOME) ?    │                               │
│              │  ├─ OK → mistral-nemo 12B   │ ← PC home RTX 2070 SUPER     │
│              │  └─ KO → phi3:mini (VPS)    │ ← fallback garanti H24       │
│              └──────────────┬──────────────┘                               │
│                             │                                               │
│  ┌──────────────────────────▼───────────────────────────────────────────┐   │
│  │  ENRICHISSEMENT (données publiques → Claude API uniquement)          │   │
│  │  Scraping DuckDuckGo · Meta description entreprise                   │   │
│  └──────────────────────────┬───────────────────────────────────────────┘   │
│                             │                                               │
│  ┌──────────────────────────▼───────────────────────────────────────────┐   │
│  │  BRIEFING (Rust + LLM)                                               │   │
│  │  Top 3 offres · scores · enrichissement · analyse privée             │   │
│  │  Sortie : ~/briefings/YYYY-MM-DD.md                                  │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
                              Lu au réveil par l'utilisateur
```

### Deux chemins étanches (ADR-003)

```
Données PUBLIQUES (offres emploi, entreprises)
    └→ Claude API (enrichissement) · Internet (scraping) · APIs job boards

Données PRIVÉES (santé, agenda, repas — futures briques)
    └→ Ollama local sur VPS uniquement · JAMAIS vers cloud
```

---

## 3. Routeur LLM hybride

### Principe

`LlmClient` dans `src/llm.rs` supporte deux nœuds :
- **VPS** : phi3:mini, `http://localhost:11434`, H24 garanti
- **PC home** : mistral-nemo:12b-Q4_K_M, URL configurable via `OLLAMA_URL_PC_HOME`, dispo variable

### Logique de routing (à implémenter — ticket K-14)

```rust
// Pseudo-code cible
impl LlmClient {
    pub async fn from_env() -> Self {
        let pc_url = std::env::var("OLLAMA_URL_PC_HOME").ok();
        if let Some(url) = pc_url {
            if health_check(&url).await.is_ok() {
                return Self::new(url, env::var("OLLAMA_MODEL_PC").unwrap());
            }
        }
        Self::new(
            env::var("OLLAMA_URL_VPS").unwrap_or("http://localhost:11434".into()),
            env::var("OLLAMA_MODEL_VPS").unwrap_or("phi3:mini".into()),
        )
    }
}
```

### Capacités par modèle

| Tâche | phi3:mini (VPS CPU) | mistral-nemo:12b (PC GPU) |
|-------|---------------------|--------------------------|
| Résumé offre emploi | Suffisant (~5-10 tok/s) | Meilleure qualité (~30-40 tok/s) |
| Analyse données privées | Suffisant (batch nocturne) | Supérieur |
| Raisonnement complexe | Limité | Nettement meilleur |
| Disponibilité | H24 | Variable (PC allumé) |

### Configuration via `.env`

```bash
OLLAMA_URL_VPS=http://localhost:11434
OLLAMA_MODEL_VPS=phi3:mini
OLLAMA_URL_PC_HOME=http://10.x.x.x:11434   # WireGuard si accès VPS→PC
OLLAMA_MODEL_PC=mistral-nemo:12b-Q4_K_M
```

---

## 4. Structure du code

```
kairos/
├── src/
│   ├── main.rs              # CLI (clap) : scrape | briefing | status | prompt
│   ├── config.rs            # Chargement profile.toml (nom, compétences, préférences)
│   ├── models.rs            # JobOffer, ScoredJob, ScoreBreakdown, DailyBriefing
│   ├── collectors/
│   │   ├── mod.rs           # Trait Collector (async, name + fetch)
│   │   ├── adzuna.rs        # Adzuna REST API — 500 req/mois, multi-pays [testé]
│   │   ├── jooble.rs        # Jooble REST API — 100 req/j [testé]
│   │   └── remotive.rs      # Remotive API — illimitée, remote-only
│   ├── matching.rs          # Scoring pondéré, déduplication inter-lots [3 tests]
│   ├── ranker.rs            # Top N non présentés (orchestration score + storage)
│   ├── enrichment.rs        # DuckDuckGo scraping + meta description [testé]
│   ├── storage.rs           # SQLite : insert, score, mark_presented, top_unpresented [testé]
│   ├── llm.rs               # LlmClient : generate, summarize, analyze_day [testé]
│   └── briefing.rs          # Markdown output → ~/briefings/ [testé]
├── config/
│   └── profile.toml         # CV : Kevin Bourbasquet, Symfony/React, 45-55k€, remote
├── data/
│   └── kairos.db            # SQLite (gitignored)
└── docs/                    # Documentation PM
```

---

## 5. Flux d'exécution — Brique 1

### Collecte (cron toutes les 6h)

```
cron →
  kairos scrape
    → Adzuna (CH, BE, LU, NL, DE, FR) — 3 pages × 6 pays
    → Jooble (backup, si quota disponible)
    → Remotive (remote global, 3 catégories)
    → Matching + Score → SQLite
    → Déduplication (titre + entreprise + pays)
```

### Batch nocturne (cron 5h00)

```
cron 5h00 →
  kairos briefing
    1. top_unpresented(3) → top 3 offres non vues
    2. Enrichissement entreprise (DuckDuckGo, meta description)
    3. LlmClient::from_env() → routing VPS/PC home
    4. summarize(offre) × 3 → résumés en français
    5. analyze_day(agenda, santé, repas) → future brique v0.3
    6. Génération Markdown → ~/briefings/YYYY-MM-DD.md
    7. mark_presented(offre_ids) → evite re-présentation
```

---

## 6. Stockage — SQLite

### Tables actuelles

```sql
-- Offres d'emploi collectées
CREATE TABLE job_offers (
    id TEXT PRIMARY KEY,          -- hash(titre + entreprise + pays)
    source TEXT,                  -- "adzuna" | "jooble" | "remotive"
    titre TEXT, entreprise TEXT, pays TEXT,
    salaire_min INTEGER, salaire_max INTEGER,
    remote INTEGER,               -- 0 ou 1
    url TEXT, description TEXT,
    date_collecte TEXT,
    score REAL,                   -- score matching 0-1
    presente INTEGER DEFAULT 0    -- 1 si dans un briefing
);

-- Briefings générés
CREATE TABLE briefings (
    date TEXT PRIMARY KEY,
    chemin_fichier TEXT,
    offres_ids TEXT               -- JSON array d'IDs présentés
);
```

### Contraintes de cohérence

- Déduplication par `hash(titre + entreprise + pays)` — pas de doublons inter-collecteurs
- `presente = 1` après inclusion dans un briefing → ne réapparaît plus
- Pas de suppression automatique — historique complet conservé

---

## 7. ADRs

Les ADRs sont définis dans [`PLAN.md`](../PLAN.md) (source of truth). Résumé ici.

| # | Décision | Statut |
|---|----------|--------|
| ADR-001 | VPS OVH comme hôte principal (pas PC maison) | Accepté |
| ADR-002 | Rust comme langage unique | Accepté |
| ADR-003 | Deux voies étanches : données privées → Ollama local, publiques → Claude API | Accepté |
| ADR-004 | SQLite comme stockage unique v0 | Accepté |
| ADR-005 | Pas de mémoire long terme avant v1.0 | Accepté |
| ADR-006 | phi3:mini comme modèle local par défaut (CPU VPS) | Accepté |
| ADR-007 | Étape zéro bloquante : écrire `OBJECTIVES.md` avant déploiement | Accepté |

### ADR-008 — Architecture hybride LLM VPS + PC home `Accepté`

**Contexte** : Le PC home (RTX 2070 SUPER, 8192 MB GDDR6, 2560 CUDA cores) peut faire tourner mistral-nemo:12b-Q4_K_M (~7.5 GB VRAM) avec une qualité significativement supérieure à phi3:mini. Mais il n'est pas allumé H24.

**Décision** : `LlmClient` implémente un routeur : health check sur `OLLAMA_URL_PC_HOME` à chaque appel. Si dispo → mistral-nemo. Si indispo → phi3:mini VPS (fallback garanti). Les deux modèles partagent la même interface `generate()`.

**Conséquences** : Amélioration transparente de la qualité quand le PC est allumé. Aucune dégradation quand il est éteint. Prérequis : `OLLAMA_URL_PC_HOME` configuré (WireGuard si accès VPS→PC home sur LAN).

---

## 8. Phases de déploiement

| Phase | Prérequis | Critère de sortie | Nouveautés |
|-------|----------|------------------|-----------|
| **Étape zéro** | — | `OBJECTIVES.md` rempli | Objectifs perso écrits (ADR-007) |
| **v0.1** | Étape zéro + `cargo test` vert | Briefing lu ≥ 5 jours consécutifs | Brique 1 : collecte, matching, briefing |
| **v0.2** | v0.1 validé | Cron 24/7 stable, 4 semaines sans erreur | Déploiement VPS, cron, routeur LLM hybride |
| **v0.3** | v0.2 validé | Données privées dans le briefing | Ollama analyse agenda/santé/repas |
| **v1.0** | v0.3 validé + besoin avéré | 2 briques actives, usage ≥ 3×/semaine | Brique 2 (planning/mémoire), SQLite + embeddings |

> **Règle v0.1** : feature freeze. Aucune amélioration avant 5 jours de briefing lus. Usage valide avant configuration supplémentaire.

---

*KAI-ARCH · v0.1 · 2026-06-30 · Kairos*  
*Dépendances : P1-Personal-Finance-Hub (intégration prévue v1.x)*
