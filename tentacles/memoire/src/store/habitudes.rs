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
const JOURS_QUOTIDIEN: [&str; 2] = ["ouvre", "weekend"];
const JOURS_HEBDO: [&str; 7] = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"];

fn code_valide(s: &str) -> bool {
    (1..=CODE_MAX).contains(&s.chars().count())
        && s.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || "._-".contains(c))
}

/// Taux d'apprentissage d'une observation, `None` si elle est invalide.
fn taux(o: &Observation) -> Option<f64> {
    let (a, jours): (f64, &[&str]) = match o.cadence.as_str() {
        "quotidien" => (A_QUOTIDIEN, &JOURS_QUOTIDIEN),
        "hebdomadaire" => (A_HEBDO, &JOURS_HEBDO),
        _ => return None,
    };
    let (creneau, type_jour) = o.contexte.rsplit_once('.')?;
    let valide = code_valide(&o.quoi)
        && code_valide(&o.contexte)
        && code_valide(&o.valeur)
        && !creneau.is_empty()
        && jours.contains(&type_jour)
        && (1..=LIBELLE_MAX).contains(&o.libelle.chars().count())
        && !o.libelle.chars().any(char::is_control);
    valide.then_some(a)
}

/// Une occurrence du contexte de `o` : la valeur observée tend vers 1, les
/// autres valeurs connues du contexte vers 0.
fn compter(c: &Connection, source: &str, o: &Observation, a: f64) -> Result<()> {
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
    Ok(())
}

impl MemoryStore {
    /// Compte les observations de `jour`, source par source, en une transaction.
    /// Un jour déjà appliqué pour une source ne compte plus. `complet` dit si
    /// toutes les sources encore attendues ont répondu.
    pub fn appliquer_observations(
        &self,
        jour: NaiveDate,
        sources: &BTreeMap<String, Vec<Observation>>,
        maintenant: NaiveDateTime,
    ) -> Result<BilanHabitudes> {
        let _ = maintenant;
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
                match taux(o) {
                    Some(a) if i < OBSERVATIONS_MAX && vus.insert((o.quoi.as_str(), o.contexte.as_str())) => {
                        compter(&tx, source, o, a)?;
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

    fn jouer(s: &MemoryStore, n: u64, valeur: &str) -> BilanHabitudes {
        s.appliquer_observations(jour(n), &lot("test", vec![obs("soir.ouvre", valeur)]), quand(n)).unwrap()
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
            let o = Observation { cadence: "hebdomadaire".into(), ..obs("soir.vendredi", "maison") };
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
}
