# Architecture multi-profil

## Contexte

Kevin veut faire une recherche d'emploi pour une amie (ingénieure d'études en
biologie, bac+5, vise Toulouse + périphérie) en réutilisant Kairos, qui est
aujourd'hui strictement mono-utilisateur : un seul `config/profile.toml`, une
seule base SQLite, un scoring calibré exclusivement pour un profil dev/tech
en full remote international.

Sans généralisation, aucune des hypothèses actuelles ne tient pour ce second
profil :
- Le gate remote (`matching.rs`) exige le full remote de façon inconditionnelle,
  alors que `preferences.remote` existe déjà dans `Profile` mais n'est jamais lu.
- Il n'existe aucun gate de localisation — seul un bonus pays (`country_bonus`)
  a du sens pour du remote international, pas pour une contrainte régionale
  (Toulouse + périphérie) dans un seul pays.
- L'ajustement salaire (`salary_adjustment`, introduit dans la refonte scoring
  du 2026-07-16) utilise des seuils absolus (48k/45k/40k) calés sur le
  salaire de Kevin — inutilisables pour un bac+5 en biologie sur un tout
  autre barème.
- `[skills]` impose des catégories dev figées (`backend`/`frontend`/
  `database`/`devops`/`learning`) qui n'ont aucun sens en biologie.
- Le chemin du profil (`config/profile.toml`) et la base de données sont
  actuellement en dur/mono-instance.
- Le nom réel de l'amie ne doit jamais atterrir dans le repo public (règles
  anti-OSINT du projet, cf. `CLAUDE.md`) — elle n'a aucune présence en ligne
  et ça doit rester ainsi.

## Objectif

Rendre Kairos capable de faire tourner un second profil, isolé, sans dupliquer
le code existant — le binaire, les collecteurs, le scoring restent partagés ;
seuls la configuration, la base de données et le routage de notification
changent par instance.

## Décisions actées (brainstormées avec Kevin)

- **Isolation par base de données séparée**, pas par colonne « profil » dans
  une base partagée — zéro risque de mélange, zéro requête à retoucher pour
  ajouter un filtre.
