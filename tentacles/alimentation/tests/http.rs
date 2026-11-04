//! Module alimentation servi en HTTP, base produits simulée.

use std::sync::Arc;

use kairos_alimentation::{
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
    let store = Arc::new(Store::memory());
    let off = Arc::new(Off::new(&fake_off().await));
    let settings = Arc::new(Settings { tz: chrono_tz::Europe::Paris });
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
