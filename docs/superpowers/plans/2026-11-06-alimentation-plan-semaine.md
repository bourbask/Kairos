# Plan de la semaine et carnet de recettes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Carnet de recettes + plan de repas avec restes/boîtes, écrit dans un calendrier CalDAV.

**Architecture:** `plan.rs` (pur : construction du plan), `store.rs` (tables `recipes`, `meal_plan`), `caldav.rs` (PUT/DELETE minimal), `tools.rs` (outils `recipe_set`, `recipes`, `meal_plan_set`, `meal_plan`), `main.rs` (env CalDAV).

**Tech Stack:** Rust 2024, rusqlite, rmcp, reqwest, chrono-tz.

**Spec:** `docs/superpowers/specs/2026-11-06-alimentation-plan-semaine-design.md`

## Global Constraints

- Commits `--no-gpg-sign`, aucune mention d'IA. Repo public : pas de donnée personnelle.
- UID `kairos-repas-AAAAMMJJ-midi|soir` ; midi 12:15/45 min, soir 19:00/60 min, heure locale `Settings.tz`.
- Période 1 à 14 jours, 7 par défaut. Créneaux : `nature::Slot` restreint à `Midi` et `Soir`.
- Identifiants CalDAV jamais journalisés ni renvoyés.

## Review Focus

1. Deux plats sur le même créneau → erreur claire, rien écrit. Test `build_rejects_double_booking`.
2. Restes au-delà de la période → avertissement, pas de créneau inventé. Test `build_warns_overflow`.
3. CalDAV en panne → plan enregistré, réponse le dit, pas d'erreur outil. Test `meal_plan_set_without_calendar`.
4. Changement d'heure (fin octobre / fin mars) : heure locale convertie par `chrono_tz`, pas d'offset fixe. Test `event_times_follow_local_time`.
5. Préférence `bureau` absente ou illisible → aucun jour de bureau, pas d'erreur. Test `office_days_parse`.

---

### Task 1: `plan.rs` — construction du plan

**Produces:** `pub struct Cook { pub day: NaiveDate, pub slot: Slot, pub dish: String, pub portions: u32 }` ; `pub enum MealKind { Cook, Leftover, Box }` ; `pub struct Meal { pub day: NaiveDate, pub slot: Slot, pub dish: String, pub kind: MealKind, pub from: NaiveDate, pub note: Option<String> }` ; `pub fn build(start: NaiveDate, days: u32, cooks: &[Cook], office: &[Weekday]) -> Result<(Vec<Meal>, Vec<String>), String>` (repas triés, avertissements) ; `pub fn office_days(pref: &str) -> Vec<Weekday>`.

Règles : grille jour×(midi, soir) ; plats placés (hors période ou créneau pris → `Err`) ; dans l'ordre chronologique, chaque plat remplit ses `portions − 1` créneaux libres suivants ; reste un midi de bureau → `Box` ; soir précédant un `Box` → note « Demain bureau : mettre une portion en boîte ce soir. » ; avertissements : restes débordants, créneaux vides, plat cuisiné un midi de bureau.

- [ ] Tests : `build_fills_leftovers_in_order`, `build_turns_office_lunch_into_box`, `build_rejects_double_booking`, `build_warns_overflow`, `office_days_parse` (« lundi, jeudi » → [Mon, Thu] ; « » → []).
- [ ] RED, implémentation, GREEN, commit `feat(alimentation): construction du plan de repas`.

### Task 2: carnet et plan en base, outils `recipe_set`, `recipes`, `meal_plan`

**Produces:** `Recipe { title, portions: u32, ingredients: Vec<String>, steps: String }` ; `Store::set_recipe(&Recipe)`, `Store::recipe(name) -> Option<Recipe>` (nom replié), `Store::recipes() -> Vec<Recipe>` ; `Store::replace_plan(from, to, &[Meal])`, `Store::plan(from, to) -> Vec<Meal>`.

- [ ] Tests HTTP : `recipe_book_round_trip` (création, lecture, correction, liste), `meal_plan_reads_back` (après Task 3).
- [ ] RED, implémentation, GREEN, commit `feat(alimentation): carnet de recettes`.

### Task 3: `caldav.rs` + `meal_plan_set`

**Produces:** `CalDav::new(url, user, pass)`, `put(uid, ics)`, `delete(uid)` ; `Settings { tz, caldav: Option<CalDav> }` ; `fn event(meal, recipe, tz) -> (uid, ics)`.

- [ ] Tests : `event_times_follow_local_time` (unitaire : 2026-11-09 midi → `DTSTART:20261109T111500Z`, 2026-07-06 midi → `T101500Z`) ; HTTP avec faux CalDAV enregistrant méthode+chemin : `meal_plan_set_writes_calendar` (PUT des créneaux remplis, DELETE des vides, boîte un lundi si `bureau = lundi`), `meal_plan_set_without_calendar` (réponse contient « Agenda non configuré »), avertissements recette absente / ingrédient absent.
- [ ] RED, implémentation, GREEN, commit `feat(alimentation): plan de la semaine écrit dans l'agenda`.

### Task 4: configuration

- [ ] `deploy/docker/alimentation.env.example` (ou fichier env existant du module) : trois variables vides commentées. Commit `docs(alimentation): variables du calendrier des repas`.
