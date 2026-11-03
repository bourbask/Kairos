# Module documents administratifs

## Intention

Les documents administratifs de l'utilisateur (contrats, assurances, bail,
bulletins de salaire, véhicule, santé, impôts…) dorment dans un dossier
synchronisé, rangé par grandes natures. Il veut les interroger en
conversation :

- « Quand dois-je passer le contrôle technique ? »
- « Mon assurance auto couvre-t-elle tel sinistre ? »
- « Compare les offres d'accès internet avec mon contrat actuel. »

et, plus tard, ajouter, déplacer ou classer des documents.

## Décisions

- **Un seul tentacule `documents`** : dépôt, index et faits extraits, étiquetés
  par nature (dossier de premier niveau). Pas un tentacule par nature
  administrative : un même document relève souvent de plusieurs natures, et le
  stockage, l'extraction et l'index seraient dupliqués. Les outils sont par
  intention, au-dessus de ce dépôt unique.
- **Le véhicule** relève de ce module tant qu'il s'agit de documents (contrôle
  technique, assurance, certificat d'immatriculation, factures). Un module
  technique (kilométrage, diagnostic embarqué) ne viendra que sur besoin réel.
- **Source de vérité : le drive cloud de l'utilisateur**, synchronisé sur le
  poste par son client officiel (l'accès WebDAV n'est pas disponible avec son
  offre). Le tentacule lit ce dossier **en lecture seule**.
- **Sauvegarde locale séparée**, quotidienne : la synchronisation étant
  bidirectionnelle, une suppression locale se propagerait au cloud. Une copie
  datée (`rclone sync --backup-dir archives/<date>`) conserve tout fichier
  modifié ou supprimé ; rien n'y est jamais effacé.
- **Confidentialité** : extraction et indexation en local, aucun document
  envoyé entier vers un service tiers ; les outils renvoient des passages
  ciblés, jamais un document complet. Des **natures privées** (pièces
  d'identité, signatures, photos d'identité…) sont comptées mais jamais
  listées, cherchées ni lues ; leur désignation tolère casse et accents.
- **Accès hors domicile** : par le réseau privé (tailnet), jeton porteur.

## Étape 1 — socle (livrée)

- Inventaire périodique (15 min) : nature, type, taille, date ; seul ce qui
  change est relu, ce qui disparaît est retiré.
- Texte : couche texte des PDF (`pdftotext`), texte brut / Markdown, `.docx`.
  Images et PDF scannés : comptés « sans texte lisible ».
- Index plein texte SQLite FTS5, insensible aux accents.
- Outils : `docs_overview`, `docs_list`, `docs_search`, `doc_excerpt`
  (passages autour des mots demandés, plafonnés).
- Déploiement : `deploy/pc/` (services `documents` et `documents-backup`).

## Étape 2 — extraction (livrée)

- **Lecture des scans** : images et PDF sans couche texte passent par la
  reconnaissance de caractères (`tesseract`, français ; PDF : 8 pages au plus,
  converties à 200 ppp). Les documents déjà indexés sans texte sont relus une
  fois (version d'extraction).
- **Faits par un modèle local** (Ollama sur le GPU du poste, non publié) :
  réponse contrainte par un schéma JSON (type, organisme, référence, résumé,
  dates typées, montants). Le modèle ne fait que lire ; les dates invalides ou
  partielles sont rejetées par le code. Faits réextraits quand le document
  change ou que le modèle change ; un échec n'est pas retenté en boucle.
- **Échéances calculées par le code**, pas par le modèle : fins, échéances,
  renouvellements, fins d'engagement, dates limites de résiliation,
  expirations ; contrôle technique à +2 ans du dernier, premier contrôle à
  +4 ans de la mise en circulation.
- **Natures privées** : seules l'échéance et le type de document sortent
  (« document privé »), ni organisme, ni référence, ni montant, ni chemin.
- Outils : `deadlines`, `contract_facts`, `brief_section` ; `docs_overview`
  indique l'avancement de l'extraction.
- **Calendrier « Échéances »** en CalDAV (créé au besoin) : une journée par
  échéance, rappel une semaine avant, événements transparents, synchronisation
  incrémentale.
- Choix du modèle mesuré sur documents réels : un modèle de 8 milliards de
  paramètres tient dans la mémoire disponible ; un 9B ne se charge pas à côté
  de la stack du poste.

## Étape suivante

3. **Écritures** en deux temps (aperçu, puis `confirm=true`) dans le dossier
   synchronisé ; « supprimer » = déplacer vers une corbeille ;
   `missing_documents` (pièces attendues par nature).
