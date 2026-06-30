# Registre des risques — Kairos

**Version** : v0.1 | **Mise à jour** : 2026-06-30

---

## Légende

- **Probabilité** : F (Faible) · M (Modérée) · E (Élevée)
- **Impact** : F (Faible) · M (Modéré) · C (Critique)
- **Score** = Probabilité × Impact : 1-4 (bas) · 5-6 (moyen) · 7-9 (élevé)
- **Statut** : `ouvert` · `mitigé` · `accepté` · `clos`

---

## Risques techniques

| ID | Risque | Prob | Impact | Score | Mitigation | Statut |
|----|--------|------|--------|-------|-----------|--------|
| R-T01 | **Adzuna quota dépassé** (500 req/mois) → collecte bloquée | M | C | 6 | Compteur en DB (K-11), fallback Jooble + Remotive si proche seuil, réduire fréquence cron | `ouvert` |
| R-T02 | **VPS OVH down** → briefing non généré | F | M | 3 | Hébergeur SLA 99.9%. Briefing manquant = acceptable (pas critique), fichier hier suffisant | `accepté` |
| R-T03 | **Ollama phi3:mini indisponible** sur VPS | M | M | 4 | Briefing généré sans section LLM (K-09), pas bloquant | `ouvert` |
| R-T04 | **Rust compilation fail** après update dépendances | F | M | 3 | `Cargo.lock` commité, versions épinglées, `cargo update` manuel uniquement | `accepté` |
| R-T05 | **SQLite corruption** (`data/kairos.db`) | F | C | 4 | Backup hebdomadaire automatique sur VPS (`cp kairos.db kairos.db.bak`), données recollectables | `ouvert` |
| R-T06 | **API keys exposées** dans git | F | C | 4 | `.env` dans `.gitignore`, `.env.example` sans valeurs, audit `git log` avant premier push | `ouvert` |

## Risques de qualité LLM

| ID | Risque | Prob | Impact | Score | Mitigation | Statut |
|----|--------|------|--------|-------|-----------|--------|
| R-L01 | **phi3:mini qualité insuffisante** pour analyse données privées | M | M | 4 | Architecture hybride : si PC home dispo → mistral-nemo:12b (RTX 2070 SUPER, 8GB GDDR6) utilisé à la place | `mitigé` |
| R-L02 | **Hallucination matching** — offre non pertinente scorée haut | M | M | 4 | Score basé sur règles Rust (pas LLM), LLM uniquement pour enrichissement textuel | `accepté` |
| R-L03 | **PC home indisponible** → routeur LLM fallback VPS non configuré | E | F | 3 | phi3:mini VPS toujours disponible comme fallback (K-14) | `ouvert` |

## Risques usage

| ID | Risque | Prob | Impact | Score | Mitigation | Statut |
|----|--------|------|--------|-------|-----------|--------|
| R-U01 | **Tinkering infini** — construction > usage | E | C | 9 | Règle 80/20 (usage/config). Pas de refacto majeur avant 90j d'usage réel. Sprint 0 = mise en prod, pas amélioration | `ouvert` |
| R-U02 | **Abandon après 2 semaines** (décrochage habituel) | M | C | 6 | Critère v0.1 : utilisé 5 jours consécutifs. Si atteint → validé. Simplicité maximale = friction minimale | `ouvert` |
| R-U03 | **Objectifs non rédigés** (ADR-007) → Kairos optimise dans la mauvaise direction | M | C | 6 | `OBJECTIVES.md` = gate bloquant avant déploiement. Pas de commit deploy sans ce fichier rempli | `ouvert` |
| R-U04 | **Over-engineering Brique 1** avant validation usage | M | M | 4 | Backlog K-F0x = Won't do v0.1. Feature freeze jusqu'à O-01 atteint | `ouvert` |

## Risques de sécurité / légal

| ID | Risque | Prob | Impact | Score | Mitigation | Statut |
|----|--------|------|--------|-------|-----------|--------|
| R-S01 | **Scraping DuckDuckGo bloqué** (enrichissement entreprise) | M | F | 2 | Non bloquant, enrichissement optionnel. Fallback = offre sans enrichissement | `accepté` |
| R-S02 | **Données perso sur Claude API** par erreur | F | C | 4 | Architecture ADR-003 : séparation physique des deux chemins. Review de code avant ajout de nouvelles sources | `ouvert` |

---

## Actions prioritaires (risques score ≥ 6)

| Risque | Action | Ticket |
|--------|--------|--------|
| R-U01 Tinkering | Sprint 0 = prod only, freeze feature | BACKLOG règle |
| R-U02 Abandon | Mesurer 5 jours consécutifs avant next sprint | K-04 |
| R-U03 Objectifs | Remplir OBJECTIVES.md avant déploiement | K-01 |
| R-T01 Quota AV | Implémenter compteur req | K-11 |

---

*KAIROS-RISKS · v0.1 · 2026-06-30*
