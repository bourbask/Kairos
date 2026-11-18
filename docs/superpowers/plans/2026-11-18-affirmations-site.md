# Affirmations sur le site : identifiants, « Pourquoi ? », barème, partage — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chaque carte d'affirmation du site a un identifiant stable, une adresse, une justification au verso, un niveau de confiance et un barème public, avec la grammaire visuelle de la maquette.

**Architecture:** Données dans le dépôt du site (`site/data/affirmations.json`, nouveau `site/data/bareme.json`), contrôlées par un script Python. Rendu dans `site/assets/app.js` (fonction `renderCards`) et `app.css`. Le retournement des tracts de `pol/vrai-faux.js` devient un module partagé `pol/retourne.js`.

**Tech Stack:** HTML/CSS/JS modules sans dépendance (site statique), Python 3 standard (scripts de contrôle, `unittest`).

**Spec:** `docs/superpowers/specs/2026-11-18-affirmations-pourquoi-signalements-design.md` (§3, §4, §6, §8 étapes 1 à 3). Maquette validée, hors dépôt : `private/maquettes/crible-pourquoi/carte.html` (dépôt Kairos). Elle fait foi pour le rendu.

**Dépôt :** Mimir. Tous les chemins sont relatifs à sa racine. Travail dans un worktree `../Mimir-pourquoi`, branche `feat/affirmations-pourquoi` depuis `origin/main`. Le formulaire de signalement n'est pas dans ce plan : il est dans le plan `2026-11-18-signalements.md`.

## Global Constraints

- `id` : `^[a-z0-9-]{3,60}$`, unique, jamais modifié une fois publié.
- `pourquoi` ≤ 400 caractères, 1 à 3 phrases.
- `confiance` ∈ {1, 2, 3}, avec `confiance_pourquoi` ≤ 200 caractères.
- `appui` : liste non vide d'entiers à partir de 1, chacun ≤ `len(facts)`.
- JSON du site : UTF-8, indentation 1, saut de ligne final. Écrire avec `json.dumps(D, ensure_ascii=False, indent=1) + "\n"`.
- Texte du barème lu depuis `bareme.json`, jamais en dur dans le code.
- Touches pixel : au survol, la touche s'enfonce d'1 px et son relief diminue. Au clic, elle s'enfonce de 2 px. Le contour de focus est à l'intérieur.
- Étiquettes de sources : partie visible de 24 px, zone tactile de 32 px, les deux moitiés à la même hauteur.
- Mouvement réduit (`prefers-reduced-motion`) : la face change sans animation.
- Après toute modification de `assets/`, relancer `python3 scripts/version-jeux.py`.
- Commits `--no-gpg-sign`, sans mention d'IA. Intégration par merge, jamais rebase.

## Review Focus

1. **Affirmation sans `pourquoi`** pendant la mise en place : le verdict reste lisible, la touche est inactive avec « justification en cours », et la carte ne se retourne pas. Test : tâche 4, étape 4.
2. **Ancre vers un `id` inconnu ou supprimé** : la vue des affirmations s'ouvre sans erreur ni surbrillance. Test : tâche 5.
3. **Affirmation dont `q` a changé** : son `id` ne change pas, et le contrôle refuse un `id` modifié par rapport à `origin/main`. Test : tâche 1.
4. **Filtres actifs qui masquent la carte visée par l'ancre** : les filtres sont réinitialisés avant de défiler. Test : tâche 5.
5. **Clic répété sur la touche verdict pendant l'animation** : un seul retournement, sans état incohérent. Test : tâche 4, étape 4.

---

### Task 1: Identifiants stables et contrôle des affirmations

**Files:**
- Create: `scripts/controle-affirmations.py`
- Create: `scripts/test_controle_affirmations.py`
- Modify: `site/data/affirmations.json` (ajout de `id` à chaque élément de `CLAIMS`, placé en premier)

**Interfaces:**
- Produces :
  - `slug(q: str, pris: set[str]) -> str`. Repli Unicode vers ASCII, minuscules, non-alphanumériques remplacés par `-`, tirets en bord retirés, coupure à 60 caractères sur une frontière de mot. En cas de doublon, suffixe `-2`, `-3`, etc.
  - `controler(claims: list, precedents: list | None, strict: bool) -> list[str]`, qui renvoie des messages d'erreur. Liste vide = conforme.
  - En ligne de commande : `python3 scripts/controle-affirmations.py [--strict] [--base <ref git>]`. `--base` vaut `origin/main` par défaut. Code de sortie 1 s'il y a une erreur.

