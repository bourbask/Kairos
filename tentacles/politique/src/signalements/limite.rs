//! Limite horaire par session, sans trace identifiante : la clé en mémoire est
//! un HMAC de la session sous un secret qui change chaque jour UTC.

use std::collections::HashMap;
use std::sync::Mutex;

use hmac::{Hmac, Mac};
use sha2::Sha256;

const HEURE: i64 = 3600;
const JOUR: i64 = 86_400;

type Cle = [u8; 32];

struct Etat {
    jour: i64,
    secret: [u8; 32],
    // Gardé pour que les envois d'avant minuit comptent encore dans l'heure glissante.
    veille: Option<[u8; 32]>,
    envois: HashMap<Cle, Vec<i64>>,
}

pub struct Limiteur {
    max: u32,
    etat: Mutex<Etat>,
}

fn secret_aleatoire() -> [u8; 32] {
    let mut s = [0u8; 32];
    getrandom::fill(&mut s).expect("source d'aléa système indisponible");
    s
}

fn empreinte(secret: &[u8; 32], session: &str) -> Cle {
    let mut m = Hmac::<Sha256>::new_from_slice(secret).expect("clé de taille quelconque");
    m.update(session.as_bytes());
    m.finalize().into_bytes().into()
}

impl Limiteur {
    pub fn new(max_par_heure: u32) -> Limiteur {
        Self::avec_secret(max_par_heure, secret_aleatoire())
    }

    // Secret initial injectable pour des tests déterministes.
    fn avec_secret(max: u32, secret: [u8; 32]) -> Limiteur {
        Limiteur {
            max,
            etat: Mutex::new(Etat { jour: i64::MIN, secret, veille: None, envois: HashMap::new() }),
        }
    }

    /// Applique rotation du secret et purge ; renvoie les clés (jour, veille).
    fn preparer(&self, session: &str, maintenant: i64) -> (std::sync::MutexGuard<'_, Etat>, Cle, Option<Cle>) {
        let mut e = self.etat.lock().unwrap_or_else(|p| p.into_inner());
        let jour = maintenant.div_euclid(JOUR);
        if e.jour == i64::MIN {
            e.jour = jour;
        } else if jour > e.jour {
            e.veille = (jour == e.jour + 1).then_some(e.secret);
            e.secret = secret_aleatoire();
            e.jour = jour;
        }
        e.envois.retain(|_, v| {
            v.retain(|&t| t > maintenant - HEURE);
            !v.is_empty()
        });
        let cle = empreinte(&e.secret, session);
        let cle_veille = e.veille.map(|s| empreinte(&s, session));
        (e, cle, cle_veille)
    }

    /// `Ok(restant)` ou `Err(minutes d'attente)`.
    pub fn verifier(&self, session_ou_cle: &str, maintenant: i64) -> Result<u32, u32> {
        let (e, cle, veille) = self.preparer(session_ou_cle, maintenant);
        let mut ts: Vec<i64> = e.envois.get(&cle).into_iter().flatten().copied().collect();
        if let Some(v) = veille {
            ts.extend(e.envois.get(&v).into_iter().flatten().copied());
        }
        let n = ts.len() as u32;
        if n < self.max {
            return Ok(self.max - n);
        }
        // Le (n - max + 1)-ième plus ancien doit sortir pour libérer une place.
        ts.sort_unstable();
        let sortie = ts[(n - self.max) as usize] + HEURE;
        Err(((sortie - maintenant).max(1) as u32).div_ceil(60))
    }

    pub fn noter(&self, session_ou_cle: &str, maintenant: i64) {
        let (mut e, cle, _) = self.preparer(session_ou_cle, maintenant);
        e.envois.entry(cle).or_default().push(maintenant);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const H: i64 = 3600;
    const JOUR: i64 = 86_400;
    const T0: i64 = 20_000 * JOUR + 10 * H; // 10 h UTC

    fn lim() -> Limiteur {
        Limiteur::avec_secret(10, [1; 32])
    }

    #[test]
    fn dix_puis_refus() {
        let l = lim();
        for i in 0..10 {
            assert_eq!(l.verifier("s", T0 + i), Ok(10 - i as u32));
            l.noter("s", T0 + i);
        }
        assert!(l.verifier("s", T0 + 10).is_err());
    }

    #[test]
    fn attente_calculee() {
        let l = lim();
        for i in 0..10 {
            l.noter("s", T0 + i * 60);
        }
        // le plus ancien (T0) sort à T0+1h ; à T0+20 min restent 40 min
        assert_eq!(l.verifier("s", T0 + 20 * 60), Err(40));
    }

    #[test]
    fn sessions_independantes() {
        let l = lim();
        for _ in 0..10 {
            l.noter("a", T0);
        }
        assert!(l.verifier("a", T0).is_err());
        assert_eq!(l.verifier("b", T0), Ok(10));
    }

    #[test]
    fn rotation_ne_reinitialise_pas() {
        let l = lim();
        let t = 20_000 * JOUR + 23 * H + 50 * 60;
        for _ in 0..10 {
            l.noter("s", t);
        }
        assert!(l.verifier("s", t + 15 * 60).is_err());
        l.noter("s", t + 15 * 60); // nouveau jour, nouvelle clé
        assert!(l.verifier("s", t + 15 * 60).is_err());
    }

    #[test]
    fn purge_apres_une_heure() {
        let l = lim();
        for _ in 0..10 {
            l.noter("s", T0);
        }
        assert_eq!(l.verifier("s", T0 + H), Ok(10));
        assert!(l.etat.lock().unwrap().envois.is_empty());
    }

    #[test]
    fn pas_de_session_en_clair() {
        let l = lim();
        l.noter("session-secrete", T0);
        let e = l.etat.lock().unwrap();
        for cle in e.envois.keys() {
            assert!(!cle.windows(15).any(|w| w == b"session-secrete"));
        }
        assert_eq!(e.envois.len(), 1);
    }
}
