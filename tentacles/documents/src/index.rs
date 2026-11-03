//! Index des documents : inventaire (nature, taille, date) et recherche plein texte.
//!
//! La nature d'un document est son dossier de premier niveau. L'index est
//! reconstruit par passages successifs : seul ce qui a changé (taille ou date)
//! est relu, ce qui a disparu est retiré.

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use rusqlite::{Connection, OptionalExtension, params};

use crate::extract::{self, Kind};

/// Nature des fichiers posés à la racine.
pub const ROOT_NATURE: &str = "(racine)";

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS files (
    path TEXT PRIMARY KEY,
    nature TEXT NOT NULL,
    name TEXT NOT NULL,
    kind TEXT NOT NULL,
    size INTEGER NOT NULL,
    mtime INTEGER NOT NULL,
    has_text INTEGER NOT NULL,
    body TEXT
);
CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(
    path UNINDEXED, name, body, tokenize = 'unicode61 remove_diacritics 2'
);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
";

#[derive(Debug, Clone, PartialEq)]
pub struct Doc {
    /// Chemin relatif à la racine, séparateur `/`.
    pub path: String,
    pub nature: String,
    pub name: String,
    pub kind: String,
    pub size: i64,
    pub mtime: i64,
    pub has_text: bool,
}

#[derive(Debug, Default, PartialEq, Eq)]
pub struct ScanReport {
    pub added: usize,
    pub updated: usize,
    pub removed: usize,
    pub unchanged: usize,
}

pub struct Index {
    db: Mutex<Connection>,
}

fn rel(root: &Path, p: &Path) -> Option<String> {
    let r = p.strip_prefix(root).ok()?;
    let parts: Vec<_> = r.components().map(|c| c.as_os_str().to_string_lossy().into_owned()).collect();
    Some(parts.join("/"))
}

fn nature_of(rel: &str) -> String {
    match rel.split_once('/') {
        Some((top, _)) => top.to_string(),
        None => ROOT_NATURE.to_string(),
    }
}

/// Fichiers visibles sous `root` (cachés, temporaires et dossiers techniques exclus).
fn walk(root: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(rd) = std::fs::read_dir(&dir) else { continue };
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            if name.starts_with('.') || name.starts_with('~') || name.ends_with(".tmp") || name == "desktop.ini" {
                continue;
            }
            let Ok(ft) = e.file_type() else { continue };
            if ft.is_dir() {
                stack.push(e.path());
            } else if ft.is_file() {
                out.push(e.path());
            }
        }
    }
    out.sort();
    out
}

fn row(r: &rusqlite::Row) -> rusqlite::Result<Doc> {
    Ok(Doc {
        path: r.get(0)?,
        nature: r.get(1)?,
        name: r.get(2)?,
        kind: r.get(3)?,
        size: r.get(4)?,
        mtime: r.get(5)?,
        has_text: r.get::<_, i64>(6)? != 0,
    })
}

const COLS: &str = "path, nature, name, kind, size, mtime, has_text";

/// Requête plein texte sûre : chaque mot devient un préfixe entre guillemets (ET implicite).
pub fn fts_query(q: &str) -> Option<String> {
    let words: Vec<String> = q
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| w.chars().count() >= 2)
        .take(12)
        .map(|w| format!("\"{}\"*", w.replace('"', "")))
        .collect();
    (!words.is_empty()).then(|| words.join(" "))
}

impl Index {
    pub fn open(path: &str) -> anyhow::Result<Index> {
        let c = Connection::open(path)?;
        c.execute_batch(SCHEMA)?;
        Ok(Index { db: Mutex::new(c) })
    }

    pub fn memory() -> Index {
        let c = Connection::open_in_memory().expect("base en mémoire");
        c.execute_batch(SCHEMA).expect("schéma");
        Index { db: Mutex::new(c) }
    }

