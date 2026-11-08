//! Extraction du texte d'un document, sans réseau.
//!
//! PDF : couche texte (`pdftotext`). Texte brut et Markdown : lus tels quels.
//! Traitement de texte (`.docx`) : XML du corps, balises retirées. Images et
//! PDF scannés sans couche texte : reconnaissance de caractères (`tesseract`,
//! français), si elle est activée.

use std::io::Read;
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// Texte au-delà duquel un document est tronqué dans l'index.
pub const MAX_TEXT: usize = 2_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Pdf,
    Text,
    Docx,
    Image,
    Other,
}

impl Kind {
    pub fn of(path: &Path) -> Kind {
        let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_lowercase();
        match ext.as_str() {
            "pdf" => Kind::Pdf,
            "txt" | "md" | "csv" => Kind::Text,
            "docx" => Kind::Docx,
            "jpg" | "jpeg" | "png" | "heic" | "webp" | "tif" | "tiff" => Kind::Image,
            _ => Kind::Other,
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Kind::Pdf => "pdf",
            Kind::Text => "texte",
            Kind::Docx => "docx",
            Kind::Image => "image",
            Kind::Other => "autre",
        }
    }
}

/// Retire les balises XML et décode les entités usuelles ; un paragraphe devient une ligne.
pub fn strip_xml(xml: &str) -> String {
    let xml = xml.replace("</w:p>", "\n");
    let mut out = String::with_capacity(xml.len() / 2);
    let mut in_tag = false;
    for c in xml.chars() {
        match c {
            '<' => in_tag = true,
            '>' => in_tag = false,
            c if !in_tag => out.push(c),
            _ => {}
        }
    }
    out.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/// Durée maximale d'une commande d'extraction.
const RUN_TIMEOUT: Duration = Duration::from_secs(120);

fn run(cmd: &str, args: &[&str]) -> Option<String> {
    run_with(cmd, args, MAX_TEXT, RUN_TIMEOUT)
}

/// Sortie standard de la commande, lue jusqu'à `max` octets ; au-delà, le tube
/// est fermé et le texte tronqué est gardé. Tuée après `timeout` (résultat `None`).
fn run_with(cmd: &str, args: &[&str], max: usize, timeout: Duration) -> Option<String> {
    let mut child =
        Command::new(cmd).args(args).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn().ok()?;
    let stdout = child.stdout.take()?;
    let reader = std::thread::spawn(move || {
        let mut buf = Vec::new();
        let _ = stdout.take(max as u64).read_to_end(&mut buf);
        buf
    });
    let deadline = Instant::now() + timeout;
    let status = loop {
        match child.try_wait() {
            Ok(Some(st)) => break st,
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(20)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
    };
    let buf = reader.join().ok()?;
    (status.success() || buf.len() >= max).then(|| String::from_utf8_lossy(&buf).into_owned())
}

fn cap(mut s: String) -> String {
    if s.len() > MAX_TEXT {
        let mut end = MAX_TEXT;
        while !s.is_char_boundary(end) {
            end -= 1;
        }
        s.truncate(end);
    }
    s
}

/// Pages d'un PDF scanné passées à la reconnaissance de caractères.
pub const OCR_MAX_PAGES: u32 = 8;

fn readable(t: &str) -> bool {
    // Moins de quelques caractères utiles : scan sans couche texte, ou bruit.
    t.chars().filter(|c| c.is_alphanumeric()).count() >= 20
}

fn ocr_image(path: &str) -> Option<String> {
    run("tesseract", &[path, "-", "-l", "fra+eng", "--psm", "3"])
}

/// PDF scanné : pages converties en images (200 ppp) puis reconnues.
fn ocr_pdf(path: &str) -> Option<String> {
    let dir = std::env::temp_dir().join(format!("kd-ocr-{}-{}", std::process::id(), rand_suffix()));
    std::fs::create_dir_all(&dir).ok()?;
    let prefix = dir.join("p");
    let last = OCR_MAX_PAGES.to_string();
    let ok = run("pdftoppm", &["-r", "200", "-l", &last, "-png", path, prefix.to_str()?]).is_some();
    let mut out = String::new();
    if ok {
        let mut pages: Vec<_> = std::fs::read_dir(&dir).ok()?.flatten().map(|e| e.path()).collect();
        pages.sort();
        for p in pages {
            if let Some(t) = p.to_str().and_then(ocr_image) {
                out.push_str(&t);
                out.push('\n');
            }
        }
    }
    let _ = std::fs::remove_dir_all(&dir);
    (!out.is_empty()).then_some(out)
}

fn rand_suffix() -> u128 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0)
}

