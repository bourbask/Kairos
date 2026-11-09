# Mémoire — lot 2 : le sommeil — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chaque nuit, Kairos extrait ce qu'il a appris dans la journée, condense les épisodes estompés en synthèses (semaine → mois → année) et oublie réellement ce qui doit l'être.

**Architecture:** Le module mémoire offre des opérations déterministes (épisodes du jour, lots condensables, condensation transactionnelle, application d'une extraction, oubli) via des outils `nuit_*` cachés au moteur. Le planificateur de la tête orchestre la nuit et appelle le modèle (auto-hébergé, par la configuration de routage existante) pour extraire et rédiger les synthèses.

**Tech Stack:** Rust 2024, SQLite (rusqlite, FTS5), rmcp ; crates `kairos-memoire`, `tete`.

**Spec:** `docs/superpowers/specs/2026-11-09-memoire-humaine-design.md` (§3.3, §3.6, §3.8, §3.9, §4, §5 ; lot 2 du §6). Lot 1 livré : `tentacles/memoire/src/force.rs`, table `souvenirs`, `rappel`.

## Global Constraints

- Familles et `S₀` : épisode 3 j ; connaissance 30 j ; synthèse semaine 60 j, mois 180 j, année sans oubli (ancre) ; renforcement et `R` du lot 1 inchangés.
- Condensation : un épisode appartient à la semaine ISO de sa date ; une semaine n'est condensée que si **tous** ses épisodes sont estompés depuis plus de `D_cond` = 14 jours (instant d'estompage = `dernier_rappel + stabilite × ln 2`) ; un mois quand toutes ses semaines sont condensées et le mois terminé ; une année quand tous ses mois le sont et l'année terminée.
- La condensation crée la synthèse et supprime ses sources **dans la même transaction**, avec leurs entrées d'index ; une synthèse de semaine ou de mois fondue dans le niveau supérieur est marquée `fondue`.
- Oubli (suppression réelle, index compris, `PRAGMA secure_delete` déjà actif) : synthèses fondues non annuelles et déductions dont `R < 0,05`. Jamais : connaissances déclarées, ancres, synthèses d'année, épisodes (ils passent par la condensation).
- Déductions : une valeur déduite ne remplace jamais une valeur déclarée ; une déduction qui contredit une valeur déclarée crée **une** intention proposée « Confirmer : … » (pas de doublon) ; confiance d'une déduction : `m ← m + 0,25 × (1 − m)` à chaque nouvel indice, départ 0,25 ; rappelée avec le préfixe « je crois que ».
- Réactivation : une connaissance déclarée re-mentionnée par l'utilisateur dans la journée (extraction) est renforcée ; rien d'autre au lot 2.
- Modèle : usages de routage `memoire_extraction` et `memoire_condensation`, auto-hébergés ; si un usage n'est pas configuré, l'étape est sautée sans erreur. Une sortie du modèle hors schéma n'applique rien.
- **Aucun trou de mémoire** (décision de l'utilisateur) : l'état de chaque étape de nuit est conservé (`dernier jour traité`, dernière exécution, dernier statut) ; l'extraction traite **tous** les jours en attente depuis le dernier jour traité (au plus 7 par nuit, plus anciens d'abord), pas seulement la veille ; une étape sautée ou en échec apparaît dans la section mémoire du briefing du matin (« Mémoire : extraction en attente depuis le <jour> — modèle indisponible »), jusqu'à ce qu'elle soit rattrapée. Les usages de nuit sont déclarés stratégiques : jamais de moteur non auto-hébergé.
- Outils `nuit_*` cachés au moteur. Aucune donnée personnelle dans les journaux (ni texte de souvenir ni valeur).
- Dépôt public : commentaires d'intention, exemples fictifs. `git commit --no-gpg-sign`. Vérification : `cargo test -p kairos-memoire -p tete` puis `cargo test --workspace`.

## Review Focus

1. Condensation interrompue ou refusée (erreur au milieu, synthèse vide, période déjà condensée) : aucun épisode supprimé sans synthèse enregistrée (Task 3 : `condensation_is_atomic_and_idempotent`).
2. Sortie du modèle invalide, tronquée ou qui invente une clé hors liste : rien d'appliqué ou clé rangée sous un préfixe connu, aucune écriture dans `fact_log` (Task 4 : `extraction_rejects_out_of_schema` ; Task 6 : `bad_model_output_applies_nothing`).
3. Une déduction ne touche jamais une connaissance déclarée, même répétée chaque nuit (Task 4 : `deduction_never_overrides_declared`).
4. Nuit relancée deux fois (redémarrage, double déclenchement) : aucun doublon de synthèse, d'intention ou de déduction (Tasks 3 et 4 : idempotence ; Task 6 : `night_twice_is_harmless`).
5. Aucun moteur joignable : la nuit saute les étapes à modèle, l'oubli déterministe passe quand même (Task 6 : `night_without_model_still_forgets`).

