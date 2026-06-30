# Kairos

Assistant personnel batch — collecte des offres d'emploi, score selon ton profil, génère un briefing Markdown chaque matin.

Tourne sur VPS OVH (H24). Produit `~/briefings/YYYY-MM-DD.md` via cron à 5h00.

---

## Statut

| Phase | Statut |
|-------|--------|
| Brique 1 — Veille emploi | 🟡 Sprint 0 (mise en prod) |
| Brique 2 — Planning/agenda | 🔲 Non démarré |
| Brique 3 — Mémoire long terme | 🔲 v1.0 |

**Avant de déployer** : remplir [`OBJECTIVES.md`](OBJECTIVES.md) (ADR-007 — bloquant).

---

## Prérequis

- Rust 2024 (`rustup update stable`)
- VPS OVH (ou équivalent) — 2 vCPU, 8 GB RAM
- [Ollama](https://ollama.ai) installé sur le VPS + modèle `phi3:mini`
- API keys : Adzuna, Jooble (optionnel)
- PC home avec Ollama + `mistral-nemo:12b-Q4_K_M` (optionnel — fallback VPS si indisponible)

---

## Installation

```bash
git clone <repo>
cd kairos

# Configurer l'environnement
cp .env.example .env
# → éditer .env avec tes API keys

# Compiler
cargo build --release

# Vérifier
cargo test
```

---

## Configuration

Copier `.env.example` → `.env` et remplir :

```bash
ADZUNA_APP_ID=xxx
ADZUNA_API_KEY=xxx
JOOBLE_API_KEY=xxx          # optionnel

OLLAMA_URL_VPS=http://localhost:11434
OLLAMA_URL_PC_HOME=         # optionnel, ex: http://192.168.1.x:11434
OLLAMA_MODEL_VPS=phi3:mini
OLLAMA_MODEL_PC=mistral-nemo:12b-Q4_K_M
```

Le profil CV est dans [`config/profile.toml`](config/profile.toml).

---

## Commandes

```bash
# Collecte manuelle des offres
cargo run -- scrape

# Générer le briefing du jour
cargo run -- briefing

# Stats (offres en DB, scores, dernière collecte)
cargo run -- status

# Requête libre à Ollama
cargo run -- prompt "Résume en 3 points ce qui me ferait changer d'emploi"
```

Ou via `make` :

```bash
make scrape      # collecte
make briefing    # génère briefing
make status      # stats
make test        # tous les tests
make build       # compilation release
```

---

## Déploiement VPS

```bash
# Sur VPS OVH
cargo build --release --target x86_64-unknown-linux-gnu
scp target/release/kairos user@vps-ovh:~/bin/

# Cron (crontab -e)
0 5 * * * /home/user/bin/kairos briefing >> /home/user/logs/kairos.log 2>&1
0 */6 * * * /home/user/bin/kairos scrape >> /home/user/logs/kairos.log 2>&1
```

---

## Architecture

```
kairos/
├── src/
│   ├── main.rs          # CLI : scrape | briefing | status | prompt
│   ├── collectors/      # Adzuna, Jooble, Remotive (trait Collector)
│   ├── matching.rs      # Scoring pondéré : skills 40% / remote 25% / salaire 20% / loc 15%
│   ├── ranker.rs        # Top N non présentés
│   ├── enrichment.rs    # Enrichissement entreprise (DuckDuckGo)
│   ├── storage.rs       # SQLite via rusqlite
│   ├── llm.rs           # LlmClient (phi3:mini VPS / mistral-nemo PC home)
│   └── briefing.rs      # Génération Markdown
├── config/
│   └── profile.toml     # CV : compétences, préférences, cible salariale
├── data/
│   └── kairos.db        # SQLite
└── docs/                # QQOQCP, ARCHITECTURE, ROADMAP
```

Voir [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) pour le détail technique et les ADRs.

---

## Tests

```bash
cargo test                    # tous les tests
cargo test matching           # tests matching uniquement
cargo test -- --nocapture     # avec output
```

18 tests unitaires couvrent : matching, storage, briefing, enrichment, collectors (adzuna), config, llm.

---

## Docs

- [`CHARTER.md`](CHARTER.md) — périmètre, contraintes, critères de succès
- [`OBJECTIVES.md`](OBJECTIVES.md) — objectifs personnels (ADR-007, à remplir)
- [`BACKLOG.md`](BACKLOG.md) — tickets sprint 0, WBS Brique 1
- [`RISKS.md`](RISKS.md) — registre des risques
- [`CHANGELOG.md`](CHANGELOG.md) — historique des versions
- [`docs/QQOQCP.md`](docs/QQOQCP.md) — analyse business
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — architecture technique et ADRs
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — roadmap 24 mois
- [`PLAN.md`](PLAN.md) — plan de développement détaillé
