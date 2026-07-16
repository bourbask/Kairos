# Scoring Gates + Bonus Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the weighted-average job scoring formula in `src/matching.rs` (which structurally caps skill-match score too low to ever clear the quality threshold) with a gate+bonus model: hard-fail gates (remote, non-freelance) already used ad-hoc in `src/ranker.rs` become the score's pass/fail check, and everything else (skills, country, salary) becomes additive bonus/malus on a fixed baseline.

**Architecture:** `Matcher::score_with_breakdown()` in `src/matching.rs` becomes the single source of truth for both the hard gates and the bonus formula. `src/ranker.rs` stops duplicating gate logic and only keeps its blacklist post-filter (dynamic, not persisted). A new `Storage::get_all_offers()` plus a new `kairos rescore` CLI command backfill the ~3400 already-collected offers with the new formula.

**Tech Stack:** Rust 2024, rusqlite (SQLite), clap (CLI), no new dependencies.

## Global Constraints

- Score formula (exact, from spec `docs/superpowers/specs/2026-07-16-scoring-gates-bonus-design.md`):
  `score = 0.0` if `is_freelance(offer)` is true or `is_remote(offer)` is false; else
  `score = clamp(0.40 + skill_bonus + country_bonus + salary_adjustment, 0.0, 1.0)`.
- `skill_bonus = (matched / min(profile_skills.len(), 6)).min(1.0) * 0.30` where `matched` counts
  profile skills whose lowercase form is a substring of `"{title} {description}"` lowercased.
  If `profile_skills.is_empty()`, `skill_bonus = 0.15` (neutral midpoint).
- `country_bonus = 0.10` if `offer.country` is `Some` and present in `preferences.countries`, else `0.0`. Never negative.
- `salary_adjustment` computed on `offer.salary_max.or(offer.salary_min)`:
  `>= 48000` → `+0.10`; `>= 45000` → `+0.05`; `>= 40000` → `0.0`; `< 40000` → `-0.20`; `None` → `0.0`.
  These thresholds are hardcoded constants, independent of `preferences.salary_min`/`salary_target`.
- `is_remote`/`is_freelance` logic is copied verbatim from `src/ranker.rs` (same substring/keyword
  heuristics), only their location moves to `src/matching.rs`.
- `min_score` (`config/profile.toml`, default `0.60`) is unchanged and still used as the SQL
  pre-filter in `Storage::get_unpresented_scored()`.
- `ScoreBreakdown` (`src/models.rs`) keeps its exact shape (`skills`, `remote`, `salary`,
  `location`: all `f64`) — no field additions/removals, only value semantics change.
- No new dependencies, no new config fields in `config/profile.toml`/`Profile`.
- `cargo test` and `cargo clippy --all-targets -- -D warnings` must stay clean on every task
  (pre-existing warnings on lines this plan does not touch are out of scope and may remain).

---

### Task 1: Rewrite `Matcher` scoring with gates + bonus formula

**Files:**
- Modify: `src/matching.rs` (entire file body below the `use` statements — struct/impl unchanged, methods rewritten)

**Interfaces:**
- Consumes: `crate::config::Profile` (`preferences.countries: Vec<String>`, `skills.all() -> Vec<String>`, `filters.*` via existing `is_blacklisted`), `crate::models::{JobOffer, ScoredJob, ScoreBreakdown}` — all pre-existing, no changes needed to those types.
- Produces: `Matcher::score_with_breakdown(&self, offer: &JobOffer) -> (f64, ScoreBreakdown)` (signature unchanged, semantics changed per Global Constraints), `Matcher::rank(&self, offers: Vec<JobOffer>) -> Vec<ScoredJob>` (unchanged), `Matcher::is_blacklisted` (unchanged), `Matcher::min_score` (unchanged). The public `Matcher::score(&self, offer: &JobOffer) -> f64` method is **removed** (dead code — no callers anywhere in the repo, already flagged by clippy as unused).

- [ ] **Step 1: Write the failing tests (replace the whole `#[cfg(test)] mod tests` block)**

