# Multi-Profile Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Kairos capable of running a second, fully isolated user profile (different config, database, and Discord destination) by generalizing the config path, the skills format, and three scoring rules (remote gate, salary adjustment, plus a new opt-in location gate) that currently hardcode assumptions specific to one profile.

**Architecture:** All changes are config-driven generalizations of existing single-profile logic in `src/config.rs` and `src/matching.rs` — no new modules, no new binary, no branching on "which profile am I." A second Docker Compose service pair (manual + scheduled) reuses the exact same image with a different env file to get full runtime isolation (own DB, own config file, own Discord channel) for free, since every value that must differ between profiles already flows through env vars today.

**Tech Stack:** Rust 2024, serde/toml (config), Docker Compose (deployment). No new dependencies.

## Global Constraints

- `PROFILE_PATH` env var (default `"config/profile.toml"`) replaces the hardcoded path in `src/main.rs` — must stay backward compatible (unset = current behavior).
- `Skills` (`src/config.rs`) becomes a single flat field: `pub skills: Vec<String>` (TOML: `[skills]\nskills = [...]`) — replaces the 5 categorized fields (`backend`/`frontend`/`database`/`devops`/`learning`). `Skills::categorized()` is deleted (pre-existing dead code per clippy, confirmed unused before this plan).
- `Preferences` (`src/config.rs`) gains `#[serde(default)] pub location_keywords: Vec<String>` — empty by default, no behavior change for any profile that doesn't declare it.
- Gate check in `Matcher::score_with_breakdown()` becomes: `is_freelance(offer) || !remote_gate_ok || !self.location_gate_ok(offer)`, where `remote_gate_ok = is_remote(offer)` when `profile.preferences.remote == "full"`, else always `true`. `"hybrid"` behaves like everything non-`"full"` (no dedicated branch — YAGNI, no profile uses it yet).
- `location_gate_ok`: empty `location_keywords` → `true` (gate skipped, no behavior change for existing profiles). Non-empty → `true` iff at least one keyword (case-insensitive) is a substring of `"{location} {title} {description}"` (lowercased), else `false`.
- `salary_adjustment` moves from a free function with hardcoded constants (48000/45000/40000) to a `&self` method on `Matcher`, computed relative to `profile.preferences.salary_min`/`salary_target`: `mid = min + (target - min) / 2.0`; `>= target` → `+0.10`; `>= mid` → `+0.05`; `>= min` → `0.0`; `< min` (known) → `-0.20`; unknown → `0.0`.
- `.gitignore` pattern `config/profile.toml` widens to `config/profile*.toml` so a second, differently-named profile file is never trackable.
- No new dependencies. No new config fields beyond `location_keywords`. No renaming of already-public function/method signatures beyond what's listed above.
- `cargo test` and `cargo clippy --all-targets -- -D warnings` must stay clean on every task (pre-existing warnings on lines this plan doesn't touch are out of scope).
- Personal data isolation: nothing in this plan's task text, code comments, config examples, docker-compose service names, or commit messages may contain any real name — the second profile is referred to only as "secondary" throughout (`profile-secondary.toml`, `kairos-secondary`, `secondary.db`, `kairos-secondary.env`).

---

### Task 1: `PROFILE_PATH` env var

**Files:**
- Modify: `src/main.rs:155`

**Interfaces:**
- Consumes: nothing new.
- Produces: the `profile` binding in `main()` now comes from a configurable path — every existing consumer of `profile` (unchanged) keeps working identically when `PROFILE_PATH` is unset.

- [ ] **Step 1: Change the hardcoded path to read from env**

In `src/main.rs`, replace line 155:

```rust
    let profile = Profile::from_file("config/profile.toml")?;
```

with:

```rust
    let profile_path = std::env::var("PROFILE_PATH").unwrap_or_else(|_| "config/profile.toml".into());
    let profile = Profile::from_file(&profile_path)?;
```

- [ ] **Step 2: Build to verify it compiles**

Run: `cargo build 2>&1 | grep -E "^error"`
Expected: no output.

- [ ] **Step 3: Manual smoke test — default path still works**

Run: `cargo run -- status 2>&1 | tail -5`
Expected: normal `status` output (same as before this change — proves the default fallback works, since no `PROFILE_PATH` is set in your shell).

- [ ] **Step 4: Manual smoke test — env override is honored**

Run:
```bash
cp config/profile.example.toml /tmp/profile-test.toml
PROFILE_PATH=/tmp/profile-test.toml cargo run -- status 2>&1 | tail -5
rm /tmp/profile-test.toml
```
Expected: same `status` output, no error about a missing/invalid profile file — proves the env var is actually read and a different file loads successfully.

- [ ] **Step 5: Run the full test suite**

Run: `cargo test 2>&1 | tail -10`
Expected: all tests still pass (this change has no automated test — `main.rs` has no existing test coverage convention for CLI/env wiring in this codebase, consistent with how `DATABASE_PATH`/`BRIEFINGS_DIR` are already handled the same way without dedicated tests).

- [ ] **Step 6: Commit**

```bash
git add src/main.rs
git commit -m "feat: PROFILE_PATH configurable (prépare le multi-profil)"
```

---

### Task 2: `config.rs` — flatten skills, add `location_keywords`

**Files:**
- Modify: `src/config.rs`
- Modify: `config/profile.example.toml`

**Interfaces:**
- Consumes: nothing new.
- Produces: `Skills { pub skills: Vec<String> }` with `Skills::all(&self) -> Vec<String>` (unchanged signature, now just returns a clone instead of concatenating 5 fields) — the only caller, `matching.rs::skill_bonus`, needs no changes since the signature is identical. `Preferences.location_keywords: Vec<String>` (new field, consumed by Task 3).

- [ ] **Step 1: Write the failing test**

`src/config.rs`'s existing test module already checks `profile.skills.all().contains(&"Node.js".to_string())` against `config/profile.example.toml` — this test will start failing once the example file's format changes in Step 3, before the struct changes in Step 2. Do the struct change first (Step 2), then the example file (Step 3), so there's a clean RED in between: after Step 2 alone, the crate won't even compile (old TOML shape vs new struct shape), which is the "RED" for this task — a compile failure is an acceptable RED state for a config-shape change like this one (there's no meaningful intermediate test to write that isn't the compile step itself).

Run: `cargo build 2>&1 | tail -5` (before making any change, to confirm current green baseline)
Expected: clean build, no errors — this is your baseline before Step 2 breaks it on purpose.

- [ ] **Step 2: Change the `Skills` struct and remove `categorized()`**

In `src/config.rs`, replace lines 52-82:

```rust
#[derive(Debug, Clone, Deserialize)]
pub struct Skills {
    pub backend: Vec<String>,
    pub frontend: Vec<String>,
    pub database: Vec<String>,
    pub devops: Vec<String>,
    pub learning: Vec<String>,
}

impl Skills {
    pub fn all(&self) -> Vec<String> {
        let mut all = Vec::new();
        all.extend(self.backend.clone());
        all.extend(self.frontend.clone());
        all.extend(self.database.clone());
        all.extend(self.devops.clone());
        all.extend(self.learning.clone());
        all
    }

    /// Return skills as a flat map with category labels for matching.
    pub fn categorized(&self) -> HashMap<&str, &[String]> {
        let mut map = HashMap::new();
        map.insert("backend", &self.backend[..]);
        map.insert("frontend", &self.frontend[..]);
        map.insert("database", &self.database[..]);
        map.insert("devops", &self.devops[..]);
        map.insert("learning", &self.learning[..]);
        map
    }
}
```

with:

```rust
#[derive(Debug, Clone, Deserialize)]
pub struct Skills {
    pub skills: Vec<String>,
}

impl Skills {
    pub fn all(&self) -> Vec<String> {
        self.skills.clone()
    }
}
```

Also remove the now-unused import at the top of the file (line 3):

```rust
use std::collections::HashMap;
```

(`HashMap` was only used by the deleted `categorized()` method — confirm nothing else in the file references it before deleting the import.)

- [ ] **Step 3: Add `location_keywords` to `Preferences`**

In `src/config.rs`, in the `Preferences` struct (currently lines 37-48), add the new field after `min_score`:

```rust
#[derive(Debug, Clone, Deserialize)]
pub struct Preferences {
    pub salary_min: u32,
    pub salary_target: u32,
    pub salary_max: u32,
    pub currency: String,
    pub remote: String,
    pub countries: Vec<String>,
    pub languages: Vec<String>,
    #[serde(default = "default_min_score")]
    pub min_score: f64,
    #[serde(default)]
    pub location_keywords: Vec<String>,
}
```

(Only the added `location_keywords` field is new — everything else in the struct is unchanged.)

- [ ] **Step 4: Update `config/profile.example.toml` to the flat skills format**

Replace the `[skills]` section:

```toml
[skills]
backend = ["Node.js", "Python"]        # adapter à ton stack
frontend = ["React", "Vue.js"]
database = ["PostgreSQL", "SQLite"]
devops = ["Docker"]
learning = []
```

with:

```toml
[skills]
skills = ["Node.js", "Python", "React", "Vue.js", "PostgreSQL", "SQLite", "Docker"]   # adapter à ton domaine
```

Also add a commented example of the new optional preference in `[preferences]` (right after the existing `min_score` line):

```toml
min_score = 0.60         # seuil 0-1 : offres sous ce score exclues du briefing (tunable sans rebuild)
# location_keywords = ["Toulouse", "31", "Haute-Garonne"]   # optionnel : vide = aucune contrainte géo (profil remote international)
```

- [ ] **Step 5: Run the full test suite to verify it's green again**

Run: `cargo test 2>&1 | tail -15`
Expected: `test result: ok.` — `config::tests::test_load_profile` passes against the new flat-list example file (the assertion `profile.skills.all().contains(&"Node.js".to_string())` holds unchanged), and every `matching::tests::*` test still passes since `Skills::all()`'s return value is byte-for-byte the same 7-item list as before, just assembled differently internally.

- [ ] **Step 6: Clippy check**

Run: `cargo clippy --all-targets -- -D warnings 2>&1 | grep "config.rs"`
Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add src/config.rs config/profile.example.toml
git commit -m "feat: skills en liste plate, ajoute preferences.location_keywords"
```

**Note for whoever deploys this:** the real `config/profile.toml` (gitignored, not touched by this task) must be migrated by hand to the new flat `[skills]` format after this lands, or the binary will fail to parse it (TOML deserialization is strict — an old-format file with `backend =`/`frontend =` etc. will no longer match the `Skills` struct). This is a deliberate breaking change to a gitignored file, not a bug — call it out when deploying.

---

### Task 3: `matching.rs` — conditional remote gate, location gate, relative salary

**Files:**
- Modify: `src/matching.rs`

**Interfaces:**
- Consumes: `profile.preferences.remote: String` (existing field, newly read), `profile.preferences.location_keywords: Vec<String>` (Task 2), `profile.preferences.salary_min`/`salary_target: u32` (existing fields, newly read by the relativized formula).
- Produces: `Matcher::score_with_breakdown(&self, offer: &JobOffer) -> (f64, ScoreBreakdown)` — same signature, new gate/formula semantics per Global Constraints. New private method `Matcher::location_gate_ok(&self, offer: &JobOffer) -> bool`. `salary_adjustment` becomes `Matcher::salary_adjustment(&self, offer: &JobOffer) -> f64` (was a free function — this is an internal-only signature change, no external caller).

- [ ] **Step 1: Write the failing tests**

In `src/matching.rs`, inside the existing `#[cfg(test)] mod tests` block, replace the `salaire_sous_40k_malus_vs_neutre` test (the only test whose expected values change under the new relative formula) and add four new tests. `config/profile.example.toml` (loaded by `test_profile()`) has `salary_min = 35000`, `salary_target = 50000` → `mid = 35000 + (50000 - 35000) / 2.0 = 42500`.

Replace this test:

```rust
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
```

with:

```rust
    #[test]
    fn salaire_quatre_paliers_relatifs_au_profil() {
        // profile.example.toml : salary_min=35000, salary_target=50000, mid=42500
        let matcher = Matcher::new(test_profile());
        let bonus = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(55000));
        let mi_chemin = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(45000));
        let neutre = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(38000));
        let malus = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, Some(30000));

        let (s_bonus, _) = matcher.score_with_breakdown(&bonus);
        let (s_mi, _) = matcher.score_with_breakdown(&mi_chemin);
        let (s_neutre, _) = matcher.score_with_breakdown(&neutre);
        let (s_malus, _) = matcher.score_with_breakdown(&malus);

        assert!((s_bonus - 0.50).abs() < 1e-9, "0.40+0.10 (>= cible 50000), obtenu {s_bonus}");
        assert!((s_mi - 0.45).abs() < 1e-9, "0.40+0.05 (>= mi-chemin 42500), obtenu {s_mi}");
        assert!((s_neutre - 0.40).abs() < 1e-9, "0.40 neutre (>= min 35000, < mi-chemin), obtenu {s_neutre}");
        assert!((s_malus - 0.20).abs() < 1e-9, "0.40-0.20 malus (< min 35000), obtenu {s_malus}");
    }
```

Add these four new tests (anywhere else inside the same `mod tests` block, e.g. right after the replaced test):

```rust
    #[test]
    fn remote_gate_desactive_si_profil_onsite() {
        let mut profile = test_profile();
        profile.preferences.remote = "on-site".to_string();
        let matcher = Matcher::new(profile);
        // Offre clairement non-remote : passe quand même, le gate remote est sauté pour ce profil.
        let offer = make_offer("Dev", "On-site position in the office", Some(false), None, None, None);
        let (score, breakdown) = matcher.score_with_breakdown(&offer);
        assert!(score > 0.0, "gate remote sauté pour un profil on-site, obtenu {score}");
        assert_eq!(breakdown.remote, 1.0);
    }

    #[test]
    fn location_gate_vide_ne_bloque_rien() {
        let matcher = Matcher::new(test_profile()); // location_keywords vide par défaut (profile.example.toml ne le déclare pas)
        let offer = make_offer("Dev", "Permanent position, fully remote", Some(true), None, None, None);
        let (score, _) = matcher.score_with_breakdown(&offer);
        assert!(score > 0.0);
    }

    #[test]
    fn location_gate_exclut_hors_zone() {
        let mut profile = test_profile();
        profile.preferences.location_keywords = vec!["Toulouse".into()];
        let matcher = Matcher::new(profile);
        let hors_zone = make_offer("Dev", "Poste basé à Lyon", Some(true), None, None, None);
        let (score, _) = matcher.score_with_breakdown(&hors_zone);
        assert_eq!(score, 0.0);
    }

    #[test]
    fn location_gate_accepte_zone_matchee() {
        let mut profile = test_profile();
        profile.preferences.location_keywords = vec!["Toulouse".into()];
        let matcher = Matcher::new(profile);
        let dans_zone = make_offer("Dev", "Poste basé à Toulouse", Some(true), None, None, None);
        let (score, _) = matcher.score_with_breakdown(&dans_zone);
        assert!(score > 0.0);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cargo test --lib matching:: 2>&1 | tail -50`
Expected: FAIL — either compile errors (if Task 2 is already applied and `location_keywords`/flattened `Skills` exist but `matching.rs` doesn't yet reference them the new way) or assertion failures from `salaire_quatre_paliers_relatifs_au_profil` and the four new tests (since the implementation below doesn't exist yet).

- [ ] **Step 3: Implement the conditional gate, location gate, and relative salary formula**

In `src/matching.rs`, replace the `score_with_breakdown` method (currently lines 41-54):

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
```

with:

```rust
    pub fn score_with_breakdown(&self, offer: &JobOffer) -> (f64, ScoreBreakdown) {
        let remote_gate_ok = match self.profile.preferences.remote.as_str() {
            "full" => is_remote(offer),
            _ => true, // "on-site"/"hybrid" : pas de contrainte remote pour ce profil
        };
        if is_freelance(offer) || !remote_gate_ok || !self.location_gate_ok(offer) {
            return (0.0, ScoreBreakdown { skills: 0.0, remote: 0.0, salary: 0.0, location: 0.0 });
        }

        const BASELINE: f64 = 0.40;
        let skills = self.skill_bonus(offer);
        let location = self.country_bonus(offer);
        let salary = self.salary_adjustment(offer);

        let total = (BASELINE + skills + location + salary).clamp(0.0, 1.0);

        (total, ScoreBreakdown { skills, remote: 1.0, salary, location })
    }

    /// Gate localisation (opt-in) : vide = aucune contrainte (profil remote international) ;
    /// non vide = l'offre doit matcher au moins un mot-clé (ville/région/département), sinon
    /// exclue — pour un profil régional non-remote.
    fn location_gate_ok(&self, offer: &JobOffer) -> bool {
        let keywords = &self.profile.preferences.location_keywords;
        if keywords.is_empty() {
            return true;
        }
        let haystack = format!(
            "{} {} {}",
            offer.location.as_deref().unwrap_or("").to_lowercase(),
            offer.title.to_lowercase(),
            offer.description.to_lowercase(),
        );
        keywords.iter().any(|k| haystack.contains(&k.to_lowercase()))
    }
```

Then replace the free function `salary_adjustment` (currently at module level, after the `impl Matcher` block closes):

```rust
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
```

with a method inside `impl Matcher` (add it right after `country_bonus`, before the closing `}` of the `impl` block):

```rust
    /// Ajustement salaire relatif au profil (preferences.salary_min/salary_target) : cible →
    /// +0.10, mi-chemin min/cible → +0.05, min → 0.0, en dessous du min → -0.20, inconnu → 0.0.
    /// Jamais de malus pour absence de donnée — seulement pour un salaire connu et sous le min.
    fn salary_adjustment(&self, offer: &JobOffer) -> f64 {
        let offered = offer.salary_max.or(offer.salary_min);
        let min = self.profile.preferences.salary_min as f64;
        let target = self.profile.preferences.salary_target as f64;
        let mid = min + (target - min) / 2.0;

        match offered.map(|s| s as f64) {
            Some(s) if s >= target => 0.10,
            Some(s) if s >= mid => 0.05,
            Some(s) if s >= min => 0.0,
            Some(_) => -0.20,
            None => 0.0,
        }
    }
