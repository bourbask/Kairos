//! Partage d'un site par liens ouvrables sur quelques appareils : création, liste et révocation pour
//! l'application ; échange jeton → session et contrôle de session pour le site.

pub mod public;
pub mod store;
pub mod tools;

/// Maintenant, en secondes Unix.
pub fn now() -> store::Ts {
    chrono::Utc::now().timestamp()
}