- **Second déploiement = second conteneur Docker** (même image `kairos:local`,
  son propre fichier d'env gitignoré), pas un branchement applicatif spécial.
  Réutilise le pattern déjà en place pour `ollama`/`radicale`/`ntfy`.
- **`preferences.remote` (second profil) = `"on-site"`** — gate remote entièrement
  différent de celui de Kevin (`"full"`).
- **CDI et CDD acceptés** pour le second profil — le gate freelance/contractor existant
  reste tel quel (ses mots-clés ne matchent pas un CDD), aucun changement.
- **`[skills]` généralisé en liste plate**, migration one-shot du
  `profile.toml` de Kevin (fichier gitignoré, pas de compat à préserver côté
  repo public).
- **Salaire relatif au profil** (pas des seuils absolus partagés) — s'applique
  aussi rétroactivement au profil de Kevin.
- **Collecteurs biologie = hors scope de cette spec**, chantier séparé
  (recherche de plateformes en cours). Cette spec doit seulement rendre
  l'architecture capable d'accueillir un second profil ; le fait qu'aucun
  collecteur pertinent n'existe encore pour la biologie est un problème de
  couverture de données, pas d'architecture.

## Design

### 1. Chemin de profil paramétrable

`main.rs` lit aujourd'hui `Profile::from_file("config/profile.toml")` en dur.
Remplacé par :

```rust
let profile_path = std::env::var("PROFILE_PATH").unwrap_or_else(|_| "config/profile.toml".into());
let profile = Profile::from_file(&profile_path)?;
```

Rétrocompatible : absence de `PROFILE_PATH` = comportement actuel inchangé.

### 2. `[skills]` : catégories → liste plate

`config.rs::Skills` passe de :

```rust
pub struct Skills {
    pub backend: Vec<String>,
    pub frontend: Vec<String>,
    pub database: Vec<String>,
    pub devops: Vec<String>,
    pub learning: Vec<String>,
}
impl Skills {
    pub fn all(&self) -> Vec<String> { /* concatène les 5 champs */ }
    pub fn categorized(&self) -> HashMap<&str, &[String]> { ... } // déjà dead code (clippy)
}
```

à :

```rust
#[derive(Debug, Clone, Deserialize)]
pub struct Skills {
    pub skills: Vec<String>,
}
impl Skills {
    pub fn all(&self) -> Vec<String> { self.skills.clone() }
}
```

`categorized()` est supprimée (déjà signalée `never used` par clippy avant
cette spec — suppression de dette existante, pas une régression introduite
ici). Tout appelant de `.all()` (uniquement `matching.rs::skill_bonus`)
reste inchangé côté signature.

**Format TOML** : `[skills] skills = ["Symfony", "React", ...]` au lieu de
`[skills] backend = [...] frontend = [...] ...`. Migration one-shot du
`config/profile.toml` réel de Kevin (gitignoré, aucune compat à préserver) et
de `config/profile.example.toml` (versionné, mis à jour dans cette spec).

### 3. Gate remote piloté par `preferences.remote`

`matching.rs` — la fonction `is_remote(offer)` ne change pas de forme, mais
son *application* dans `score_with_breakdown()` devient conditionnelle :

```rust
let remote_gate_ok = match self.profile.preferences.remote.as_str() {
    "full" => is_remote(offer),   // comportement actuel, inchangé pour Kevin
    _ => true,                    // "on-site"/"hybrid" : pas de gate remote
};
if is_freelance(offer) || !remote_gate_ok {
    return (0.0, ScoreBreakdown { .. });
}
```

`"hybrid"` se comporte comme `"on-site"` pour l'instant (aucun profil ne
l'utilise aujourd'hui) — pas de logique dédiée tant qu'un besoin réel
n'apparaît pas (YAGNI). `ScoreBreakdown.remote` reste `1.0` quand la gate est
passée (gate remote skippée = gate passée, sémantique inchangée pour
l'affichage briefing).

### 4. Nouveau gate localisation (opt-in)

`config.rs::Preferences` gagne un champ :

```rust
#[serde(default)]
pub location_keywords: Vec<String>,
```

`matching.rs` ajoute un gate, appliqué comme les autres (score 0 si échec) :

```rust
fn location_gate_ok(&self, offer: &JobOffer) -> bool {
    if self.profile.preferences.location_keywords.is_empty() {
        return true; // pas de contrainte déclarée = gate sautée (Kevin)
    }
    let haystack = format!(
        "{} {} {}",
        offer.location.as_deref().unwrap_or("").to_lowercase(),
        offer.title.to_lowercase(),
        offer.description.to_lowercase(),
    );
    self.profile.preferences.location_keywords.iter()
        .any(|k| haystack.contains(&k.to_lowercase()))
}
```

Profil du second utilisateur (illustratif, non réel) : `location_keywords = ["Toulouse", "Blagnac", "Colomiers",
"Balma", "Ramonville", "Labège", "Muret", "Tournefeuille", "Haute-Garonne",
"31"]` (liste à affiner avec elle — placeholder raisonnable, pas figé dans le
code, entièrement piloté par sa config).

Vide par défaut (`#[serde(default)]`) : aucun changement pour le profil de
Kevin qui ne déclare pas ce champ.

Ce gate se combine avec les deux autres dans le même court-circuit en tête de
`score_with_breakdown()` — les trois causes d'échec (freelance, remote,
localisation) partagent un seul retour anticipé :

```rust
if is_freelance(offer) || !remote_gate_ok || !self.location_gate_ok(offer) {
    return (0.0, ScoreBreakdown { skills: 0.0, remote: 0.0, salary: 0.0, location: 0.0 });
}
```

### 5. Salaire relatif au profil (remplace les seuils absolus)

`matching.rs::salary_adjustment` passe de constantes en dur à un calcul basé
sur `preferences.salary_min`/`preferences.salary_target` du profil courant :

