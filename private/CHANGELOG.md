# Changelog

Format : [Keep a Changelog](https://keepachangelog.com/fr/1.0.0/)  
Versioning : [Semantic Versioning](https://semver.org/)

---

## [Unreleased]

### Prévu (Sprint 0)
- Chargement config depuis `.env` (variables d'environnement)
- Circuit-breaker sur collecteurs API
- Gestion gracieuse Ollama indisponible
- Rate limiting Adzuna (compteur en DB)
- Routeur LLM hybride VPS ↔ PC home (health check)
- Déploiement cron VPS OVH

---

## [0.1.0] — 2026-06-30

### Ajouté
- **Brique 1 : Veille emploi** — MVP fonctionnel
- CLI avec 4 commandes : `scrape`, `briefing`, `status`, `prompt`
- 3 collecteurs API : Adzuna (testé), Jooble, Remotive
- Scoring pondéré : compétences 40% / remote 25% / salaire 20% / localisation 15%
- Déduplication des offres en base
- Enrichissement entreprise (DuckDuckGo + meta description)
- Stockage SQLite (`data/kairos.db`) — offres, scores, historique de présentation
- Génération briefing Markdown quotidien (`~/briefings/YYYY-MM-DD.md`)
- Bridge Ollama (`LlmClient`) — phi3:mini par défaut
- Profil CV TOML (`config/profile.toml`) — Symfony/React, 45-55k€, full remote CH/BE/LU/NL/DE
- 18 tests unitaires (matching, storage, briefing, enrichment, adzuna, config, llm)
- Documentation PM complète (CHARTER, OBJECTIVES, BACKLOG, RISKS, QQOQCP, ARCHITECTURE, ROADMAP)

### Infrastructure
- Rust 2024 edition
- Dépendances : tokio, reqwest, serde, rusqlite, cron, scraper, toml, chrono, clap, anyhow

---

*KAIROS-CHANGELOG · 2026-06-30*
