//! Résumé de la dernière passe de veille des sources, pour le brief du matin.

use std::path::Path;

/// Fichiers lus dans le dossier de veille.
const HISTORIQUE: &str = "historique.txt";
pub const RAPPORT: &str = "dernier-rapport.md";

/// Comptes d'une passe : adresses, mortes, modifiées, redirigées, inaccessibles, suspectes.
#[derive(Debug, PartialEq, Eq)]
pub struct Passe {
    pub date: String,
    pub comptes: [u32; 6],
}

impl Passe {
    /// Une source à revoir : morte, modifiée ou redirigée.
    pub fn a_revoir(&self) -> bool {
        self.comptes[1..4].iter().any(|n| *n > 0)
    }
}

/// Ligne `AAAA-MM-JJ · N adresses · mortes X · …` ; tout écart de forme → `None`.
pub fn analyser(ligne: &str) -> Option<Passe> {
    let mut champs = ligne.split('·').map(str::trim);
    let date = champs.next()?;
    let b = date.as_bytes();
    if b.len() != 10 || !b.iter().enumerate().all(|(i, c)| if i == 4 || i == 7 { *c == b'-' } else { c.is_ascii_digit() }) {
        return None;
    }
    let mut comptes = [0u32; 6];
    for (i, mot) in ["adresses", "mortes", "modifiées", "redirigées", "inaccessibles", "suspectes"].iter().enumerate() {
        let c = champs.next()?;
        // « N adresses » (nombre d'abord) ou « mortes X » (nombre ensuite).
        let n = if i == 0 { c.strip_suffix(mot)? } else { c.strip_prefix(mot)? };
        comptes[i] = n.trim().parse().ok()?;
    }
    champs.next().is_none().then(|| Passe { date: date.to_string(), comptes })
}

/// Section de brief ; vide si le dossier est absent, illisible ou la dernière ligne inintelligible.
pub fn section(dossier: &Path) -> String {
    let Ok(texte) = std::fs::read_to_string(dossier.join(HISTORIQUE)) else { return String::new() };
    let Some(p) = texte.lines().rev().find(|l| !l.trim().is_empty()).and_then(analyser) else { return String::new() };
    let [n, mortes, modifiees, redirigees, inaccessibles, suspectes] = p.comptes;
    let mut out = format!(
        "Veille des sources ({}) : {n} adresses · mortes {mortes} · modifiées {modifiees} · redirigées {redirigees} · inaccessibles {inaccessibles} · suspectes {suspectes}.",
        p.date
    );
    if p.a_revoir() {
        out.push_str(&format!("\nSources à revoir : consulter {RAPPORT}."));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const L: &str = "2026-11-22 · 120 adresses · mortes 2 · modifiées 0 · redirigées 1 · inaccessibles 3 · suspectes 0";

    #[test]
    fn analyse_une_ligne() {
        let p = analyser(L).unwrap();
        assert_eq!(p.date, "2026-11-22");
        assert_eq!(p.comptes, [120, 2, 0, 1, 3, 0]);
        assert!(p.a_revoir());
    }

    #[test]
    fn rejette_les_lignes_mal_formees() {
        assert!(analyser("").is_none());
        assert!(analyser("n'importe quoi").is_none());
        assert!(analyser("2026-11-22 · 120 adresses").is_none());
        assert!(analyser(&format!("{L} · extra")).is_none());
    }

    #[test]
    fn dossier_absent_ou_vide() {
        assert_eq!(section(Path::new("/inexistant-veille")), "");
        let d = std::env::temp_dir().join(format!("veille-vide-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        assert_eq!(section(&d), "");
        std::fs::write(d.join(HISTORIQUE), "illisible\n").unwrap();
        assert_eq!(section(&d), "");
        std::fs::remove_dir_all(&d).unwrap();
    }

    #[test]
    fn signal_selon_les_comptes() {
        let d = std::env::temp_dir().join(format!("veille-signal-{}", std::process::id()));
        std::fs::create_dir_all(&d).unwrap();
        let zero = "2026-11-29 · 120 adresses · mortes 0 · modifiées 0 · redirigées 0 · inaccessibles 2 · suspectes 1";
        std::fs::write(d.join(HISTORIQUE), format!("{L}\n{zero}\n\n")).unwrap();
        let s = section(&d);
        assert!(s.contains("2026-11-29") && !s.contains("à revoir"));
        std::fs::write(d.join(HISTORIQUE), format!("{zero}\n{L}\n")).unwrap();
        let s = section(&d);
        assert!(s.contains("Sources à revoir") && s.contains(RAPPORT));
        std::fs::remove_dir_all(&d).unwrap();
    }
}
