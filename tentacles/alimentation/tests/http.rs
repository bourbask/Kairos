//! Module alimentation servi en HTTP, base produits simulée.

use std::sync::Arc;

use kairos_alimentation::{
    caldav::CalDav,
    off::Off,
    store::Store,
    tools::{FoodServer, Settings},
};
use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// Fausse base produits : connaît un seul code-barres.
async fn fake_off() -> String {
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", l.local_addr().unwrap());
    tokio::spawn(async move {
        loop {
            let Ok((mut s, _)) = l.accept().await else { break };
            let mut buf = vec![0u8; 4096];
            let n = s.read(&mut buf).await.unwrap_or(0);
            let req = String::from_utf8_lossy(&buf[..n]).to_string();
            let (code, body) = if req.contains("/3017620422003.json") {
                ("200 OK", json!({"status": 1, "product": {"product_name_fr": "Pâte à tartiner", "brands": "Marque A, Groupe B",
                    "quantity": "400 g", "categories_tags": ["en:spreads", "fr:pates-a-tartiner"], "nutriscore_grade": "e", "nova_group": 4}}).to_string())
            } else {
                ("404 Not Found", json!({"status": 0}).to_string())
            };
            let resp = format!(
                "HTTP/1.1 {code}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = s.write_all(resp.as_bytes()).await;
        }
    });
    url
}

async fn start() -> String {
    start_with(None).await
}

async fn start_with(caldav: Option<CalDav>) -> String {
    let store = Arc::new(Store::memory());
    let off = Arc::new(Off::new(&fake_off().await));
    let settings = Arc::new(Settings { tz: chrono_tz::Europe::Paris, caldav: caldav.map(Arc::new) });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(tentacle_common::serve_listener(listener, "t".into(), move || {
        FoodServer::new(store.clone(), off.clone(), settings.clone())
    }));
    url
}

async fn call(url: &str, name: &str, args: Value) -> (String, bool) {
    let body = json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":name,"arguments":args}});
    let v: Value = reqwest::Client::new()
        .post(url)
        .header("Accept", "application/json, text/event-stream")
        .bearer_auth("t")
        .json(&body)
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    (v["result"]["content"][0]["text"].as_str().unwrap_or_default().to_string(), v["result"]["isError"] == json!(true))
}

fn in_days(n: i64) -> String {
    (chrono::Utc::now().with_timezone(&chrono_tz::Europe::Paris).date_naive() + chrono::Duration::days(n)).to_string()
}

#[tokio::test]
async fn order_then_receive_then_meals_drive_the_stock() {
    let url = start().await;
    let items = json!([
        {"name": "Œufs plein air", "qty": 12, "location": "frigo", "expiry": in_days(1), "price": 3.2},
        {"name": "Poulet entier", "qty": 1.4, "unit": "kg", "location": "frigo", "expiry": in_days(3)},
        {"name": "Pommes de terre", "qty": 2.5, "unit": "kg", "location": "placard"},
        {"name": "Pains au chocolat", "qty": 4, "location": "placard"},
        {"barcode": "3017620422003", "qty": 1, "location": "placard"}
    ]);
    let args = json!({"store": "drive", "reference": "A1", "pickup": in_days(2), "total": 41.5, "items": items});
    let (t, _) = call(&url, "order_record", args.clone()).await;
    assert!(t.contains("Aperçu") && t.contains("Pâte à tartiner (Marque A) : 1 pièce → placard"), "{t}");
    let (t, _) = call(&url, "orders", json!({})).await;
    assert_eq!(t, "Aucune commande enregistrée.");

    let mut ok = args.clone();
    ok["confirm"] = json!(true);
    let (t, _) = call(&url, "order_record", ok).await;
    assert!(t.contains("enregistrée (commande 1)") && t.contains("41,50 €"), "{t}");
    let (t, _) = call(&url, "brief_section", json!({"period": "matin"})).await;
    assert!(t.contains("Commande drive à retirer le"), "{t}");

    // Réception avec une rupture.
    let (t, _) = call(&url, "order_receive", json!({"missing": ["pains chocolat"]})).await;
    assert!(t.contains("4 ligne(s)") && t.contains("rupture : Pains au chocolat"), "{t}");
    let (t, _) = call(&url, "order_receive", json!({"missing": ["pains chocolat"], "confirm": true})).await;
    assert!(t.contains("réceptionnée : 4 produit(s)") && t.contains("rupture, non stockée"), "{t}");
    let (t, err) = call(&url, "order_receive", json!({})).await;
    assert!(err && t.contains("Aucune commande à retirer"), "{t}");

    // Repas : déduction du stock, y compris au-delà du connu.
    let meal = json!({"slot": "soir", "name": "Omelette, pommes de terre sautées",
        "ingredients": [{"name": "oeufs", "qty": 3}, {"name": "pommes de terre", "qty": 400, "unit": "g"}, {"name": "beurre", "qty": 10, "unit": "g"}]});
    let (t, _) = call(&url, "meal_log", meal).await;
    assert!(t.contains("Œufs plein air : -3 pièces (reste 9 pièces)"), "{t}");
    assert!(t.contains("Pommes de terre : -400 g (reste 2,1 kg)"), "{t}");
    assert!(t.contains("non déduit : Aucun produit « beurre »"), "{t}");
    call(&url, "meal_log", json!({"slot": "midi", "name": "Pizza", "place": "bureau"})).await;
    let (t, _) = call(&url, "meals", json!({"days": 1})).await;
    assert!(
        t.starts_with("2 repas depuis") && t.contains("(1 à la maison, 1 dehors)") && t.contains("Pizza (bureau)"),
        "{t}"
    );

    // Priorités, découpe et congélation, inventaire.
    let (t, _) = call(&url, "use_first", json!({"days": 1})).await;
    assert!(t.contains("Œufs plein air : 9 pièces, entamé, à finir demain") && !t.contains("Poulet"), "{t}");
    let (t, _) = call(&url, "stock_move", json!({"name": "poulet", "to": "congélo", "expiry": in_days(90)})).await;
    assert!(t.starts_with("Poulet entier → congelateur"), "{t}");
    let (t, _) = call(&url, "stock_set", json!({"items": [{"name": "oeufs", "qty": 0}]})).await;
    assert_eq!(t, "Œufs plein air : 9 pièces → 0 pièce");
    let (t, _) = call(&url, "stock", json!({})).await;
    assert!(
        t.contains("congelateur :\n- Poulet entier : 1,4 kg") && t.contains("placard :") && !t.contains("Œufs"),
        "{t}"
    );
    let (t, _) = call(&url, "stock", json!({"location": "frigo"})).await;
    assert_eq!(t, "Rien en stock pour ce filtre.");
}

#[tokio::test]
async fn prefs_lookup_and_validation() {
    let url = start().await;
    call(
        &url,
        "pref_set",
        json!({"key": "À éviter", "value": "ail, échalote ; oignon émincé très fin, en petite quantité"}),
    )
    .await;
    call(&url, "pref_set", json!({"key": "équipement", "value": "gazinière, four, poêles, casseroles"})).await;
    let (t, _) = call(&url, "prefs", json!({})).await;
    assert!(t.contains("- à éviter : ail, échalote") && t.contains("- équipement : gazinière"), "{t}");
    call(&url, "pref_set", json!({"key": "équipement", "value": ""})).await;
    assert!(!call(&url, "prefs", json!({})).await.0.contains("équipement"));

    let (t, _) = call(&url, "product_lookup", json!({"barcode": "3017620422003"})).await;
    assert!(t.contains("Pâte à tartiner — Marque A") && t.contains("Nutri-Score : E") && t.contains("NOVA : 4"), "{t}");
    let (t, err) = call(&url, "product_lookup", json!({"barcode": "0000000000000"})).await;
    assert!(err && t.contains("absent"), "{t}");
    let (t, err) = call(&url, "product_lookup", json!({"barcode": "12ab"})).await;
    assert!(err && t.contains("invalide"), "{t}");

    for (items, msg) in [
        (json!([{"name": "Riz", "qty": 1, "unit": "sac"}]), "unité « sac » inconnue"),
        (json!([{"name": "Riz", "qty": -1, "unit": "kg"}]), "quantité positive"),
        (json!([{"name": "Riz", "qty": 1, "unit": "kg", "location": "cave"}]), "Emplacement inconnu"),
        (json!([{"name": "Riz", "qty": 1, "unit": "kg", "expiry": "31/12/2026"}]), "AAAA-MM-JJ"),
    ] {
        let (t, err) = call(&url, "stock_add", json!({"items": items})).await;
        assert!(err && t.contains(msg), "{msg} : {t}");
    }
}

#[tokio::test]
async fn order_import_reads_a_bon_once() {
    let url = start().await;
    let text = "Retrait le 14/03/27 à 18H30
Détail de votre commande n°555 du 12/03/2027 à 10h05 (3 produits)
Désignation                                    Quantité   Prix unitaire TTC   Total TTC
FRUITS LÉGUMES (1 produit)
  Carottes - Sachet 2kg                                1                1.99        1.99
SURGELÉS (1 produit)
  Epinards hachés - 600g                               1                1.89        1.89
ENTRETIEN NETTOYAGE (1 produit)
  Liquide vaisselle - 500ml                            1                0.67        0.67
                                 Total de la commande :       4.55
";
    let (t, _) = call(&url, "order_import", json!({"text": text})).await;
    assert!(t.contains("Commande drive n° 555, retrait le 2027-03-14, 4,55 € — 2 ligne(s). Aperçu"), "{t}");
    assert!(
        t.contains("Carottes : 2 kg → frigo, 1,99 €") && t.contains("Écartés (non alimentaires) : Liquide vaisselle"),
        "{t}"
    );
    let (t, _) = call(&url, "order_import", json!({"text": text, "confirm": true})).await;
    assert!(t.contains("enregistrée (commande 1)"), "{t}");
    let (t, _) = call(&url, "order_import", json!({"text": text, "confirm": true})).await;
    assert_eq!(t, "Commande n° 555 déjà enregistrée (commande 1) : rien à faire.");
    let (t, err) = call(&url, "order_import", json!({"text": "Une facture quelconque"})).await;
    assert!(err && t.contains("ne ressemble pas"), "{t}");
}

#[tokio::test]
async fn product_set_qualifies_and_lists_unknowns() {
    let url = start().await;
    call(
        &url,
        "stock_add",
        json!({"items": [
            {"name": "Objet mystère", "qty": 1, "location": "placard"},
            {"name": "Carottes", "qty": 1, "unit": "kg", "location": "frigo"}
        ]}),
    )
    .await;
    let (out, err) = call(&url, "product_set", json!({})).await;
    assert!(!err && out.contains("Objet mystère") && !out.contains("Carottes"), "{out}");
    let (out, err) = call(&url, "product_set", json!({"name": "objet mystère", "nature": "plat"})).await;
    assert!(!err, "{out}");
    let (out, _) = call(&url, "product_set", json!({})).await;
    assert!(!out.contains("Objet mystère"), "{out}");
    let (out, err) = call(&url, "product_set", json!({"name": "carottes", "nature": "légume-racine"})).await;
    assert!(err && out.contains("proteine"), "{out}");
}

#[tokio::test]
async fn product_set_unknown_product() {
    let url = start().await;
    let (out, err) = call(&url, "product_set", json!({"name": "licorne", "nature": "plat"})).await;
    assert!(err, "{out}");
}

/// Inventaire de référence : frais à cuisiner, viennoiseries, condiments très anciens.
async fn stocked() -> String {
    let url = start().await;
    let items = json!([
        {"name": "Poulet fermier jaune", "qty": 1.25, "unit": "kg", "location": "frigo", "expiry": in_days(7)},
        {"name": "Champignons de Paris bruns", "qty": 250, "unit": "g", "location": "frigo"},
        {"name": "Carottes", "qty": 1.5, "unit": "kg", "location": "frigo"},
        {"name": "Patates douces", "qty": 1, "unit": "kg", "location": "frigo"},
        {"name": "Pains au chocolat", "qty": 2, "location": "placard", "expiry": in_days(3)},
        {"name": "Sauce soja sucrée", "qty": 1, "location": "placard", "expiry": in_days(4)},
        {"name": "Condiment balsamique blanc", "qty": 350, "unit": "ml", "location": "placard", "expiry": in_days(-695)},
        {"name": "Bouillon pot-au-feu", "qty": 1, "location": "placard", "expiry": in_days(-770)},
        {"name": "Vinaigre balsamique", "qty": 400, "unit": "ml", "location": "placard", "expiry": in_days(-285)},
        {"name": "Tartinade marocaine", "qty": 1, "location": "placard", "expiry": in_days(-675)},
        {"name": "Dosettes café", "qty": 1, "location": "placard", "expiry": in_days(-236)},
        {"name": "Olivade verte", "qty": 1, "location": "placard", "expiry": in_days(-98)},
        {"name": "Lait demi-écrémé", "qty": 1, "unit": "l", "location": "frigo", "expiry": in_days(1)}
    ]);
    let (out, err) = call(&url, "stock_add", json!({"items": items})).await;
    assert!(!err, "{out}");
    url
}

#[tokio::test]
async fn cook_options_dinner_proposes_real_food() {
    let url = stocked().await;
    let (out, err) = call(&url, "cook_options", json!({"slot": "soir"})).await;
    assert!(!err, "{out}");
    assert!(out.contains("Poulet") && out.contains("Carottes"), "{out}");
    assert!(!out.contains("Pains au chocolat"), "{out}");
    let head = out.split("Dans le placard aussi").next().unwrap();
    assert!(!head.contains("Sauce soja"), "{out}");
    assert!(out.contains("Dans le placard aussi") && out.contains("Sauce soja"), "{out}");
    assert!(out.find("Légumes").unwrap() < out.find("Protéines").unwrap(), "soir : légumes d'abord\n{out}");
}

#[tokio::test]
async fn cook_options_breakfast_and_hike() {
    let url = stocked().await;
    let (out, _) = call(&url, "cook_options", json!({"slot": "petit-dejeuner"})).await;
    assert!(out.contains("Pains au chocolat") && !out.contains("Poulet"), "{out}");
    let (out, _) = call(&url, "cook_options", json!({"slot": "midi", "context": "rando"})).await;
    assert!(!out.contains("Poulet") && !out.contains("Patates"), "{out}");
    let (out, _) = call(&url, "cook_options", json!({"slot": "midi", "context": "boulot"})).await;
    assert!(out.contains("à préparer la veille"), "{out}");
}

#[tokio::test]
async fn cook_options_day_excludes_expired_by_then() {
    let url = stocked().await;
    let (out, _) = call(&url, "cook_options", json!({"slot": "soir"})).await;
    assert!(out.contains("Lait"), "{out}");
    let (out, _) = call(&url, "cook_options", json!({"slot": "soir", "day": in_days(3)})).await;
    assert!(!out.contains("Lait"), "{out}");
}

#[tokio::test]
async fn cook_options_rejects_bad_day() {
    let url = stocked().await;
    let (out, err) = call(&url, "cook_options", json!({"slot": "soir", "day": "demain"})).await;
    assert!(err && out.contains("AAAA-MM-JJ"), "{out}");
}

#[tokio::test]
async fn cook_options_empty_stock() {
    let url = start().await;
    let (out, err) = call(&url, "cook_options", json!({"slot": "soir"})).await;
    assert!(!err && out.starts_with("Rien d'utilisable"), "{out}");
}

#[tokio::test]
async fn use_first_splits_urgent_and_stale() {
    let url = stocked().await;
    let (out, _) = call(&url, "use_first", json!({"days": 4})).await;
    let (urgent, stale) = out.split_once("Vieux, à vérifier").expect(&out);
    assert!(urgent.contains("Lait") && !urgent.contains("Sauce soja"), "{out}");
    assert_eq!(stale.lines().filter(|l| l.starts_with("- ")).count(), 5, "{out}");
    assert!(stale.find("Bouillon").unwrap() < stale.find("Condiment").unwrap(), "plus anciens d'abord\n{out}");
}

#[tokio::test]
async fn recipe_book_round_trip() {
    let url = start().await;
    let r = json!({"name": "Dhal de lentilles corail", "portions": 4,
        "ingredients": ["lentilles corail", "lait de coco"], "steps": "1. Rincer.\n2. Cuire 20 min."});
    let (out, err) = call(&url, "recipe_set", r).await;
    assert!(!err, "{out}");
    let (out, _) = call(&url, "recipes", json!({"name": "dhal"})).await;
    assert!(out.contains("4 portions") && out.contains("lentilles corail") && out.contains("2. Cuire"), "{out}");
    call(&url, "recipe_set", json!({"name": "dhal de lentilles corail", "portions": 3, "ingredients": [], "steps": "Corrigé."})).await;
    let (out, _) = call(&url, "recipes", json!({})).await;
    assert_eq!(out.matches("Dhal").count() + out.matches("dhal").count(), 1, "correction, pas doublon : {out}");
    assert!(out.contains("3 portions"), "{out}");
    let (out, err) = call(&url, "recipes", json!({"name": "licorne"})).await;
    assert!(err, "{out}");
}

/// Faux agenda : note « MÉTHODE chemin » de chaque requête et l'accepte.
async fn fake_caldav() -> (String, Arc<std::sync::Mutex<Vec<String>>>) {
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/cal/repas/", l.local_addr().unwrap());
    let log = Arc::new(std::sync::Mutex::new(Vec::new()));
    let seen = log.clone();
    tokio::spawn(async move {
        loop {
            let Ok((mut s, _)) = l.accept().await else { break };
            let seen = seen.clone();
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let mut chunk = [0u8; 4096];
                loop {
                    let n = s.read(&mut chunk).await.unwrap_or(0);
                    if n == 0 {
                        break;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                    let text = String::from_utf8_lossy(&buf).to_string();
                    if let Some(h) = text.find("\r\n\r\n") {
                        let len = text[..h]
                            .lines()
                            .find_map(|l| l.to_lowercase().strip_prefix("content-length:").map(|v| v.trim().parse::<usize>().unwrap_or(0)))
                            .unwrap_or(0);
                        if buf.len() >= h + 4 + len {
                            break;
                        }
                    }
                }
                let text = String::from_utf8_lossy(&buf).to_string();
                let first = text.lines().next().unwrap_or("").to_string();
                let mut parts = first.split_whitespace();
                seen.lock().unwrap().push(format!("{} {}", parts.next().unwrap_or(""), parts.next().unwrap_or("")));
                let _ = s.write_all(b"HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await;
            });
        }
    });
    (url, log)
}

#[tokio::test]
async fn meal_plan_set_writes_calendar() {
    let (cal, log) = fake_caldav().await;
    let url = start_with(Some(CalDav::new(&cal, "u".into(), "p".into()))).await;
    call(&url, "pref_set", json!({"key": "bureau", "value": "lundi"})).await;
    call(&url, "recipe_set", json!({"name": "Farfalle", "portions": 2, "ingredients": ["farfalle"], "steps": "1. Cuire."})).await;
    let plan = json!({"start": "2026-11-08", "days": 2, "meals": [
        {"day": "2026-11-08", "slot": "soir", "dish": "Farfalle", "portions": 2},
        {"day": "2026-11-09", "slot": "soir", "dish": "Riz"}
    ]});
    let (out, err) = call(&url, "meal_plan_set", plan).await;
    assert!(!err, "{out}");
    assert!(out.contains("Farfalle (boîte)"), "{out}");
    assert!(out.contains("Riz : pas de recette"), "{out}");
    assert!(out.contains("manque farfalle"), "{out}");
    assert!(out.contains("Agenda mis à jour"), "{out}");
    let mut seen = log.lock().unwrap().clone();
    seen.sort();
    assert_eq!(
        seen,
        [
            "DELETE /cal/repas/kairos-repas-20261108-midi.ics",
            "PUT /cal/repas/kairos-repas-20261108-soir.ics",
            "PUT /cal/repas/kairos-repas-20261109-midi.ics",
            "PUT /cal/repas/kairos-repas-20261109-soir.ics",
        ]
    );
}

#[tokio::test]
async fn meal_plan_set_without_calendar() {
    let url = start().await;
    let plan = json!({"start": "2026-11-08", "days": 1, "meals": [{"day": "2026-11-08", "slot": "midi", "dish": "Omelette"}]});
    let (out, err) = call(&url, "meal_plan_set", plan).await;
    assert!(!err && out.contains("Agenda non configuré"), "{out}");
    let (out, _) = call(&url, "meal_plan", json!({"day": "2026-11-08"})).await;
    assert!(out.contains("Omelette"), "{out}");
    let bad = json!({"start": "2026-11-08", "days": 1, "meals": [{"day": "2026-11-08", "slot": "gouter", "dish": "X"}]});
    let (out, err) = call(&url, "meal_plan_set", bad).await;
    assert!(err, "{out}");
}