Replace the entire existing test module at the bottom of `src/matching.rs` with:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Profile;
    use chrono::Utc;

    // config/profile.example.toml skills: Node.js, Python, React, Vue.js, PostgreSQL, SQLite, Docker (7 total)
    fn test_profile() -> Profile {
        Profile::from_file("config/profile.example.toml").unwrap()
    }

    fn make_offer(title: &str, desc: &str, remote: Option<bool>, country: Option<&str>,
                  sal_min: Option<u32>, sal_max: Option<u32>) -> JobOffer {
        JobOffer {
            id: "test".into(),
            source: "test".into(),
            title: title.into(),
            company: "Test".into(),
            description: desc.into(),
            url: "https://test.com".into(),
            location: None,
            country: country.map(String::from),
            salary_min: sal_min,
            salary_max: sal_max,
            currency: Some("EUR".into()),
            remote,
            published_at: None,
            collected_at: Utc::now(),
        }
    }

    #[test]
    fn offre_realiste_depasse_le_seuil() {
        let matcher = Matcher::new(test_profile());
        // 6/7 compétences du profil (pas SQLite) + remote confirmé + CDI + salaire >=48k + pays préféré
        let offer = make_offer(
            "Senior Full-Stack Developer",
            "Node.js, Python, React, Vue.js, PostgreSQL, Docker. Permanent position, fully remote.",
            Some(true), Some("FR"), None, Some(55000),
        );
        let (score, breakdown) = matcher.score_with_breakdown(&offer);
        assert!((score - 0.90).abs() < 1e-9, "attendu 0.90 (0.40+0.30+0.10+0.10), obtenu {score}");
        assert_eq!(breakdown.remote, 1.0);
    }

    #[test]
    fn gate_remote_echoue_score_nul() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer("Dev", "On-site position in the Paris office", Some(false), Some("FR"), None, Some(60000));
        let (score, breakdown) = matcher.score_with_breakdown(&offer);
        assert_eq!(score, 0.0);
        assert_eq!(breakdown.remote, 0.0);
    }

    #[test]
    fn gate_freelance_echoue_score_nul() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer("Freelance React Developer", "Fully remote contractor role", Some(true), Some("FR"), None, Some(60000));
        let (score, _) = matcher.score_with_breakdown(&offer);
        assert_eq!(score, 0.0);
    }

    #[test]
    fn salaire_sous_40k_malus_vs_neutre() {
        let matcher = Matcher::new(test_profile());
        let bas = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(35000));
        let neutre = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(42000));
        let (score_bas, _) = matcher.score_with_breakdown(&bas);
        let (score_neutre, _) = matcher.score_with_breakdown(&neutre);
        assert!((score_bas - 0.20).abs() < 1e-9, "0.40 - 0.20 malus, obtenu {score_bas}");
        assert!((score_neutre - 0.40).abs() < 1e-9, "0.40 neutre (40-45k), obtenu {score_neutre}");
    }

    #[test]
    fn salaire_inconnu_neutre() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, None);
        let (score, breakdown) = matcher.score_with_breakdown(&offer);
        assert_eq!(breakdown.salary, 0.0);
        assert!((score - 0.40).abs() < 1e-9);
    }

    #[test]
    fn pays_hors_liste_pas_de_malus() {
        let matcher = Matcher::new(test_profile());
        let offer = make_offer("Dev", "Permanent position, fully remote", Some(true), Some("JP"), None, None);
        let (_, breakdown) = matcher.score_with_breakdown(&offer);
        assert_eq!(breakdown.location, 0.0);
    }

    #[test]
    fn blacklist_entreprise_et_mot_cle() {
        let mut profile = test_profile();
        profile.filters.blacklist_companies = vec!["EvilCorp".into()];
        profile.filters.blacklist_keywords = vec!["gambling".into()];
        let matcher = Matcher::new(profile);

        let mut o = make_offer("Dev", "great job", Some(true), Some("CH"), None, None);
        assert!(!matcher.is_blacklisted(&o), "rien ne matche");
        o.company = "EvilCorp GmbH".into();
        assert!(matcher.is_blacklisted(&o), "entreprise (sous-chaîne, casse)");
        o.company = "Nice Co".into();
        o.description = "online GAMBLING platform".into();
        assert!(matcher.is_blacklisted(&o), "mot-clé secteur");
    }

    #[test]
    fn blacklist_vide_nexclut_rien() {
        let mut profile = test_profile();
        profile.filters.blacklist_companies = vec!["".into()];
        let matcher = Matcher::new(profile);
        let o = make_offer("Dev", "x", Some(true), Some("CH"), None, None);
        assert!(!matcher.is_blacklisted(&o));
    }

    #[test]
    fn test_ranking_order() {
        let matcher = Matcher::new(test_profile());
        let good = make_offer("Symfony React", "Symfony React Docker", Some(true), Some("CH"), None, Some(110000));
        let bad = make_offer("Cleaner", "Cleaning, on-site only", Some(false), Some("JP"), None, None);

        let ranked = matcher.rank(vec![bad, good]);
        assert_eq!(ranked.len(), 2);
        assert!(ranked[0].score > ranked[1].score);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --lib matching:: 2>&1 | tail -40`
Expected: compile errors or assertion failures (the implementation below doesn't exist yet) —
e.g. `error[E0599]: no method named ... ` or the old formula's assertions no longer matching
(since the test bodies above already assume the new formula's exact numbers).

- [ ] **Step 3: Replace the `Matcher` impl and free functions with the new formula**

Replace everything from the `pub fn score(...)` line through the end of `score_location` (i.e.
`src/matching.rs` lines 41-146 in the pre-change file) with:

```rust
    pub fn score_with_breakdown(&self, offer: &JobOffer) -> (f64, ScoreBreakdown) {
        if is_freelance(offer) || !is_remote(offer) {
            return (0.0, ScoreBreakdown { skills: 0.0, remote: 0.0, salary: 0.0, location: 0.0 });
        }

        const BASELINE: f64 = 0.40;
        let skills = self.skill_bonus(offer);
        let location = self.country_bonus(offer);
        let salary = salary_adjustment(offer);

        let total = (BASELINE + skills + location + salary).clamp(0.0, 1.0);

        (total, ScoreBreakdown { skills, remote: 1.0, salary, location })
    }

    pub fn rank(&self, offers: Vec<JobOffer>) -> Vec<ScoredJob> {
        let mut scored: Vec<ScoredJob> = offers
            .into_iter()
            .map(|offer| {
                let (score, breakdown) = self.score_with_breakdown(&offer);
                ScoredJob { offer, score, breakdown }
            })
            .collect();

        scored.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        scored
    }

    /// Bonus compétences : matched / min(profile_skills.len(), 6), plafonné à 1.0, *0.30.
    /// Plafond à 6 quelle que soit la taille du profil (25 en prod aujourd'hui) — une offre
    /// qui matche 6 compétences du profil ou plus obtient le bonus plein, au lieu de diluer
    /// sur la totalité de la liste.
    fn skill_bonus(&self, offer: &JobOffer) -> f64 {
        let haystack = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
        let profile_skills = self.profile.skills.all();

        if profile_skills.is_empty() {
            return 0.15; // neutre (mi-chemin du bonus max) si le profil ne déclare aucune compétence
        }

        let matched = profile_skills
            .iter()
            .filter(|skill| haystack.contains(&skill.to_lowercase()))
            .count();
        let denom = profile_skills.len().min(6) as f64;
        (matched as f64 / denom).min(1.0) * 0.30
    }

    /// Bonus pays préféré : jamais de malus, juste +0.10 si le pays de l'offre est dans la liste.
    fn country_bonus(&self, offer: &JobOffer) -> f64 {
        match &offer.country {
            Some(country) if self.profile.preferences.countries.iter().any(|c| c == country) => 0.10,
            _ => 0.0,
        }
    }
}

