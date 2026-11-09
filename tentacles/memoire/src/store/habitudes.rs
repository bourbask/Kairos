//! Habitudes apprises chaque nuit à partir d'observations structurées des
//! modules, sans modèle de langue : une moyenne glissante par valeur et par
//! contexte, idempotente par source et par jour.

use super::*;

/// Observation d'une journée, fournie par une source.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
pub struct Observation {
    pub quoi: String,
    pub contexte: String,
    pub valeur: String,
    pub libelle: String,
    pub cadence: String,
}

/// Résultat d'une application d'observations.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct BilanHabitudes {
    pub complet: bool,
    pub appliquees: u32,
    pub nees: u32,
    pub changements: u32,
    pub retours: u32,
    pub ignorees: u32,
}

/// Taux d'apprentissage d'une source quotidienne.
pub const A_QUOTIDIEN: f64 = 0.1;
/// Taux d'apprentissage d'une source hebdomadaire.
pub const A_HEBDO: f64 = 0.25;
/// Observations retenues par source et par jour.
pub const OBSERVATIONS_MAX: usize = 50;
/// Au-delà de ce silence, en jours, une source n'est plus attendue.
pub const SOURCE_ABSENTE_JOURS: u64 = 14;
/// Longueur maximale d'un code ou d'un nom de source.
const CODE_MAX: usize = 64;
const LIBELLE_MAX: usize = 120;
/// Force minimale d'une habitude active.
const SEUIL_ACTIVE: f64 = 0.5;
/// Observations positives minimales avant la naissance.
const POSITIFS_NAISSANCE: i64 = 3;
const JOURS_QUOTIDIEN: [&str; 2] = ["ouvre", "weekend"];
const JOURS_HEBDO: [&str; 7] = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"];

fn code_valide(s: &str) -> bool {
    (1..=CODE_MAX).contains(&s.chars().count())
        && s.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "._-".contains(c))
}

/// Taux d'apprentissage d'une observation de `jour`, `None` si elle est
/// invalide ou si son type de jour ne correspond pas à `jour`.
fn taux(o: &Observation, jour: NaiveDate) -> Option<f64> {
    let rang = jour.weekday().num_days_from_monday() as usize;
    let (a, attendu) = match o.cadence.as_str() {
        "quotidien" => (A_QUOTIDIEN, JOURS_QUOTIDIEN[usize::from(rang >= 5)]),
        "hebdomadaire" => (A_HEBDO, JOURS_HEBDO[rang]),
        _ => return None,
    };
    let (creneau, type_jour) = o.contexte.rsplit_once('.')?;
    let valide = code_valide(&o.quoi)
        && code_valide(&o.contexte)
        && code_valide(&o.valeur)
        && !creneau.is_empty()
        && type_jour == attendu
        && (1..=LIBELLE_MAX).contains(&o.libelle.chars().count())
        && !o.libelle.chars().any(char::is_control);
    valide.then_some(a)
}

/// Une occurrence du contexte de `o` : la valeur observée tend vers 1, les
/// autres valeurs connues du contexte vers 0.
fn compter(c: &Connection, source: &str, o: &Observation, a: f64, maintenant: NaiveDateTime) -> Result<()> {
    c.execute(
        "UPDATE habitudes SET m = m + ?4 * (0 - m)
         WHERE source = ?1 AND quoi = ?2 AND contexte = ?3 AND valeur <> ?5",
        params![source, o.quoi, o.contexte, a, o.valeur],
    )?;
    // Réapprentissage rapide d'une ancienne habitude.
    let n = c.execute(
        "UPDATE habitudes SET m = m + (CASE etat WHEN 'ancienne' THEN 2 * ?4 ELSE ?4 END) * (1 - m),
                positifs = positifs + 1, libelle = ?6
         WHERE source = ?1 AND quoi = ?2 AND contexte = ?3 AND valeur = ?5",
        params![source, o.quoi, o.contexte, a, o.valeur, o.libelle],
    )?;
    if n == 0 {
        c.execute(
            "INSERT INTO habitudes (source, quoi, contexte, valeur, libelle, cadence, m, positifs)
             VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
            params![source, o.quoi, o.contexte, o.valeur, o.libelle, o.cadence, a],
        )?;
    }
    // Souvenir d'une habitude née : rappelé si observée, confiance = force tant que non confirmée.
    c.execute(
        "UPDATE souvenirs SET dernier_rappel = ? WHERE nature = 'habitude' AND ref =
         (SELECT CAST(id AS TEXT) FROM habitudes WHERE source = ? AND quoi = ? AND contexte = ? AND valeur = ?)",
        params![ts(maintenant), source, o.quoi, o.contexte, o.valeur],
    )?;
    c.execute(
        "UPDATE souvenirs SET confiance = (SELECT h.m FROM habitudes h WHERE CAST(h.id AS TEXT) = souvenirs.ref)
         WHERE nature = 'habitude' AND ref IN (SELECT CAST(id AS TEXT) FROM habitudes
             WHERE source = ? AND quoi = ? AND contexte = ? AND confirmee = 0)",
        params![source, o.quoi, o.contexte],
    )?;
    Ok(())
}