- [ ] **Step 1: Écrire les tests** (`unittest`), dans `scripts/test_controle_affirmations.py` :
  - `test_slug_repli_accents` : `slug("Les médias entretiennent l'amalgame…", set())` donne `"les-medias-entretiennent-l-amalgame"`.
  - `test_slug_doublon` : avec `pris={"le-smic-tue-l-emploi"}`, donne `"le-smic-tue-l-emploi-2"`.
  - `test_slug_longueur` : 60 caractères au plus, ne finit pas par `-`.
  - `test_id_manquant`, `test_id_mal_forme` (`"Abc"`), `test_id_double` : chacun produit une erreur qui nomme l'identifiant.
  - `test_id_change` : `precedents=[{"id":"a-b","q":"X"}]`, `claims=[{"id":"a-c","q":"X"}]` produit l'erreur « identifiant modifié : a-b ». La correspondance se fait par `q`.
  - `test_id_disparu` : un `id` présent dans `precedents` et absent de `claims` produit une erreur.
  - `test_appui_hors_faits` : `appui=[3]` avec 2 faits produit une erreur.
  - `test_pourquoi_trop_long` : 401 caractères produit une erreur.
  - `test_confiance_invalide` : `confiance=4` produit une erreur.
  - `test_non_strict_tolere_absence` : sans `pourquoi`, `confiance` ni `appui`, il n'y a pas d'erreur hors mode strict. En mode strict, il y en a une.
  - `test_partiel_refuse` : `pourquoi` présent sans `confiance` produit une erreur, même hors mode strict.
- [ ] **Step 2:** `python3 -m unittest scripts/test_controle_affirmations.py`. Attendu : échec à l'import.
- [ ] **Step 3: Implémenter `slug` et `controler`.** Lecture de la version de base via `git show <ref>:site/data/affirmations.json`. Si la référence est absente (premier ajout des identifiants), `precedents=None` : pas de contrôle de stabilité.
- [ ] **Step 4:** les tests passent.
- [ ] **Step 5: Poser les identifiants**, en une fois, avec un court code Python qui appelle `slug` dans l'ordre de `CLAIMS` et écrit `id` en première clé, sans toucher au reste. Vérifier que `git diff --stat` ne montre que des ajouts de lignes `"id"`.
- [ ] **Step 6:** `python3 scripts/controle-affirmations.py` sort avec le code 0. `python3 scripts/controle-sources.py` est inchangé.
- [ ] **Step 7: Commit** : `Affirmations : identifiants stables et contrôle`.

### Task 2: Barème

**Files:**
- Create: `site/data/bareme.json`
- Modify: `scripts/controle-affirmations.py` (contrôle du barème)
- Modify: `scripts/test_controle_affirmations.py`

**Interfaces:**
- Produces : `bareme.json` = `{"VERDICTS": [{"id","libelle","famille","definition"}], "CONFIANCE": [{"niveau","libelle","definition"}], "PRINCIPE": "…"}`.
  - L'ordre de `VERDICTS` est celui des clés de `VLABEL` (`site/assets/app.js:190`).
  - `famille` ∈ {`solide`, `partiel`, `fragile`}, comme `VGROUP` (`app.js:192`).

- [ ] **Step 1: Test `test_bareme_couvre_les_verdicts`** : chaque valeur `v` présente dans `CLAIMS` a une entrée dans `VERDICTS`. Les trois niveaux 1, 2 et 3 sont présents.
- [ ] **Step 2:** le test échoue (fichier absent).
- [ ] **Step 3: Écrire `bareme.json`.**
  - Définitions des verdicts : celles de la maquette, mot pour mot.
  - `non-documente` : « Pas de source exploitable trouvée : le verdict reste en suspens. », famille `fragile`.
  - Confiance : les trois définitions du §3.2 de la spec.
  - `PRINCIPE` : « Le verdict juge la phrase telle qu'elle est dite, mot pour mot, avec la même exigence de preuve quel que soit le camp. »
- [ ] **Step 4:** les tests passent. Le script lit et contrôle aussi `bareme.json`.
- [ ] **Step 5: Commit** : `Affirmations : barème public`.

### Task 3: Retournement partagé

