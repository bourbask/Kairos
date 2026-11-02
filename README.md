# Kairos

Personal batch assistant — monitors job offers, scores them against your profile, generates a daily Markdown briefing.

Runs on a VPS 24/7. Produces `~/briefings/YYYY-MM-DD.md` via cron.

---

## Status

| Module | Status |
|--------|--------|
| Job monitoring (Brique 1) | 🟢 MVP |
| Planning/agenda (Brique 2) | 🟡 In progress |
| Calendar sync | 🟡 In progress |
| Long-term memory (Brique 3) | 🔲 v1.0 |

---

## Prerequisites

- Rust 2024 (`rustup update stable`)
- A VPS or always-on server (2 vCPU, 8 GB RAM recommended)
- [Ollama](https://ollama.ai) + a local model (e.g. `phi3:mini` or `mistral-nemo:12b`)
- API keys: Adzuna, Jooble (optional)

---

## Installation

```bash
git clone <repo>
cd kairos

cp .env.example .env
# → edit .env with your API keys

cp config/profile.example.toml config/profile.toml
# → edit with your skills, salary targets, location preferences

cargo build --release
cargo test
```

---

## Configuration

`.env` file:
```bash
ADZUNA_APP_ID=xxx
ADZUNA_API_KEY=xxx
JOOBLE_API_KEY=xxx
```

Your CV profile goes in `config/profile.toml` (gitignored — copy from `.example`).

Optional per-module settings go in `config/modules.toml` (gitignored — copy from
`.example`). A missing file or section leaves that module inactive.

Generation routing goes in `config/llm.toml` (gitignored — copy from `.example`).
It declares two paths, self-hosted and external, and is the single source of the
engine choice: callers declare how sensitive and how urgent a generation is,
never which engine runs it. Strategic generations stay self-hosted with no
fallback; the only exceptions are the usages explicitly listed in
`strategic_concessions`.

---

## Commands

```bash
cargo run -- scrape              # fetch offers from all sources
cargo run -- briefing            # generate today's briefing
cargo run -- status              # stats (offers in DB, scores, last fetch)
cargo run -- prompt "..."        # send a prompt to the local LLM
cargo run -- plan add "title"         # add a task
cargo run -- plan list                # list pending tasks
cargo run -- plan done <id>           # mark task as done
cargo run -- plan routine show        # show morning routine
cargo run -- calendar sync            # sync calendar events
cargo run -- calendar today           # show today's events
```

Or via `make`:
```bash
make scrape      # collect
make briefing    # generate briefing
make status      # stats
make test        # all tests
make build       # release build
```

---

## Architecture

```
kairos/
├── src/
│   ├── main.rs          # CLI: scrape | briefing | status | prompt | plan
│   ├── collectors/      # Adzuna, Jooble, Remotive (Collector trait)
│   ├── planning/        # Task CRUD + morning routine
│   ├── matching.rs      # Weighted scoring
│   ├── ranker.rs        # Top N unseen offers
│   ├── enrichment.rs    # Company enrichment
│   ├── storage.rs       # SQLite (jobs + planning)
│   ├── llm.rs           # Inference client + generation router
│   └── briefing.rs      # Markdown generation
├── config/
│   ├── profile.toml     # Your CV (gitignored)
│   ├── profile.example.toml
│   ├── modules.toml     # Optional module config (gitignored)
│   ├── modules.example.toml
│   ├── llm.toml         # Generation routing (gitignored)
│   └── llm.example.toml
├── data/
│   └── kairos.db        # SQLite (gitignored)
└── docs/
    └── ARCHITECTURE.md  # Detailed architecture
```

### Privacy

Personal data never leaves your server. Public data (job offers, company info) can optionally use cloud APIs. Private data (health, agenda) stays local via Ollama.

---

## Deploy

```bash
cargo build --release
scp target/release/kairos user@your-server:~/bin/

# cron (crontab -e)
0 5 * * * /home/user/bin/kairos briefing
0 */6 * * * /home/user/bin/kairos scrape
```

---

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — technical architecture
- [`docs/delivery-procedure.md`](docs/delivery-procedure.md) — delivery workflow

---

## License

MIT
