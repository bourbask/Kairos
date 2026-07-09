# Kairos — Project Charter

**Version** : v0.1 | **Date** : 2026-06-30 | **Phase** : Initiation

---

## Vision

Kairos est un assistant personnel batch qui tourne sur VPS OVH H24, collecte des signaux utiles (emploi, actualités, données perso), et produit chaque matin un fichier Markdown de briefing lu au réveil. Pas de UI, pas de chat temps réel : un outil qui travaille pendant que tu dors.

---

## Objectifs

Voir [`OBJECTIVES.md`](OBJECTIVES.md) pour les objectifs personnels (ADR-007, bloquant avant déploiement).

**Objectifs projet v0.1 :**

| # | Objectif | Critère mesurable |
|---|----------|------------------|
| O-01 | Brique 1 (veille emploi) fonctionnelle | Briefing généré et lu ≥ 5 jours consécutifs |
| O-02 | Pipeline collecte stable | ≥ 4 semaines sans erreur bloquante |
| O-03 | Déploiement VPS actif | Cron 5h00 opérationnel, pas de supervision manuelle |
| O-04 | Temps de revue ≤ 5 min/jour | Lecture du briefing suffit, pas d'action requise sur l'outil |

---

## Périmètre

### Ce que Kairos FAIT (v0.1)

- Collecte d'offres d'emploi tech (Adzuna, Jooble, Remotive)
- Scoring et matching vs profil CV (Symfony/React, 45-55k€, full remote)
- Enrichissement entreprise (meta description)
- Génération briefing Markdown quotidien (`~/briefings/YYYY-MM-DD.md`)
- Analyse données privées via Ollama local (phi3:mini sur VPS)

### Ce que Kairos NE FAIT PAS (v0.1)

- ❌ Interface chat / temps réel
- ❌ Modification d'agenda ou envoi d'emails
- ❌ Exécution d'actions financières
- ❌ Mémoire long terme (ADR-005 — v1.0 au plus tôt)
- ❌ Analyse des finances personnelles (projet séparé : `personal-finance-hub`)
- ❌ Intégration Google Calendar / Notion (briques futures)
- ❌ Déploiement PC maison comme hôte principal (VPS first)

### Briques futures (hors scope v0.1)

| Brique | Horizon |
|--------|---------|
| Veille planning / agenda | v0.3+ |
| Analyse données santé/repas | v0.3+ |
| Mémoire long terme (RAG) | v1.0 |
| Portefeuille investissement | v1.x (lié à P2) |
| Intégration finances perso | v1.x (lié à P1) |

---

## Contraintes

| Contrainte | Valeur | Impact |
|-----------|--------|--------|
| Budget VPS | ~5 €/mois | OVH Starter 2 vCPU / 8 GB RAM |
| Adzuna API | 500 req/mois (free tier) | Collecte toutes les 6h max, 8 pays |
| Jooble API | 100 req/jour (free tier) | Backup uniquement |
| Remotive API | Illimitée | Usage libre |
| Ollama sur VPS | CPU-only, phi3:mini | 5-10 tok/s — OK pour batch nocturne |
| Temps dispo | ~5-10h/semaine | Règle 80/20 : 80% usage, 20% config |
| Rust 2024 | Compilation lente | Pas de prototypage rapide, stabilité en contrepartie |

---

## Critères de succès — v0.1

| Critère | Mesure | Horizon |
|---------|--------|---------|
| Briefing utilisé | Lu ≥ 5 jours consécutifs | M+1 |
| Pipeline stable | 0 erreur bloquante sur 4 semaines | M+1 |
| Qualité matching | ≥ 1 offre pertinente/semaine dans le top 3 | M+2 |
| Coût infra | ≤ 10 €/mois total (VPS + API) | Continu |
| Maintenance | < 30 min/semaine pour garder opérationnel | M+2 |

---

## Décision de go/no-go

**Avant tout déploiement sur VPS :**

1. `OBJECTIVES.md` rempli (ADR-007 — bloquant non négociable)
2. `cargo test` passe sans erreur
3. `.env` configuré avec les vraies API keys
4. Cron testé en local au moins une fois

---

*KAIROS-CHARTER · v0.1 · 2026-06-30*
