//! Extraction du texte d'un document, sans réseau.
//!
//! PDF : couche texte (`pdftotext`). Texte brut et Markdown : lus tels quels.
//! Traitement de texte (`.docx`) : XML du corps, balises retirées. Images et
//! PDF scannés sans couche texte : rien (reconnaissance de caractères à venir).

use std::path::Path;
use std::process::Command;

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

fn run(cmd: &str, args: &[&str]) -> Option<String> {
    let out = Command::new(cmd).args(args).output().ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).into_owned())
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

/// Texte du document, ou `None` s'il n'en a pas de lisible.
pub fn text(path: &Path) -> Option<String> {
    let p = path.to_str()?;
    let t = match Kind::of(path) {
        Kind::Pdf => run("pdftotext", &["-layout", "-q", "-enc", "UTF-8", p, "-"])?,
        Kind::Text => std::fs::read_to_string(path).ok()?,
        Kind::Docx => strip_xml(&run("unzip", &["-p", p, "word/document.xml"])?),
        Kind::Image | Kind::Other => return None,
    };
    let t = cap(t);
    // Moins de quelques caractères utiles : scan sans couche texte.
    (t.chars().filter(|c| c.is_alphanumeric()).count() >= 20).then_some(t)
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
        assert!(text(&long).unwrap().contains("préavis"));
        assert_eq!(text(&short), None);
        assert_eq!(text(&dir.join("photo.jpg")), None);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn cap_respects_char_boundaries() {
        let s = "é".repeat(MAX_TEXT);
        assert!(cap(s).len() <= MAX_TEXT);
    }
}
