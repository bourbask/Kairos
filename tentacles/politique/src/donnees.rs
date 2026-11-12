//! Dossier de fichiers JSON relu à chaud : un fichier dont la date ou la taille change est relu
//! au prochain appel ; un fichier illisible garde sa dernière version valide.

use std::collections::{BTreeMap, HashMap};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use serde::Deserialize;
use serde_json::Value;

/// Fichiers qui ne sont pas des rubriques.
pub const META: &str = "meta";
pub const IDENTITES: &str = "identites";
pub const COMPARAISONS: &str = "comparaisons";
pub const PRESENTATION: &str = "presentation";
pub const CARTE: &str = "carte";

/// Vues de l'application qu'une indication de présentation peut demander.
const VUES: &[&str] = &["verifications", "rhetorique", "revirements", "votes", "bulle", "generique"];

/// Rôles qu'une indication peut donner aux champs d'une entrée.
const ROLES: &[&str] =
    &["titre", "verdict", "faits", "source", "theme", "camp", "dit", "vote", "definition", "reperer", "exemple", "question", "date"];

/// Taille au-delà de laquelle un fichier est ignoré (protège la mémoire du conteneur).
const MAX_FICHIER: u64 = 4 * 1024 * 1024;

/// Caractères permis dans une image d'avatar : rôles du thème, couleur de l'identité, puis teintes
/// fixes de cheveux (`g` blancs ou gris, `b` bruns, `y` blonds).
const AVATAR_CHARS: &[char] = &['.', 'F', 'h', 'D', 'A', 'C', 'g', 'b', 'y'];

/// Empreinte stable d'un fichier lu : date de modification (ns) et taille, en hexadécimal.
fn empreinte(stamp: (SystemTime, u64)) -> String {
    let ns = stamp.0.duration_since(SystemTime::UNIX_EPOCH).map_or(0, |d| d.as_nanos());
    format!("{ns:x}-{:x}", stamp.1)
}

struct Fichier {
    stamp: (SystemTime, u64),
    valeur: Arc<Value>,
}

pub struct Donnees {
    dir: PathBuf,
    cache: Mutex<BTreeMap<String, Fichier>>,
}

/// Identité (candidat ou parti) et les libellés sous lesquels les rubriques la désignent.
#[derive(Debug, Clone, Deserialize)]
pub struct Identite {
    pub id: String,
    pub nom: String,
    #[serde(default)]
    pub parti: Option<String>,
    #[serde(default)]
    pub couleur: Option<String>,
    #[serde(default)]
    pub alias: Vec<String>,
    #[serde(default)]
    pub avatar: Option<Value>,
    /// « personne », « parti » ou « camp » ; absent : l'application le déduit.
    #[serde(default, rename = "type")]
    pub genre: Option<String>,
}

impl Identite {
    /// Libellés repliés : le nom vaut alias.
    pub fn cles(&self) -> Vec<String> {
        std::iter::once(&self.nom).chain(&self.alias).map(|a| fold(a)).filter(|a| !a.is_empty()).collect()
    }
}

/// Instantané cohérent des fichiers, pour la durée d'un appel.
pub struct Etat {
    pub fichiers: BTreeMap<String, Arc<Value>>,
    /// Empreinte de la version lue de chaque fichier.
    pub versions: BTreeMap<String, String>,
    pub identites: Vec<Identite>,
    /// Alias replié → identités, dans l'ordre du fichier.
    index: HashMap<String, Vec<usize>>,
    pub avatars_invalides: usize,
    /// Indications de présentation valides, dans l'ordre du fichier.
    pub presentations: Vec<Value>,
    pub presentations_invalides: usize,
}