/// Ajustement salaire : seuils en dur (indépendants de preferences.salary_min/salary_target).
/// Jamais de malus pour absence de donnée — seulement pour un salaire connu et vraiment bas.
fn salary_adjustment(offer: &JobOffer) -> f64 {
    let offered = offer.salary_max.or(offer.salary_min);
    match offered {
        Some(s) if s >= 48_000 => 0.10,
        Some(s) if s >= 45_000 => 0.05,
        Some(s) if s >= 40_000 => 0.0,
        Some(_) => -0.20,
        None => 0.0,
    }
}

/// Détecte une offre freelance/contract (l'utilisateur veut un CDI, sans statut freelance).
/// Heuristique par mots-clés sur titre + description. Rapatriée depuis ranker.rs (K-F0x scoring rework).
// ponytail: schéma-libre ; migrer vers un champ employment_type structuré si l'imprécision gêne.
fn is_freelance(offer: &JobOffer) -> bool {
    let h = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
    const MARKERS: [&str; 7] = [
        "freelance", "freelancer", "contractor", "c2c", "corp-to-corp", "corp to corp", "self-employed",
    ];
    MARKERS.iter().any(|m| h.contains(m))
}

/// Full remote DUR (exigence utilisateur) : gate, pas un score. Flag explicite s'il existe ;
/// sinon mot-clé remote dans le texte ET pas d'« hybride ». Rapatriée depuis ranker.rs.
/// Strict par choix : mieux vaut rater une offre non taggée que polluer avec de l'on-site.
fn is_remote(offer: &JobOffer) -> bool {
    match offer.remote {
        Some(v) => v,
        None => {
            let h = format!("{} {}", offer.title.to_lowercase(), offer.description.to_lowercase());
            let hybride = h.contains("hybrid") || h.contains("hybride");
            let remote = h.contains("remote") || h.contains("télétravail")
                || h.contains("teletravail") || h.contains("home office") || h.contains("work from home");
            remote && !hybride
        }
    }
}
```

This replaces everything starting at the line `pub fn score(&self, offer: &JobOffer) -> f64 {`
(old line 43) through the closing `}` of the old `score_location` method (old line 146), inclusive.
The block above is self-contained: it opens with `score_with_breakdown` still inside `impl Matcher`
(started earlier in the file by the untouched `pub fn new`/`min_score`/`is_blacklisted`), closes
that `impl` block after `country_bonus`, then defines `salary_adjustment`, `is_freelance`,
`is_remote` as module-level free functions.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --lib matching:: 2>&1 | tail -20`
Expected: `test result: ok. 9 passed; 0 failed;`

