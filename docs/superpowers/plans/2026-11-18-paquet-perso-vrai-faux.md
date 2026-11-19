# Paquet perso de « Vrai ou faux ? » — plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** composer, jouer et partager un paquet de « Vrai ou faux ? » filtré par mot-clé, thèmes et camps, aussi lançable depuis la rubrique Affirmations.

**Architecture:** un module pur `site/assets/paquet.js` (correspondance, jetons, fragment, résumé) partagé par la rubrique (`app.js`) et le jeu (`pol/vrai-faux.js`) ; le jeu remplace « un thème » par « des filtres » ; le routage passe la partie `?…` du fragment au module du jeu.

**Tech Stack:** JS ES modules sans dépendance, CSS existant (`pol/pol.css`, `app.css`), tests `node --test` (Node 22).

**Spec:** `docs/superpowers/specs/2026-11-18-paquet-perso-vrai-faux-design.md` (dépôt Kairos). Code cible : dépôt **Mimir**, `site/`.

## Global Constraints

- CSP stricte : aucun script ni attribut `style` en ligne (positions via CSSOM / `data-style` comme l'existant).
- Fragment de lien : `#vrai-faux?q=<mot-clé encodé>&t=<jetons thèmes>&c=<jetons camps>` ; paramètres vides omis.
- Jeton de thème = `themeHash(libellé)` (`site/assets/rubriques.js`) ; jeton de camp = même calcul (`gauche`, `centre`, `droite`, `extreme-droite`) ; « Camp non établi » = `aucun` (camp vide/absent dans les données).
- Logique : thèmes entre eux OU, camps entre eux OU, thèmes × camps × mot-clé ET ; aucun thème (ou camp) choisi = tous.
- Recherche : sans accents ni casse, sur phrase + thème + camp + faits (balises retirées) — identique à la rubrique.
- Paquet jouable à partir de **3 cartes** ; sous ce seuil, bouton inactif avec le texte « Il faut au moins 3 cartes ».
- Pas de filtre par verdict ; depuis la rubrique, un filtre de verdict actif est ignoré et le bouton l'annonce : « verdict ignoré pour ne pas dévoiler les réponses ».
- Libellés exacts : « Composer mon paquet », « Mon dernier paquet », « Jouer ces N cartes » (jeu), « Jouer ces N phrases » (rubrique), « Partager ce paquet », « Lien copié », « Camp non établi », préfixe « Paquet perso : ».
- Mémoire locale : clé `crible-vf-paquet`, lecture/écriture sous try/catch ; absente ou illisible = pas de tuile.
- Ne pas déplacer `expected` hors de `pol/vrai-faux.js` : `scripts/controle-affirmations.py` (`controler_jeu`) y lit la liste des verdicts jouables.
- Après tout changement d'asset : `python3 scripts/version-jeux.py`.
- Commits : français, génériques, `--no-gpg-sign`, aucune mention d'IA.

## Review Focus

1. Mot-clé avec majuscules, accents, espaces autour (« OQTF », « oqtf », «  Égalité ») → mêmes résultats que la rubrique. (Tâche 1, `correspond`.)
2. Lien abîmé : jeton inconnu, paramètre répété, `q` contenant `&`, `,`, espace ou accent → aller-retour exact, inconnu ignoré, jamais d'exception. (Tâche 1, `depuisFragment`.)
3. Lien de paquet ouvert alors que le jeu est déjà chargé ou qu'une partie est en cours → écran de départ pré-rempli, partie abandonnée. (Tâche 3, vérif navigateur.)
4. Composition sous 3 cartes, ou 0 → bouton inactif + raison, aucune partie lancée. (Tâche 2.)
5. Stockage local indisponible (exception à l'accès) → aucune erreur console, pas de tuile « Mon dernier paquet ». (Tâche 2, vérif navigateur.)

---

### Task 1: Module `paquet.js` (logique pure) et tests

**Files:**
- Create: `site/assets/paquet.js`
- Test: `scripts/test_paquet.mjs`
- Modify: `README.md` (une ligne : `node --test scripts/` dans la section des contrôles)

**Interfaces:**
- Produces (exports de `site/assets/paquet.js`) :
  - `SANS_CAMP = "aucun"`
  - `jeton(libelle: string): string` — `themeHash` réutilisé (import depuis `./rubriques.js`).
  - `correspond(claim: {q, theme, camp, facts}, f: Filtres): boolean` avec `Filtres = { q: string, themes: string[], camps: string[] }` ; `themes`/`camps` sont des **libellés** ; `""` dans `camps` = camp non établi ; `q` brut (replié à l'intérieur). Texte de recherche mis en cache par claim (WeakMap).
  - `versFragment(f: Filtres): string` → `"vrai-faux"` ou `"vrai-faux?q=…&t=…&c=…"`.
  - `depuisFragment(query: string, themes: string[], camps: string[]): Filtres` — `query` = ce qui suit `?` ; `themes`/`camps` = libellés connus pour traduire les jetons ; inconnu ignoré.
  - `resume(f: Filtres): string` — mot-clé entre guillemets français, puis thèmes, puis camps, séparés par « · » ; au-delà de 2 thèmes : « Immigration, Justice +2 » ; vide = « Toutes les phrases ».
  - `vide(f: Filtres): boolean`.

- [ ] **Step 1: Write the failing tests** (`node:test`, `node:assert/strict`), données fictives :

```js
const C = [
  { q: "Les OQTF ne sont jamais exécutées", theme: "Immigration", camp: "Droite", facts: ["10 % d'exécution"] },
  { q: "L'égalité salariale est atteinte", theme: "Droits des femmes", camp: "", facts: ["<b>Écart</b> de 14 %"] },
  { q: "La dette explose", theme: "Dette et déficit", camp: "Gauche", facts: [] },
];
const F = o => ({ q: "", themes: [], camps: [], ...o });
test("mot-clé sans casse ni accents, espaces ignorés", () => {
  for (const q of ["OQTF", "oqtf", "  oqtf "]) assert.equal(correspond(C[0], F({ q })), true);
  assert.equal(correspond(C[1], F({ q: "egalite" })), true);
  assert.equal(correspond(C[1], F({ q: "ecart" })), true);      // faits, balises retirées
});
test("OU entre thèmes, ET entre familles", () => {
  const f = F({ themes: ["Immigration", "Dette et déficit"], camps: ["Gauche"] });
  assert.deepEqual(C.map(c => correspond(c, f)), [false, false, true]);
});
test("camp non établi", () => {
  assert.deepEqual(C.map(c => correspond(c, F({ camps: [""] }))), [false, true, false]);
});
test("fragment aller-retour, caractères spéciaux", () => {
  const f = F({ q: "R&D, à 3 %", themes: ["Droits des femmes"], camps: ["Extrême droite", ""] });
  const s = versFragment(f);
  assert.match(s, /^vrai-faux\?/);
  assert.match(s, /c=extreme-droite,aucun/);
  assert.deepEqual(depuisFragment(s.split("?")[1], ["Droits des femmes"], ["Extrême droite"]), f);
});
test("fragment vide et jetons inconnus", () => {
  assert.equal(versFragment(F({})), "vrai-faux");
  assert.deepEqual(depuisFragment("t=inconnu,immigration&c=zz&x=1&t=dette-et-deficit", ["Immigration", "Dette et déficit"], ["Gauche"]),
    F({ themes: ["Immigration", "Dette et déficit"] }));
  assert.deepEqual(depuisFragment("%E0%A4%A", [], []), F({}));   // encodage cassé : pas d'exception
});
test("résumé", () => {
  assert.equal(resume(F({})), "Toutes les phrases");
  assert.equal(resume(F({ q: "oqtf", themes: ["Immigration"], camps: [""] })), "« oqtf » · Immigration · Camp non établi");
  assert.equal(resume(F({ themes: ["A", "B", "C", "D"] })), "A, B +2");
});
```

Paramètre répété : les valeurs s'additionnent (dédoublonnées), comme le test l'attend.

- [ ] **Step 2: Run** `node --test scripts/` — Expected: FAIL (module introuvable).
- [ ] **Step 3: Implement** `site/assets/paquet.js` selon l'interface (`URLSearchParams` pour lire/écrire ; `decodeURIComponent` protégé par try/catch).
- [ ] **Step 4: Run** `node --test scripts/` puis `python3 -m pytest -q scripts` — Expected: tout PASS.
- [ ] **Step 5: Commit** `Paquet perso : module de filtres partagé et tests`.

### Task 2: Composer, jouer et retrouver un paquet perso dans le jeu

**Files:**
- Modify: `site/assets/pol/vrai-faux.js` (`deckOf`, `startHTML`, `play`, état `S`, récapitulatif, `start`)
- Modify: `site/assets/pol/pol.css` (tuile dépliable, puces, compteur ; grammaire pixel existante des `.vf-pack` / puces `.chip`)

**Interfaces:**
- Consumes: Task 1 (`correspond`, `resume`, `vide`).
- Produces:
  - `deckOf(claims, camps, pred: (claim) => boolean)` — les paquets de thème existants passent `c => c.theme === t` (ou `() => true`).
  - `S.paquet = { nom: string, filtres: Filtres | null }` remplace `S.theme` ; `filtres === null` pour les paquets de thème existants.
  - `play(paquet)` ; `afficherDepart({ filtres?: Filtres, ouvert?: boolean })` (remplace `showStart`, mêmes appelants).

- [ ] **Step 1:** `deckOf` prend un prédicat ; adapter `startHTML`/`play`/« Rejouer » ; vérifier que les 39 paquets existants affichent les mêmes nombres qu'avant (relever 3 compteurs avant/après dans le navigateur).
- [ ] **Step 2:** Tuile « Composer mon paquet » en tête de `.vf-packs`, repliée par défaut (`<details>` ou bouton `aria-expanded`), contenant : champ `type="search"`, puces thèmes et camps (`aria-pressed`, camps = `CAMPS` + « Camp non établi »), compteur `role="status"` « N cartes · M camps », bouton « Jouer ces N cartes » (inactif + « Il faut au moins 3 cartes » sous 3). Mise à jour à chaque saisie/appui.
- [ ] **Step 3:** Lancer le paquet : `play({ nom: "Paquet perso : " + resume(f), filtres: f })` ; enregistrer `f` sous `crible-vf-paquet` (try/catch). Tuile « Mon dernier paquet » (résumé + nombre de cartes) si la clé est lisible et donne au moins 3 cartes.
- [ ] **Step 4:** Barre de jeu, annonce `say(…)` et récapitulatif utilisent `S.paquet.nom`.
- [ ] **Step 5: Vérifier au navigateur sans interface** (desktop et 375 px, clair et sombre) : composer « oqtf » → compteur cohérent avec la rubrique ; 2 cartes → bouton inactif avec la raison ; jouer 3 cartes ; recharger → « Mon dernier paquet » présent ; avec `localStorage` rendu inaccessible (surcharge du getter qui lève) → 0 erreur console, pas de tuile ; clavier du jeu inchangé (← Maj+→ 1 4 Entrée) et aucune touche du jeu ne réagit pendant la saisie. 0 erreur console, 0 défilement horizontal.
- [ ] **Step 6:** `python3 scripts/version-jeux.py`, `node --test scripts/`, `python3 -m pytest -q scripts`, `python3 scripts/controle-affirmations.py` → PASS. Commit `Vrai ou faux : composer et rejouer son paquet`.

### Task 3: Lien de partage et routage

**Files:**
- Modify: `site/assets/app.js` (`show`, entrée `"vrai-faux"` de `SECTIONS`)
- Modify: `site/assets/pol/vrai-faux.js` (export `ouvrirPaquet`, bouton de fin de partie)

**Interfaces:**
- Consumes: Task 1 (`versFragment`, `depuisFragment`), Task 2 (`afficherDepart`, `S.paquet`).
- Produces: `export function ouvrirPaquet(query: string): void` dans `vrai-faux.js` — abandonne toute partie, affiche le départ avec la composition dépliée et pré-remplie (`depuisFragment(query, thèmes connus, CAMPS)`), sans lancer.

- [ ] **Step 1:** `show()` : vue = segment avant `/` **ou** `?` (les deux lignes qui font `split("/")`) ; mémoriser la requête du fragment quand la vue est `vrai-faux`. Le rendu de la section appelle `m.start(d)` puis `m.ouvrirPaquet(q)` si une requête attend ; si la section est déjà prête, `show` importe le module (même URL versionnée) et appelle `ouvrirPaquet`. L'ancre `#a-<id>` et les vues `jeux/…` restent inchangées.
- [ ] **Step 2:** Fin de partie d'un paquet perso : bouton « Partager ce paquet » → URL `location.origin + location.pathname + "#" + versFragment(S.paquet.filtres)` ; `navigator.share` s'il existe, sinon `navigator.clipboard.writeText` puis annonce « Lien copié » (statut visible + `say`). Échec du presse-papiers : afficher l'URL sélectionnable.
- [ ] **Step 3: Vérifier au navigateur sans interface** : ouvrir directement `#vrai-faux?q=oqtf&t=immigration` → départ pré-rempli, compteur juste, pas de lancement ; pendant une partie en cours, changer le fragment vers un lien de paquet → départ pré-rempli ; `#vrai-faux?t=inconnu` → composition vide, 0 erreur ; `#a-<id>` existant toujours fonctionnel ; « Partager » → presse-papiers contient le lien attendu.
- [ ] **Step 4:** version-jeux, tests, contrôles → PASS. Commit `Vrai ou faux : partager un paquet par lien`.

### Task 4: « Jouer ces N phrases » depuis la rubrique Affirmations

**Files:**
- Modify: `site/assets/app.js` (`renderFilters`, `renderCards`)
- Modify: `site/assets/app.css` (bouton, dans la grammaire des touches pixel)

**Interfaces:**
- Consumes: Task 1 (`correspond`, `versFragment`), Task 3 (routage `#vrai-faux?…`).

- [ ] **Step 1:** `renderCards` utilise `correspond(x, { q, themes: theme ? [theme] : [], camps: camp ? [camp] : [] })` à la place de son test de thème/camp/mot-clé (le filtre de verdict reste local à la rubrique) ; résultats identiques à avant (vérifier 3 recherches : « oqtf », « dette », thème + camp).
- [ ] **Step 2:** Sous les filtres, lien-bouton « Jouer ces N phrases » vers `#` + `versFragment(…)`, N = affirmations correspondantes hors `non-documente` (seul verdict exclu du jeu, garanti par `controler_jeu`) ; masqué sous 3 ; si un filtre de verdict est actif, mention « verdict ignoré pour ne pas dévoiler les réponses ».
- [ ] **Step 3: Vérifier au navigateur sans interface** : rubrique, chercher « oqtf » → bouton avec N ; clic → jeu, départ pré-rempli avec le même N ; filtre de verdict actif → mention visible ; 0 erreur console, 375 px sans défilement horizontal.
- [ ] **Step 4:** version-jeux, tests, contrôles → PASS. Commit `Affirmations : jouer la sélection dans Vrai ou faux`.
