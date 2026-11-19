# Aides de lecture — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** sur le site, quand les aides sont affichées (médaillon ou touche `?`), marquer les termes et blocs expliqués ; un appui fait venir la mascotte et ouvre une bulle d'explication.

**Architecture:** un module pur `site/assets/aides-texte.js` (repérage des termes, règles de densité) testé sous Node ; un module DOM `site/assets/aides.js` (marquage, bulle, déplacement de la mascotte) branché sur l'état `html[data-aides]` déjà posé par `app.js` ; données `site/data/aides.json` contrôlées par `scripts/controle-aides.py`.

**Tech Stack:** JS ES modules sans dépendance, CSS du site (DA pixel), `node --test`, pytest.

**Spec:** `docs/superpowers/specs/2026-11-18-aides-de-lecture-design.md` (dépôt Kairos). Maquette validée (fait foi pour le rendu) : `private/maquettes/crible-aides/aides.html` (hors dépôt ; source lisible : scratchpad `aides-src.html`). Code cible : dépôt **Mimir**, `site/`.

## Global Constraints

- Aides **masquées par défaut** ; état sur `<html data-aides="on|off">`, clé `crible-aides`, déjà gérés par `poserAides` dans `app.js` (médaillon `.medal`, touche `?`) : ne pas dupliquer cet état.
- Aides masquées : **aucune** marque dans le DOM (rien de focalisable en plus), texte identique à aujourd'hui.
- Termes : soulignement pointillé pixel ; première occurrence par carte ou section ; **3 marques au plus par carte** ; jamais dans un titre (`h1`–`h4`), un lien, un bouton, un champ, une étiquette de source (`.stag`), une `kbd`.
- Blocs (`graphique:<id>`, `affirmation:<id>`, `section:<id>`) : pastille « ? » pixel, une par bloc ; graphique : touche « Comment lire ce graphique ».
- Bulle : explication simple, « En savoir plus » qui déplie le détail, source si présente ; fermeture Échap, clic à côté, nouvel appui ; focus rendu à la marque. Téléphone (< 640 px) : feuille en bas.
- Mascotte : quitte le médaillon, se pose **dans l'en-tête de la bulle** (jamais sur le texte lu), détourée d'une case sombre hors de sa tuile ; retour au médaillon à la fermeture. Si un jeu embarque la mascotte (médaillon replié), la bulle s'ouvre seule.
- Animations 150–350 ms en `steps()` ; `prefers-reduced-motion` : pas de déplacement, bulle directe.
- Élément expliqué surligné légèrement (contour pointillé + voile ~6 %, pas d'aplat).
- Rappel discret : tant que les aides n'ont jamais été activées, petit sursaut de la mascotte à l'arrivée sur une section qui a au moins 5 aides ; 3 fois au plus (compteur `crible-aides-rappel`), plus jamais après une première activation. Aucune bulle.
- Réglages (dialogue « Apparence ») : groupe « Aides de lecture » — « Masquées par défaut » / « Toujours visibles » — lié au même état `crible-aides`.
- CSP stricte : ni script ni `style` en ligne (positions par CSSOM).
- `aides.json` : `simple` ≤ 280 caractères, `detail` ≤ 900, source obligatoire si l'aide contient un chiffre ; ids uniques ; une forme ne pointe que vers un terme ; cibles existantes.
- Après tout changement d'asset : `python3 scripts/version-jeux.py` (ajouter les nouveaux modules à sa liste `files`).
- Tests : `node --test scripts/test_*.mjs`, `python3 -m pytest -q scripts`.
- Commits : français, génériques, `--no-gpg-sign`, aucune mention d'IA.

## Review Focus

1. Terme au milieu d'un mot ou collé à une ponctuation (« PIB, », « (OQTF) », « PIBs », « l'OQTF ») → marqué seulement sur frontière de mot, apostrophe élidée gérée. (Tâche 1.)
2. Terme qui chevauche deux nœuds texte (`<b>dette</b> publique`) → ignoré proprement, jamais de HTML cassé. (Tâche 3.)
3. Section re-rendue (filtres de la rubrique, carte retournée, paquet de jeu) alors que les aides sont affichées → marques reposées, pas de doublon, pas de marque orpheline. (Tâche 3.)
4. Bulle ouverte puis changement de rubrique, rotation d'écran ou redimensionnement → bulle fermée ou repositionnée, mascotte rendue au médaillon. (Tâche 4.)
5. Stockage indisponible → aides masquées, pas d'erreur, réglage sans effet persistant mais sans exception. (Tâche 5.)