```

(Remove the old free-function version entirely — it moves inside `impl Matcher`, it doesn't get duplicated.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cargo test --lib matching:: 2>&1 | tail -30`
Expected: `test result: ok. 13 passed; 0 failed;` (the 9 tests already in the file minus 1 replaced, plus 4 new ones = 12 — recount: `offre_realiste_depasse_le_seuil`, `gate_remote_echoue_score_nul`, `gate_freelance_echoue_score_nul`, `salaire_quatre_paliers_relatifs_au_profil` (replaces `salaire_sous_40k_malus_vs_neutre`), `salaire_inconnu_neutre`, `pays_hors_liste_pas_de_malus`, `blacklist_entreprise_et_mot_cle`, `blacklist_vide_nexclut_rien`, `test_ranking_order`, `remote_gate_desactive_si_profil_onsite`, `location_gate_vide_ne_bloque_rien`, `location_gate_exclut_hors_zone`, `location_gate_accepte_zone_matchee` = 13 tests total in this module).

- [ ] **Step 5: Run the full test suite**

Run: `cargo test 2>&1 | tail -10`
Expected: all tests pass across every module (44 pre-existing minus 1 replaced plus 4 new = 48 total, but the exact repo-wide count depends on what else exists at execution time — the important signal is `0 failed`).