---

### Task 1: Les conversations deviennent des épisodes

**Files:** Modify `tentacles/memoire/src/store.rs` (migration, `conversation_ingest`, `conversation_delete`, `purge_transcripts`, `rappel`).

**Interfaces:**
- Produces: nature `'conversation'` dans `souvenirs` (ref = id), Episode, importance 0, confiance 1 ; `rappel` inclut les conversations (texte « AAAA-MM-JJ : <résumé, sinon sujets> », pertinence via `conversations_fts`).
- Migration : comme au lot 1, à chaque ouverture, lignes manquantes créées avec la date de migration, orphelines retirées.

- [ ] Tests : `conversations_become_episodes` (migration + ingestion), `conversation_delete_removes_memory`, `recall_finds_conversation_by_summary`.
- [ ] Implémenter, `cargo test -p kairos-memoire` vert, commit `feat(memoire): conversations rangées parmi les épisodes`.

### Task 2: Synthèses et épisodes du jour

**Files:** Modify `tentacles/memoire/src/store.rs`.

**Interfaces:**
- Produces:
  - Table `syntheses(id INTEGER PRIMARY KEY, niveau TEXT NOT NULL CHECK(niveau IN ('semaine','mois','annee')), periode TEXT NOT NULL, texte TEXT NOT NULL, fondue INTEGER NOT NULL DEFAULT 0, cree_le TEXT NOT NULL, UNIQUE(niveau, periode))`, `syntheses_fts(texte)` (rowid = id) ; nature `'synthese'` dans `souvenirs` (Synthese, `S₀` 60 / 180 ; année : ancre).
  - `pub struct Element { pub nature: String, pub r#ref: String, pub date: NaiveDate, pub texte: String }`.
  - `pub fn episodes_du_jour(&self, jour: NaiveDate) -> Result<Vec<Element>>` (journal et conversations résumées de ce jour).
  - `rappel` inclut les synthèses (texte « <periode> : <texte> »).
  - Périodes : semaine `"2026-W45"` (ISO), mois `"2026-11"`, année `"2026"`.

- [ ] Tests : `syntheses_schema_and_recall`, `episodes_of_the_day`.
- [ ] Implémenter, tests verts, commit `feat(memoire): synthèses et épisodes du jour`.

### Task 3: Lots condensables et condensation

**Files:** Modify `tentacles/memoire/src/store.rs`.