- [ ] **Step 5: Clippy check**

Run: `cargo clippy --all-targets -- -D warnings 2>&1 | grep "matching.rs"`
Expected: no output (or only pre-existing warnings unrelated to lines touched — none expected here since `score_remote`/`score_skills`/`score_salary`/`score_location`/`score` were fully replaced).

- [ ] **Step 6: Commit**

```bash
git add src/matching.rs
git commit -m "feat: refonte scoring en gates + bonus (matching.rs)"
```

---

### Task 2: Simplify `ranker.rs` — drop duplicated gates, keep blacklist filter

**Files:**
- Modify: `src/ranker.rs`

**Interfaces:**
- Consumes: `Matcher::score_with_breakdown` (Task 1, now encodes remote/freelance gates), `Matcher::is_blacklisted` (unchanged), `Storage::get_unpresented_scored`, `Storage::insert_offers`, `Storage::update_score` (all unchanged, from `src/storage.rs`).
- Produces: `Ranker::top_unpresented(&self, n: u32) -> Vec<ScoredJob>` (signature unchanged, filter body simplified), `Ranker::score_new_offers` (unchanged).

- [ ] **Step 1: Write the failing test for blacklist-only filtering**

Replace the entire `#[cfg(test)] mod tests` block at the bottom of `src/ranker.rs` with:

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Profile;

    #[test]
    fn recency_penalise_les_vieilles() {
        use chrono::Duration;
        let now = Utc::now();
        assert_eq!(recency_factor(None, now), 1.0);                    // pas de date = neutre
        assert!((recency_factor(Some(now), now) - 1.0).abs() < 1e-9); // frais
        assert!((recency_factor(Some(now - Duration::days(30)), now) - 0.7).abs() < 1e-6);
        assert!((recency_factor(Some(now - Duration::days(100)), now) - 0.7).abs() < 1e-6); // plancher
        assert!(recency_factor(Some(now - Duration::days(2)), now)
            > recency_factor(Some(now - Duration::days(20)), now));
    }

    fn offer(id: &str, company: &str) -> JobOffer {
        JobOffer {
            id: id.into(), source: "t".into(), title: "Dev".into(), company: company.into(),
            description: "Permanent position, fully remote".into(), url: "u".into(), location: None,
            country: None, salary_min: None, salary_max: None, currency: None,
            remote: Some(true), published_at: None, collected_at: Utc::now(),
        }
    }

    #[test]
    fn top_unpresented_filtre_la_blacklist() {
        let mut profile = Profile::from_file("config/profile.example.toml").unwrap();
        profile.filters.blacklist_companies = vec!["EvilCorp".into()];

        let storage = Arc::new(Storage::open(":memory:").unwrap());
        storage.insert_offers(&[offer("good", "GoodCo"), offer("bad", "EvilCorp GmbH")]).unwrap();
        storage.update_score("good", 0.60).unwrap();
        storage.update_score("bad", 0.60).unwrap();

        let ranker = Ranker::new(Matcher::new(profile), Arc::clone(&storage));
        let top = ranker.top_unpresented(10);

        assert_eq!(top.len(), 1);
        assert_eq!(top[0].offer.id, "good");
    }
}
```

- [ ] **Step 2: Run tests to verify the new test fails (or doesn't compile yet)**

Run: `cargo test --lib ranker:: 2>&1 | tail -40`
Expected: compiles and passes already for `top_unpresented_filtre_la_blacklist` (the current
`.filter()` still has `is_remote`/`is_freelance` calls in scope, so nothing breaks yet) — this
step exists to confirm the test harness is wired correctly before the simplification in Step 3.
The three old tests (`detecte_freelance`, `cdi_non_exclu`, `remote_dur`) no longer exist in the
file, which is expected since we deleted them along with `is_freelance`/`is_remote` reliance —
if compilation fails at this point (e.g. `is_freelance`/`is_remote` still defined and now
unused), that's fine, proceed to Step 3 which removes them.

- [ ] **Step 3: Remove the duplicated gate functions and simplify the filter**

In `src/ranker.rs`:

1. Delete the two free functions `is_freelance` and `is_remote` (now duplicated in
   `src/matching.rs` from Task 1) — delete everything from the `/// Détecte une offre
   freelance/contract...` comment through the end of the `is_remote` function body.

2. Change the `.filter()` inside `top_unpresented()` from:

```rust
            .filter(|(offer, _)| {
                !is_freelance(offer) && is_remote(offer) && !self.matcher.is_blacklisted(offer)
            })