/// Élit l'active du contexte : la valeur non rejetée de plus grande force,
/// si elle dépasse le seuil et l'active courante. Une naissance est annoncée
/// une fois ; un retour ne l'est jamais.
fn elire(c: &Connection, source: &str, o: &Observation, j: &str, maintenant: NaiveDateTime, b: &mut BilanHabitudes) -> Result<()> {
    // (id, etat, libelle, m, positifs), plus forte d'abord.
    let lignes = c
        .prepare(
            "SELECT id, etat, libelle, m, positifs FROM habitudes
             WHERE source = ? AND quoi = ? AND contexte = ? AND rejetee = 0 ORDER BY m DESC, id",
        )?
        .query_map(params![source, o.quoi, o.contexte], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, f64>(3)?, r.get::<_, i64>(4)?))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    let Some((id, etat, libelle, m, positifs)) = lignes.first() else { return Ok(()) };
    let active = lignes.iter().find(|l| l.1 == "active");
    let eligible = match etat.as_str() {
        "candidate" => *m >= SEUIL_ACTIVE && *positifs >= POSITIFS_NAISSANCE,
        "ancienne" => *m >= SEUIL_ACTIVE,
        _ => false,
    };
    if !eligible || active.is_some_and(|a| a.3 >= *m) {
        return Ok(());
    }
    c.execute(
        "UPDATE habitudes SET etat = 'ancienne', ancienne_depuis = ?4
         WHERE source = ?1 AND quoi = ?2 AND contexte = ?3 AND etat = 'active'",
        params![source, o.quoi, o.contexte, j],
    )?;
    if etat == "ancienne" {
        c.execute("UPDATE habitudes SET etat = 'active', ancienne_depuis = NULL WHERE id = ?", [id])?;
        b.retours += 1;
        return Ok(());
    }
    let what = match active {
        Some(a) => format!("Confirmer : habitude — {libelle} (avant : {}) ?", a.2),
        None => format!("Confirmer : habitude — {libelle} ?"),
    };
    let question = proposer_question(c, &what, maintenant)?;
    c.execute(
        "UPDATE habitudes SET etat = 'active', nee_le = ?, question = ?, annoncee_le = ? WHERE id = ?",
        params![j, question, ts(maintenant), id],
    )?;
    c.execute(
        "INSERT OR REPLACE INTO souvenirs (nature, ref, stabilite, dernier_rappel, importance, confiance)
         VALUES ('habitude', ?, ?, ?, 0.0, ?)",
        params![id.to_string(), stabilite_initiale(Famille::Habitude, 0.0), ts(maintenant), m],
    )?;
    b.nees += 1;
    if active.is_some() {
        b.changements += 1;
    }
    Ok(())
}

