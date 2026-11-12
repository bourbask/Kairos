# Espace Politique — lot 2 : refonte native

## Intention

Le lot 1 a livré un espace natif générique, jugé illisible. Une maquette interactive (hors dépôt, dossier privé
`private/maquettes/politique-v3/`, `index.html` + `NOTES.md`) a été validée par l'utilisateur. Le lot 2 la porte
en Compose, fidèlement : mêmes écrans, mêmes gestes, mêmes hiérarchies, mêmes états vides.

Le jeu de données s'est enrichi (fichiers plus gros, nouvelles rubriques) : le module doit livrer des fichiers
qui dépassent la taille d'une réponse.

## Ce que l'utilisateur doit retrouver

- **Pas de barre de recherche** : les données sont mises en scène (carrousels, cartes à retourner, jeux, carte).
- **Couleur d'identité** partout où un candidat, un parti ou un camp est cité ; **couleurs d'état** neutres pour
  les verdicts (jamais celles d'un parti).
- **Sources** : toujours une action nommée, jamais une URL brute ; seules les URL `https` s'ouvrent.
- **Balance derrière chaque chiffre** (champ `lecture`) : phrase courte de contrepartie et postes visibles sans
  effort, texte complet dans une feuille.
- **Évaluateurs** : étiquette colorée selon le niveau d'indépendance (1 à 4), qui ouvre la fiche de l'évaluateur.
- **Dit / voté** : le taux principal ne compte que la parole publique hors séance ; la ligne annoncée en séance
  est affichée à part ; « aucune déclaration » n'est jamais un succès.
- **Carte** : bulles par région à l'échelle du pays, repères regroupés quand ils se chevauchent.
- **Mode « À table »** sur les cartes partageables ; **duel** sans vainqueur ; **quiz** à quatre réponses.

## Contrat du module

Nouvel outil `app_politique_fichier { id, page }` : renvoie un fichier de données tel quel, découpé en pages
de taille bornée quand il dépasse la borne de réponse. Les nouvelles rubriques ont un titre ; le fond de carte
est un fichier de service. Le reste du contrat du lot 1 est inchangé.

## Hors périmètre

Recherche plein texte, écriture sur l'appareil au-delà du cache commun, tests tactiles sur appareil (faits par
l'utilisateur après livraison).

## Contraintes du dépôt public

Aucun nom réel (personne, parti, organisme, média, ville) dans le code, les commentaires, les tests ou les
fixtures : tout libellé nominatif vient des données à l'exécution ; fixtures fictives.
