# Espace Agenda — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Espace Agenda dédié : jour en frise avec plages libres, tâches, à venir, ajout d'un événement, données fraîches à l'ouverture.

**Architecture:** Le module agenda expose des outils `app_*` en JSON (passe-plat de la tête inchangé) : lecture d'un jour et d'une semaine, synchronisation à la demande, ajout d'événement (CalDAV), tâche faite. L'application ajoute `ui/agenda/` sur la boîte à outils `ui/kit/`.

**Tech Stack:** Rust (tentacle agenda, `kairos::calendar` pour la synchronisation, écriture CalDAV sur le modèle du module alimentation), Kotlin/Compose.

**Spec:** `docs/superpowers/specs/2026-11-08-app-espaces-design.md` (section « Espace Agenda »).

## Global Constraints

- `CLAUDE.md`, `CONTRIBUTING.md` ; branche `feat/app-agenda`, PR vers `develop`.
- Titres, lieux, participants : données personnelles, jamais journalisées ; adresses et identifiants des calendriers lus dans l'environnement existant (`CALENDAR_URL`, `CALENDAR_WORK_URL` et leurs identifiants), jamais dans le code.
- Heures en heure locale de l'utilisateur (fuseau du serveur, comme les autres outils agenda).
- Gestes : pas de glissement depuis le bord ; chaque geste a un bouton visible. Système de mouvement `ui/theme/Motion.kt`.
- Rust : `cargo test -p kairos-agenda` vert. Android : `./gradlew --no-daemon testDebugUnitTest assembleDebug` vert.

### Contrat des outils `app_*` du module agenda

| Outil | Arguments | Résultat |
|---|---|---|
| `app_agenda_day` | `{"day"?}` | `{"day","events":[{"uid","title","start"?,"end"?,"all_day","location"?,"calendar":"perso"\|"travail"}],"free":[{"start","end","minutes"}],"tasks":[{"id","title","due"?}]}` (heures `HH:MM`) |
| `app_agenda_week` | `{"from"?}` | `{"days":[{"day","events","busy_minutes"}],"upcoming":[{"uid","title","day","start"?,"calendar"}]}` (7 jours) |
| `app_agenda_sync` | — | `{"synced": bool, "at"}` ; ne relance pas une synchronisation de moins de 60 s |
| `app_event_add` | `{"title","day","start"?,"minutes"?,"all_day"?,"calendar"}` | `{"message","uid"}` ; écrit l'événement (VEVENT) dans le calendrier choisi puis met à jour la base locale |
| `app_task_done` | `{"id"}` | `{"message"}` |

## Review Focus

1. Synchronisation impossible (calendrier injoignable) : l'espace s'affiche avec la base locale et un bandeau, rien ne plante (Task 1 : `sync_failure_keeps_local`).
2. Événement sur plusieurs jours ou journée entière : frise et liste corrects (réutiliser la règle des cartes : « jusqu'à », « dès », « toute la journée ») (Task 1 : `multi_day_in_day_view`).
3. Ajout avec un titre contenant des caractères spéciaux (virgule, point-virgule, retour à la ligne) : échappés selon iCalendar (Task 1 : `event_add_escapes_ical`).
4. Double appui sur « Ajouter » : un seul événement créé (Task 3 : `add_once`).

---

### Task 1: Module agenda — outils `app_*`

**Files:** `tentacles/agenda/src/tools.rs`, nouveau `tentacles/agenda/src/write.rs` (écriture CalDAV : PUT d'un VEVENT, uid stable, échappement), `tentacles/agenda/Cargo.toml` si besoin, tests dans le crate.

- [ ] Tests d'abord : `app_day_json_shape`, `free_slots_in_day`, `multi_day_in_day_view`, `app_week_counts_and_upcoming`, `sync_throttled`, `sync_failure_keeps_local`, `event_add_escapes_ical`, `event_add_visible_immediately`, `task_done_marks_task`.
- [ ] Implémenter selon le contrat (réutiliser `kairos::calendar` pour la synchronisation et le stockage, la logique `slots.rs` pour les plages libres). Commit `feat(agenda): outils de l'espace Agenda de l'application`.

### Task 2: Android — composant frise du jour

**Files:** `ui/kit/Timeline.kt` (frise horaire générique : blocs, plages libres, trait de l'heure ; réutilisable), `ui/kit/KitMath.kt` (placement des blocs, chevauchements en colonnes).

- [ ] Tests d'abord : `blocks_positioned_by_minutes`, `overlapping_events_side_by_side`, `now_line_only_today`, `all_day_events_on_top`.
- [ ] Implémenter. Commit `feat(mobile): frise horaire de la boîte à outils`.

### Task 3: Android — espace Agenda

**Files:** `ui/agenda/AgendaScreen.kt`, `ui/agenda/AgendaState.kt` (pur), `data/Models.kt`, `data/Repository.kt` (cache hors ligne par jour), `ui/spaces/SpacesScreen.kt` (carte Agenda : prochain événement).

Bandeau de jours, vue du jour (frise), tâches (glisser → faite, annulable), à venir, panneau « Ajouter un événement » ; synchronisation à l'ouverture puis lecture ; badge du mode de travail (champ `work` de `/days`).

- [ ] Tests d'abord (`AgendaStateTest`) : `day_strip_seven_days`, `swipe_changes_day_not_from_edge`, `add_once`, `add_form_validation` (titre vide, durée), `offline_keeps_last_day`.
- [ ] Implémenter. Build + tests verts. Commit `feat(mobile): espace Agenda`.
