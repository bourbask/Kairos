# Architecture — Kairos

> ⚠️ **Recalibré le 2026-07-09.** Décisions techniques à jour dans [`docs/recalibration/`](recalibration/). Certaines lignes ci-dessous peuvent refléter des hypothèses obsolètes (chemin cloud, routeur LLM, rétention) — pour l'archi cible, la source de vérité est le dossier recalibration.

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

### CLI commands

```
kairos scrape              # Fetch job offers from all sources
kairos briefing [--top N]  # Generate daily briefing (jobs + planning)
kairos status              # Show stats
kairos prompt <text>       # Send prompt to local LLM

kairos plan add <title> [--desc] [--due YYYY-MM-DD] [--priority]
kairos plan list [--today] [--all]
kairos plan done <id>
kairos plan delete <id>

kairos plan routine show   # Show today's morning routine
kairos plan routine check <id>
kairos plan routine seed   # Seed default routine steps
```

---

## 3. Code structure

```
kairos/
├── src/
│   ├── main.rs              # CLI (clap) : scrape | briefing | status | prompt | plan
│   ├── config.rs            # Profile loading (name, skills, preferences)
│   ├── models.rs            # JobOffer, ScoredJob, ScoreBreakdown, Task, Routine
│   ├── collectors/
│   │   ├── mod.rs           # Collector trait (async, name + fetch)
│   │   ├── adzuna.rs        # Adzuna REST API — multi-country [tested]
│   │   ├── jooble.rs        # Jooble REST API [tested]
│   │   └── remotive.rs      # Remotive API — unlimited, remote-only
│   ├── planning/
│   │   ├── mod.rs           # Planner : task CRUD, routine management
│   ├── calendar/
│   │   ├── mod.rs           # calendar sync + ICS parsing
│   ├── matching.rs          # Weighted scoring, inter-batch deduplication [3 tests]
│   ├── ranker.rs            # Top N unseen (orchestrates score + storage)
│   ├── enrichment.rs        # Company enrichment [tested]
│   ├── storage.rs           # SQLite : jobs + planning tables [tested]
│   ├── llm.rs               # LlmClient : generate, summarize, analyze_day [tested]
│   └── briefing.rs          # Markdown output → ~/briefings/ [tested]
├── config/
│   ├── profile.toml         # CV : skills, salary target, countries, contact (gitignored)
│   └── profile.example.toml # Template
├── data/
│   └── kairos.db            # SQLite (gitignored)
├── docs/                    # Documentation
│   ├── ARCHITECTURE.md
│   ├── CHANGELOG.md
│   └── delivery-procedure.md
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
    3. Planning module → today's tasks + routine status
    4. Markdown generation → ~/briefings/YYYY-MM-DD.md
       - Routine matinale (checkboxes)
       - Tâches du jour (priority-sorted)
       - Tâches en attente
       - Offres d'emploi (scored, enriched)
    5. mark_presented(offer_ids)
```

---

## 5. Storage — SQLite

### Job offers tables

```sql
CREATE TABLE job_offers (
    id TEXT PRIMARY KEY,
    source TEXT NOT NULL,
    title TEXT NOT NULL,
    company TEXT NOT NULL,
    description TEXT NOT NULL,
    url TEXT NOT NULL,
    location TEXT,
    country TEXT,
    salary_min INTEGER,
    salary_max INTEGER,
    currency TEXT,
    remote INTEGER,
    published_at TEXT,
    collected_at TEXT NOT NULL,
    score REAL,
    presented INTEGER DEFAULT 0,
    presented_at TEXT
);

CREATE TABLE briefings (
    date TEXT PRIMARY KEY,
    content TEXT NOT NULL,
    generated_at TEXT NOT NULL
);
```

### Planning tables

```sql
CREATE TABLE tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    description TEXT,
    due_date TEXT,
    priority TEXT NOT NULL DEFAULT 'medium',
    completed INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    completed_at TEXT
);

CREATE TABLE routines (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    step_name TEXT NOT NULL,
    step_order INTEGER NOT NULL,
    estimated_minutes INTEGER,
    enabled INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE routine_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    routine_step_id INTEGER NOT NULL,
    completed INTEGER NOT NULL DEFAULT 0,
    completed_at TEXT,
    FOREIGN KEY (routine_step_id) REFERENCES routines(id)
);
```

### Consistency

- Deduplication by `hash(title + company + country)` — no cross-collector duplicates
- `presente = 1` after briefing inclusion → never shown again
- Tasks support priority sorting (high → medium → low)
- Routine log per date tracks morning routine completion
- Retention — deux classes : données éphémères (notes, RDV) archivées puis supprimées ; données durables/sensibles chiffrées et déportées vers un stockage externe (stub + résumé conservés localement).

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
| Planning module as independent brique (task CRUD + routine) | Accepted |
| Priority-sorted tasks with due dates | Accepted |
| Morning routine with daily completion log | Accepted |
