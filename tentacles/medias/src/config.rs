//! Services de la stack, déclarés par l'environnement. Chacun est facultatif.

use chrono_tz::Tz;

use crate::http::{Auth, Opt, Svc, client};
use crate::render::tz_or_paris;
use crate::services::Qbit;

pub struct Stack {
    pub plex: Opt,
    pub sonarr: Opt,
    pub radarr: Opt,
    pub bazarr: Opt,
    pub seerr: Opt,
    pub prowlarr: Opt,
    pub nzbget: Opt,
    pub tdarr: Opt,
    pub qbit: Option<Qbit>,
    pub tz: Tz,
    /// Seuil d'espace libre (Go) sous lequel le disque est signalé.
    pub disk_min_gb: i64,
}

fn valid_url(u: &str) -> bool {
    reqwest::Url::parse(u).is_ok_and(|u| matches!(u.scheme(), "http" | "https") && u.has_host())
}

/// Lecture d'une variable : absente ou vide → `None`.
pub type Lookup<'a> = &'a dyn Fn(&str) -> Option<String>;

impl Stack {
    pub fn from_env() -> anyhow::Result<Stack> {
        let get = |k: &str| std::env::var(k).ok().filter(|v| !v.is_empty());
        Stack::from_lookup(&get)
    }

    /// Un service est déclaré par `MEDIAS_<NOM>_URL` ; ses secrets deviennent alors obligatoires.
    pub fn from_lookup(get: Lookup) -> anyhow::Result<Stack> {
        let http = client();
        let req = |k: String| get(&k).ok_or_else(|| anyhow::anyhow!("variable {k} absente ou vide"));
        let url = |name: &str| -> anyhow::Result<Option<String>> {
            match get(&format!("MEDIAS_{name}_URL")) {
                None => Ok(None),
                Some(u) if valid_url(&u) => Ok(Some(u)),
                Some(_) => anyhow::bail!("variable MEDIAS_{name}_URL invalide"),
            }
        };
        let keyed = |name: &'static str, label: &'static str, header: &'static str| -> anyhow::Result<Opt> {
            Ok(match url(name)? {
                None => None,
                Some(u) => {
                    Some(Svc::new(label, &u, Auth::Header(header, req(format!("MEDIAS_{name}_KEY"))?), http.clone()))
                }
            })
        };
        let basic = |name: &'static str,
                     label: &'static str|
         -> anyhow::Result<Option<(String, String, String, &'static str)>> {
            Ok(match url(name)? {
                None => None,
                Some(u) => Some((u, req(format!("MEDIAS_{name}_USER"))?, req(format!("MEDIAS_{name}_PASS"))?, label)),
            })
        };
        Ok(Stack {
            plex: keyed("PLEX", "plex", "X-Plex-Token")?,
            sonarr: keyed("SONARR", "sonarr", "X-Api-Key")?,
            radarr: keyed("RADARR", "radarr", "X-Api-Key")?,
            bazarr: keyed("BAZARR", "bazarr", "X-API-KEY")?,
            seerr: keyed("SEERR", "seerr", "X-Api-Key")?,
            prowlarr: keyed("PROWLARR", "prowlarr", "X-Api-Key")?,
            nzbget: basic("NZBGET", "nzbget")?.map(|(u, us, p, l)| Svc::new(l, &u, Auth::Basic(us, p), http.clone())),
            qbit: basic("QBIT", "qbittorrent")?
                .map(|(u, user, pass, l)| Qbit::new(Svc::new(l, &u, Auth::None, http.clone()), user, pass)),
            tdarr: url("TDARR")?.map(|u| Svc::new("tdarr", &u, Auth::None, http.clone())),
            tz: tz_or_paris(get("MEDIAS_TZ").as_deref()),
            disk_min_gb: get("MEDIAS_DISK_MIN_GB").and_then(|v| v.parse().ok()).unwrap_or(50),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn stack(vars: &[(&str, &str)]) -> anyhow::Result<Stack> {
        let m: HashMap<String, String> = vars.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        let get = move |k: &str| m.get(k).cloned().filter(|v| !v.is_empty());
        Stack::from_lookup(&get)
    }

    #[test]
    fn empty_env_means_nothing_configured() {
        let s = stack(&[]).unwrap();
        assert!(s.plex.is_none() && s.sonarr.is_none() && s.qbit.is_none() && s.tdarr.is_none());
        assert_eq!(s.disk_min_gb, 50);
        assert_eq!(s.tz, chrono_tz::Europe::Paris);
    }

    #[test]
    fn declared_service_requires_its_secret() {
        assert!(stack(&[("MEDIAS_SONARR_URL", "http://sonarr:8989")]).is_err());
        assert!(stack(&[("MEDIAS_QBIT_URL", "http://q:1"), ("MEDIAS_QBIT_USER", "u")]).is_err());
        let s = stack(&[("MEDIAS_SONARR_URL", "http://sonarr:8989"), ("MEDIAS_SONARR_KEY", "k")]).unwrap();
        assert!(s.sonarr.is_some());
    }

    #[test]
    fn invalid_url_is_refused() {
        assert!(stack(&[("MEDIAS_TDARR_URL", "ftp://x")]).is_err());
        assert!(stack(&[("MEDIAS_TDARR_URL", "pas une url")]).is_err());
    }
}