```

to:

```rust
            .filter(|(offer, _)| !self.matcher.is_blacklisted(offer))
```

3. Update the doc comment directly above `top_unpresented` from:

```rust
    /// Top N offres non présentées. Filtres durs : score >= min_score (SQL),
    /// full remote, hors freelance, hors blacklist. Puis re-rank par score
    /// pénalisé par l'ancienneté (offres récentes d'abord) — le seuil reste sur
    /// le score brut, une offre parfaite mais vieille reste éligible, juste rétrogradée.
```

to:

```rust
    /// Top N offres non présentées. Filtres durs : score >= min_score (SQL, encode déjà
    /// full remote/hors freelance via matching.rs), hors blacklist (dynamique, non persisté).
    /// Puis re-rank par score pénalisé par l'ancienneté (offres récentes d'abord) — le seuil
    /// reste sur le score brut, une offre parfaite mais vieille reste éligible, juste rétrogradée.
```

4. Add `use crate::config::Profile;` is NOT needed in non-test code (only the test module needs
   it, already added in Step 1's `use crate::config::Profile;` inside `mod tests`). Verify the
   top-of-file `use` block still reads:

```rust
use std::sync::Arc;
use chrono::{DateTime, Utc};
use crate::models::{JobOffer, ScoredJob};
use crate::matching::Matcher;
use crate::storage::Storage;
```

(unchanged from before this task — no new top-level imports needed since `Storage`, `Matcher`
are already imported, and the test module pulls in `Profile` itself via `use super::*` plus its
own explicit `use crate::config::Profile;`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --lib ranker:: 2>&1 | tail -20`
Expected: `test result: ok. 2 passed; 0 failed;` (`recency_penalise_les_vieilles` and
`top_unpresented_filtre_la_blacklist`).

- [ ] **Step 5: Run the full test suite (cross-module sanity)**

Run: `cargo test 2>&1 | tail -10`
Expected: all tests pass (matching.rs's 9 new tests + ranker.rs's 2 tests + every other
pre-existing test in the repo, unaffected by this change).

- [ ] **Step 6: Clippy check**

