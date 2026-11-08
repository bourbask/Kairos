# Application — espaces des modules (Finances d'abord)

Complète `2026-11-08-app-mobile-design.md`. Décidé avec l'utilisateur le
2026-11-08.

## Intention

Chaque module de Kairos a son espace dans l'application, accessible depuis
« Espaces » : un aperçu de son état et les actions utiles, sur un écran **conçu
pour lui** (comme l'Inventaire). « Aujourd'hui » reste l'entrée principale et
agrège l'essentiel de la journée.

## Principes

- **Écran dédié par module**, construit avec une **boîte à outils commune**
  (`ui/kit/`) : chiffre clé, jauge, barres, courbe, anneau, liste, bandeau
  d'état, montant masquable. Même identité partout, développement rapide.
- Écran générique de secours seulement pour un module sans écran dédié.
- Données lues par les outils `app_*` du module (passe-plat de la tête), en
  JSON ; dernier état gardé hors ligne comme ailleurs, lecture seule sans
  réseau.
- **Montants masquables** : un geste visible (icône œil) masque tous les
  montants des espaces (utile en public) ; réglage mémorisé.
- Ordre de réalisation : **Finances**, puis Agenda, puis Médias ; ensuite
  Marchés, Documents, Mémoire.

## Espace Finances

Sources : module finances (cycle budgétaire, enveloppes, alertes, « puis-je me
permettre ») et backend de finances (patrimoine, historique mensuel, prêts,
portefeuille).

Deux onglets :

**Cycle** (ouverture par défaut)
- En tête : **reste à vivre** du cycle (gros chiffre), jours restants, jauge
  d'avancement du cycle (début → fin, aujourd'hui marqué).
- **Enveloppes** : une barre par poste, dépensé / prévu, couleur ambre au-delà
  de 80 %, brique au-delà de 100 %.
- **Charges à venir** du cycle : liste datée avec montants.
- **Coussin** : solde face au plancher.
- **Alertes** du module (titre, détail, remède).
- **« Puis-je me permettre ? »** : bouton → saisie d'un montant + choix du
  poste en puces → verdict (oui/non, solde après, coupes proposées).
- Bandeau si données périmées ou consentement bancaire expiré (date de
  dernière synchronisation).

**Patrimoine**
- Patrimoine net (gros chiffre) et sa **courbe mensuelle**.
- Dettes et prêts (restant dû, mensualité, fin prévue).
- Portefeuille : **anneau** par catégorie de risque et liste des lignes.

Hors du premier jet : saisie de stratégie, flux détaillé (Sankey), liste des
opérations.

## Non-objectifs

- Écrire dans la stratégie de finances depuis l'application.
- Graphiques interactifs avancés (zoom, comparaison).
