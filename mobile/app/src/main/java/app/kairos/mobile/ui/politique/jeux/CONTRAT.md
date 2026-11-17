# Contrat des jeux Politique (app Android)

Un jeu = **un fichier** `jeux/jeu/<NomDuJeu>.kt` + **une ligne** dans `jeux/Registre.kt`. Ne touchez à rien d'autre
(coque, kit, autres jeux). Besoin d'un composant commun absent du kit : signalez-le plutôt que de le modifier.

Données : `jeux.json` (fichier `jeux` du module), schéma `site/data/jeux.schema.md` côté données. Aucun contenu
politique en dur dans le code. Le slug du jeu = sa clé dans `JEUX`.

## Ce que la coque fait (ne pas le refaire)

Carte du jeu, titre, pager (points, flèches), compteur `n/6`, fenêtre question → réponse → « suivant » (geste, butée,
vibration, invitation à glisser), glissement auto après la bonne réponse, réponse complète depuis `reponse`
(titre, verdict coloré, texte en paragraphes `\n\n`, `cache`, sources double tag), étoile « du premier coup »,
parties de 6, fins de partie, progression persistée, paliers de la mascotte, annonces, animations réduites.

## Forme d'un jeu

```kotlin
@Serializable
data class MonD(val choix: List<String> = emptyList(), val bonne: Int = -1)   // forme de `d`, tolérante

class MonJeu : PolJeu<MonD>("mon-slug", MonD.serializer()) {
    override fun valide(d: MonD, params: JsonObject) = d.bonne in d.choix.indices   // manche écartée sinon
    override val glyphe = true                                          // facultatif : pictogramme citation
    @Composable override fun Fenetre(m: Manche, d: MonD, st: MancheEtat, c: Coque) { … }   // facultatif : défaut = kicker + q
    @Composable override fun Manette(m: Manche, d: MonD, st: MancheEtat, c: Coque) { … }
    override fun etiquette(m: Manche, d: MonD, st: MancheEtat, params: JsonObject): String? = …  // si verdict null
    @Composable override fun ColumnScope.ReponseEnPlus(m: Manche, d: MonD, st: MancheEtat) { … } // rare
}
```

Registre : `MonJeu(),` dans la liste de `JEUX_REGISTRE` (remplace la ligne `Exemple("mon-slug")` si elle existe).
Référence minimale : `jeu/Exemple.kt` (touches physiques + voyants).

## État et réponses

`MancheEtat` (observable, neuf à chaque manche) : `manche`, `page` (`Question`/`Reponse`/`Suivant`), `solved`,
`err`, `out` (clés refusées, dans l'ordre), `react` (incrémenté à chaque erreur : `LaunchedEffect(st.react)` pour
animer `st.out.last()`). Lecture seule pour le jeu, sauf `jeu` : emplacement libre pour l'état propre au jeu
(champ simple, à remplir d'un état observable créé une fois, ex. `st.jeu as? MutableIntState ?: mutableIntStateOf(0).also { st.jeu = it }`),
lisible aussi depuis `Fenetre` et `etiquette`.

`Coque` (`c`) :

| Membre | Rôle |
|---|---|
| `juste()` | bonne réponse, une fois (carte comptée, glissement vers la réponse) |
| `faux(cle, msg)` | mauvaise réponse : `cle` rejoint `out`, annonce « msg Réessayez. » ; grisez ensuite les clés de `out` |
| `annoncer(msg)` | lecteur d'écran |
| `cran()` | retour haptique court (cran, appui) |
| `reduit` | animations système coupées : durées à 0, rien ne bouge |
| `params` | `params` du jeu (constantes communes), à décoder par le jeu |
| `data` | `PolData` : évaluateurs (`evFor`), identités (avatars), etc. |

Après `solved`, la manette reste affichée, figée sur l'état final (bonne réponse marquée, erreurs grisées).

## Kit (`jeux/kit/`)

| Composant | Signature (essentiel) |
|---|---|
| Bouton physique | `BoutonPixel(label, onClick, glyphe = Glyphes.check, couleur = Teintes.valid, forme = Forme.Valider, mode = GlyphMode.Grave, enabled, maintenu, cellule = 3.dp)` ; formes `Valider`, `Carre`, `Rond`, `Large` ; glyphes `check`, `plus`, `minus`, `zero`, `play`, `redo` ou grille `"..##.."` ; `maintenu` = reste enfoncé |
| Valider | `BoutonValider(onClick, enabled)` |
| Teintes | `Teintes.valid`, `rouge`, `bleu`, `neutre`, `eteint`, `ombre` (clair/sombre) ; `mix(a, b, t)` |
| Voyant | `Led(LedEtat.Eteinte / Rouge / Verte)` |
| Tuiles | `val gd = rememberGlisserDeposer(c) { tuile, cible -> ok }` ; `Tuile(id, gd, label) { Text(…) }` ; `Emplacement(id, gd, label, plein = { … }) { Text(…) }` ; refus (false) : la tuile revient, l'emplacement tremble ; appui-appui inclus |
| Formes libres | `Modifier.glissable(id, gd, label, enabled)` (élément attrapable ; `gd.leve(id)` : levé) et `Modifier.cible(id, gd, label, enabled)` (`gd.survol == id` : survolée) |
| Tremblement | `val a = rememberTremblement(st.react, c.reduit, ici = st.out.lastOrNull() == cle)` puis `Modifier.tremble(a)` |
| Cadran | `Cadran(niveau 0..4, onNiveau, ecartes, description, libelle)` |
| Levier | `Levier(crans, valeur, onValeur, libelle, onCran = c::cran)` |
| Banderole | `Banderole(texte)` |
| Mascotte gravité | `MascotteGravite(niveau)` (décorative) |
| Pictos pixel | `PixelIcon(nom, couleur)`, `PixelGrid(grille, couleur)`, `PixelShape()` (coins en marches) |

Commun : `Etoile()`, `FenetreQuestion(m, citation)`, couleurs du thème (`MaterialTheme.colorScheme`, `KairosColors`,
`PolColors`), `VerdictChip`, `frTypo`.

## Règles

- Rendu fidèle à la maquette validée ; DA de l'app (jetons du thème, clair **et** sombre), aucune couleur en dur hors voyants.
- Pixel art pour tout élément physique : `Canvas` en cellules entières (`cell()`, `cellPx()`), aplats, marches, pas de dégradé ni d'ombre floue.
- Manette ≤ 260 dp de haut ; rien ne déborde à 390 × 800 dp ; aucun défilement horizontal dans la manette.
- Essais illimités, aucun indice ; pas de libellé superflu (garder `contentDescription`).
- Accessibilité : cibles ≥ 48 dp, `contentDescription`, annonces via `c.annoncer`, `c.reduit` respecté.
- Textes de données : `frTypo()` ; jamais de nom de jeu ou de thème en dur (ils viennent des données).
- Commentaires = intention seule ; aucune donnée personnelle dans les tests (valeurs fictives).
- Tests JVM de la logique pure du jeu dans `src/test/.../politique/` ; `./gradlew :app:testDebugUnitTest` vert.
