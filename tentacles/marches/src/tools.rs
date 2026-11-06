//! Outils MCP du module marchés : section de brief, alertes, sélection.
//!
//! Tous les textes sont composés ici à partir des chiffres calculés ; les
//! titres venus de la source sont déjà assainis à l'enregistrement.

use std::sync::Arc;

use chrono::{DateTime, Datelike, Duration, Utc, Weekday};
use rmcp::{
    ServerHandler,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerConfig},
    schemars, tool, tool_handler, tool_router,
};
use serde::Deserialize;
use serde_json::json;
use tentacle_common::BriefPeriod;

use crate::api::clean;
use crate::signals::{Move, Settings, pct, pts, reaches, scenario_alerts, single_alerts};
use crate::store::{Followed, Store};

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
    use super::parse_links;

    #[test]
    fn links_format() {
        assert_eq!(parse_links("1+, 4-").unwrap(), vec![(1, 1), (4, -1)]);
        assert_eq!(parse_links(" 2 \u{2212}").unwrap(), vec![(2, -1)]);
        assert!(parse_links("1").is_err());
        assert!(parse_links("x+").is_err());
    }
}