Run: `cargo clippy --all-targets -- -D warnings 2>&1 | grep "ranker.rs"`
Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add src/ranker.rs
git commit -m "refactor: ranker.rs ne filtre plus que la blacklist (gates dans matching.rs)"
```

---

### Task 3: Add `Storage::get_all_offers()`

**Files:**
- Modify: `src/storage.rs`

**Interfaces:**
- Consumes: existing `Storage` struct/`self.conn` (rusqlite connection), `crate::models::JobOffer` (unchanged).
- Produces: `pub fn get_all_offers(&self) -> SqlResult<Vec<JobOffer>>` — used by Task 4's `kairos rescore` command.

- [ ] **Step 1: Write the failing test**

Add to the `#[cfg(test)] mod tests` block at the bottom of `src/storage.rs` (after
`test_deduplication`, before the closing `}` of the `mod tests` block):

```rust
    #[test]
    fn test_get_all_offers() {
        let store = Storage::open(":memory:").unwrap();

        let offer1 = JobOffer {
            id: "a".into(), source: "test".into(), title: "Job A".into(), company: "Corp".into(),
            description: "desc".into(), url: "https://example.com".into(), location: None,
            country: None, salary_min: None, salary_max: None, currency: None,
            remote: None, published_at: None, collected_at: Utc::now(),
        };
        let offer2 = JobOffer { id: "b".into(), title: "Job B".into(), ..offer1.clone() };

        store.insert_offers(&[offer1, offer2]).unwrap();
        let all = store.get_all_offers().unwrap();

        assert_eq!(all.len(), 2);
        let ids: Vec<&str> = all.iter().map(|o| o.id.as_str()).collect();
        assert!(ids.contains(&"a"));
        assert!(ids.contains(&"b"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test --lib storage::tests::test_get_all_offers 2>&1 | tail -20`
Expected: FAIL — `error[E0599]: no method named 'get_all_offers' found`.

- [ ] **Step 3: Implement `get_all_offers`**

Add this method to `impl Storage` in `src/storage.rs`, directly after `get_unpresented_scored`
(reuses its exact row-mapping pattern, minus the `WHERE`/`score` column):

```rust
    pub fn get_all_offers(&self) -> SqlResult<Vec<JobOffer>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, source, title, company, description, url, location, country,
                    salary_min, salary_max, currency, remote, published_at, collected_at
             FROM job_offers"
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(JobOffer {
                id: row.get(0)?,
                source: row.get(1)?,
                title: row.get(2)?,
                company: row.get(3)?,
                description: row.get(4)?,
                url: row.get(5)?,
                location: row.get(6)?,
                country: row.get(7)?,
                salary_min: row.get(8)?,
                salary_max: row.get(9)?,
                currency: row.get(10)?,
                remote: row.get::<_, Option<i32>>(11)?.map(|r| r != 0),
                published_at: row.get::<_, Option<String>>(12)?
                    .and_then(|s| chrono::DateTime::parse_from_rfc3339(&s).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
                collected_at: Utc::now(),
            })
        })?;

        rows.collect()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cargo test --lib storage::tests::test_get_all_offers 2>&1 | tail -20`
Expected: `test result: ok. 1 passed;`

- [ ] **Step 5: Run full storage test suite**

Run: `cargo test --lib storage:: 2>&1 | tail -10`
Expected: all storage tests pass (`test_insert_and_count`, `test_deduplication`,
`test_get_all_offers`, plus any others already in the file).

- [ ] **Step 6: Clippy check**

Run: `cargo clippy --all-targets -- -D warnings 2>&1 | grep "storage.rs"`
Expected: no output attributable to the new method (pre-existing dead-code warnings on
`save_briefing`/`get_calendar_stats` may still appear — those are out of scope, leave them).

- [ ] **Step 7: Commit**

```bash
git add src/storage.rs
git commit -m "feat: Storage::get_all_offers pour le backfill de score (kairos rescore)"
```

---

### Task 4: Add `kairos rescore` CLI command

**Files:**
- Modify: `src/main.rs`

**Interfaces:**
- Consumes: `Storage::get_all_offers` (Task 3), `Matcher::score_with_breakdown` (Task 1),
  `Storage::update_score` (pre-existing, `src/storage.rs:164`).