/// Texte du document, ou `None` s'il n'en a pas de lisible. `ocr` : lire aussi les scans.
pub fn text(path: &Path, ocr: bool) -> Option<String> {
    let p = path.to_str()?;
    let t = match Kind::of(path) {
        Kind::Pdf => match run("pdftotext", &["-layout", "-q", "-enc", "UTF-8", p, "-"]) {
            Some(t) if readable(&t) => t,
            _ if ocr => ocr_pdf(p)?,
            _ => return None,
        },
        Kind::Text => {
            let mut buf = Vec::new();
            std::fs::File::open(path).ok()?.take(MAX_TEXT as u64).read_to_end(&mut buf).ok()?;
            String::from_utf8_lossy(&buf).into_owned()
        }
        Kind::Docx => strip_xml(&run("unzip", &["-p", p, "word/document.xml"])?),
        Kind::Image if ocr => ocr_image(p)?,
        Kind::Image | Kind::Other => return None,
    };
    let t = cap(t);
    readable(&t).then_some(t)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kinds_by_extension() {
        assert_eq!(Kind::of(Path::new("a/b.PDF")), Kind::Pdf);
        assert_eq!(Kind::of(Path::new("x.jpeg")), Kind::Image);
        assert_eq!(Kind::of(Path::new("x.md")), Kind::Text);
        assert_eq!(Kind::of(Path::new("x")), Kind::Other);
    }

    #[test]
    fn docx_xml_becomes_plain_text() {
        let xml = "<w:p><w:r><w:t>Contrat n°&#160;12 &amp; avenant</w:t></w:r></w:p><w:p><w:t>Fin</w:t></w:p>";
        assert_eq!(strip_xml(xml), "Contrat n°&#160;12 & avenant\nFin\n");
    }

    #[test]
    fn text_files_are_read_and_short_ones_ignored() {
        let dir = std::env::temp_dir().join(format!("kd-extract-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let long = dir.join("note.md");
        std::fs::write(&long, "Échéance du contrat le 12 mars, préavis de deux mois.").unwrap();
        let short = dir.join("vide.txt");
        std::fs::write(&short, "a b").unwrap();
        assert!(text(&long, false).unwrap().contains("préavis"));
        assert_eq!(text(&short, false), None);
        assert_eq!(text(&dir.join("photo.jpg"), false), None);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn run_is_bounded_in_size_and_time() {
        let t = Duration::from_secs(10);
        assert_eq!(run_with("sh", &["-c", "echo bonjour"], 100, t).as_deref(), Some("bonjour\n"));
        assert_eq!(run_with("sh", &["-c", "echo x; exit 1"], 100, t), None);
        // Sortie sans fin : coupée à la borne.
        assert_eq!(run_with("yes", &[], 1000, t).map(|s| s.len()), Some(1000));
        // Processus bloqué : tué au délai.
        let start = std::time::Instant::now();
        assert_eq!(run_with("sleep", &["30"], 100, Duration::from_millis(300)), None);
        assert!(start.elapsed() < Duration::from_secs(5));
    }

    #[test]
    fn cap_respects_char_boundaries() {
        let s = "é".repeat(MAX_TEXT);
        assert!(cap(s).len() <= MAX_TEXT);
    }
}