- [ ] **Step 6: Clippy check**

Run: `cargo clippy --all-targets -- -D warnings 2>&1 | grep "matching.rs"`
Expected: no output (or only pre-existing warnings on lines this task didn't touch).

- [ ] **Step 7: Commit**

```bash
git add src/matching.rs
git commit -m "feat: gate remote conditionnel, gate localisation, salaire relatif au profil"
```

**Note for whoever deploys this:** this changes the salary formula's exact thresholds for every existing profile (see spec `docs/superpowers/specs/2026-07-16-multi-profile-design.md` §5 for the exact before/after table on the real production profile) — **run `kairos rescore` again after deploying this**, same as after the original scoring rework.

---

### Task 4: `.gitignore`, Docker Compose secondary service, README

**Files:**
- Modify: `.gitignore`
- Modify: `deploy/docker/docker-compose.yml`
- Modify: `deploy/docker/README.md`

**Interfaces:**
- Consumes: `PROFILE_PATH` (Task 1) — the new services set this env var to point at a second config file.
- Produces: two new Compose services (`kairos-secondary` for manual runs, `scheduler-secondary` for automatic cron) that a future deploy can `docker compose up -d scheduler-secondary` once a real `config/profile-secondary.toml` and `deploy/docker/kairos-secondary.env` exist on the host (both gitignored, created manually at deploy time — not part of this task).

- [ ] **Step 1: Widen the `.gitignore` pattern**

In `.gitignore`, change:

```
config/profile.toml
```

to:

```
config/profile*.toml
```

- [ ] **Step 2: Verify the widened pattern still ignores the existing file and also a differently-named one**

Run:
```bash
git check-ignore -v config/profile.toml
touch config/profile-secondary.toml
git check-ignore -v config/profile-secondary.toml
rm config/profile-secondary.toml
```
Expected: both commands print a match against the `config/profile*.toml` line in `.gitignore` (proves the existing file stays ignored and a new secondary-named file is ignored too, without needing to touch `.gitignore` again next time).

- [ ] **Step 3: Add the two new services to `docker-compose.yml`**

In `deploy/docker/docker-compose.yml`, add after the existing `kairos:` service (currently the last service before the `volumes:` block):

**Important — YAML merge-key gotcha:** `<<: *kairos-common` merges `x-kairos-common`'s
top-level keys into the service, but if the service *also* defines its own `environment:`
block, that local block **fully replaces** the anchor's `environment:` map — it does not
deep-merge individual variables. Both new services below therefore repeat every one of
`x-kairos-common`'s environment values (`OLLAMA_URL`, `OLLAMA_MODEL`, `NTFY_URL`, `TZ`)
alongside the three overridden ones (`PROFILE_PATH`, `DATABASE_PATH`, `BRIEFINGS_DIR`) —
this is not duplication for its own sake, it is required for `OLLAMA_URL` etc. to survive
the merge at all.

```yaml
  # Second profil isolé : même image, sa propre config/DB/notif via kairos-secondary.env.
  # Répète l'environment de x-kairos-common (le `<<:` ne fusionne pas les sous-clés d'un
  # bloc `environment:` redéfini localement — cf. note ci-dessus) avec 3 valeurs overridées.
  # Exécutions manuelles :
  kairos-secondary:
    <<: *kairos-common
    profiles: ["tools"]
    environment:
      OLLAMA_URL: http://ollama:11434
      OLLAMA_MODEL: phi3:mini
      NTFY_URL: http://ntfy
      TZ: Europe/Paris
      PROFILE_PATH: /app/config/profile-secondary.toml
      DATABASE_PATH: /app/data/secondary.db
      BRIEFINGS_DIR: /app/briefings-secondary
    env_file:
      - path: kairos-secondary.env   # secrets/config du second profil (gitignoré)
        required: false

  # Planificateur du second profil — même image/crontab que `scheduler`, environnement propre.
  scheduler-secondary:
    <<: *kairos-common
    build:
      context: ../..
      dockerfile: deploy/docker/Dockerfile
    restart: unless-stopped
    init: true
    entrypoint: ["supercronic"]
    command: ["/app/supercronic.crontab"]
    environment:
      OLLAMA_URL: http://ollama:11434
      OLLAMA_MODEL: phi3:mini
      NTFY_URL: http://ntfy
      TZ: Europe/Paris
      PROFILE_PATH: /app/config/profile-secondary.toml
      DATABASE_PATH: /app/data/secondary.db
      BRIEFINGS_DIR: /app/briefings-secondary
    env_file:
      - path: kairos-secondary.env
        required: false
```

(Deliberate simplification vs. the spec's mention of "horaires décalés" — this reuses the exact same `supercronic.crontab` schedule as the primary `scheduler`, unmodified. Two lightweight HTTP-bound cron jobs running a few minutes apart is not a real resource contention risk on this VPS; staggering can be added later as a two-line crontab-file change if it ever becomes one. `location_keywords` in the second profile's own `preferences.remote = "on-site"` config already means the `is_remote` collectors' output nets to a much smaller working set than the primary profile's international remote search.)

- [ ] **Step 4: Validate the Compose file parses**

Run: `cd deploy/docker && docker compose config -q && echo OK`
Expected: `OK` (proves the new YAML is syntactically valid and the `<<: *kairos-common` anchor merge works for both new services — this does not require `config/profile-secondary.toml` or `kairos-secondary.env` to exist yet, since Compose only validates structure, not that mounted/env-file paths exist at config-check time).

- [ ] **Step 5: Update `deploy/docker/README.md`**

In the "Composants" bullet list, add after the `kairos` bullet:

```markdown
  - `kairos-secondary` / `scheduler-secondary` — second profil isolé (même image, sa propre
    config/DB/notif via `kairos-secondary.env` — cf. section dédiée ci-dessous).
```

Add a new section right before "## Notes" (at the end of the file):

```markdown
## Second profil (module optionnel)

Fait tourner un second profil isolé (config, base de données, notification Discord distincts)
sur la même image, sans rien dupliquer côté code.

```bash
# 1. config du second profil (jamais commitée — son nom réel ne doit apparaître nulle part
#    dans le repo, cf. règles anti-OSINT du projet)
cp ../../config/profile.example.toml ../../config/profile-secondary.toml
"$EDITOR" ../../config/profile-secondary.toml

# 2. secrets/config déploiement du second profil
cp ../kairos.env.example kairos-secondary.env
"$EDITOR" kairos-secondary.env   # au minimum NOTIFY_CHANNEL_ID (thread Discord dédié)

# 3. test manuel
docker compose run --rm kairos-secondary scrape
docker compose run --rm kairos-secondary briefing

# 4. démarrer son propre planificateur
docker compose up -d scheduler-secondary
```

Base de données et briefings entièrement séparés de ceux du profil principal
(`data/secondary.db`, `briefings-secondary/`) — aucun risque de mélange.
```

- [ ] **Step 6: Commit**

```bash
git add .gitignore deploy/docker/docker-compose.yml deploy/docker/README.md
git commit -m "feat(deploy): service Docker pour un second profil isolé"
```

---

### Task 5: Full verification pass

**Files:** none (verification only; fix inline if anything surfaces).

**Interfaces:** N/A.

- [ ] **Step 1: Full test suite**

Run: `cargo test 2>&1 | tail -15`
Expected: `test result: ok.` with 0 failures across every module.

- [ ] **Step 2: Full clippy pass**

Run: `cargo clippy --all-targets -- -D warnings 2>&1 | grep -E "^error"`
Expected: only pre-existing errors on files/lines this plan didn't touch (dead-code on unrelated methods, the pre-existing `Arc` Send/Sync warning, `build_event` too-many-arguments in `src/calendar/mod.rs`, etc.). If any error appears on a line this plan added or modified, fix it inline now.

- [ ] **Step 3: If anything was fixed in Step 2, commit**

```bash
git add -A
git commit -m "fix: nettoyage clippy résiduel architecture multi-profil"
```

(Skip this step entirely if Step 2 found nothing to fix.)

- [ ] **Step 4: Confirm no personal data leaked into the diff**

Run: `git diff origin/develop..HEAD -- . ':!private' | grep -inE "maryse|poli\b"`
Expected: no output. (Substitute the actual name if the human running this plan knows it and it differs — the point of this check is that the second profile's real identity never appears in a versioned file, per the project's anti-OSINT rules and this plan's Global Constraints.)

- [ ] **Step 5: Confirm the Compose config still parses with the final state of all files**

Run: `cd deploy/docker && docker compose config -q && echo OK`
Expected: `OK`.