---

### Task 1: Repérage des termes (logique pure) et tests

**Files:**
- Create: `site/assets/aides-texte.js`
- Test: `scripts/test_aides_texte.mjs`

**Interfaces:**
- Produces:
  - `indexer(termes: {id, formes: string[]}[]): Index` — prépare la recherche (formes triées de la plus longue à la plus courte).
  - `occurrences(texte: string, index: Index): {debut: number, fin: number, id: string}[]` — insensible à la casse et aux accents, frontières de mot Unicode, élision gérée (« l'OQTF » → « OQTF »), sans chevauchement (la forme la plus longue gagne).
  - `densite(occ, deja: Set<string>, max = 3): occ[]` — garde la première occurrence de chaque id absent de `deja`, au plus `max` ; met à jour `deja`.

- [ ] **Step 1: Write the failing tests** (`node:test`), dont :

```js
const I = indexer([{ id: "pib", formes: ["PIB", "produit intérieur brut"] }, { id: "oqtf", formes: ["OQTF"] }, { id: "dette", formes: ["dette publique", "dette"] }]);
test("frontières et ponctuation", () => {
  assert.deepEqual(occurrences("Le PIB, (OQTF) et PIBs", I).map(o => o.id), ["pib", "oqtf"]);
  assert.deepEqual(occurrences("l'OQTF", I).map(o => [o.debut, o.fin]), [[2, 6]]);
});
test("casse, accents, forme la plus longue", () => {
  assert.deepEqual(occurrences("La Dette Publique et le produit interieur brut", I).map(o => o.id), ["dette", "pib"]);
  assert.equal(occurrences("La dette publique", I)[0].fin, 17);
});
test("densité : première occurrence, 3 au plus", () => {
  const deja = new Set();
  const occ = occurrences("PIB dette OQTF PIB dette", indexer([...["pib","dette","oqtf","x"]].map(id => ({ id, formes: [id] }))));
  assert.deepEqual(densite(occ, deja).map(o => o.id), ["pib", "dette", "oqtf"]);
  assert.deepEqual(densite(occ, deja), []);
});
```

- [ ] **Step 2: Run** `node --test scripts/test_*.mjs` — Expected: FAIL (module absent).
- [ ] **Step 3: Implement** `aides-texte.js` (repli des accents comme `paquet.js`, mais en conservant les positions du texte d'origine).
- [ ] **Step 4: Run** tests — Expected: PASS.
- [ ] **Step 5: Commit** `Aides de lecture : repérage des termes`.

### Task 2: Données et contrôle

**Files:**
- Create: `site/data/aides.json` (contenu relu fourni par le contrôleur : `scratchpad/aides/aides.json`)
- Create: `scripts/controle-aides.py`, `scripts/test_controle_aides.py`
- Modify: `README.md` (contrôle à lancer)

**Interfaces:**
- Consumes: Task 1 (aucune ; données seules).
- Produces: `aides.json` au format de la spec §4 (`termes[]`, `blocs[]`).

- [ ] **Step 1: Write failing tests** pour `controle-aides.py` : id en double → erreur ; forme partagée par deux termes (comparaison sans casse ni accents) → erreur ; `simple` > 280 ou `detail` > 900 → erreur ; chiffre dans `simple`/`detail` sans `source` → erreur ; cible `affirmation:<id>` absente de `affirmations.json` → erreur ; cible `graphique:<id>` dont l'id n'existe pas dans `index.html` → erreur ; fichier conforme → code 0.
- [ ] **Step 2: Run** `python3 -m pytest -q scripts` — Expected: FAIL.
- [ ] **Step 3: Implement** le contrôle (même style que `controle-themes.py`) et copier `aides.json`.
- [ ] **Step 4: Run** `python3 scripts/controle-aides.py` puis pytest — Expected: PASS, 0 erreur.
- [ ] **Step 5: Commit** `Aides de lecture : données et contrôle`.

### Task 3: Marquage des termes et des blocs

**Files:**
- Create: `site/assets/aides.js`
- Modify: `site/assets/app.js` (chargement d'`aides.json` à la première activation ; appel après chaque rendu de section ; branchement sur `poserAides`)
- Modify: `site/assets/app.css` (marques)

**Interfaces:**
- Consumes: Task 1 (`indexer`, `occurrences`, `densite`), Task 2 (`aides.json`), `poserAides(on)` existant.
- Produces: `export function marquer(racine: Element, donnees)`, `export function demarquer(racine: Element)` ; chaque marque est un `<button type="button" class="aide-t" data-aide="<id>">` (terme) ou `<button class="aide-b" data-aide-bloc="<cible>">` (bloc).

- [ ] **Step 1:** `marquer` parcourt les nœuds texte (TreeWalker) des conteneurs de contenu, hors exclusions des contraintes ; conteneur de densité = la carte (`#cards .flip`, `.box`, section) la plus proche ; un terme coupé entre deux nœuds texte est ignoré. `demarquer` remet le texte d'origine (aucune marque ne reste).
- [ ] **Step 2:** Branchement : aides `on` → marquer la section visible (et à chaque rendu : filtres, retournement, `ensure`) ; `off` → `demarquer` partout. Un `MutationObserver` limité à la section visible est acceptable s'il évite les doublons (Review Focus 3).
- [ ] **Step 3: Vérifier au navigateur sans interface** (desktop, 375 px, clair/sombre) : aides off → DOM identique à avant (comparer `innerHTML` d'une section) ; on → OQTF marqué dans une carte Immigration, 3 marques au plus par carte, aucune dans titres/liens/étiquettes de source ; filtrer la rubrique puis revenir → pas de doublon ; `graphique:chart` porte « Comment lire ce graphique » ; 0 erreur console ; aucun défilement horizontal.
- [ ] **Step 4:** version-jeux (ajouter `aides.js`, `aides-texte.js`), tests → PASS. Commit `Aides de lecture : marquage des termes et des blocs`.

### Task 4: Bulle et mascotte

**Files:**
- Modify: `site/assets/aides.js`, `site/assets/app.css`

**Interfaces:**
- Consumes: Task 3 (marques `.aide-t` / `.aide-b`), médaillon `.medal` et fonction `hote()` existante d'`app.js` (exporter ce qu'il faut).
- Produces: `ouvrirAide(marque: Element)`, `fermerAide()`.

- [ ] **Step 1:** Bulle selon la maquette : titre (terme ou « Comment lire ce graphique »), `simple`, « En savoir plus » (`aria-expanded`) qui déplie `detail`, source (étiquette du site via `SRC.list` si elle a une adresse). `role="dialog"` non modal, focus sur la bulle à l'ouverture ; Échap, clic à côté, nouvel appui sur la marque ferment ; focus rendu à la marque ; élément expliqué surligné.
- [ ] **Step 2:** Mascotte : vol médaillon → en-tête de la bulle (≤ 350 ms, `steps()`), retour à la fermeture ; mouvement réduit : pas de vol ; médaillon replié (jeu) : bulle seule. Une seule mascotte visible à tout moment.
- [ ] **Step 3:** < 640 px : bulle en feuille du bas, élément expliqué remonté au-dessus. Changement de rubrique, redimensionnement, rotation : fermer la bulle (Review Focus 4).
- [ ] **Step 4: Vérifier au navigateur sans interface** : parcours clavier complet (Tab vers marque, Entrée, « En savoir plus », Échap, focus rendu) ; feuille à 375 px ; mouvement réduit ; jeu avec mascotte embarquée ; une seule mascotte visible ; 0 erreur console.
- [ ] **Step 5:** version-jeux, tests → PASS. Commit `Aides de lecture : bulle et mascotte`.

### Task 5: Découverte et réglage

**Files:**
- Modify: `site/assets/app.js` (rappel discret, dialogue « Apparence »), `site/assets/app.css`

**Interfaces:**
- Consumes: `poserAides`, Task 2 (`aides.json` pour compter les aides d'une section).

- [ ] **Step 1:** Rappel : à l'affichage d'une section qui compte au moins 5 aides, si `crible-aides` n'a jamais valu `on` et que `crible-aides-rappel` < 3 : sursaut court de la mascotte (`steps()`, aucun en mouvement réduit), compteur incrémenté. Toutes les lectures/écritures sous try/catch.
- [ ] **Step 2:** Dialogue « Apparence » : groupe radio « Aides de lecture » — « Masquées par défaut » / « Toujours visibles » — qui appelle `poserAides`.
- [ ] **Step 3: Vérifier au navigateur sans interface** : 3 sursauts au plus sur 4 visites, aucun après une activation ; réglage reflète et modifie l'état ; stockage qui lève → 0 erreur, aides masquées (Review Focus 5).
- [ ] **Step 4:** version-jeux, tests → PASS. Commit `Aides de lecture : rappel discret et réglage`.