/// Minuscules, sans accents ni ponctuation, espaces simples.
pub fn fold(s: &str) -> String {
    let mapped: String = s
        .to_lowercase()
        .chars()
        .map(|c| match c {
            'à' | 'á' | 'â' | 'ä' | 'ã' => 'a',
            'é' | 'è' | 'ê' | 'ë' => 'e',
            'î' | 'ï' | 'í' => 'i',
            'ô' | 'ö' | 'ó' => 'o',
            'ù' | 'û' | 'ü' | 'ú' => 'u',
            'ç' => 'c',
            'ñ' => 'n',
            c if c.is_alphanumeric() => c,
            _ => ' ',
        })
        .collect();
    mapped.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Listes d'entrées d'une rubrique : tableaux de premier niveau, et `rows` des objets de premier niveau.
pub fn listes(v: &Value) -> Vec<(String, &Vec<Value>)> {
    match v {
        Value::Array(a) => vec![(String::new(), a)],
        Value::Object(o) => o
            .iter()
            .filter_map(|(k, x)| match x {
                Value::Array(a) => Some((k.clone(), a)),
                Value::Object(sub) => sub.get("rows").and_then(Value::as_array).map(|a| (format!("{k}.rows"), a)),
                _ => None,
            })
            .collect(),
        _ => vec![],
    }
}

/// Valeurs texte de premier niveau d'une entrée.
fn textes(e: &Value) -> Vec<&str> {
    match e {
        Value::String(s) => vec![s],
        Value::Array(a) => a.iter().filter_map(Value::as_str).collect(),
        Value::Object(o) => o.values().filter_map(Value::as_str).collect(),
        _ => vec![],
    }
}

/// Avatar valide : 1 à 4 images de 16 lignes de 16 caractères permis, durée par image de 100 à 2000 ms.
pub fn avatar_valide(a: &Value) -> bool {
    let ms_ok = a.get("ms").and_then(Value::as_f64).is_some_and(|ms| (100.0..=2000.0).contains(&ms));
    let images_ok = a.get("images").and_then(Value::as_array).is_some_and(|imgs| {
        (1..=4).contains(&imgs.len())
            && imgs.iter().all(|img| {
                img.as_array().is_some_and(|lignes| {
                    lignes.len() == 16
                        && lignes.iter().all(|l| l.as_str().is_some_and(|l| l.chars().count() == 16 && l.chars().all(|c| AVATAR_CHARS.contains(&c))))
                })
            })
    });
    ms_ok && images_ok
}

/// Indication valide, réduite à ses quatre clés ; sinon `None`.
fn presentation(p: &Value) -> Option<Value> {
    let rubrique = p.get("rubrique")?.as_str().filter(|r| !r.is_empty())?;
    let chemin = p.get("chemin")?.as_str()?;
    let vue = p.get("vue")?.as_str().filter(|v| VUES.contains(v))?;
    let champs = match p.get("champs") {
        None => serde_json::Map::new(),
        Some(c) => c.as_object()?.clone(),
    };
    if !champs.iter().all(|(role, champ)| ROLES.contains(&role.as_str()) && champ.as_str().is_some_and(|c| !c.is_empty())) {
        return None;
    }
    Some(serde_json::json!({"rubrique": rubrique, "chemin": chemin, "vue": vue, "champs": champs}))
}

impl Etat {
    fn new(fichiers: BTreeMap<String, Arc<Value>>, versions: BTreeMap<String, String>) -> Etat {
        let mut avatars_invalides = 0;
        let identites: Vec<Identite> = fichiers
            .get(IDENTITES)
            .and_then(|v| v.as_array())
            .map(|a| a.iter().filter_map(|x| Identite::deserialize(x).ok()).collect())
            .unwrap_or_default();
        let identites = identites
            .into_iter()
            .map(|mut i| {
                if i.avatar.as_ref().is_some_and(|a| !avatar_valide(a)) {
                    i.avatar = None;
                    avatars_invalides += 1;
                }
                i
            })
            .collect::<Vec<_>>();
        let mut index: HashMap<String, Vec<usize>> = HashMap::new();
        for (n, i) in identites.iter().enumerate() {
            for cle in i.cles() {
                let ids = index.entry(cle).or_default();
                if !ids.contains(&n) {
                    ids.push(n);
                }
            }
        }
        // Forme inattendue : aucune indication, l'application devine.
        let brutes = fichiers.get(PRESENTATION).and_then(|v| v.as_array()).map(Vec::as_slice).unwrap_or_default();
        let presentations: Vec<Value> = brutes.iter().filter_map(presentation).collect();
        let presentations_invalides = brutes.len() - presentations.len();
        Etat { fichiers, versions, identites, index, avatars_invalides, presentations, presentations_invalides }
    }

    pub fn identite(&self, id: &str) -> Option<&Identite> {
        self.identites.iter().find(|i| i.id == id)
    }

    /// Rubriques : tous les fichiers sauf ceux de service.
    pub fn rubriques(&self) -> impl Iterator<Item = (&String, &Arc<Value>)> {
        self.fichiers.iter().filter(|(k, _)| ![META, IDENTITES, COMPARAISONS, PRESENTATION, CARTE].contains(&k.as_str()))
    }

    /// Identités d'une entrée : toutes celles dont un alias égale une de ses valeurs texte.
    pub fn rattachement(&self, e: &Value) -> Vec<&str> {
        let mut n: Vec<usize> = textes(e).into_iter().filter_map(|t| self.index.get(&fold(t))).flatten().copied().collect();
        n.sort_unstable();
        n.dedup();
        n.into_iter().map(|i| self.identites[i].id.as_str()).collect()
    }

    /// Alias qui ne désignent aucune entrée d'aucune rubrique.
    pub fn alias_inconnus(&self) -> usize {
        let vus: std::collections::HashSet<String> =
            self.rubriques().flat_map(|(_, v)| listes(v).into_iter().flat_map(|(_, l)| l.iter().flat_map(textes).map(fold).collect::<Vec<_>>())).collect();
        self.identites.iter().flat_map(|i| i.alias.iter()).filter(|a| !vus.contains(&fold(a))).count()
    }
}

impl Donnees {
    pub fn new(dir: impl Into<PathBuf>) -> Donnees {
        Donnees { dir: dir.into(), cache: Mutex::new(BTreeMap::new()) }
    }

    /// Relit les fichiers modifiés, puis renvoie un instantané. Les journaux ne portent que des noms
    /// de fichiers et des comptes, jamais de contenu.
    pub fn etat(&self) -> Etat {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let mut change = false;
        match std::fs::read_dir(&self.dir) {
            Err(_) => eprintln!("politique : dossier de données illisible, dernière version gardée"),
            Ok(rd) => {
                let mut presents = Vec::new();
                for e in rd.flatten() {
                    let path = e.path();
                    let Some(nom) = path.file_stem().and_then(|s| s.to_str()).map(str::to_string) else { continue };
                    // Fichiers ordinaires seulement : un lien symbolique n'est pas suivi.
                    if path.extension().and_then(|s| s.to_str()) != Some("json") || !e.file_type().is_ok_and(|t| t.is_file()) {
                        continue;
                    }
                    let Ok(meta) = e.metadata() else { continue };
                    presents.push(nom.clone());
                    let stamp = (meta.modified().unwrap_or(SystemTime::UNIX_EPOCH), meta.len());
                    if cache.get(&nom).is_some_and(|f| f.stamp == stamp) {
                        continue;
                    }
                    let lu = if meta.len() > MAX_FICHIER {
                        None
                    } else {
                        std::fs::read(&path).ok().and_then(|b| serde_json::from_slice::<Value>(&b).ok())
                    };
                    match lu {
                        Some(v) => {
                            cache.insert(nom, Fichier { stamp, valeur: Arc::new(v) });
                            change = true;
                        }
                        None => {
                            eprintln!("politique : {nom}.json illisible, dernière version valide gardée");
                            // Même version cassée : signalée une fois, pas à chaque appel.
                            if let Some(f) = cache.get_mut(&nom) {
                                f.stamp = stamp;
                            }
                        }
                    }
                }
                let avant = cache.len();
                cache.retain(|k, _| presents.contains(k));
                change |= cache.len() != avant;
            }
        }
        let etat = Etat::new(cache.iter().map(|(k, f)| (k.clone(), f.valeur.clone())).collect(), cache.iter().map(|(k, f)| (k.clone(), empreinte(f.stamp))).collect());
        if change {
            eprintln!(
                "politique : {} fichiers, {} identités, {} alias inconnus, {} avatars invalides, {} indications invalides",
                etat.fichiers.len(),
                etat.identites.len(),
                etat.alias_inconnus(),
                etat.avatars_invalides,
                etat.presentations_invalides
            );
        }
        etat
    }
}
