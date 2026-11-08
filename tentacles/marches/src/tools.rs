//! Outils MCP du module marchés : section de brief, alertes, sélection ; les
//! outils `app_*` servent l'application en JSON.
//!
//! Tous les textes sont composés ici à partir des chiffres calculés ; les
//! titres venus de la source sont déjà assainis à l'enregistrement.

use std::sync::Arc;

use chrono::{DateTime, Datelike, Duration, SecondsFormat, Utc, Weekday};
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;
use serde_json::{Value, json};
use tentacle_common::BriefPeriod;

use crate::api::clean;
use crate::signals::{Move, Settings, pct, pts, reaches, scenario_alerts, single_alerts};
use crate::store::{Followed, Scenario, Store};

type Out = Result<String, String>;

fn e(x: anyhow::Error) -> String {
    x.to_string()
}

#[derive(Clone)]
pub struct MarketServer {
    store: Arc<Store>,
    settings: Arc<Settings>,
    tool_router: ToolRouter<Self>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct BriefArgs {
    /// Contrat commun des sections de brief.
    period: BriefPeriod,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct FollowArgs {
    /// Numéros de la dernière liste de propositions.
    numbers: Vec<u32>,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct NumberArgs {
    /// Numéro dans la liste des questions suivies.
    number: u32,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct IdArgs {
    /// Identifiant de la question.
    id: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ScenarioArgs {
    /// Nom du scénario.
    name: String,
    /// Maillons « 1+, 4- » : numéros des questions suivies, + si la hausse va dans le sens du scénario, - sinon.
    links: String,
}

#[derive(Deserialize, schemars::JsonSchema)]
struct ProjectionArgs {
    /// Numéro dans la liste des questions suivies (ou bien scenario_name).
    number: Option<u32>,
    /// Nom d'un scénario (ou bien number).
    scenario_name: Option<String>,
    /// Ce que change une hausse.
    up: String,
    /// Ce que change une baisse.
    down: String,
}

/// Profondeur de la mini-courbe et de l'historique (durée de conservation des relevés).
const HISTORY_DAYS: i64 = 30;
/// Alertes récentes montrées par l'application.
const APP_ALERT_DAYS: i64 = 7;

fn rfc(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Secs, true)
}

fn json_out(v: Value) -> Out {
    serde_json::to_string(&v).map_err(|x| x.to_string())
}

fn scenario_json(sc: &Scenario) -> Value {
    let links: Vec<Value> = sc.links.iter().map(|l| json!({"id": l.market, "sign": l.sign})).collect();
    json!({"name": sc.name, "links": links})
}

fn label(f: &Followed) -> String {
    if f.outcome == "Yes" { f.title.clone() } else { format!("{} [{}]", f.title, f.outcome) }
}

/// Échéance passée sans clôture : en attente du résultat, jamais d'alerte.
fn pending(f: &Followed, now: DateTime<Utc>) -> bool {
    f.end.is_some_and(|end| end <= now)
}

fn projection_line(p: Option<(String, String)>, up: bool) -> Option<String> {
    let (u, d) = p?;
    Some(if up { format!("→ si ça monte : {u}") } else { format!("→ si ça baisse : {d}") })
}

/// Sens d'une clé de scénario « sc:<nom>:<+|->:<date> ».
fn rising(key: &str) -> bool {
    key.rsplit(':').nth(1) == Some("+")
}

/// « 1+, 4- » → (numéro, signe).
fn parse_links(s: &str) -> Result<Vec<(u32, i8)>, String> {
    s.split(',')
        .map(|t| {
            let t = t.trim();
            let (n, sign) = if let Some(n) = t.strip_suffix('+') {
                (n, 1)
            } else if let Some(n) = t.strip_suffix('-').or_else(|| t.strip_suffix('\u{2212}')) {
                (n, -1)
            } else {
                return Err(format!("Maillon « {t} » : attendu un numéro suivi de + ou -."));
            };
            n.trim().parse().map(|n| (n, sign)).map_err(|_| format!("Maillon « {t} » : numéro invalide."))
        })
        .collect()
}

impl MarketServer {
    pub fn new(store: Arc<Store>, settings: Arc<Settings>) -> Self {
        Self { store, settings, tool_router: Self::tool_router() }
    }

    fn window(&self) -> Duration {
        Duration::hours(self.settings.window_hours)
    }

    /// Probabilité d'il y a `ago`, à `stale_hours` près ; plus ancienne → aucune comparaison.
    fn prob_ago(&self, f: &Followed, now: DateTime<Utc>, ago: Duration) -> Result<Option<f64>, String> {
        self.store.prob_at(&f.market, &f.outcome, now - ago, Duration::hours(self.settings.stale_hours)).map_err(e)
    }

    fn followed_by_id(&self, id: &str) -> Result<Option<Followed>, String> {
        Ok(self.store.followed().map_err(e)?.into_iter().find(|f| f.market == id))
    }

    /// Une question suivie : probabilité, variations 24 h / 7 j (omises sans relevé
    /// comparable ou une fois l'échéance passée), un point par jour.
    fn followed_json(&self, f: &Followed, now: DateTime<Utc>) -> Result<Value, String> {
        let st = &self.store;
        let prob = st.latest(&f.market, &f.outcome).map_err(e)?.map(|(p, _, _)| p);
        let spark: Vec<f64> = st
            .daily(&f.market, &f.outcome, now - Duration::days(HISTORY_DAYS))
            .map_err(e)?
            .into_iter()
            .map(|d| d.prob)
            .collect();
        let closed = pending(f, now);
        let mut o = json!({"id": f.market, "question": f.title, "outcome": f.outcome, "prob": prob, "spark": spark, "closed": closed});
        if let Some(end) = f.end {
            o["end"] = json!(rfc(end));
        }
        if let Some(p) = prob.filter(|_| !closed) {
            if let Some(x) = self.prob_ago(f, now, Duration::hours(24))? {
                o["d1"] = json!(p - x);
            }
            if let Some(x) = self.prob_ago(f, now, Duration::days(7))? {
                o["d7"] = json!(p - x);
            }
        }
        Ok(o)
    }

    fn nth(&self, n: u32) -> Result<Followed, String> {
        let list = self.store.followed().map_err(e)?;
        n.checked_sub(1)
            .and_then(|i| list.into_iter().nth(i as usize))
            .ok_or_else(|| format!("Aucune question suivie n° {n} (voir la liste des questions suivies)."))
    }

    /// Variations sur la fenêtre des questions suivies encore ouvertes.
    fn moves(&self, now: DateTime<Utc>) -> Result<Vec<Move>, String> {
        let mut out = Vec::new();
        for f in self.store.followed().map_err(e)? {
            if pending(&f, now) {
                continue;
            }
            let Some((to, volume, _)) = self.store.latest(&f.market, &f.outcome).map_err(e)? else { continue };
            let Some(from) = self.prob_ago(&f, now, self.window())? else {
                continue;
            };
            out.push(Move { title: label(&f), market: f.market, outcome: f.outcome, from, to, volume });
        }
        Ok(out)
    }

    /// Ligne d'une question, puis la projection du sens observé si elle a assez bougé.
    fn item(&self, out: &mut Vec<String>, f: &Followed, now: DateTime<Utc>) -> Result<(), String> {
        let st = &self.store;
        let Some((p, _, _)) = st.latest(&f.market, &f.outcome).map_err(e)? else {
            out.push(format!("- {} : pas encore de relevé", label(f)));
            return Ok(());
        };
        let d24 = self.prob_ago(f, now, self.window())?.map(|x| p - x);
        let d7 = self.prob_ago(f, now, Duration::days(7))?.map(|x| p - x);
        let mut parts = Vec::new();
        if let Some(d) = d24 {
            parts.push(format!("{} / {} h", pts(d), self.settings.window_hours));
        }
        if let Some(d) = d7 {
            parts.push(format!("{} / 7 j", pts(d)));
        }
        let mut line = format!("- {} — {}", label(f), pct(p));
        if !parts.is_empty() {
            line.push_str(&format!(" ({})", parts.join(", ")));
        }
        if pending(f, now) {
            line.push_str(" — en attente du résultat");
        }
        out.push(line);
        if let Some(d) = d24.filter(|d| reaches(*d, self.settings.scenario_threshold))
            && let Some(l) = projection_line(st.projection(&f.market).map_err(e)?, d >= 0.0)
        {
            out.push(format!("  {l}"));
        }
        Ok(())
    }
}

#[tool_router]
impl MarketServer {
    #[tool(description = "Obtenir la section marchés d'un briefing (le matin seulement) : scénarios, questions suivies, bascules, dénouements, propositions du lundi.")]
    async fn brief_section(&self, Parameters(a): Parameters<BriefArgs>) -> Out {
        if a.period == BriefPeriod::Soir {
            return Ok(String::new());
        }
        let now = Utc::now();
        let (st, s) = (&self.store, &self.settings);
        let mut out = Vec::new();
        if let Some(last) = st.last_snapshot().map_err(e)?
            && now - last > Duration::hours(s.stale_hours)
        {
            out.push(format!("⚠ Données de marché du {}", last.format("%d/%m %H:%M UTC")));
        }
        let followed = st.followed().map_err(e)?;
        let scenarios = st.scenarios().map_err(e)?;
        let moves = self.moves(now)?;
        for sc in &scenarios {
            out.push(format!("Scénario {} :", sc.name));
            for l in &sc.links {
                if let Some(f) = followed.iter().find(|f| f.market == l.market && f.outcome == l.outcome) {
                    self.item(&mut out, f, now)?;
                }
            }
            if let Some((key, _)) = scenario_alerts(sc, &moves, s, now.date_naive()) {
                let up = rising(&key);
                if let Some(l) = projection_line(st.projection(&format!("sc:{}", sc.name)).map_err(e)?, up) {
                    out.push(l);
                }
            }
        }
        let loose: Vec<&Followed> = followed
            .iter()
            .filter(|f| !scenarios.iter().any(|sc| sc.links.iter().any(|l| l.market == f.market)))
            .collect();
        if !loose.is_empty() {
            out.push("Questions suivies :".into());
            for f in loose {
                self.item(&mut out, f, now)?;
            }
        }
        let since = st.last_brief().map_err(e)?.unwrap_or(now - Duration::days(7));
        let shifts = st.alerts_since(since).map_err(e)?;
        if !shifts.is_empty() {
            out.push("Bascules depuis le dernier brief :".into());
            out.extend(shifts.iter().map(|t| format!("- {}", t.replace('\n', "\n  "))));
        }
        let done = st.take_resolutions().map_err(e)?;
        if !done.is_empty() {
            out.push("Dénouements :".into());
            out.extend(done.iter().map(|d| format!("- {d}")));
        }
        // Liste calculée par le relevé : la section ne fait que la lire.
        let list = if now.weekday() == Weekday::Mon { st.proposals_of_week(now).map_err(e)? } else { vec![] };
        if !list.is_empty() {
            out.push("Propositions de suivi :".into());
            for p in &list {
                let o = if p.outcome == "Yes" { String::new() } else { format!(" [{}]", p.outcome) };
                out.push(format!("{}. {}{o}", p.n, p.title));
            }
            out.push("Répondre : suivre 1, 3".into());
        }
        st.mark_briefed(now).map_err(e)?;
        if !out.is_empty() {
            out.insert(0, "Marchés de prédiction".into());
        }
        Ok(out.join("\n"))
    }

    #[tool(description = "Lister les bascules à signaler au format JSON : id, text, counts_budget.")]
    async fn alerts(&self) -> Out {
        let now = Utc::now();
        let (st, s) = (&self.store, &self.settings);
        let fresh = st.last_snapshot().map_err(e)?.is_some_and(|t| now - t <= Duration::hours(s.stale_hours));
        if !fresh {
            return Ok("[]".into());
        }
        let moves = self.moves(now)?;
        let mut cands: Vec<(String, String)> = Vec::new();
        for (key, m) in single_alerts(&moves, s) {
            let mut text = format!(
                "{} : {} → {} ({} / {} h)",
                m.title,
                pct(m.from),
                pct(m.to),
                pts(m.delta()),
                s.window_hours
            );
            if let Some(l) = projection_line(st.projection(&m.market).map_err(e)?, m.delta() >= 0.0) {
                text.push_str(&format!("\n{l}"));
            }
            cands.push((key, text));
        }
        for sc in st.scenarios().map_err(e)? {
            let Some((key, ms)) = scenario_alerts(&sc, &moves, s, now.date_naive()) else { continue };
            // Même scénario, même sens, déjà signalé dans la fenêtre : la date de la clé ne suffit pas.
            let same = key.rsplit_once(':').map_or(key.as_str(), |(p, _)| p);
            if st.alert_prefix_since(&format!("{same}:"), now - self.window()).map_err(e)? {
                continue;
            }
            let mut text = format!("Scénario {} : mouvement d'ensemble sur {} h", sc.name, s.window_hours);
            for m in &ms {
                text.push_str(&format!("\n- {} : {} → {} ({})", m.title, pct(m.from), pct(m.to), pts(m.delta())));
            }
            let up = rising(&key);
            if let Some(l) = projection_line(st.projection(&format!("sc:{}", sc.name)).map_err(e)?, up) {
                text.push_str(&format!("\n{l}"));
            }
            cands.push((key, text));
        }
        let mut emitted = Vec::new();
        for (key, text) in cands {
            if st.alert_known(&key).map_err(e)? {
                continue;
            }
            // Au-delà du budget : enregistrée pour le brief suivant, pas émise.
            let budgeted = st.budgeted_since(now - Duration::days(7)).map_err(e)? < s.weekly_budget;
            st.record_alert(&key, now, budgeted, &text).map_err(e)?;
            if budgeted {
                emitted.push(json!({"id": key, "text": text, "counts_budget": true}));
            }
        }
        serde_json::to_string(&emitted).map_err(|x| x.to_string())
    }

    #[tool(description = "Suivre des questions de la dernière liste de propositions, par numéros. Un numéro inconnu ou répété : rien n'est suivi.")]
    async fn follow(&self, Parameters(a): Parameters<FollowArgs>) -> Out {
        if a.numbers.is_empty() {
            return Err("Aucun numéro donné.".into());
        }
        let mut picked = Vec::new();
        for (i, n) in a.numbers.iter().enumerate() {
            if a.numbers[..i].contains(n) {
                return Err(format!("Numéro {n} répété : rien n'est suivi."));
            }
            match self.store.proposal(*n).map_err(e)? {
                Some(p) => picked.push(p),
                None => return Err(format!("Proposition {n} inconnue : rien n'est suivi.")),
            }
        }
        for p in &picked {
            self.store.follow(&p.market, &p.outcome, &p.title).map_err(e)?;
        }
        Ok(format!("Suivi : {}.", picked.iter().map(|p| p.title.as_str()).collect::<Vec<_>>().join(" ; ")))
    }

    #[tool(description = "Ne plus suivre une question, par son numéro dans la liste des questions suivies.")]
    async fn unfollow(&self, Parameters(a): Parameters<NumberArgs>) -> Out {
        let f = self.nth(a.number)?;
        self.store.unfollow(&f.market).map_err(e)?;
        Ok(format!("Ne plus suivre : {}.", label(&f)))
    }

    #[tool(description = "Créer ou remplacer un scénario reliant des questions suivies, maillons au format « 1+, 4- ».")]
    async fn scenario(&self, Parameters(a): Parameters<ScenarioArgs>) -> Out {
        let name = clean(a.name.trim());
        if name.is_empty() {
            return Err("Nom de scénario vide.".into());
        }
        let mut links: Vec<(String, String, i8)> = Vec::new();
        let mut shown = Vec::new();
        for (n, sign) in parse_links(&a.links)? {
            let f = self.nth(n)?;
            if links.iter().any(|(m, _, _)| *m == f.market) {
                return Err(format!("Question {n} répétée."));
            }
            shown.push(format!("{} ({})", label(&f), if sign > 0 { '+' } else { '\u{2212}' }));
            links.push((f.market, f.outcome, sign));
        }
        if links.len() < 2 {
            return Err("Un scénario relie au moins deux questions.".into());
        }
        self.store.set_scenario(&name, &links).map_err(e)?;
        Ok(format!("Scénario {name} : {}.", shown.join(", ")))
    }

    #[tool(description = "Poser la projection d'une question suivie (number) ou d'un scénario (scenario_name) : ce que change une hausse, ce que change une baisse.")]
    async fn projection(&self, Parameters(a): Parameters<ProjectionArgs>) -> Out {
        let (up, down) = (a.up.trim(), a.down.trim());
        if up.is_empty() || down.is_empty() {
            return Err("Les deux sens sont requis : hausse et baisse.".into());
        }
        let (target, shown) = match (a.number, a.scenario_name) {
            (Some(n), None) => {
                let f = self.nth(n)?;
                (f.market.clone(), label(&f))
            }
            (None, Some(name)) => {
                let name = name.trim();
                if !self.store.scenarios().map_err(e)?.iter().any(|sc| sc.name == name) {
                    return Err(format!("Scénario « {} » inconnu.", clean(name)));
                }
                (format!("sc:{name}"), format!("scénario {name}"))
            }
            _ => return Err("Donner soit un numéro, soit un nom de scénario.".into()),
        };
        self.store.set_projection(&target, up, down).map_err(e)?;
        Ok(format!("Projection enregistrée pour {shown}."))
    }

    #[tool(description = "Vue JSON des marchés pour l'application : questions suivies, scénarios, alertes récentes.")]
    async fn app_markets(&self) -> Out {
        let now = Utc::now();
        let st = &self.store;
        let followed = st
            .followed()
            .map_err(e)?
            .iter()
            .map(|f| self.followed_json(f, now))
            .collect::<Result<Vec<_>, _>>()?;
        let scenarios: Vec<Value> = st.scenarios().map_err(e)?.iter().map(scenario_json).collect();
        let alerts: Vec<Value> = st
            .alert_rows_since(now - Duration::days(APP_ALERT_DAYS))
            .map_err(e)?
            .into_iter()
            .map(|a| json!({"id": a.key, "text": a.text, "at": rfc(a.at)}))
            .collect();
        json_out(json!({"followed": followed, "scenarios": scenarios, "alerts": alerts}))
    }

    #[tool(description = "Détail JSON d'une question suivie pour l'application : historique, volume par jour, projection, scénarios.")]
    async fn app_market(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        let now = Utc::now();
        let st = &self.store;
        let f = self.followed_by_id(&a.id)?.ok_or("Question suivie inconnue.")?;
        let days = st.daily(&f.market, &f.outcome, now - Duration::days(HISTORY_DAYS)).map_err(e)?;
        let history: Vec<Value> = days.iter().map(|d| json!({"at": rfc(d.at), "prob": d.prob})).collect();
        // Volume cumulé à la source : la barre d'un jour est l'écart avec le jour relevé précédent.
        let volume: Vec<Value> = days
            .windows(2)
            .map(|w| json!({"day": w[1].at.format("%Y-%m-%d").to_string(), "value": (w[1].volume - w[0].volume).max(0.0)}))
            .collect();
        // ponytail: seule l'issue suivie est relevée ; les autres issues demanderaient d'élargir les relevés.
        let outcomes: Vec<Value> = st
            .latest(&f.market, &f.outcome)
            .map_err(e)?
            .map(|(p, _, _)| json!({"name": f.outcome, "prob": p}))
            .into_iter()
            .collect();
        let projection = match st.projection(&f.market).map_err(e)? {
            Some((up, down)) => json!({"up": up, "down": down}),
            None => json!({}),
        };
        let scenarios: Vec<Value> = st
            .scenarios()
            .map_err(e)?
            .iter()
            .filter(|sc| sc.links.iter().any(|l| l.market == f.market))
            .map(scenario_json)
            .collect();
        json_out(json!({
            "id": f.market, "question": f.title, "outcomes": outcomes, "history": history,
            "volume": volume, "projection": projection, "scenarios": scenarios,
        }))
    }

    #[tool(description = "Propositions de suivi de la semaine en JSON pour l'application, hors questions déjà suivies.")]
    async fn app_market_proposals(&self) -> Out {
        let st = &self.store;
        let followed = st.followed().map_err(e)?;
        let list: Vec<Value> = st
            .proposals_of_week(Utc::now())
            .map_err(e)?
            .into_iter()
            .filter(|p| !followed.iter().any(|f| f.market == p.market))
            .map(|p| {
                let mut o = json!({"id": p.market, "question": p.title, "outcome": p.outcome, "prob": p.prob});
                if let Some(v) = p.volume {
                    o["volume"] = json!(v);
                }
                if let Some(end) = p.end {
                    o["end"] = json!(rfc(end));
                }
                o
            })
            .collect();
        json_out(json!(list))
    }

    #[tool(description = "Suivre une question de la dernière liste de propositions, par identifiant (application).")]
    async fn app_market_follow(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        if let Some(f) = self.followed_by_id(&a.id)? {
            return json_out(json!({"message": format!("Déjà suivie : {}.", label(&f))}));
        }
        // Même périmètre que app_market_proposals : la liste de la semaine en cours.
        let p = self
            .store
            .proposals_of_week(Utc::now())
            .map_err(e)?
            .into_iter()
            .find(|p| p.market == a.id)
            .ok_or("Proposition inconnue ou périmée : rien n'est suivi.")?;
        self.store.follow(&p.market, &p.outcome, &p.title).map_err(e)?;
        json_out(json!({"message": format!("Suivi : {}.", p.title)}))
    }

    #[tool(description = "Ne plus suivre une question, par identifiant (application).")]
    async fn app_market_unfollow(&self, Parameters(a): Parameters<IdArgs>) -> Out {
        let f = self.followed_by_id(&a.id)?.ok_or("Question inconnue ou déjà retirée du suivi.")?;
        self.store.unfollow(&f.market).map_err(e)?;
        json_out(json!({"message": format!("Ne plus suivre : {}.", label(&f))}))
    }

    #[tool(description = "Lister les questions suivies, numérotées, et les scénarios.")]
    async fn followed_list(&self) -> Out {
        let list = self.store.followed().map_err(e)?;
        if list.is_empty() {
            return Ok("Aucune question suivie.".into());
        }
        let mut out = Vec::new();
        for (i, f) in list.iter().enumerate() {
            let p = self.store.latest(&f.market, &f.outcome).map_err(e)?;
            out.push(format!("{}. {}{}", i + 1, label(f), p.map(|(p, _, _)| format!(" — {}", pct(p))).unwrap_or_default()));
        }
        let names: Vec<String> = self.store.scenarios().map_err(e)?.into_iter().map(|sc| sc.name).collect();
        if !names.is_empty() {
            out.push(format!("Scénarios : {}", names.join(", ")));
        }
        Ok(out.join("\n"))
    }
}

#[tool_handler(router = self.tool_router)]
impl ServerHandler for MarketServer {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::Proposal;
    use serde_json::Value;

    fn srv() -> MarketServer {
        MarketServer::new(Arc::new(Store::memory()), Arc::new(Settings::default()))
    }

    fn js(o: Out) -> Value {
        serde_json::from_str(&o.unwrap()).unwrap()
    }

    fn id(s: &str) -> Parameters<IdArgs> {
        Parameters(IdArgs { id: s.into() })
    }

    fn prop(n: u32, market: &str, title: &str) -> Proposal {
        Proposal { n, market: market.into(), outcome: "Yes".into(), title: title.into(), prob: Some(0.3), volume: Some(2e5), end: None }
    }

    /// Minuit UTC d'il y a `days` jours, plus `hours` heures.
    fn day(days: i64, hours: i64) -> DateTime<Utc> {
        let today = Utc::now().date_naive().and_hms_opt(0, 0, 0).unwrap().and_utc();
        today - Duration::days(days) + Duration::hours(hours)
    }

    #[tokio::test]
    async fn app_markets_json_shape() {
        let s = srv();
        let now = Utc::now();
        s.store.follow("m1", "Yes", "Q1").unwrap();
        s.store.follow("m2", "Yes", "Q2").unwrap();
        s.store.set_end("m1", Some(now + Duration::days(30))).unwrap();
        s.store.snapshot("m1", "Yes", now - Duration::hours(1), 0.6, 1e5).unwrap();
        s.store.set_scenario("sc", &[("m1".into(), "Yes".into(), 1), ("m2".into(), "Yes".into(), -1)]).unwrap();
        s.store.record_alert("k", now, true, "bascule").unwrap();
        let v = js(s.app_markets().await);
        let f = &v["followed"][0];
        assert_eq!(f["id"], "m1");
        assert_eq!(f["question"], "Q1");
        assert_eq!(f["outcome"], "Yes");
        assert_eq!(f["prob"], 0.6);
        assert_eq!(f["closed"], false);
        assert!(f["end"].is_string());
        assert_eq!(f["spark"].as_array().unwrap().len(), 1);
        assert_eq!(v["followed"].as_array().unwrap().len(), 2);
        assert_eq!(v["scenarios"][0]["name"], "sc");
        assert_eq!(v["scenarios"][0]["links"][1], json!({"id": "m2", "sign": -1}));
        assert_eq!(v["alerts"][0]["id"], "k");
        assert_eq!(v["alerts"][0]["text"], "bascule");
        assert!(v["alerts"][0]["at"].is_string());
    }

    #[tokio::test]
    async fn spark_one_point_per_day() {
        let s = srv();
        s.store.follow("m", "Yes", "Q").unwrap();
        s.store.snapshot("m", "Yes", day(3, 1), 0.1, 1.0).unwrap();
        s.store.snapshot("m", "Yes", day(2, 1), 0.2, 1.0).unwrap();
        s.store.snapshot("m", "Yes", day(2, 5), 0.25, 1.0).unwrap();
        s.store.snapshot("m", "Yes", day(1, 1), 0.3, 1.0).unwrap();
        // Hors des 30 jours : absent.
        s.store.snapshot("m", "Yes", day(40, 1), 0.9, 1.0).unwrap();
        let v = js(s.app_markets().await);
        assert_eq!(v["followed"][0]["spark"], json!([0.1, 0.25, 0.3]));
    }

    #[tokio::test]
    async fn deltas_from_history() {
        let s = srv();
        let now = Utc::now();
        s.store.follow("m", "Yes", "Q").unwrap();
        s.store.snapshot("m", "Yes", now - Duration::days(7) - Duration::hours(1), 0.20, 1.0).unwrap();
        s.store.snapshot("m", "Yes", now - Duration::hours(25), 0.30, 1.0).unwrap();
        s.store.snapshot("m", "Yes", now - Duration::hours(1), 0.45, 1.0).unwrap();
        let v = js(s.app_markets().await);
        let f = &v["followed"][0];
        assert!((f["d1"].as_f64().unwrap() - 0.15).abs() < 1e-9);
        assert!((f["d7"].as_f64().unwrap() - 0.25).abs() < 1e-9);
    }

    #[tokio::test]
    async fn no_history_omits_deltas() {
        let s = srv();
        s.store.follow("new", "Yes", "Nouvelle").unwrap();
        s.store.follow("one", "Yes", "Un relevé").unwrap();
        s.store.snapshot("one", "Yes", Utc::now() - Duration::hours(1), 0.5, 1.0).unwrap();
        let v = js(s.app_markets().await);
        let (a, b) = (&v["followed"][0], &v["followed"][1]);
        assert!(a["prob"].is_null());
        assert_eq!(a["spark"], json!([]));
        assert!(a.get("d1").is_none() && a.get("d7").is_none());
        assert_eq!(b["prob"], 0.5);
        assert!(b.get("d1").is_none() && b.get("d7").is_none());
    }

    #[tokio::test]
    async fn closed_market_shape() {
        let s = srv();
        let now = Utc::now();
        s.store.follow("m", "No", "Q").unwrap();
        s.store.set_end("m", Some(now - Duration::hours(2))).unwrap();
        s.store.snapshot("m", "No", now - Duration::hours(25), 0.5, 1.0).unwrap();
        s.store.snapshot("m", "No", now - Duration::hours(1), 0.99, 1.0).unwrap();
        let v = js(s.app_markets().await);
        let f = &v["followed"][0];
        assert_eq!(f["closed"], true);
        assert_eq!(f["outcome"], "No");
        assert_eq!(f["prob"], 0.99);
        assert!(f.get("d1").is_none() && f.get("d7").is_none());
    }

    #[tokio::test]
    async fn app_market_detail_history_and_volume() {
        let s = srv();
        s.store.follow("m", "Yes", "Q").unwrap();
        s.store.snapshot("m", "Yes", day(2, 1), 0.2, 100.0).unwrap();
        s.store.snapshot("m", "Yes", day(2, 3), 0.25, 150.0).unwrap();
        s.store.snapshot("m", "Yes", day(1, 1), 0.3, 400.0).unwrap();
        s.store.set_projection("m", "haut", "bas").unwrap();
        s.store.set_scenario("sc", &[("m".into(), "Yes".into(), 1), ("x".into(), "Yes".into(), -1)]).unwrap();
        let v = js(s.app_market(id("m")).await);
        assert_eq!(v["id"], "m");
        assert_eq!(v["question"], "Q");
        assert_eq!(v["outcomes"], json!([{"name": "Yes", "prob": 0.3}]));
        let probs: Vec<f64> = v["history"].as_array().unwrap().iter().map(|h| h["prob"].as_f64().unwrap()).collect();
        assert_eq!(probs, vec![0.25, 0.3]);
        assert!(v["history"][0]["at"].is_string());
        // Volume cumulé à la source : une barre par jour = écart avec la veille.
        assert_eq!(v["volume"], json!([{"day": day(1, 0).format("%Y-%m-%d").to_string(), "value": 250.0}]));
        assert_eq!(v["projection"], json!({"up": "haut", "down": "bas"}));
        assert_eq!(v["scenarios"][0]["name"], "sc");
        assert!(s.app_market(id("inconnue")).await.is_err());
    }

    #[tokio::test]
    async fn external_text_cleaned() {
        let s = srv();
        let dirty = format!("Q\u{0007}\nsuite{}", "x".repeat(500));
        s.store.set_proposals(&[prop(1, "m", &dirty)]).unwrap();
        s.store.mark_proposed(Utc::now()).unwrap();
        let v = js(s.app_market_proposals().await);
        let q = v[0]["question"].as_str().unwrap();
        assert!(q.starts_with("Q suite") && !q.chars().any(char::is_control));
        assert_eq!(q.chars().count(), crate::api::MAX_TITLE);
        js(s.app_market_follow(id("m")).await);
        let v = js(s.app_markets().await);
        assert_eq!(v["followed"][0]["question"], q);
    }

    #[tokio::test]
    async fn follow_by_id_from_proposals() {
        let s = srv();
        s.store.set_proposals(&[prop(1, "a", "A"), prop(2, "b", "B")]).unwrap();
        s.store.mark_proposed(Utc::now()).unwrap();
        let v = js(s.app_market_proposals().await);
        assert_eq!(v[1], json!({"id": "b", "question": "B", "outcome": "Yes", "prob": 0.3, "volume": 2e5}));
        let m = js(s.app_market_follow(id("b")).await);
        assert!(m["message"].as_str().unwrap().contains('B'));
        let ids: Vec<_> = s.store.followed().unwrap().into_iter().map(|f| f.market).collect();
        assert_eq!(ids, vec!["b"]);
        // Suivie : retirée des propositions en cours.
        assert_eq!(js(s.app_market_proposals().await).as_array().unwrap().len(), 1);
        assert!(s.app_market_follow(id("zz")).await.is_err());
    }

    #[tokio::test]
    async fn follow_idempotent() {
        let s = srv();
        s.store.set_proposals(&[prop(1, "a", "A")]).unwrap();
        s.store.mark_proposed(Utc::now()).unwrap();
        js(s.app_market_follow(id("a")).await);
        let m = js(s.app_market_follow(id("a")).await);
        assert!(m["message"].as_str().unwrap().contains("Déjà suivie"));
        assert_eq!(s.store.followed().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn follow_rejects_previous_week_proposal() {
        let s = srv();
        s.store.set_proposals(&[prop(1, "a", "A")]).unwrap();
        s.store.mark_proposed(Utc::now() - Duration::days(8)).unwrap();
        assert!(s.app_market_follow(id("a")).await.is_err());
        assert!(s.store.followed().unwrap().is_empty());
    }

    #[tokio::test]
    async fn unfollow_unknown_is_clear_error() {
        let s = srv();
        s.store.follow("a", "Yes", "A").unwrap();
        let err = s.app_market_unfollow(id("zz")).await.unwrap_err();
        assert!(err.contains("inconnue"), "{err}");
        let m = js(s.app_market_unfollow(id("a")).await);
        assert!(m["message"].as_str().unwrap().contains('A'));
        assert!(s.store.followed().unwrap().is_empty());
    }

    #[test]
    fn links_format() {
        assert_eq!(parse_links("1+, 4-").unwrap(), vec![(1, 1), (4, -1)]);
        assert_eq!(parse_links(" 2 \u{2212}").unwrap(), vec![(2, -1)]);
        assert!(parse_links("1").is_err());
        assert!(parse_links("x+").is_err());
    }
}
