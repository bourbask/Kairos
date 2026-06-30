# Backlog — Kairos

**Sprint 0 = Brique 1 production-ready** | Mise à jour : 2026-06-30

---

## Légende

- **Taille** : XS (<1h) · S (1-2h) · M (2-4h) · L (4-8h)
- **MoSCoW** : M (Must) · S (Should) · C (Could) · W (Won't v0.1)
- **Statut** : `todo` · `in-progress` · `done` · `blocked`

---

## Sprint 0 — Mise en production Brique 1

### Infrastructure & Configuration

| ID | Tâche | Taille | MoSCoW | Statut | Critère Done |
|----|-------|--------|--------|--------|--------------|
| K-01 | Remplir `OBJECTIVES.md` (ADR-007) | XS | **M** | `todo` | Fichier rempli, commité |
| K-02 | Créer `.env` depuis `.env.example` avec vraies API keys | XS | **M** | `todo` | `cargo run -- status` passe |
| K-03 | Déployer binaire sur VPS OVH | S | **M** | `todo` | `ssh vps kairos status` répond |
| K-04 | Configurer cron 5h00 sur VPS | XS | **M** | `todo` | Cron listé `crontab -l`, briefing généré au réveil |
| K-05 | Configurer cron collecte toutes les 6h | XS | **M** | `todo` | Offres insérées en DB sans intervention |
| K-06 | Installer Ollama + phi3:mini sur VPS | S | **M** | `todo` | `ollama run phi3:mini "test"` répond sur VPS |

### Code — Correctifs & Stabilité

| ID | Tâche | Taille | MoSCoW | Statut | Critère Done |
|----|-------|--------|--------|--------|--------------|
| K-07 | Charger config depuis `.env` (ADZUNA_APP_ID etc.) | S | **M** | `todo` | Plus de valeurs hardcodées dans le code |
| K-08 | Ajouter circuit-breaker sur collecteurs API | M | **S** | `todo` | Si API down → log warning, continue sans crasher |
| K-09 | Gérer erreur gracieuse si Ollama indisponible | S | **S** | `todo` | Briefing généré sans section LLM si Ollama KO |
| K-10 | Ajouter collector Arbeitnow | S | **S** | `todo` | `cargo test collectors::arbeitnow` passe |
| K-11 | Rate limiting Adzuna (500 req/mois) | S | **M** | `todo` | Compteur en DB, log si proche du seuil |
| K-12 | Déduplication inter-collecteurs (même offre = même entreprise+titre) | S | **M** | `todo` | Test de déduplication passe, 0 doublon en DB |

### LLM — Routeur Hybride (PC home + VPS)

| ID | Tâche | Taille | MoSCoW | Statut | Critère Done |
|----|-------|--------|--------|--------|--------------|
| K-13 | Ajouter `OLLAMA_URL_PC_HOME` dans `.env.example` | XS | **S** | `todo` | Variable documentée |
| K-14 | Implémenter health check dans `LlmClient` | S | **S** | `todo` | `LlmClient::from_env()` tente PC home avant VPS |
| K-15 | Tester routing automatique VPS ↔ PC home | S | **S** | `todo` | Log indique quel nœud est utilisé |

### Documentation & Qualité

| ID | Tâche | Taille | MoSCoW | Statut | Critère Done |
|----|-------|--------|--------|--------|--------------|
| K-16 | Premier commit git | XS | **M** | `todo` | `git log --oneline` montre ≥ 1 commit |
| K-17 | `cargo test` 100% vert | XS | **M** | `todo` | 0 test en échec |
| K-18 | `cargo clippy` 0 warning | S | **S** | `todo` | `cargo clippy -- -D warnings` passe |

---

## Backlog futur — Brique 1 v0.2+

> Trié par valeur, pas par ordre de réalisation.

| ID | Idée | Valeur |
|----|------|--------|
| K-F01 | Filtrage négatif (entreprises blacklistées, secteurs non souhaités) | Qualité matching |
| K-F02 | Score de nouveauté (offre ancienne pénalisée) | Pertinence briefing |
| K-F03 | Enrichissement LinkedIn via Adzuna company API | Qualité enrichissement |
| K-F04 | Notification push locale (ntfy.sh self-hosted) si offre score > 90 | Réactivité |
| K-F05 | Export briefing vers fichier agenda (ICS) | Intégration workflow |
| K-F06 | Statistiques hebdomadaires (offres vues, candidatures, réponses) | Tracking |
| K-F07 | Intégration WireGuard pour accès PC home depuis VPS | Prérequis K-14 |

---

## Won't do (v0.1)

| Idée | Raison |
|------|--------|
| Interface web | Scope hors Brique 1 |
| API REST pour les briefings | Pas de consommateur identifié |
| Intégration Indeed / LinkedIn | Anti-bot trop agressif |
| Chat temps réel | Architecture batch by design |
| Mémoire long terme | ADR-005 |

---

*KAIROS-BACKLOG · Sprint 0 · 2026-06-30*
