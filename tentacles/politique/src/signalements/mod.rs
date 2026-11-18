pub mod validation;
pub mod store;
pub mod limite;
pub mod public;

/// Ouvre la base ; en cas d'échec, journalise le type d'erreur et désactive les signalements.
pub fn ouvrir_optionnel(chemin: impl AsRef<std::path::Path>) -> Option<store::Store> {
    match store::Store::open(chemin) {
        Ok(s) => Some(s),
        Err(e) => {
            let genre = e.downcast_ref::<rusqlite::Error>().and_then(|r| r.sqlite_error_code());
            tracing::error!(?genre, "base des signalements indisponible : signalements désactivés");
            None
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn base_illisible_desactive() {
        assert!(super::ouvrir_optionnel("/proc/inexistant/x.db").is_none());
    }

    #[test]
    fn base_valide_ouverte() {
        let d = std::env::temp_dir().join(format!("sig-opt-{}.db", std::process::id()));
        assert!(super::ouvrir_optionnel(&d).is_some());
        let _ = std::fs::remove_file(&d);
    }
}