**Files:**
- Create: `site/assets/pol/retourne.js`
- Modify: `site/assets/pol/vrai-faux.js:318-366` (`turnAnim` remplacé par un appel au module)

**Interfaces:**
- Produces : `export function retourne(hote: HTMLElement, carte: HTMLElement, { de: string, vers: string, motif: (face, px, py, h, W, H) => string, couleurs: Record<string, [r,g,b]>, rayon?: number }, fini: () => void)`.
  - Même algorithme, mêmes 8 images clés et mêmes durées que `turnAnim` aujourd'hui.
  - `motif` renvoie une clé de `couleurs`.
  - `rayon` (défaut 0) découpe des coins arrondis, comme dans la maquette.
  - Le coin corné des tracts reste propre à `vrai-faux.js`, via son `motif`.
- Produces aussi : `export function rgb(css: string): [number, number, number]`. C'est la sonde de couleur, qui gère `color-mix` et `color(srgb …)`. Elle remplace la fonction `rgba` locale de `vrai-faux.js`, qui l'importe désormais.

- [ ] **Step 1:** déplacer l'algorithme sans changer son comportement. `vrai-faux.js` appelle `retourne(...)` avec son motif de tract actuel.
- [ ] **Step 2:** `node --check` sur les deux fichiers. Puis vérification à l'écran : le retournement d'un tract de « Vrai ou faux ? » est identique avant et après, coin corné compris, en clair et en sombre.
- [ ] **Step 3:** `python3 scripts/version-jeux.py`, puis commit : `Jeux : retournement mis en commun`.

### Task 4: Carte recto/verso

**Files:**
- Modify: `site/assets/app.js:234-250` (`renderCards`), `site/assets/app.js:31` (`load` : charger `bareme.json` avec les affirmations)
- Modify: `site/assets/app.css` (bloc `.card`, l. 584-601)

**Interfaces:**
- Consumes : `retourne`, `rgb` (tâche 3) ; `bareme.json` (tâche 2) ; `SRC.list`, déjà dans `sources.js`.
- Produces :
  - balisage `.flip#a-<id> > .inner > article.face.recto + article.face.verso` ;
  - fonction `retournerCarte(id: string, versVerso?: boolean)`, exportée dans le module pour la tâche 5 ;
  - fonction `ouvrirBareme()`.

