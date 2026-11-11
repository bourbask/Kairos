//! Outils `app_*` du partage : réservés à l'application, jamais proposés au moteur.

use std::sync::Arc;

use chrono::{DateTime, SecondsFormat};
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::store::{Share, Store, Ts};

type Out = Result<String, String>;

/// Longueur maximale d'un libellé, en caractères.
pub const LABEL_MAX: usize = 40;

#[derive(Clone)]
pub struct ShareServer {
    store: Arc<Store>,
    /// Adresse du site, sans `/` final.
    site: String,
    link_ttl: Ts,
    now: fn() -> Ts,
    tool_router: ToolRouter<Self>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct CreateArgs {
    /// Libellé court du partage (qui le reçoit, ou pourquoi).
    libelle: String,
    /// Lien d'accès du propriétaire : remplace ses liens précédents jamais ouverts.
    #[serde(default)]
    proprietaire: bool,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct IdArgs {
    /// Identifiant du partage.
    id: i64,
}

fn rfc(t: Ts) -> String {
    DateTime::from_timestamp(t, 0).unwrap_or_default().to_rfc3339_opts(SecondsFormat::Secs, true)
}

fn json_out(v: Value) -> Out {
    serde_json::to_string(&v).map_err(|x| x.to_string())
}

/// Libellé nettoyé : caractères de contrôle retirés, espaces resserrés, longueur bornée.
pub fn clean_label(raw: &str) -> Result<String, String> {
    let s: String = raw.chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
    let s = s.split_whitespace().collect::<Vec<_>>().join(" ");
    if s.is_empty() {
        return Err("Libellé vide.".into());
    }
    if s.chars().count() > LABEL_MAX {
        return Err(format!("Libellé trop long ({LABEL_MAX} caractères au plus)."));
    }
    Ok(s)
}

fn share_json(s: &Share, now: Ts) -> Value {
    json!({
        "id": s.id,
        "libelle": s.libelle,
        "cree_le": rfc(s.cree_le),
        "expire_le": rfc(s.expire_le),
        "ouvert_le": s.ouvert_le.map(rfc),
        "actif": s.actif(now),
    })
}

impl ShareServer {
    pub fn new(store: Arc<Store>, site: &str, link_ttl: Ts, now: fn() -> Ts) -> Self {
        Self { store, site: site.trim_end_matches('/').to_string(), link_ttl, now, tool_router: Self::tool_router() }
    }
}

#[tool_router]
impl ShareServer {
    #[tool(description = "Créer un lien de partage à usage unique (application).")]
    fn app_partage_creer(&self, Parameters(a): Parameters<CreateArgs>) -> Out {
        let label = clean_label(&a.libelle)?;
        let create = if a.proprietaire { Store::create_owner } else { Store::create };
        let (_, token, expire) = create(&self.store, &label, (self.now)(), self.link_ttl).map_err(|e| e.to_string())?;
        // Jeton dans le fragment : jamais envoyé au serveur, donc absent des journaux.
        json_out(json!({"lien": format!("{}/entrer#k={token}", self.site), "expire_le": rfc(expire)}))
    }

    #[tool(description = "Lister les partages, les plus récents d'abord (application).")]
    fn app_partage_liste(&self) -> Out {
        let now = (self.now)();
        let list = self.store.list().map_err(|e| e.to_string())?;
        json_out(Value::Array(list.iter().filter(|s| s.visible(now)).map(|s| share_json(s, now)).collect()))
    }

    #[tool(description = "Révoquer un partage et la session qui en est issue (application).")]
    fn app_partage_revoquer(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        let s = self.store.get(a.id).map_err(|e| e.to_string())?.ok_or("Partage inconnu.")?;
        if !self.store.revoke(a.id, (self.now)()).map_err(|e| e.to_string())? {
            return json_out(json!({"message": format!("Déjà révoqué : {}.", s.libelle)}));
        }
        json_out(json!({"message": format!("Accès révoqué : {}.", s.libelle)}))
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for ShareServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn server() -> ShareServer {
        ShareServer::new(Arc::new(Store::memory().unwrap()), "https://site.example.org/", 72 * 3600, || 1_000)
    }

    fn create(s: &ShareServer, label: &str) -> Value {
        serde_json::from_str(&s.app_partage_creer(Parameters(CreateArgs { libelle: label.into(), proprietaire: false })).unwrap()).unwrap()
    }

    #[test]
    fn tool_names_are_app_tools() {
        let names: Vec<String> = server().tool_router.list_all().into_iter().map(|t| t.name.to_string()).collect();
        assert_eq!(names.len(), 3);
        assert!(names.iter().all(|n| n.starts_with("app_")), "{names:?}");
    }

    #[test]
    fn create_gives_fragment_link_and_deadline() {
        let s = server();
        let v = create(&s, "  Partage\n du 01/02 ");
        let link = v["lien"].as_str().unwrap();
        let token = link.strip_prefix("https://site.example.org/entrer#k=").unwrap();
        assert!(crate::store::well_formed(token));
        assert_eq!(v["expire_le"], rfc(1_000 + 72 * 3600));
        assert_eq!(s.store.list().unwrap()[0].libelle, "Partage du 01/02");
    }

    #[test]
    fn label_rules() {
        assert!(clean_label(" \t ").is_err());
        assert!(clean_label(&"x".repeat(LABEL_MAX + 1)).is_err());
        assert_eq!(clean_label(&"é".repeat(LABEL_MAX)).unwrap().chars().count(), LABEL_MAX);
    }

    #[test]
    fn owner_flag_replaces_and_hides_previous_owner_link() {
        let s = server();
        let owner = |s: &ShareServer| s.app_partage_creer(Parameters(CreateArgs { libelle: "moi".into(), proprietaire: true })).unwrap();
        owner(&s);
        owner(&s);
        create(&s, "moi");
        let list: Value = serde_json::from_str(&s.app_partage_liste().unwrap()).unwrap();
        // Le lien remplacé disparaît ; le dernier lien du propriétaire et le partage ordinaire restent.
        assert_eq!(list.as_array().unwrap().len(), 2, "{list}");
        assert!(list.as_array().unwrap().iter().all(|l| l["actif"] == true));
    }

    #[test]
    fn list_then_revoke() {
        let s = server();
        create(&s, "a");
        let list: Value = serde_json::from_str(&s.app_partage_liste().unwrap()).unwrap();
        let first = &list[0];
        assert_eq!(first["libelle"], "a");
        assert_eq!(first["actif"], true);
        assert!(first["ouvert_le"].is_null());
        assert_eq!(first["cree_le"], rfc(1_000));
        let id = first["id"].as_i64().unwrap();
        let m: Value = serde_json::from_str(&s.app_partage_revoquer(Parameters(IdArgs { id })).unwrap()).unwrap();
        assert!(m["message"].as_str().unwrap().contains("révoqué"));
        let list: Value = serde_json::from_str(&s.app_partage_liste().unwrap()).unwrap();
        assert_eq!(list[0]["actif"], false);
        assert!(s.app_partage_revoquer(Parameters(IdArgs { id: 999 })).is_err());
    }
}