    /// Un passage d'inventaire. Le texte est extrait hors verrou.
    pub fn scan(&self, root: &Path, now: i64) -> anyhow::Result<ScanReport> {
        let mut r = ScanReport::default();
        let mut seen = std::collections::HashSet::new();
        for p in walk(root) {
            let Some(path) = rel(root, &p) else { continue };
            let Ok(md) = std::fs::metadata(&p) else { continue };
            let size = md.len() as i64;
            let mtime = md
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0);
            seen.insert(path.clone());
            let known: Option<(i64, i64)> = self
                .db
                .lock()
                .unwrap()
                .query_row("SELECT size, mtime FROM files WHERE path = ?1", [&path], |x| Ok((x.get(0)?, x.get(1)?)))
                .optional()?;
            if known == Some((size, mtime)) {
                r.unchanged += 1;
                continue;
            }
            let body = extract::text(&p);
            let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            let db = self.db.lock().unwrap();
            db.execute("DELETE FROM fts WHERE path = ?1", [&path])?;
            db.execute(
                "INSERT INTO files (path, nature, name, kind, size, mtime, has_text, body)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                 ON CONFLICT(path) DO UPDATE SET nature = excluded.nature, name = excluded.name, kind = excluded.kind,
                   size = excluded.size, mtime = excluded.mtime, has_text = excluded.has_text, body = excluded.body",
                params![path, nature_of(&path), name, Kind::of(&p).as_str(), size, mtime, body.is_some() as i64, body],
            )?;
            db.execute(
                "INSERT INTO fts (path, name, body) VALUES (?1, ?2, ?3)",
                params![path, name, body.as_deref().unwrap_or("")],
            )?;
            if known.is_some() {
                r.updated += 1;
            } else {
                r.added += 1;
            }
        }
        let db = self.db.lock().unwrap();
        let all: Vec<String> =
            db.prepare("SELECT path FROM files")?.query_map([], |x| x.get(0))?.collect::<rusqlite::Result<_>>()?;
        for p in all.into_iter().filter(|p| !seen.contains(p)) {
            db.execute("DELETE FROM files WHERE path = ?1", [&p])?;
            db.execute("DELETE FROM fts WHERE path = ?1", [&p])?;
            r.removed += 1;
        }
        db.execute("INSERT OR REPLACE INTO meta (k, v) VALUES ('last_scan', ?1)", [now.to_string()])?;
        Ok(r)
    }

    pub fn last_scan(&self) -> Option<i64> {
        self.db
            .lock()
            .unwrap()
            .query_row("SELECT v FROM meta WHERE k = 'last_scan'", [], |r| r.get::<_, String>(0))
            .optional()
            .ok()
            .flatten()
            .and_then(|v| v.parse().ok())
    }

    /// Natures avec (nombre, sans texte lisible, dernière modification).
    pub fn natures(&self) -> rusqlite::Result<Vec<(String, i64, i64, i64)>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare(
            "SELECT nature, COUNT(*), SUM(1 - has_text), MAX(mtime) FROM files GROUP BY nature ORDER BY nature",
        )?;
        st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?.collect()
    }

    pub fn list(&self, nature: &str) -> rusqlite::Result<Vec<Doc>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare(&format!("SELECT {COLS} FROM files WHERE nature = ?1 ORDER BY path"))?;
        st.query_map([nature], row)?.collect()
    }

    pub fn get(&self, path: &str) -> rusqlite::Result<Option<(Doc, Option<String>)>> {
        let db = self.db.lock().unwrap();
        db.query_row(&format!("SELECT {COLS}, body FROM files WHERE path = ?1"), [path], |r| Ok((row(r)?, r.get(7)?)))
            .optional()
    }

    /// Recherche : (document, extrait), meilleurs d'abord, natures exclues filtrées.
    pub fn search(
        &self,
        query: &str,
        nature: Option<&str>,
        exclude: &[String],
        limit: usize,
    ) -> rusqlite::Result<Vec<(Doc, String)>> {
        let Some(q) = fts_query(query) else { return Ok(Vec::new()) };
        let db = self.db.lock().unwrap();
        let mut st = db.prepare(&format!(
            "SELECT {}, snippet(fts, 2, '«', '»', '…', 20)
             FROM fts JOIN files f ON f.path = fts.path
             WHERE fts MATCH ?1 ORDER BY bm25(fts, 0.0, 4.0, 1.0) LIMIT 200",
            COLS.split(", ").map(|c| format!("f.{c}")).collect::<Vec<_>>().join(", ")
        ))?;
        let rows = st.query_map([q], |r| Ok((row(r)?, r.get::<_, String>(7)?)))?;
        let mut out = Vec::new();
        for x in rows {
            let (d, snip) = x?;
            if exclude.iter().any(|e| e.eq_ignore_ascii_case(&d.nature))
                || nature.is_some_and(|n| !n.eq_ignore_ascii_case(&d.nature))
            {
                continue;
            }
            out.push((d, snip));
            if out.len() >= limit {
                break;
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fts_queries_are_quoted_prefixes() {
        assert_eq!(fts_query("assurance auto").as_deref(), Some("\"assurance\"* \"auto\"*"));
        assert_eq!(fts_query("bris de \"glace\" OR x").as_deref(), Some("\"bris\"* \"de\"* \"glace\"* \"OR\"*"));
        assert_eq!(fts_query("a ; *"), None);
    }

    #[test]
    fn natures_come_from_top_folder() {
        assert_eq!(nature_of("Banque/releve.pdf"), "Banque");
        assert_eq!(nature_of("Logement/2024/bail.pdf"), "Logement");
        assert_eq!(nature_of("note.md"), ROOT_NATURE);
    }

    fn tree() -> PathBuf {
        let d = std::env::temp_dir().join(format!("kd-index-{}-{}", std::process::id(), rand_suffix()));
        std::fs::create_dir_all(d.join("Assurances/Auto")).unwrap();
        std::fs::create_dir_all(d.join("Logement")).unwrap();
        std::fs::create_dir_all(d.join("Privé")).unwrap();
        std::fs::write(
            d.join("Assurances/Auto/conditions.md"),
            "Garantie bris de glace incluse, franchise de 150 euros.",
        )
        .unwrap();
        std::fs::write(d.join("Logement/bail.txt"), "Bail d'habitation, préavis d'un mois en zone tendue.").unwrap();
        std::fs::write(d.join("Privé/secret.md"), "Garantie confidentielle qui ne doit jamais ressortir.").unwrap();
        std::fs::write(d.join("photo.jpg"), [0u8; 10]).unwrap();
        std::fs::write(d.join(".cache"), "x").unwrap();
        d
    }

    fn rand_suffix() -> u128 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
    }

    #[test]
    fn scan_is_incremental_and_search_is_accent_insensitive() {
        let root = tree();
        let ix = Index::memory();
        assert_eq!(ix.scan(&root, 1).unwrap(), ScanReport { added: 4, ..Default::default() });
        assert_eq!(ix.scan(&root, 2).unwrap(), ScanReport { unchanged: 4, ..Default::default() });
        assert_eq!(ix.last_scan(), Some(2));

        let hits = ix.search("preavis", None, &[], 10).unwrap();
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].0.path, "Logement/bail.txt");
        assert!(hits[0].1.contains("«préavis»"), "{}", hits[0].1);

        // Natures exclues et filtre de nature.
        assert_eq!(ix.search("garantie", None, &[], 10).unwrap().len(), 2);
        assert_eq!(ix.search("garantie", None, &["privé".into(), "Privé".into()], 10).unwrap().len(), 1);
        assert_eq!(ix.search("garantie", Some("logement"), &[], 10).unwrap().len(), 0);

        let n = ix.natures().unwrap();
        assert_eq!(
            n.iter().map(|x| (x.0.as_str(), x.1, x.2)).collect::<Vec<_>>(),
            [("(racine)", 1, 1), ("Assurances", 1, 0), ("Logement", 1, 0), ("Privé", 1, 0)]
        );

        // Modification puis suppression.
        std::fs::write(root.join("Logement/bail.txt"), "Bail modifié : préavis de trois mois, hors zone tendue.")
            .unwrap();
        let t = std::time::SystemTime::now() + std::time::Duration::from_secs(5);
        std::fs::File::options().write(true).open(root.join("Logement/bail.txt")).unwrap().set_modified(t).unwrap();
        std::fs::remove_file(root.join("photo.jpg")).unwrap();
        let r = ix.scan(&root, 3).unwrap();
        assert_eq!((r.updated, r.removed, r.unchanged), (1, 1, 2));
        assert!(ix.search("trois mois", None, &[], 10).unwrap()[0].1.contains("«trois»"));
        assert!(ix.get("photo.jpg").unwrap().is_none());
        std::fs::remove_dir_all(&root).unwrap();
    }
}