```rust
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

Devient une méthode de `Matcher` (accès à `self.profile`) plutôt qu'une
fonction libre — seul changement de signature.

**Correction après calcul exact** (contrairement à une première estimation) :
pour le profil réel de Kevin (`salary_min=45000`, `salary_target=50000`,
mid=47500), le comportement **change** par rapport aux seuils absolus actuels
(48k/45k/40k) :

| Salaire offert | Ancien (seuils absolus) | Nouveau (relatif, mid=47500) |
|-----------------|--------------------------|-------------------------------|
| 55000           | +0.10                   | +0.10 (identique)             |
| 48000           | +0.10                   | +0.05 (change)                |
| 46000           | +0.05                   | 0.0 (change)                  |
| 42000           | 0.0 (neutre)             | **-0.20** (malus — change)    |
| 35000           | -0.20                   | -0.20 (identique)             |

La bande neutre de Kevin se resserre : elle ne descend plus 5k sous son
minimum déclaré, elle s'arrête pile à son minimum. Le test existant
`salaire_sous_40k_malus_vs_neutre` (35000 vs 42000, qui attend 42000 =
neutre) **doit être mis à jour** pour refléter ce nouveau comportement
(42000 devient malus) — ce n'est pas une régression cachée, c'est le prix
attendu de rendre le seuil pilotable par profil plutôt que figé sur le sien.
Kevin doit valider explicitement qu'il accepte ce resserrement pour lui-même
avant qu'on l'implémente. **Nécessite un rerun de `kairos rescore`** après
déploiement, comme après la refonte scoring initiale.

### 6. Isolation des données personnelles

`.gitignore` actuel : `config/profile.toml` (un seul chemin). Élargi en
`config/profile*.toml` pour couvrir `config/profile-secondary.toml` (ou tout
autre nom futur) sans jamais les committer. Son nom réel n'apparaît dans
aucun fichier versionné — ni code, ni exemple, ni documentation, ni message
de commit.

### 7. Déploiement — second service Docker

`docker-compose.yml` est un fichier **versionné** — son nom réel n'y figure
donc pas. Nom de service neutre `kairos-secondary` :

```yaml
  kairos-secondary:
    <<: *kairos-common
    profiles: ["tools"]
    environment:
      PROFILE_PATH: /app/config/profile-secondary.toml
      DATABASE_PATH: /app/data/secondary.db
      BRIEFINGS_DIR: /app/briefings-secondary
    env_file:
      - path: kairos-secondary.env
        required: false
```

Son nom réel n'existe que dans les fichiers gitignorés (`config/profile-
secondary.toml`, `deploy/docker/kairos-secondary.env`) — jamais dans un
chemin, un identifiant de service, un commentaire ou un message de commit
versionné. Même règle que pour les données de Kevin.

Nouvelles entrées dans `supercronic.crontab` (propre planificateur, horaires
décalés pour ne pas concurrencer les crons de Kevin) invoquant le binaire
avec les mêmes variables. `notify::discord()` est déjà entièrement piloté
par les env vars `NOTIFY_TOKEN`/`NOTIFY_CHANNEL_ID` lues à l'exécution —
aucun changement de code : `kairos-secondary.env` fixe juste un
`NOTIFY_CHANNEL_ID` différent (l'ID du thread Discord dédié), et le message
part au bon endroit sans branchement supplémentaire.

## Hors scope (sous-projets séparés)

- **Collecteurs biologie/Toulouse** : recherche de plateformes en cours
  (France Travail, APEC, ABG, Adzuna catégorie recherche...). Spec/plan
  séparés une fois les sources identifiées.
- **Sélection des collecteurs actifs par profil** : `Command::Scrape`
  lance aujourd'hui la liste fixe des 5 collecteurs tech + Adzuna pour
  toute exécution. Sans collecteur biologie, cette question ne se pose pas
  encore ; à trancher dans le sous-projet collecteurs (probablement un
  champ `active_collectors: Vec<String>` dans `Preferences`, filtrant la
  liste construite dans `main.rs`).
- **Catégorie Adzuna paramétrable** : actuellement `category=it-jobs` en
  dur dans `collectors/adzuna.rs` — à généraliser dans le sous-projet
  collecteurs si Adzuna sert de source pour le second profil.
- **Révision de CV** : demandée par Kevin pour plus tard, sans rapport avec
  cette spec.