**Interfaces:**
- Consumes: Task 2.
- Produces:
  - `pub struct Lot { pub niveau: String, pub periode: String, pub elements: Vec<Element> }`.
  - `pub fn condensables(&self, maintenant: NaiveDateTime, max: usize) -> Result<Vec<Lot>>` (règles des Global Constraints ; éléments d'un mois = synthèses de ses semaines, d'une année = synthèses de ses mois ; plus anciens d'abord).
  - `pub fn condenser(&self, niveau: &str, periode: &str, texte: &str, maintenant: NaiveDateTime) -> Result<Option<i64>>` : recalcule le lot de la période (jamais confiance dans une liste fournie), refuse si le lot n'est plus condensable ou si le texte est vide (`Ok(None)`), sinon crée la synthèse, supprime les épisodes sources (lignes, index, souvenirs) ou marque `fondue` les synthèses sources, dans une transaction.
- [ ] Tests : `week_condensable_only_when_all_faded_14_days`, `month_after_all_weeks_and_month_over`, `condensation_is_atomic_and_idempotent` (deux fois → une synthèse ; texte vide → rien supprimé), `condensed_sources_leave_no_index_trace`.
- [ ] Implémenter, tests verts, commit `feat(memoire): condensation des épisodes estompés`.

### Task 4: Déductions et application d'une extraction

**Files:** Modify `tentacles/memoire/src/store.rs`.

**Interfaces:**
- Produces:
  - Table `deductions(key TEXT NOT NULL, value TEXT NOT NULL, confiance REAL NOT NULL, cree_le TEXT NOT NULL, PRIMARY KEY(key, value))` ; nature `'deduction'` dans `souvenirs` (ref = `key` + `\u{1f}` + `value`, Connaissance, importance 0).
  - `pub struct Indice { pub key: String, pub value: String }`.
  - `pub fn appliquer_extraction(&self, jour: NaiveDate, indices: &[Indice], maintenant: NaiveDateTime) -> Result<Bilan>` avec `Bilan { renforces, deduits, questions, ignores: u32 }` :
    - clé déclarée, même valeur (comparaison sans casse ni espaces) → renforcement (une fois par clé et par jour) ;
    - clé déclarée, autre valeur → intention proposée « Confirmer : <key> = <value> ? » si aucune intention ouverte identique ;
    - sinon → déduction (confiance 0,25, puis `+ 0,25 × (1 − m)` par jour d'indice nouveau).
    - clé vide, persona, ou valeur de plus de 300 caractères → ignorée.
  - `rappel` inclut la déduction la plus confiante par clé, texte « je crois que <key> : <value> ».
- [ ] Tests : `deduction_never_overrides_declared`, `repeated_hint_raises_confidence_once_per_day`, `contradiction_asks_once`, `extraction_rejects_out_of_schema`, `recall_marks_deductions`.
- [ ] Implémenter, tests verts, commit `feat(memoire): déductions et questions de confirmation`.

### Task 5: Oubli et outils de nuit

**Files:** Modify `tentacles/memoire/src/store.rs`, `tentacles/memoire/src/tools.rs`, `tentacles/memoire/tests/http.rs` ; `tete/src/tentacles.rs` (outils `nuit_*` cachés).

**Interfaces:**
- Produces:
  - `pub fn oublier(&self, maintenant: NaiveDateTime) -> Result<u32>` (règles des Global Constraints ; renvoie le nombre de souvenirs supprimés).
  - Outils : `nuit_episodes {jour}` → `[Element]` ; `nuit_cles {}` → liste des clés connues (déclarées et déduites) ; `nuit_appliquer {jour, indices:[{key,value}]}` → `Bilan` ; `nuit_condensables {max}` → `[Lot]` ; `nuit_condenser {niveau, periode, texte}` → `{"id"|null}` ; `nuit_oublier {}` → `{"supprimes"}`.
  - `is_app_tool` : tout nom commençant par `nuit_` est caché au moteur.
  - Table `nuit_etat(etape TEXT PRIMARY KEY, dernier_jour TEXT, derniere_execution TEXT, statut TEXT)` ; outils `nuit_etat {}` → états, `nuit_noter {etape, statut, dernier_jour?}` ; `brief_section` de la mémoire ajoute une ligne par étape en attente ou en échec (« Mémoire : <étape> en attente depuis le <jour> — <raison courte> »).
- [ ] Tests : `pending_step_shows_in_morning_brief`, `forget_only_folded_and_deduced`, `forget_never_touches_declared_anchor_year`, `night_tools_shape`, `night_tools_hidden_from_model`.
- [ ] Implémenter, tests verts, commit `feat(memoire): oubli réel et outils de nuit`.

### Task 6: La nuit dans la tête

**Files:** Modify `tete/src/jobs.rs` (après les résumés existants), tests `tete/tests/` ; `config/llm.example.toml` (usages `memoire_extraction`, `memoire_condensation`, commentaire neutre).

**Interfaces:**
- Consumes: Task 5.
- Produces:
  - Étape **extraction** (usage `memoire_extraction`) : pour chaque jour en attente (depuis `nuit_etat`, au plus 7, plus anciens d'abord, jusqu'à la veille), `nuit_episodes` + `nuit_cles` → prompt « liste d'indices `{key, value}` sur l'utilisateur, uniquement ce qu'il a dit ou fait, clés de la liste ou sous un préfixe existant, JSON seul » → `nuit_appliquer`.
  - Étape **condensation** (usage `memoire_condensation`) : `nuit_condensables {max: 4}` → pour chaque lot, prompt « résume en 3 à 6 phrases, sans inventer, en gardant dates et décisions » → `nuit_condenser`.
  - Étape **oubli** : `nuit_oublier`, toujours (sans modèle).
  - Moteur obtenu par la voie existante (`engine(...)` avec repli déclaré), usages déclarés stratégiques ; usage absent ou moteur injoignable → étape sautée, notée par `nuit_noter` (statut « en attente : modèle indisponible »), journal sans contenu ; succès → `nuit_noter` avec le dernier jour traité.
- [ ] Tests : `skipped_days_are_caught_up_next_night`, `skipped_step_is_recorded`, `night_runs_extraction_condensation_forget`, `bad_model_output_applies_nothing`, `night_without_model_still_forgets`, `night_twice_is_harmless`.
- [ ] Implémenter, `cargo test -p tete` puis `cargo test --workspace` verts, commit `feat(tete): le sommeil de la mémoire`.

**À la livraison :** déclarer les usages de nuit dans la configuration LLM de l'hôte.
