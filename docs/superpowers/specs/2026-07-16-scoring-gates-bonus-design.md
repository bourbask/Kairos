# Refonte du scoring — gates durs + bonus (K-F0x)

## Contexte

Diagnostic (session 2026-07-16) : sur 3422 offres collectées, seulement 2 dépassaient
le seuil `min_score` de 0.60. Cause : `Matcher::score()` (`src/matching.rs`) est une
moyenne pondérée (skills 40% / remote 25% / salaire 20% / localisation 15%) où le
score skills divise le nombre de compétences matchées par la **totalité** des
compétences du profil (25 dans `config/profile.toml`) — même une excellente offre
qui mentionne 8-10 technologies du profil n'obtient qu'un score skills de ~0.35,
diluant le score total bien en dessous de 0.60.

Simulation sur les données réelles (3422 offres, script ad-hoc) : avec un plafond de
6 compétences au lieu de 25, 127 offres dépassent 0.60 au lieu de 2. Confirmé comme
direction, mais affiné : Kevin est flexible sur la stack exacte (Java/Kotlin/Rust/JS
équivalents à l'ère de l'IA dev) tant que les critères non-négociables (full remote,
CDI, pas d'entreprise blacklistée) sont respectés — d'où un modèle **gates + bonus**
plutôt qu'un simple recalibrage de poids.

Deux gates candidats (langue FR/EN, secteur IT) ont été évalués puis abandonnés :
les sources de collecte (Arbeitnow, Remotive, RemoteOK, Jobicy, WeWorkRemotely,
Adzuna filtré `category=it-jobs`, LinkedIn email) garantissent déjà secteur et
langue exploitable — un gate texte en plus n'aurait ajouté que du bruit/faux négatifs.

## Objectif

Que les offres qui respectent déjà les critères durs de Kevin (remote, CDI,
non-blacklistées) apparaissent systématiquement au-dessus du seuil de qualité,
sans que l'exigence exacte de stack technique les évince — tout en gardant un vrai
signal de tri (« liste vide plutôt que du bruit » reste vrai : le seuil filtre
encore les vrais mauvais matchs, ex. salaire trop bas).

## Design

### Gates (tout ou rien — score = 0.0 si un seul échoue)

- **Full remote** — logique existante `is_remote()` (actuellement dans `ranker.rs`),
  rapatriée dans `matching.rs`. Flag explicite si présent, sinon mot-clé remote
  dans le texte ET pas d'« hybride ».
- **Non-freelance/contractor (CDI)** — logique existante `is_freelance()` (idem,
  rapatriée), mots-clés freelance/contractor/C2C/self-employed.
- **Non-blacklistée** — `is_blacklisted()`, **reste inchangée** : appliquée comme
  filtre de lecture dans `ranker.rs::top_unpresented()`, pas persistée dans le
  score, pour que les changements de blacklist s'appliquent sans re-scraper.

Gates écartés : secteur IT et langue (redondants avec les sources, cf. Contexte).

### Score une fois les gates passés

```
score = 0.40                                              (baseline)
      + bonus_competences   0 → +0.30
      + bonus_pays          0 ou +0.10
      + ajustement_salaire  -0.20 → +0.10
→ clampé [0.0, 1.0]
```

- **`bonus_competences`** : `(matched / min(profile_skills.len(), 6)).min(1.0) * 0.30`.
  Plafond à 6 compétences matchées pour le score plein, quelle que soit la taille
  de la liste du profil (25 aujourd'hui) — évite la dilution actuelle.
- **`bonus_pays`** : `+0.10` si `offer.country` est dans `preferences.countries`
  (CH/BE/LU/NL/DE/AT/FR), sinon `0.0`. Jamais de malus — le gate remote garantit
  déjà l'essentiel, ce bonus ne sert que les cas de contrainte de résidence
  malgré le remote.
- **`ajustement_salaire`** (sur `salary_max` si connu, sinon `salary_min`) :
  - `≥ 48000` → `+0.10`
  - `45000..48000` → `+0.05`
  - `40000..45000` → `0.0`
  - `< 40000` → `-0.20`
  - inconnu (`None`) → `0.0` (neutre, pas de malus pour absence de donnée)

Validé par simulation sur les 3422 offres réelles : 801 passent les gates, dont
43 dépassent 0.60 (contre 2 avec l'ancienne formule) — sélectif mais réel, le
seuil `min_score = 0.60` (`config/profile.toml`) **reste inchangé**, pas besoin
de le retoucher.

### Où ça vit

- **`src/matching.rs`** :
  - `is_remote()`/`is_freelance()` rapatriées depuis `ranker.rs` comme fonctions
    libres privées du module (identique à leur forme actuelle — elles ne dépendent
    pas de `self.profile`, contrairement à `is_blacklisted` qui reste une méthode).
  - `Matcher::score_with_breakdown()` réécrite : gate check en premier (retourne
    `(0.0, breakdown_neutre)` si échec), sinon calcul baseline + bonus ci-dessus.
  - `score_skills`/`score_salary`/`score_location` existantes remplacées par les
    fonctions de bonus (signatures probablement différentes : retournent
    directement une contribution au score total, pas un ratio 0..1 à pondérer).
  - `score_remote` supprimée (absorbée dans le gate).
- **`src/ranker.rs`** :
  - `is_remote()`/`is_freelance()` et leurs tests supprimés d'ici (déplacés dans
    `matching.rs`).
  - `top_unpresented()` perd le filtre `!is_freelance(offer) && is_remote(offer)`
    dans son `.filter()` — le score vaut déjà 0 pour ces cas, filtré par le
    `WHERE score >= min_score` SQL de `get_unpersented_scored()`. Garde
    `!self.matcher.is_blacklisted(offer)`.
- **`src/models.rs`** : `ScoreBreakdown` inchangée dans sa forme (`skills`,
  `remote`, `salary`, `location` : `f64`), sémantique ajustée : `remote` devient
  un flag gate (1.0 si passé — puisqu'un score non-nul implique gate passée),
  `salary`/`location` devienent des contributions déjà pondérées (pas un ratio
  0..1 à multiplier par un poids externe). Affichage dans `briefing.rs`
  (pourcentages) reste fonctionnel sans changement de code côté briefing.
- **`config/profile.toml`** / `Profile` : aucun nouveau champ. Réutilise
  `preferences.salary_min`, `preferences.salary_target` (existants, mais les
  seuils 45k/48k/40k sont **en dur** dans le code, pas dérivés de `salary_min`/
  `salary_target` — à noter explicitement en commentaire pour éviter la confusion
  si Kevin change ces préférences plus tard sans toucher les seuils salaire du
  scoring). `preferences.countries` réutilisé tel quel pour le bonus pays.

### Migration des offres déjà collectées

Les 3422 offres déjà en base gardent leur ancien `score` tant qu'elles ne sont
pas re-scrapées (ce qui n'arrivera jamais pour la plupart, elles sont déjà
collectées). Nouvelle commande CLI **`kairos rescore`** : relit toutes les
offres (nouvelle méthode `Storage::get_all_offers()`), réapplique
`Matcher::score_with_breakdown()`, ré-écrit `score` en base via
`update_score()` existant. Une seule exécution manuelle après déploiement,
pas de cron (le scoring des nouvelles offres reste dans `scrape` via
`Ranker::score_new_offers()`, inchangé).

### Tests

- `matching.rs` : réécriture complète des tests existants.
  - `test_perfect_match` (actuel : 15 compétences collées dans la description)
    devient un cas normal, pas un extrême — à remplacer par un cas réaliste
    (offre mentionnant 6-8 technologies du profil, remote confirmé, CDI,
    salaire correct) et vérifier `score > 0.60`.
  - Cas gate remote échoue → `score == 0.0`.
  - Cas gate freelance échoue → `score == 0.0`.
  - Cas salaire `< 40000` → malus appliqué, score plausible mais démoté.
  - Cas salaire inconnu → neutre (ni bonus ni malus).
  - Cas pays hors liste → pas de malus (juste absence de bonus).
- `ranker.rs` : `remote_dur`/`detecte_freelance`/`cdi_non_exclu` supprimés d'ici
  (migrés vers `matching.rs` avec le code qu'ils testent) ; `top_unpresented()`
  garde un test vérifiant que le filtre blacklist fonctionne toujours seul.

## Hors scope (chantier séparé, cf. discussion)

Bonus « réputation entreprise » : pas de signal fiable/bon marché disponible pour
un scoring batch nocturne sur ~3000+ offres (scraping type Glassdoor fragile/
bloqué). Réservé à la future feature « perle rare » (flag manuel + rapport
d'analyse approfondie à la demande sur une seule entreprise) — brainstormée
séparément.