/// Réponse à une question : une habitude acceptée est confirmée, une refusée
/// n'est plus rappelée ni candidate au rôle d'active.
pub(super) fn repondre(c: &Connection, question: i64, state: IntentionState, maintenant: NaiveDateTime) -> Result<()> {
    let souvenir = "nature = 'habitude' AND ref IN (SELECT CAST(id AS TEXT) FROM habitudes WHERE question = ?)";
    match state {
        IntentionState::Acceptee => {
            c.execute("UPDATE habitudes SET confirmee = 1, rejetee = 0 WHERE question = ?", [question])?;
            // Refusée puis acceptée : le souvenir est recréé.
            c.execute(
                "INSERT OR IGNORE INTO souvenirs (nature, ref, stabilite, dernier_rappel, importance, confiance)
                 SELECT 'habitude', CAST(id AS TEXT), ?, ?, 0.0, 1.0 FROM habitudes WHERE question = ?",
                params![stabilite_initiale(Famille::Habitude, 0.0), ts(maintenant), question],
            )?;
            c.execute(&format!("UPDATE souvenirs SET confiance = 1.0 WHERE {souvenir}"), [question])?;
        }
        IntentionState::Refusee => {
            c.execute("UPDATE habitudes SET rejetee = 1, confirmee = 0 WHERE question = ?", [question])?;
            c.execute(&format!("DELETE FROM souvenirs WHERE {souvenir}"), [question])?;
        }
        _ => {}
    }
    Ok(())
}

/// Détache les habitudes dont la question n'existe plus : un identifiant
/// d'intention réutilisé ne doit jamais répondre à une autre habitude.
pub(super) fn delier_questions(c: &Connection) -> Result<()> {
    c.execute(
        "UPDATE habitudes SET question = NULL WHERE question IS NOT NULL AND question NOT IN (SELECT id FROM intentions)",
        [],
    )?;
    Ok(())
}

/// Efface les habitudes choisies par `filtre` (clause sur `habitudes`), avec
/// leur souvenir et leur question encore ouverte ; renvoie le nombre effacé.
fn effacer(c: &Connection, filtre: &str, p: impl rusqlite::Params + Copy) -> Result<usize> {
    c.execute(
        &format!("DELETE FROM souvenirs WHERE nature = 'habitude' AND ref IN (SELECT CAST(id AS TEXT) FROM habitudes WHERE {filtre})"),
        p,
    )?;
    c.execute(
        &format!("DELETE FROM intentions WHERE state IN ('ouverte','proposee') AND id IN (SELECT question FROM habitudes WHERE {filtre})"),
        p,
    )?;
    Ok(c.execute(&format!("DELETE FROM habitudes WHERE {filtre}"), p)?)
}

/// Oubli de nuit : habitudes non confirmées dont la force est passée sous le seuil.
pub(super) fn oublier(c: &Connection) -> Result<u32> {
    Ok(effacer(c, "confirmee = 0 AND m < ?", [SEUIL_OUBLI])? as u32)
}

/// Aligne les souvenirs d'habitudes : orphelins retirés, habitude née non
/// rejetée sans souvenir pourvue d'un souvenir neuf.
pub(super) fn migrer(c: &Connection, maintenant: NaiveDateTime) -> Result<()> {
    c.execute(
        "DELETE FROM souvenirs WHERE nature = 'habitude' AND ref NOT IN
         (SELECT CAST(id AS TEXT) FROM habitudes WHERE nee_le IS NOT NULL AND rejetee = 0)",
        [],
    )?;
    c.execute(
        "INSERT OR IGNORE INTO souvenirs (nature, ref, stabilite, dernier_rappel, importance, confiance)
         SELECT 'habitude', CAST(id AS TEXT), ?, ?, 0.0, CASE confirmee WHEN 1 THEN 1.0 ELSE m END
         FROM habitudes WHERE nee_le IS NOT NULL AND rejetee = 0",
        params![stabilite_initiale(Famille::Habitude, 0.0), ts(maintenant)],
    )?;
    Ok(())
}

impl MemoryStore {
    /// Efface pour de bon une habitude, son souvenir et sa question ouverte ;
    /// `false` si elle est inconnue.
    pub fn habitude_oublier(&self, id: i64) -> Result<bool> {
        let tx = self.conn.unchecked_transaction()?;
        let n = effacer(&tx, "id = ?", [id])?;
        tx.commit()?;
        Ok(n > 0)
    }

