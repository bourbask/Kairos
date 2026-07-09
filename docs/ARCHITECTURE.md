# Architecture — Kairos

**Stack** : Rust 2024, VPS, SQLite, Ollama

---

## 1. Stack

| Component | Technology | Role |
|-----------|------------|------|
| Language | Rust 2024 | Application code |
| Runtime | VPS (2 vCPU, 8 GB RAM) | 24/7 host |
| Storage | SQLite (`rusqlite`) | Offers, scores, briefings, history |
| LLM | Ollama + local model | Private data, nightly batch |
| CLI | `clap` | User interface (`scrape`, `briefing`, `status`, `prompt`) |
| HTTP | `reqwest` + `tokio` | API collectors, enrichment, Ollama |
| Scheduling | cron | Nightly batch, periodic collection |

---

## 2. Global architecture

```
┌────────────────────────────────────────────────────────┐
│                    KAIROS                               │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │  COLLECTORS (Rust, periodic cron)                 │   │
│  │  Adzuna · Jooble · Remotive                       │   │
│  └──────────────────────┬───────────────────────────┘   │
│                         │ raw offers                     │
│                         ▼                                │
│  ┌──────────────────────────────────────────────────┐   │
│  │  MATCHING (Rust, synchronous)                     │   │
│  │  skills / remote / salary / location scoring      │   │
│  │  Deduplication · Ranking · Composite score        │   │
│  └──────────────────────┬───────────────────────────┘   │
│                         │ scored offers                  │
│                         ▼                                │
│  ┌──────────────────────────────────────────────────┐   │
│  │  SQLite (data/kairos.db)                          │   │
│  │  job_offers · scores · presentation_history       │   │
│  └──────────────────────┬───────────────────────────┘   │
│                         │                                │
│              ┌──────────┴──────────┐                    │
│              │  NIGHTLY BATCH      │                    │
│              └──────────┬──────────┘                    │
│                         │                                │
│              ┌──────────▼──────────┐                    │
│              │  LLM ROUTER         │                    │
│              │  health_check() ?   │                    │
│              │  ├─ OK → secondary  │                    │
│              │  └─ KO → primary    │ ← guaranteed H24   │
│              └──────────┬──────────┘                    │
│                         │                                │
│  ┌──────────────────────▼────────────────────────────┐   │
│  │  ENRICHMENT (public data → optional cloud API)    │   │
│  │  Company description enrichment                    │   │
│  └──────────────────────┬────────────────────────────┘   │
│                         │                                │
│  ┌──────────────────────▼────────────────────────────┐   │
│  │  BRIEFING (Rust + LLM)                            │   │
│  │  Top N offers · scores · enrichment · analysis    │   │
│  │  Output: ~/briefings/YYYY-MM-DD.md                 │   │
│  └──────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┘
```

### Two isolated paths

```
Public data (job offers, companies)
    └→ Cloud API / scraping / job board APIs

Private data (health, agenda — future modules)
    └→ Local Ollama only · NEVER to cloud
```

---

## 3. Code structure

```
kairos/
├── src/
│   ├── main.rs              # CLI (clap) : scrape | briefing | status | prompt
│   ├── config.rs            # Profile loading (name, skills, preferences)
│   ├── models.rs            # JobOffer, ScoredJob, ScoreBreakdown, DailyBriefing
│   ├── collectors/
│   │   ├── mod.rs           # Collector trait (async, name + fetch)
│   │   ├── adzuna.rs        # Adzuna REST API — multi-country [tested]
│   │   ├── jooble.rs        # Jooble REST API [tested]
│   │   └── remotive.rs      # Remotive API — unlimited, remote-only
│   ├── matching.rs          # Weighted scoring, inter-batch deduplication [3 tests]
│   ├── ranker.rs            # Top N unseen (orchestrates score + storage)
│   ├── enrichment.rs        # Company enrichment [tested]
│   ├── storage.rs           # SQLite : insert, score, mark_presented, top_unpresented [tested]
│   ├── llm.rs               # LlmClient : generate, summarize, analyze_day [tested]
│   └── briefing.rs          # Markdown output → ~/briefings/ [tested]
├── config/
│   ├── profile.toml         # CV : skills, salary target, countries, contact (gitignored)
│   └── profile.example.toml # Template
├── data/
│   └── kairos.db            # SQLite (gitignored)
├── docs/                    # Documentation
│   └── ARCHITECTURE.md
└── private/                 # Personal notes (gitignored)
```

---

## 4. Execution flow — Job monitoring module

### Collection (periodic cron)

```
cron →
  kairos scrape
    → Adzuna (configured countries)
    → Jooble (backup)
    → Remotive (global remote)
    → Matching + Score → SQLite
    → Deduplication (title + company + country)
```

### Nightly batch

```
cron →
  kairos briefing
    1. top_unseen(N) → top N unseen offers
    2. Company enrichment
    3. LLM routing (primary / secondary)
    4. summarize(offer) × N
    5. Markdown generation → ~/briefings/YYYY-MM-DD.md
    6. mark_presented(offer_ids)
```

---

## 5. Storage — SQLite

### Current tables

```sql
CREATE TABLE job_offers (
    id TEXT PRIMARY KEY,          -- hash(title + company + country)
    source TEXT,                  -- "adzuna" | "jooble" | "remotive"
    titre TEXT, entreprise TEXT, pays TEXT,
    salaire_min INTEGER, salaire_max INTEGER,
    remote INTEGER,               -- 0 or 1
    url TEXT, description TEXT,
    date_collecte TEXT,
    score REAL,                   -- matching score 0-1
    presente INTEGER DEFAULT 0    -- 1 if shown in a briefing
);

CREATE TABLE briefings (
    date TEXT PRIMARY KEY,
    chemin_fichier TEXT,
    offres_ids TEXT               -- JSON array of presented IDs
);
```

### Consistency

- Deduplication by `hash(title + company + country)` — no cross-collector duplicates
- `presente = 1` after briefing inclusion → never shown again
- No automatic deletion — full history kept

---

## 6. Architecture decisions

| Decision | Status |
|----------|--------|
| VPS as primary host (not personal machine) | Accepted |
| Rust as sole language | Accepted |
| Two isolated paths: private → local LLM, public → cloud API | Accepted |
| SQLite as v0 storage | Accepted |
| No long-term memory before v1.0 | Accepted |
| Local LLM model by default on VPS CPU | Accepted |
| Blocking step zero: written objectives before deployment | Accepted |
| Hybrid LLM routing (primary server + optional secondary) | Accepted |
