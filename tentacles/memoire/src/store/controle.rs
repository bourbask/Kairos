//! Contrôle de la mémoire par l'utilisateur : ce qui est toujours retenu, ce
//! qui s'estompe, les habitudes apprises. Lire ne renforce jamais rien.

use super::*;

/// Taille maximale des listes rendues à l'application.
pub const CONTROLE_MAX: usize = 100;
/// Natures que l'utilisateur peut ancrer ; jamais une déduction ni une habitude.
const NATURES_ANCRABLES: [&str; 4] = ["fait", "journal", "conversation", "synthese"];

/// Souvenir tel que l'utilisateur le voit dans le contrôle.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct SouvenirVu {
    pub nature: String,
    #[serde(rename = "ref")]
    pub r#ref: String,
    pub texte: String,
    pub sensitive: bool,
    /// Jour où le souvenir s'est estompé.
    pub depuis: Option<NaiveDate>,
    /// Ce que la nuit en fera : `condensation` ou `oubli`.
    pub devenir: Option<String>,
    /// Ancre que l'utilisateur ne peut pas retirer.
    pub protege: bool,
}

/// Habitude née, telle que l'utilisateur peut la confirmer ou la rejeter.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct HabitudeVue {
    pub id: i64,
    pub libelle: String,
    pub etat: String,
    pub force: f64,
    pub confirmee: bool,
    pub rejetee: bool,
    pub estompee: bool,
    pub ancienne_depuis: Option<String>,
}

/// Issue d'une pose ou d'un retrait d'ancre.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ancrage {
    Fait,
    Inconnu,
    Plein,
    NonAncrable,
    Protege,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Controle {
    pub ancres_max: usize,
    pub ancres: Vec<SouvenirVu>,
    pub estompes: Vec<SouvenirVu>,
    pub estompes_total: usize,
    pub habitudes: Vec<HabitudeVue>,
}