    /// Compte les observations de `jour`, source par source, puis élit l'active
    /// de chaque contexte touché, en une transaction.
    /// Un jour déjà appliqué pour une source ne compte plus. `complet` dit si
    /// toutes les sources encore attendues ont répondu.
    pub fn appliquer_observations(
        &self,
        jour: NaiveDate,
        sources: &BTreeMap<String, Vec<Observation>>,
        maintenant: NaiveDateTime,
    ) -> Result<BilanHabitudes> {
        let j = jour.format("%Y-%m-%d").to_string();
        let mut b = BilanHabitudes::default();
        let tx = self.conn.unchecked_transaction()?;
        for (source, obs) in sources {
            if source.is_empty() || source.chars().count() > CODE_MAX {
                b.ignorees += obs.len() as u32;
                continue;
            }
            let dernier: Option<String> = tx
                .query_row("SELECT dernier_jour FROM habitudes_sources WHERE source = ?", [source], |r| r.get(0))
                .optional()?;
            if dernier.is_some_and(|d| d >= j) {
                continue;
            }
            let mut vus = BTreeSet::new();
            for (i, o) in obs.iter().enumerate() {
                match taux(o, jour) {
                    Some(a) if i < OBSERVATIONS_MAX && vus.insert((o.quoi.as_str(), o.contexte.as_str())) => {
                        compter(&tx, source, o, a, maintenant)?;
                        elire(&tx, source, o, &j, maintenant, &mut b)?;
                        b.appliquees += 1;
                    }
                    _ => b.ignorees += 1,
                }
            }
            tx.execute(
                "INSERT INTO habitudes_sources (source, dernier_jour) VALUES (?1, ?2)
                 ON CONFLICT(source) DO UPDATE SET dernier_jour = ?2",
                [source, &j],
            )?;
        }
        // Une source muette depuis trop longtemps n'est plus attendue ; ses habitudes restent.
        let limite = (jour - Days::new(SOURCE_ABSENTE_JOURS)).format("%Y-%m-%d").to_string();
        tx.execute("DELETE FROM habitudes_sources WHERE dernier_jour < ?", [limite])?;
        let connues = tx
            .prepare("SELECT source FROM habitudes_sources")?
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        b.complet = connues.iter().all(|s| sources.contains_key(s));
        tx.commit()?;
        Ok(b)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn jour(n: u64) -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 1, 5).unwrap() + Days::new(n)
    }

    /// Passage de nuit qui suit le jour `n`.
    fn quand(n: u64) -> NaiveDateTime {
        jour(n + 1).and_hms_opt(3, 0, 0).unwrap()
    }

    fn obs(contexte: &str, valeur: &str) -> Observation {
        Observation {
            quoi: "repas.lieu".into(),
            contexte: contexte.into(),
            valeur: valeur.into(),
            libelle: format!("repas du soir : {valeur}"),
            cadence: "quotidien".into(),
        }
    }

    fn lot(source: &str, v: Vec<Observation>) -> BTreeMap<String, Vec<Observation>> {
        BTreeMap::from([(source.to_owned(), v)])
    }

    /// `n`-ième jour ouvré à partir du lundi `jour(0)`.
    fn ouvre(n: u64) -> NaiveDate {
        jour(n / 5 * 7 + n % 5)
    }

    fn jouer(s: &MemoryStore, n: u64, valeur: &str) -> BilanHabitudes {
        let quand = ouvre(n).succ_opt().unwrap().and_hms_opt(3, 0, 0).unwrap();
        s.appliquer_observations(ouvre(n), &lot("test", vec![obs("soir.ouvre", valeur)]), quand).unwrap()
    }

    /// `(m, positifs)` d'une valeur du contexte de test.
    fn ligne(s: &MemoryStore, valeur: &str) -> (f64, i64) {
        s.conn
            .query_row("SELECT m, positifs FROM habitudes WHERE valeur = ?", [valeur], |r| Ok((r.get(0)?, r.get(1)?)))
            .unwrap()
    }

    fn store() -> MemoryStore {
        MemoryStore::open(":memory:").unwrap()
    }

    #[test]
    fn daily_counting_matches_spec() {
        let s = store();
        for n in 0..7 {
            jouer(&s, n, "maison");
        }
        let (m, p) = ligne(&s, "maison");
        assert!((m - (1.0 - 0.9f64.powi(7))).abs() < 1e-9, "{m}");
        assert_eq!(p, 7);
        jouer(&s, 7, "restaurant");
        assert!((ligne(&s, "maison").0 - 0.9 * (1.0 - 0.9f64.powi(7))).abs() < 1e-9);
        assert!((ligne(&s, "restaurant").0 - 0.1).abs() < 1e-9);
    }

    #[test]
    fn weekly_counting_uses_quarter() {
        let s = store();
        for n in 0..3 {
            let o = Observation { cadence: "hebdomadaire".into(), ..obs("soir.lundi", "maison") };
            s.appliquer_observations(jour(7 * n), &lot("test", vec![o]), quand(7 * n)).unwrap();
        }
        assert!((ligne(&s, "maison").0 - 0.578125).abs() < 1e-9);
    }

    #[test]
    fn same_day_twice_counts_once() {
        let s = store();
        jouer(&s, 0, "maison");
        jouer(&s, 1, "maison");
        let avant = ligne(&s, "maison");
        let b = jouer(&s, 1, "maison");
        assert_eq!(ligne(&s, "maison"), avant);
        assert_eq!(b.appliquees, 0);
    }

    #[test]
    fn missing_known_source_holds_day() {
        let s = store();
        let deux = |n: u64, avec_a: bool| {
            let mut m = lot("b", vec![]);
            if avec_a {
                m.insert("a".into(), vec![obs("soir.ouvre", "maison")]);
            }
            s.appliquer_observations(jour(n), &m, quand(n)).unwrap()
        };
        assert!(deux(0, true).complet);
        assert!(!deux(1, false).complet);
        assert!(deux(1, true).complet);
        // Absente plus de 14 jours après son dernier jour : retirée des sources connues.
        assert!(!deux(15, false).complet);
        assert!(deux(16, false).complet);
        let connue: bool = s.conn.prepare("SELECT 1 FROM habitudes_sources WHERE source = 'a'").unwrap().exists([]).unwrap();
        assert!(!connue);
        let lignes: i64 = s.conn.query_row("SELECT COUNT(*) FROM habitudes WHERE source = 'a'", [], |r| r.get(0)).unwrap();
        assert_eq!(lignes, 1);
    }

    #[test]
    fn bad_observations_ignored() {
        let s = store();
        let v = vec![
            obs("soir.ouvre", "maison"),
            Observation { quoi: String::new(), ..obs("midi.ouvre", "maison") },
            obs("matin.ouvre", &"x".repeat(65)),
            Observation { cadence: "mensuel".into(), ..obs("nuit.ouvre", "maison") },
            Observation { libelle: "a\nb".into(), ..obs("midi.weekend", "maison") },
            obs("soir.ouvre", "bureau"),
        ];
        let b = s.appliquer_observations(jour(0), &lot("test", v), quand(0)).unwrap();
        assert_eq!((b.appliquees, b.ignorees), (1, 5));
        assert_eq!(ligne(&s, "maison").1, 1);
        let v: Vec<_> = (0..51).map(|i| obs(&format!("c{i}.ouvre"), "maison")).collect();
        let b = s.appliquer_observations(jour(0), &lot("autre", v), quand(0)).unwrap();
        assert_eq!((b.appliquees, b.ignorees), (50, 1));
    }

    /// État et identifiant d'une valeur du contexte de test.
    fn etat(s: &MemoryStore, valeur: &str) -> (String, i64) {
        s.conn.query_row("SELECT etat, id FROM habitudes WHERE valeur = ?", [valeur], |r| Ok((r.get(0)?, r.get(1)?))).unwrap()
    }

    fn colonne(s: &MemoryStore, col: &str, valeur: &str) -> Option<String> {
        s.conn.query_row(&format!("SELECT {col} FROM habitudes WHERE valeur = ?"), [valeur], |r| r.get(0)).unwrap()
    }

    fn intentions(s: &MemoryStore) -> Vec<Intention> {
        let mut st = s.conn.prepare("SELECT id, what FROM intentions ORDER BY id").unwrap();
        st.query_map([], |r| {
            Ok(Intention { id: r.get(0)?, what: r.get(1)?, constraints: String::new(), expires_at: quand(0), state: IntentionState::Proposee })
        })
        .unwrap()
        .collect::<rusqlite::Result<_>>()
        .unwrap()
    }

    /// Joue `n` jours de `valeur` à partir du jour `debut` ; renvoie le dernier bilan.
    fn serie(s: &MemoryStore, debut: u64, n: u64, valeur: &str) -> BilanHabitudes {
        (debut..debut + n).map(|d| jouer(s, d, valeur)).last().unwrap()
    }

    #[test]
    fn habit_born_after_seven_evenings() {
        let s = store();
        serie(&s, 0, 6, "maison");
        assert_eq!(etat(&s, "maison").0, "candidate");
        assert!(s.intentions_open(quand(5)).unwrap().is_empty());
        let b = jouer(&s, 6, "maison");
        assert_eq!(b.nees, 1);
        let (e, id) = etat(&s, "maison");
        assert_eq!(e, "active");
        assert_eq!(colonne(&s, "nee_le", "maison").as_deref(), Some("2026-01-13"));
        assert!(s.force("habitude", &id.to_string()).unwrap().is_some());
        let ouvertes = s.intentions_open(quand(6)).unwrap();
        assert_eq!(ouvertes.len(), 1);
        assert_eq!(ouvertes[0].what, "Confirmer : habitude — repas du soir : maison ?");
    }

    #[test]
    fn birth_asks_once() {
        let s = store();
        serie(&s, 0, 7, "maison");
        jouer(&s, 6, "maison");
        serie(&s, 7, 10, "maison");
        assert_eq!(intentions(&s).len(), 1);
    }

    #[test]
    fn change_marks_old_habit() {
        let s = store();
        serie(&s, 0, 22, "maison");
        serie(&s, 22, 6, "restaurant");
        assert_eq!(etat(&s, "restaurant").0, "candidate");
        let b = jouer(&s, 28, "restaurant");
        assert_eq!(etat(&s, "restaurant").0, "active");
        assert_eq!(etat(&s, "maison").0, "ancienne");
        assert_eq!(colonne(&s, "ancienne_depuis", "maison").as_deref(), Some("2026-02-12"));
        assert_eq!(b.changements, 1);
        let q = intentions(&s);
        assert_eq!(q.len(), 2);
        assert!(q[1].what.contains("(avant : "), "{}", q[1].what);
    }

    #[test]
    fn old_habit_fades_in_two_to_three_weeks() {
        let s = store();
        serie(&s, 0, 22, "maison");
        serie(&s, 22, 10, "restaurant");
        assert!(ligne(&s, "maison").0 >= 0.3);
        jouer(&s, 32, "restaurant");
        assert!(ligne(&s, "maison").0 < 0.3);
    }

    #[test]
    fn return_is_fast() {
        let s = store();
        serie(&s, 0, 22, "maison");
        serie(&s, 22, 12, "restaurant");
        let avant = intentions(&s).len();
        serie(&s, 34, 2, "maison");
        assert_eq!(etat(&s, "maison").0, "ancienne");
        assert!((ligne(&s, "maison").0 - 0.5230).abs() < 1e-3);
        assert!((ligne(&s, "restaurant").0 - 0.5812).abs() < 1e-3);
        let b = jouer(&s, 36, "maison");
        assert!((ligne(&s, "maison").0 - 0.6184).abs() < 1e-3);
        assert_eq!(etat(&s, "maison").0, "active");
        assert_eq!(etat(&s, "restaurant").0, "ancienne");
        assert_eq!(colonne(&s, "ancienne_depuis", "maison"), None);
        assert_eq!((b.retours, b.nees), (1, 0));
        assert_eq!(intentions(&s).len(), avant);
    }

    #[test]
    fn confirmation_sets_confidence_one() {
        let s = store();
        serie(&s, 0, 7, "maison");
        let id = etat(&s, "maison").1;
        let q: i64 = s.conn.query_row("SELECT question FROM habitudes WHERE id = ?", [id], |r| r.get(0)).unwrap();
        assert!(s.intention_set_state(q, IntentionState::Acceptee).unwrap());
        assert_eq!(colonne(&s, "CAST(confirmee AS TEXT)", "maison").as_deref(), Some("1"));
        assert_eq!(s.force("habitude", &id.to_string()).unwrap().unwrap().confiance, 1.0);
        // Confirmée : la confiance ne suit plus le comptage.
        jouer(&s, 7, "restaurant");
        assert_eq!(s.force("habitude", &id.to_string()).unwrap().unwrap().confiance, 1.0);
    }

    #[test]
    fn rejected_habit_never_asked_again() {
        let s = store();
        serie(&s, 0, 7, "maison");
        let id = etat(&s, "maison").1;
        let q: i64 = s.conn.query_row("SELECT question FROM habitudes WHERE id = ?", [id], |r| r.get(0)).unwrap();
        assert!(s.intention_set_state(q, IntentionState::Refusee).unwrap());
        assert!(s.force("habitude", &id.to_string()).unwrap().is_none());
        serie(&s, 7, 20, "maison");
        assert_eq!(intentions(&s).len(), 1);
        assert!(s.force("habitude", &id.to_string()).unwrap().is_none());
    }

    fn question(s: &MemoryStore, valeur: &str) -> i64 {
        s.conn.query_row("SELECT question FROM habitudes WHERE valeur = ?", [valeur], |r| r.get(0)).unwrap()
    }

    fn rappel_habitudes(s: &MemoryStore, n: u64) -> Vec<String> {
        let quand = ouvre(n).succ_opt().unwrap().and_hms_opt(8, 0, 0).unwrap();
        s.rappel(None, quand, 50).unwrap().into_iter().filter(|r| r.nature == "habitude").map(|r| r.texte).collect()
    }

    fn compte(s: &MemoryStore, sql: &str) -> i64 {
        s.conn.query_row(sql, [], |r| r.get(0)).unwrap()
    }

    #[test]
    fn answers_are_exclusive() {
        let s = store();
        serie(&s, 0, 7, "maison");
        let q = question(&s, "maison");
        s.intention_set_state(q, IntentionState::Refusee).unwrap();
        s.intention_set_state(q, IntentionState::Acceptee).unwrap();
        assert_eq!(colonne(&s, "confirmee || rejetee", "maison").as_deref(), Some("10"));
        assert_eq!(rappel_habitudes(&s, 6), ["habitude : repas du soir : maison"]);
        s.intention_set_state(q, IntentionState::Refusee).unwrap();
        assert_eq!(colonne(&s, "confirmee || rejetee", "maison").as_deref(), Some("01"));
        assert!(rappel_habitudes(&s, 6).is_empty());
    }

    #[test]
    fn day_part_must_match_the_day() {
        let s = store();
        let samedi = jour(5);
        let v = vec![
            obs("soir.ouvre", "maison"),
            obs("midi.weekend", "maison"),
            Observation { cadence: "hebdomadaire".into(), ..obs("matin.lundi", "maison") },
            Observation { cadence: "hebdomadaire".into(), ..obs("nuit.samedi", "maison") },
        ];
        let b = s.appliquer_observations(samedi, &lot("test", v), quand(5)).unwrap();
        assert_eq!((b.appliquees, b.ignorees), (2, 2));
    }

    #[test]
    fn deleted_question_unlinks_habit() {
        let s = store();
        let v = |_| vec![Observation { libelle: "repas = maison".into(), ..obs("soir.ouvre", "maison") }];
        for n in 0..7 {
            s.appliquer_observations(ouvre(n), &lot("test", v(n)), quand(n + 3)).unwrap();
        }
        let q = question(&s, "maison");
        // La clé effacée partage le début du texte de la question.
        s.fact_forget("habitude — repas").unwrap();
        assert_eq!(compte(&s, "SELECT COUNT(*) FROM intentions"), 0);
        assert_eq!(colonne(&s, "question", "maison"), None);
        // Identifiant réutilisé : la nouvelle intention ne répond pas à l'habitude.
        assert_eq!(s.intention_create("arroser", "", quand(30)).unwrap(), q);
        s.intention_set_state(q, IntentionState::Acceptee).unwrap();
        assert_eq!(colonne(&s, "CAST(confirmee AS TEXT)", "maison").as_deref(), Some("0"));
    }

    #[test]
    fn recall_shows_habit_with_hedge() {
        let s = store();
        serie(&s, 0, 7, "maison");
        assert_eq!(rappel_habitudes(&s, 6), ["je crois que habitude : repas du soir : maison"]);
        s.intention_set_state(question(&s, "maison"), IntentionState::Acceptee).unwrap();
        let r = rappel_habitudes(&s, 6);
        assert!(r[0].starts_with("habitude : "), "{r:?}");
    }

    #[test]
    fn recall_marks_old_habit() {
        let s = store();
        serie(&s, 0, 22, "maison");
        serie(&s, 22, 10, "restaurant");
        let r = rappel_habitudes(&s, 31);
        assert!(r.iter().any(|t| t.contains("maison (ancienne habitude depuis le 12/02/2026)")), "{r:?}");
        jouer(&s, 32, "restaurant");
        let r = rappel_habitudes(&s, 32);
        assert!(!r.iter().any(|t| t.contains("maison")), "{r:?}");
    }

    #[test]
    fn recall_skips_candidates_and_rejected() {
        let s = store();
        serie(&s, 0, 3, "maison");
        assert!(rappel_habitudes(&s, 2).is_empty());
        serie(&s, 3, 4, "maison");
        s.intention_set_state(question(&s, "maison"), IntentionState::Refusee).unwrap();
        assert!(rappel_habitudes(&s, 6).is_empty());
    }

    #[test]
    fn forget_drops_faded_deduced_habits() {
        let s = store();
        serie(&s, 0, 7, "maison");
        jouer(&s, 7, "bureau");
        serie(&s, 8, 30, "restaurant");
        let id = etat(&s, "maison").1;
        let q = question(&s, "maison");
        assert!(ligne(&s, "maison").0 < 0.05 && ligne(&s, "bureau").0 < 0.05);
        let n = s.oublier(quand(40)).unwrap();
        assert!(n >= 2, "{n}");
        assert_eq!(compte(&s, "SELECT COUNT(*) FROM habitudes WHERE valeur IN ('maison', 'bureau')"), 0);
        assert!(s.force("habitude", &id.to_string()).unwrap().is_none());
        assert_eq!(compte(&s, &format!("SELECT COUNT(*) FROM intentions WHERE id = {q}")), 0);
        // L'habitude active reste.
        assert_eq!(etat(&s, "restaurant").0, "active");
    }

    #[test]
    fn confirmed_habit_survives_forget() {
        let s = store();
        serie(&s, 0, 7, "maison");
        s.intention_set_state(question(&s, "maison"), IntentionState::Acceptee).unwrap();
        serie(&s, 7, 60, "restaurant");
        assert!(ligne(&s, "maison").0 < 0.05);
        s.oublier(quand(80)).unwrap();
        let id = etat(&s, "maison").1;
        assert!(s.force("habitude", &id.to_string()).unwrap().is_some());
    }

    #[test]
    fn habit_forget_is_real() {
        let s = store();
        serie(&s, 0, 7, "maison");
        let id = etat(&s, "maison").1;
        assert!(s.habitude_oublier(id).unwrap());
        assert_eq!(compte(&s, "SELECT COUNT(*) FROM habitudes"), 0);
        assert_eq!(compte(&s, "SELECT COUNT(*) FROM souvenirs WHERE nature = 'habitude'"), 0);
        assert_eq!(compte(&s, "SELECT COUNT(*) FROM intentions"), 0);
        assert!(!s.habitude_oublier(id).unwrap());
    }

    #[test]
    fn reopen_aligns_habit_memories() {
        let chemin = std::env::temp_dir().join(format!("memoire-habitudes-{}.db", std::process::id()));
        let _ = std::fs::remove_file(&chemin);
        let p = chemin.to_str().unwrap();
        let s = MemoryStore::open(p).unwrap();
        serie(&s, 0, 7, "maison");
        let id = etat(&s, "maison").1;
        s.conn.execute("DELETE FROM souvenirs", []).unwrap();
        s.conn.execute("INSERT INTO souvenirs (nature, ref, stabilite, dernier_rappel, importance, confiance) VALUES ('habitude', '999', 1, '2026-01-01 00:00:00', 0, 1)", []).unwrap();
        drop(s);
        let s = MemoryStore::open(p).unwrap();
        let _ = std::fs::remove_file(&chemin);
        assert!(s.force("habitude", &id.to_string()).unwrap().is_some());
        assert!(s.force("habitude", "999").unwrap().is_none());
    }
}