- Produces: new `Command::Rescore` CLI subcommand (`kairos rescore`), no other code depends on it.

- [ ] **Step 1: Add the `Rescore` variant to the `Command` enum**

In `src/main.rs`, inside `enum Command`, add after the `Scrape` variant:

```rust
    /// Fetch job offers from all sources
    Scrape,
    /// Recompute the score of every offer already in the database with the current formula
    Rescore,
```

- [ ] **Step 2: Add the match arm**

In `src/main.rs`, inside the `match cli.command` block, add after the `Command::Scrape => { ... }`
arm (before `Command::Briefing`):

```rust
        Command::Rescore => {
            let matcher = Matcher::new(profile);
            let offers = storage.get_all_offers()?;
            let total = offers.len();
            let mut updated = 0;
            for offer in &offers {
                let (score, _) = matcher.score_with_breakdown(offer);
                if storage.update_score(&offer.id, score).is_ok() {
                    updated += 1;
                }
            }
            println!("Rescored {updated}/{total} offers.");
        }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `cargo build 2>&1 | grep -E "^error"`
Expected: no output (clean build). If `profile` reports a "moved value" error, confirm this arm
is the only place using `profile` in this match — each `match` arm owns its own scope, so this
should compile as-is (same pattern already used in the `Command::Briefing` arm which also does
`Matcher::new(profile)`).

- [ ] **Step 4: Manual smoke test against a scratch database (not the dev DB)**

Run:
```bash
rm -f /tmp/kairos-rescore-smoke.db
DATABASE_PATH=/tmp/kairos-rescore-smoke.db cargo run -- rescore
```
Expected output: `Rescored 0/0 offers.` (empty scratch DB, proves the command runs end-to-end
without crashing — the scoring formula itself is already covered by Task 1's unit tests and the
row-mapping by Task 3's unit test).

- [ ] **Step 5: Run the full test suite one more time**

Run: `cargo test 2>&1 | tail -10`
Expected: all tests still pass (this task adds no new automated tests — `main.rs` has no
existing test coverage convention for CLI wiring in this codebase; correctness is covered by
Tasks 1 and 3's unit tests plus this step's manual smoke test).

- [ ] **Step 6: Clippy check**

Run: `cargo clippy --all-targets -- -D warnings 2>&1 | grep "main.rs"`
Expected: only pre-existing warnings unrelated to the `Rescore` arm (e.g. the existing
`Arc`-not-`Send`/`Sync` warning, the existing collapsible-if on the Adzuna key check) — nothing
new attributable to lines added in this task.

- [ ] **Step 7: Commit**

```bash
git add src/main.rs
git commit -m "feat: commande kairos rescore (backfill du score avec la nouvelle formule)"
```

---

### Task 5: Full verification pass

**Files:** none (verification only; fix inline if anything surfaces).

**Interfaces:** N/A.

- [ ] **Step 1: Full test suite**

Run: `cargo test 2>&1 | tail -15`
Expected: `test result: ok.` with 0 failures across every module (matching, ranker, storage, and
all pre-existing modules untouched by this plan).

- [ ] **Step 2: Full clippy pass**

Run: `cargo clippy --all-targets -- -D warnings 2>&1 | grep -E "^error"`
Expected: only pre-existing errors on files/lines this plan didn't touch (e.g.
`build_event` too-many-arguments in `src/calendar/mod.rs`, various `never used`/dead-code on
unrelated methods, the `Arc` Send/Sync warning in `main.rs`). If any error appears on a line
this plan added or modified, fix it inline now.

- [ ] **Step 3: If anything was fixed in Step 2, commit**

```bash
git add -A
git commit -m "fix: nettoyage clippy résiduel scoring gates+bonus"
```

(Skip this step entirely if Step 2 found nothing to fix.)

- [ ] **Step 4: Confirm scope — nothing else references the removed `Matcher::score()` method or the removed `ranker::is_remote`/`ranker::is_freelance` functions**

Run: `grep -rn "matcher\.score(\|ranker::is_remote\|ranker::is_freelance" src/ --include=*.rs`
Expected: no output (proves the deleted dead code had no remaining callers, confirming Task 1
Step 3 and Task 2 Step 3 were safe removals).