impl MemoryStore {
    /// Ancres (importance puis récence), souvenirs estompés (les plus récemment
    /// estompés d'abord, `CONTROLE_MAX` au plus) et habitudes nées, rejetées
    /// comprises (actives puis anciennes, les plus fortes d'abord), sans rien renforcer.
    pub fn controle(&self, maintenant: NaiveDateTime) -> Result<Controle> {
        let annees: BTreeSet<String> = self
            .conn
            .prepare("SELECT CAST(id AS TEXT) FROM syntheses WHERE niveau = 'annee'")?
            .query_map([], |r| r.get(0))?
            .collect::<rusqlite::Result<_>>()?;
        let mut ancres = Vec::new();
        let mut estompes = Vec::new();
        for lu in self.lire_souvenirs("1")?.into_iter().filter(|l| l.nature != "habitude") {
            // Instant où la récupérabilité passe sous 1/2 : calculé, jamais stocké.
            let estompe_le = lu.dernier + chrono::Duration::milliseconds((lu.stabilite * LN_2 * 86_400_000.0) as i64);
            let estompe = !lu.ancre && !est_actif(recuperabilite(lu.dernier, lu.stabilite, maintenant, false));
            let vu = SouvenirVu {
                protege: lu.nature == "synthese" && annees.contains(&lu.r#ref),
                depuis: estompe.then(|| estompe_le.date()),
                nature: lu.nature,
                r#ref: lu.r#ref,
                texte: lu.texte,
                sensitive: lu.sensible,
                devenir: lu.devenir,
            };
            if lu.ancre {
                ancres.push((lu.importance, lu.dernier, vu));
            } else if estompe {
                estompes.push((estompe_le, vu));
            }
        }
        ancres.sort_by(|a, b| b.0.total_cmp(&a.0).then(b.1.cmp(&a.1)));
        estompes.sort_by(|a, b| b.0.cmp(&a.0));
        let estompes_total = estompes.len();
        let habitudes = self
            .conn
            .prepare(
                "SELECT id, libelle, etat, m, confirmee, rejetee, ancienne_depuis FROM habitudes
                 WHERE nee_le IS NOT NULL ORDER BY etat = 'ancienne', m DESC, id LIMIT ?",
            )?
            .query_map([CONTROLE_MAX], |r| {
                let force: f64 = r.get(3)?;
                Ok(HabitudeVue {
                    id: r.get(0)?,
                    libelle: r.get(1)?,
                    etat: r.get(2)?,
                    force,
                    confirmee: r.get(4)?,
                    rejetee: r.get(5)?,
                    estompee: force < SEUIL_ESTOMPE_HABITUDE,
                    ancienne_depuis: r.get(6)?,
                })
            })?
            // Ligne illisible : ignorée.
            .filter_map(rusqlite::Result::ok)
            .collect();
        Ok(Controle {
            ancres_max: ANCRES_MAX,
            ancres: ancres.into_iter().map(|a| a.2).collect(),
            estompes: estompes.into_iter().take(CONTROLE_MAX).map(|e| e.1).collect(),
            estompes_total,
            habitudes,
        })
    }

    /// Pose ou retire l'ancre demandée par l'utilisateur, dans la limite de
    /// `ANCRES_MAX`. Une ancre retirée repart de `maintenant` ; une ancre déjà
    /// posée (ou un retrait sans ancre) ne change rien.
    pub fn marquer_ancre(&self, nature: &str, r#ref: &str, ancre: bool, maintenant: NaiveDateTime) -> Result<Ancrage> {
        if !NATURES_ANCRABLES.contains(&nature) {
            return Ok(Ancrage::NonAncrable);
        }
        let tx = self.conn.unchecked_transaction()?;
        let Some(deja) = tx
            .query_row("SELECT ancre FROM souvenirs WHERE nature = ? AND ref = ?", [nature, r#ref], |r| r.get::<_, bool>(0))
            .optional()?
        else {
            return Ok(Ancrage::Inconnu);
        };
        if deja == ancre {
            return Ok(Ancrage::Fait);
        }
        if ancre {
            let n: i64 = tx.query_row("SELECT COUNT(*) FROM souvenirs WHERE ancre = 1", [], |r| r.get(0))?;
            if n >= ANCRES_MAX as i64 {
                return Ok(Ancrage::Plein);
            }
            tx.execute("UPDATE souvenirs SET ancre = 1 WHERE nature = ? AND ref = ?", [nature, r#ref])?;
        } else {
            let annee = nature == "synthese"
                && tx
                    .prepare("SELECT 1 FROM syntheses WHERE CAST(id AS TEXT) = ? AND niveau = 'annee'")?
                    .exists([r#ref])?;
            if annee {
                return Ok(Ancrage::Protege);
            }
            tx.execute(
                "UPDATE souvenirs SET ancre = 0, dernier_rappel = ? WHERE nature = ? AND ref = ?",
                params![ts(maintenant), nature, r#ref],
            )?;
        }
        tx.commit()?;
        Ok(Ancrage::Fait)
    }

    /// Ravive un souvenir (signal réel de l'utilisateur) ; `false` pour une
    /// habitude, dont la force ne suit pas la courbe d'oubli, ou un souvenir absent.
    pub fn raviver(&self, nature: &str, r#ref: &str, maintenant: NaiveDateTime) -> Result<bool> {
        if nature == "habitude" {
            return Ok(false);
        }
        renforcer_souvenir(&self.conn, nature, r#ref, maintenant)
    }

    /// `(nature, ref)` des souvenirs ancrés.
    pub fn ancres_app(&self) -> Result<BTreeSet<(String, String)>> {
        Ok(self
            .conn
            .prepare("SELECT nature, ref FROM souvenirs WHERE ancre = 1")?
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<rusqlite::Result<_>>()?)
    }
}

#[cfg(test)]
mod tests {
    use super::super::tests::{compte, episode_le, note, store, store_extrait, t, vieillir};
    use super::*;
    use chrono::Duration;

    fn jours(n: i64) -> NaiveDateTime {
        t() - Duration::days(n)
    }

    fn entree(s: &MemoryStore, texte: &str, sensitivity: Sensitivity) -> String {
        s.journal_write(&NewJournalEntry {
            source: "s".into(),
            kind: "note".into(),
            content: serde_json::json!({ "texte": texte }),
            sensitivity,
            provenance: "p".into(),
        })
        .unwrap()
        .to_string()
    }

    /// Déduction de `key = value`, rappelée pour la dernière fois à `quand`.
    fn deduction(s: &MemoryStore, key: &str, value: &str, quand: NaiveDateTime) -> String {
        s.conn.execute("INSERT INTO deductions VALUES (?, ?, 0.5, ?, '2026-01-01')", params![key, value, ts(quand)]).unwrap();
        let r = ref_deduction(key, value);
        s.conn
            .execute("INSERT INTO souvenirs VALUES ('deduction', ?, 30.0, ?, 0, 0.0, 0, 0.5)", params![r, ts(quand)])
            .unwrap();
        r
    }

    /// Habitude de libellé `libelle` ; née si `nee`.
    fn habitude(s: &MemoryStore, libelle: &str, m: f64, nee: bool, rejetee: bool) -> i64 {
        s.conn
            .execute(
                "INSERT INTO habitudes (source, quoi, contexte, valeur, libelle, cadence, m, positifs, etat, nee_le, rejetee)
                 VALUES ('test', 'repas.lieu', 'soir.ouvre', ?1, ?1, 'quotidien', ?2, 5, ?3, ?4, ?5)",
                params![libelle, m, if nee { "active" } else { "candidate" }, nee.then_some("2026-10-01"), rejetee],
            )
            .unwrap();
        s.conn.last_insert_rowid()
    }

    fn trouve<'a>(l: &'a [SouvenirVu], nature: &str, r#ref: &str) -> Option<&'a SouvenirVu> {
        l.iter().find(|x| x.nature == nature && x.r#ref == r#ref)
    }

    #[test]
    fn controle_lists_anchors_faded_and_habits() {
        let s = store();
        s.fact_set_at("cafe.prefere", "noir", "p", jours(200)).unwrap();
        s.ancrer("fait", "cafe.prefere", true).unwrap();
        s.fact_set_at("ville.exemple", "Exemple", "p", jours(200)).unwrap();
        s.fact_set_at("frais.exemple", "Exemple", "p", t()).unwrap();
        let ded = deduction(&s, "loisir", "voile", jours(200));
        let j = note(&s, "Exemple de note");
        vieillir(&s, "journal", &j.to_string(), jours(10));
        let secret = entree(&s, "Exemple stratégique", Sensitivity::Strategique);
        vieillir(&s, "journal", &secret, jours(11));
        let active = habitude(&s, "repas : maison", 0.6, true, false);
        habitude(&s, "repas : restaurant", 0.2, false, false);
        let rejetee = habitude(&s, "repas : bureau", 0.4, true, true);

        let c = s.controle(t()).unwrap();
        assert_eq!(c.ancres_max, 20);
        assert!(trouve(&c.ancres, "fait", "cafe.prefere").is_some());
        assert!(trouve(&c.estompes, "fait", "cafe.prefere").is_none());
        let v = trouve(&c.estompes, "fait", "ville.exemple").expect("estompé");
        let attendu = (jours(200) + Duration::milliseconds((90.0 * LN_2 * 86_400_000.0) as i64)).date();
        assert_eq!(v.depuis, Some(attendu));
        assert_eq!(v.devenir, None);
        for l in [&c.ancres, &c.estompes] {
            assert!(trouve(l, "fait", "frais.exemple").is_none());
        }
        let d = trouve(&c.estompes, "deduction", &ded).expect("déduction");
        assert!(d.texte.starts_with("je crois que "), "{}", d.texte);
        assert_eq!(d.devenir.as_deref(), Some("oubli"));
        let e = trouve(&c.estompes, "journal", &j.to_string()).expect("journal");
        assert_eq!(e.devenir.as_deref(), Some("condensation"));
        assert!(!e.sensitive);
        assert!(trouve(&c.estompes, "journal", &secret).expect("stratégique").sensitive);
        assert_eq!(c.estompes_total, c.estompes.len());

        assert_eq!(c.habitudes.len(), 2);
        let h = c.habitudes.iter().find(|h| h.id == active).unwrap();
        assert_eq!((h.etat.as_str(), h.force, h.rejetee), ("active", 0.6, false));
        assert!(c.habitudes.iter().any(|h| h.id == rejetee && h.rejetee));
        assert!(!c.habitudes.iter().any(|h| h.libelle == "repas : restaurant"));
    }

    #[test]
    fn controle_never_reinforces() {
        let s = store();
        s.fact_set_at("cafe.prefere", "noir", "p", jours(200)).unwrap();
        s.ancrer("fait", "cafe.prefere", true).unwrap();
        let j = note(&s, "Exemple").to_string();
        vieillir(&s, "journal", &j, jours(10));
        let avant = (s.force("fait", "cafe.prefere").unwrap(), s.force("journal", &j).unwrap());
        s.controle(t()).unwrap();
        s.controle(t() + Duration::days(1)).unwrap();
        s.ancres_app().unwrap();
        assert_eq!((s.force("fait", "cafe.prefere").unwrap(), s.force("journal", &j).unwrap()), avant);
    }

    #[test]
    fn controle_caps_faded_list() {
        let s = store();
        for i in 0..120 {
            s.fact_set_at(&format!("f{i}"), "Exemple", "p", jours(200 + i)).unwrap();
        }
        let c = s.controle(t()).unwrap();
        assert_eq!(c.estompes.len(), CONTROLE_MAX);
        assert_eq!(c.estompes_total, 120);
        assert_eq!(c.estompes[0].r#ref, "f0");
    }

    #[test]
    fn year_synthesis_is_protected_anchor() {
        let s = store();
        let id = s.synthese_creer("annee", "2025", "Exemple d'année", t()).unwrap().to_string();
        let c = s.controle(t()).unwrap();
        assert!(trouve(&c.ancres, "synthese", &id).expect("ancre").protege);
        assert!(s.ancres_app().unwrap().contains(&("synthese".to_string(), id)));
    }

    fn ancre(s: &MemoryStore, nature: &str, r#ref: &str, ancre: bool) -> Ancrage {
        s.marquer_ancre(nature, r#ref, ancre, t()).unwrap()
    }

    #[test]
    fn anchor_cap_is_twenty() {
        let s = store();
        for i in 0..21 {
            s.fact_set_at(&format!("f{i}"), "Exemple", "p", t()).unwrap();
        }
        for i in 0..20 {
            assert_eq!(ancre(&s, "fait", &format!("f{i}"), true), Ancrage::Fait);
        }
        assert_eq!(ancre(&s, "fait", "f20", true), Ancrage::Plein);
        assert!(!s.force("fait", "f20").unwrap().unwrap().ancre);
        // Reposée : déjà comptée, rien ne change.
        assert_eq!(ancre(&s, "fait", "f3", true), Ancrage::Fait);
        assert_eq!(ancre(&s, "fait", "f3", false), Ancrage::Fait);
        assert_eq!(ancre(&s, "fait", "f20", true), Ancrage::Fait);
        assert!(s.force("fait", "f20").unwrap().unwrap().ancre);

        // Une synthèse d'année compte parmi les vingt.
        let s = store();
        s.synthese_creer("annee", "2025", "Exemple d'année", t()).unwrap();
        for i in 0..20 {
            s.fact_set_at(&format!("f{i}"), "Exemple", "p", t()).unwrap();
        }
        for i in 0..19 {
            assert_eq!(ancre(&s, "fait", &format!("f{i}"), true), Ancrage::Fait);
        }
        assert_eq!(ancre(&s, "fait", "f19", true), Ancrage::Plein);
    }

    #[test]
    fn unanchor_restarts_from_now() {
        let s = store();
        s.fact_set_at("cafe.prefere", "noir", "p", jours(400)).unwrap();
        assert_eq!(ancre(&s, "fait", "cafe.prefere", true), Ancrage::Fait);
        let avant = s.force("fait", "cafe.prefere").unwrap().unwrap();
        assert_eq!(ancre(&s, "fait", "cafe.prefere", false), Ancrage::Fait);
        let apres = s.force("fait", "cafe.prefere").unwrap().unwrap();
        assert!(!apres.ancre);
        assert_eq!(apres.dernier_rappel, t());
        assert_eq!((apres.stabilite, apres.rappels), (avant.stabilite, avant.rappels));
        assert!(trouve(&s.controle(t()).unwrap().estompes, "fait", "cafe.prefere").is_none());
    }

    #[test]
    fn deduction_and_habit_not_anchorable() {
        let s = store();
        let ded = deduction(&s, "loisir", "voile", t());
        let h = habitude(&s, "repas : maison", 0.6, true, false).to_string();
        s.conn
            .execute("INSERT INTO souvenirs VALUES ('habitude', ?, 14.0, ?, 0, 0.0, 0, 0.6)", params![h, ts(t())])
            .unwrap();
        assert_eq!(ancre(&s, "deduction", &ded, true), Ancrage::NonAncrable);
        assert_eq!(ancre(&s, "habitude", &h, true), Ancrage::NonAncrable);
        assert!(!s.force("deduction", &ded).unwrap().unwrap().ancre);
        let annee = s.synthese_creer("annee", "2025", "Exemple d'année", t()).unwrap().to_string();
        assert_eq!(ancre(&s, "synthese", &annee, false), Ancrage::Protege);
        assert!(s.force("synthese", &annee).unwrap().unwrap().ancre);
    }

    #[test]
    fn revive_reinforces_with_spacing() {
        let s = store();
        s.fact_set_at("ville.exemple", "Exemple", "p", jours(90)).unwrap();
        let avant = s.force("fait", "ville.exemple").unwrap().unwrap();
        assert_eq!(avant.stabilite, 90.0);
        assert!(trouve(&s.controle(t()).unwrap().estompes, "fait", "ville.exemple").is_some());
        assert!(s.raviver("fait", "ville.exemple", t()).unwrap());
        let f = s.force("fait", "ville.exemple").unwrap().unwrap();
        assert!((f.stabilite - 90.0 * (1.0 + 2.0 * (1.0 - (-1f64).exp()))).abs() < 1e-9, "{}", f.stabilite);
        assert_eq!((f.rappels, f.dernier_rappel), (avant.rappels + 1, t()));
        assert!(trouve(&s.controle(t()).unwrap().estompes, "fait", "ville.exemple").is_none());
    }

    #[test]
    fn revive_or_anchor_erased_is_refused() {
        let s = store();
        s.fact_set_at("ville.exemple", "Exemple", "p", jours(90)).unwrap();
        s.fact_forget("ville.exemple").unwrap();
        assert!(!s.raviver("fait", "ville.exemple", t()).unwrap());
        assert_eq!(ancre(&s, "fait", "ville.exemple", true), Ancrage::Inconnu);
        assert_eq!(compte(&s, "SELECT COUNT(*) FROM souvenirs"), 0);
        let h = habitude(&s, "repas : maison", 0.6, true, false).to_string();
        s.conn
            .execute("INSERT INTO souvenirs VALUES ('habitude', ?, 14.0, ?, 0, 0.0, 0, 0.6)", params![h, ts(jours(30))])
            .unwrap();
        assert!(!s.raviver("habitude", &h, t()).unwrap());
        assert_eq!(s.force("habitude", &h).unwrap().unwrap().rappels, 0);
    }

    #[test]
    fn revive_blocks_pending_condensation() {
        let s = store_extrait();
        let a = episode_le(&s, "Exemple de semis", t());
        let b = episode_le(&s, "Exemple d'arrosage", t() + Duration::days(1));
        let m = t() + Duration::days(19);
        let lot = s.condensables(m, 10).unwrap().remove(0);
        assert!(s.raviver("journal", &b.to_string(), m).unwrap());
        assert_eq!(s.condenser("semaine", &lot.periode, "Exemple de synthèse", &lot.empreinte, m).unwrap(), None);
        assert_eq!(compte(&s, &format!("SELECT COUNT(*) FROM journal WHERE id IN ({a}, {b})")), 2);
    }

    #[test]
    fn revived_deduction_survives_forget() {
        let s = store();
        let ded = deduction(&s, "loisir", "voile", jours(120));
        assert!(s.raviver("deduction", &ded, t()).unwrap());
        s.oublier(t()).unwrap();
        assert!(s.force("deduction", &ded).unwrap().is_some());
    }
}