- [ ] **Step 1: Recto**, selon la maquette :
  - le bandeau en grille, `minmax(0,1fr) auto`, avec le texte à gauche et la paire de touches à droite (`.share` puis `.verdict`, 30 px chacune, 4 px d'écart) ;
  - l'affirmation ;
  - la jauge pixel du Duel (`.meter > b`, au tiers, aux deux tiers ou pleine selon `VGROUP`) ;
  - les faits ;
  - les sources, en dernier.
- [ ] **Step 2: Verso** :
  - le titre avec le verdict ;
  - `pourquoi` ;
  - le panneau de confiance, avec ses blocs, le libellé du barème et `confiance_pourquoi` ;
  - la rangée « Appuyé sur », avec une étiquette `Fait n` par numéro d'`appui` ;
  - les touches `.key` « Notre barème » et « Signaler une erreur ». « Signaler » appelle `window.CribleSignaler?.(id)` et reste masquée tant que cette fonction n'existe pas : le plan signalements la fournit.
- [ ] **Step 3: Barème** : une `<dialog>` construite depuis `bareme.json` (verdicts et confiance avec mini-blocs), selon la maquette.
- [ ] **Step 4: Comportement** :
  - les touches pixel suivent les contraintes globales ;
  - la face cachée est `inert`, et le focus passe sur la touche verdict de la face visible ;
  - un verrou `data-busy` ignore les clics pendant le geste ;
  - mouvement réduit : la face change sans animation ;
  - sans `pourquoi` : touche verdict `disabled`, libellé accessible « Verdict : X. Justification en cours », pas de « ? ».

  Vérification à l'écran, en clair et en sombre, à 400 px de large :
  - un double-clic rapide ne retourne la carte qu'une fois ;
  - une carte sans `pourquoi` ne se retourne pas.
- [ ] **Step 5:** `node --check site/assets/app.js`, puis `python3 scripts/version-jeux.py`, puis commit : `Affirmations : verso Pourquoi, confiance et barème`.

### Task 5: Ancre et partage

**Files:**
- Modify: `site/assets/app.js:532-557` (`show`, `hashchange`)

**Interfaces:**
- Consumes : `retournerCarte` (tâche 4).
- Produces : le fragment `#a-<id>`, routé vers la vue qui contient les affirmations.

- [ ] **Step 1: Routage.** Dans `show`, un fragment qui commence par `a-` :
  - ouvre la vue des affirmations ;
  - réinitialise les filtres et la recherche ;
  - fait défiler jusqu'à `#a-<id>`, au centre ;
  - pose la classe `.target` pendant 4 s.

  Un `id` inconnu ouvre la vue, sans erreur ni surbrillance.
- [ ] **Step 2: Touche partage.**
  - Avec `navigator.share` : `navigator.share({ title: "Le Crible 2027", text: q, url })`, où `url` est l'adresse de la page avec le fragment.
  - Sinon : `navigator.clipboard.writeText(url)`, puis un toast « Lien copié ». En cas d'échec, l'adresse est sélectionnée dans un champ.
- [ ] **Step 3: Vérifications à l'écran** :
  - avec des filtres actifs qui masquent la carte, `#a-<id>` les réinitialise et la montre ;
  - `#a-inconnu` ouvre la vue sans erreur ;
  - la touche partage copie l'adresse sur ordinateur.
- [ ] **Step 4:** `python3 scripts/version-jeux.py`, puis commit : `Affirmations : ancre et partage`.

### Task 6: Harmonisations visuelles

**Files:**
- Modify: `site/assets/app.css:806-851` (composant sources)
- Modify: `site/assets/sources.js:77-87` (`tag`) et `:97-114` (fiche)
- Modify: `site/assets/pol/pol.css:419-420` (`.vf-prog`)

- [ ] **Step 1: Sources.**
  - `.stag .st-in` : `min-height: 24px`, `padding: 2px 7px`.
  - Zones tactiles (`.stag > a/button/.st-lab`, `.st-nolink`) : `min-height: 32px`, `padding: 4px 0`, `align-items: stretch`.
- [ ] **Step 2: Source sans fiche.** Dans `tag`, la branche `st-lab` devient un bouton `st-ev unk`, avec `data-evname`, la classe `st-in ind0` et une pastille `?`.
  - Ajouter `openInconnue(nom, opener)`, qui réutilise la `<dialog>` de `openFiche`. Contenu :
    - « Qui chiffre ? · Non précisé » ;
    - le nom ;
    - « Indépendance non qualifiée » ;
    - le texte de la spec §6.
  - Brancher le gestionnaire de clic sur `.st-ev[data-evname]`.
- [ ] **Step 3: Barre de progression de « Vrai ou faux ? ».** `.vf-prog` prend le style de la jauge du Duel : creux cerclé, remplissage bordé et graduations (règles `.du-btrack` / `.bfill`, `pol.css:682-685`).
- [ ] **Step 4: Vérifications.**
  - `python3 scripts/controle-sources.py` est inchangé.
  - À l'écran : une source CNCDH sans fiche ouvre la fiche « pas encore analysée ».
  - Une étiquette dont la partie droite fait deux lignes a ses deux moitiés à la même hauteur.
- [ ] **Step 5:** `python3 scripts/version-jeux.py`, puis commit : `Sources et jauges : harmonisation pixel`.

### Task 7: Justifications par lots

**Files:**
- Modify: `site/data/affirmations.json` (ajouts de `pourquoi`, `confiance`, `confiance_pourquoi`, `appui`)
- Create (hors dépôt, dans `private/` de Kairos) : une page de revue par thème, sur le modèle de `private/maquettes/crible-pourquoi/revue-verdicts.html`

- [ ] **Step 1: Par thème, rédaction des brouillons par un agent**, à partir des seuls faits et sources de chaque carte :
  - `pourquoi`, qui relie les faits au verdict et dit ce qui retient d'un verdict plus fort ou plus faible ;
  - `confiance`, selon les critères du barème ;
  - `appui`.

  Une carte dont le verdict paraît incohérent avec ses faits n'est pas justifiée. Elle est signalée à part.
- [ ] **Step 2: Validation.** Une page de revue par thème (garder, corriger, note), dont la synthèse est collée dans la session. Les corrections sont appliquées, puis commit par thème : `Affirmations : justifications <thème>`.
- [ ] **Step 3:** quand tous les thèmes sont faits, `python3 scripts/controle-affirmations.py --strict` sort avec le code 0. Commit : `Affirmations : justification obligatoire`.
