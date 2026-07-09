# Changelog

Format : [Keep a Changelog](https://keepachangelog.com/fr/1.0.0/)  
Versioning : [Semantic Versioning](https://semver.org/)

---

## [0.1.0] — 2026-06-30

### Added
- **Job monitoring module** — MVP
- CLI with 4 commands : `scrape`, `briefing`, `status`, `prompt`
- 3 API collectors : Adzuna (tested), Jooble, Remotive
- Weighted scoring : skills / remote / salary / location
- Deduplication across collectors
- Company enrichment
- SQLite storage — offers, scores, presentation history
- Daily Markdown briefing generation
- Ollama bridge for local LLM
- TOML CV profile (gitignored)
- 18 unit tests (matching, storage, briefing, enrichment, adzuna, config, llm)
- Full documentation

### Infrastructure
- Rust 2024 edition
- Dependencies : tokio, reqwest, serde, rusqlite, cron, scraper, toml, chrono, clap, anyhow
