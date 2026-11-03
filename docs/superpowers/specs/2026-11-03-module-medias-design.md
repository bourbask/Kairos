# Module médias

## Intention

L'utilisateur héberge une stack média complète (lecteur, gestionnaires de
séries / films / musique / livres, sous-titres, demandes, clients de
téléchargement, réencodage). Chaque application a son interface ; aucune ne
répond aux questions qu'il se pose réellement :

- « Qu'est-ce que je peux regarder ce soir, en VF ou avec des sous-titres
  français ? J'ai 1 h 30. »
- « Pourquoi le dernier épisode de X n'est pas encore là ? »
- « Qu'est-ce qui est en cours de téléchargement ? »
- « Quelles sont les prochaines sorties des séries que je suis ? »
- « Trouve-moi une série du genre de Y et ajoute-la. »

Le module branche cette stack sur Kairos par un tentacule MCP, comme le module
finances l'a fait pour l'application de finances.

## Décisions actées

| # | Sujet | Décision |
|---|---|---|
| D1 | Écritures | Toutes celles qu'exigent les usages, sur demande explicite. **Aucune suppression de fichier média.** « Ne plus suivre » arrête la surveillance, sans supprimer. |
| D2 | Source « vu / en cours » | Le lecteur (Plex). |
| D3 | Musique, lecture, livres audio | Dans le périmètre. Les bibliothèques sont vides : le module aide aussi à démarrer (suivre un artiste, une série de livres). |
| D4 | Notifications | Seulement (a) la fin d'une demande asynchrone faite à Kairos — fait, échec, délai dépassé ; (b) le manque d'espace disque. Le reste va dans le brief. |
| D5 | Ouvrir le lecteur | Chaque élément porte un lien web du lecteur. Pas d'ouverture active tant qu'il n'existe pas d'application Kairos. |
| D6 | Données transmises à la tête | Acceptées en phase de test. Durcissement (filtrage, tête auto-hébergée) à la fin de la phase. |

## Architecture

- **Tentacule `medias` en Rust**, même socle que les autres
  (`tentacle-common` : MCP HTTP sans état, jeton porteur).
- **Il tourne sur la machine qui héberge la stack**, dans son réseau Docker,
  et joint chaque service par son nom interne (`deploy/pc/`). La machine
  n'étant pas allumée en continu, une stack éteinte donne une réponse
  « injoignable », jamais une erreur bloquante.
- **Façade, pas moteur** : chaque outil interroge les API des services et
  renvoie un texte digéré. Le tentacule ne stocke rien (étape 1).
- **Chaque service est facultatif.** Déclaré par `MEDIAS_<NOM>_URL`, ses
  secrets deviennent obligatoires ; absent, les outils répondent sans lui.
- **Outils par intention**, pas un outil par point d'API : un outil interroge
  plusieurs services pour répondre à une question.
- **Les tentacules ne s'appellent pas entre eux** ; la tête compose (créneaux
  libres de l'agenda, puis `ready_to_watch max_minutes=…`). Exceptions
  d'infrastructure prévues : écriture CalDAV, envoi de notifications.
- **Santé par les API des services**, jamais par le socket Docker (accès
  équivalent à root).

## Catalogue

### Lecture — étape 1 (livrée)

| Outil | Rôle | Services |
|---|---|---|
| `ready_to_watch` | Prochain épisode des séries en cours et films récents non vus ; sous-titres FR (complets, forcés seulement, recherchés, absents), VF ; filtre de durée ; lien | lecteur, sous-titres, séries |
| `continue_watching` | Reprises en cours, temps restant | lecteur |
| `upcoming` | Épisodes suivis (heure locale) et sorties de films | séries, films |
| `media_status` | Où en est un titre et pourquoi il bloque ; sinon, résultats de recherche | séries, films, files, sous-titres, lecteur, demandes |
| `downloads` | File (avancement, temps restant, échecs regroupés), état des clients | séries, films, clients |
| `library_health` | Manquants, sous-titres manquants, échecs, réencodage, disque | séries, films, sous-titres, réencodage |
| `stack_health` | Services joignables, alertes, indexeurs en échec, tunnel du client torrent, client usenet en pause | tous |
| `brief_section` | Section médias du brief | lecteur, séries, files |

Correspondance lecteur ↔ gestionnaires par identifiants externes (TVDB, TMDB) :
les titres affichés diffèrent souvent (traductions).

### Étapes suivantes

2. **Écritures et suivi asynchrone** : `request` (demande d'une série / d'un
   film, d'un artiste, d'un livre), `follow` / `unfollow`, `retry` (recherche
   de la version ou des sous-titres), `blocklist_release`,
   `downloads_control` (pause, débit réduit), `mark_watched`. Les demandes
   sont suivies jusqu'au bout et notifiées (D4).
3. **Calendrier des sorties** poussé vers le serveur CalDAV (calendrier dédié),
   tant que la machine tourne ; consultable même machine éteinte.
4. **Musique, lecture, livres audio** (`music_updates`, `reading_updates`,
   `audiobooks`) ; `abandoned`, `watch_stats`, `seeding_obligations`.
5. `recap` (matière d'un résumé sans spoiler) ; débit réduit pendant les
   visioconférences (automatisme à placer : le tentacule ne lit pas l'agenda).

## Sécurité

- Jeton porteur obligatoire ; port publié sur la boucle locale.
- Secrets dans `deploy/pc/medias.env`, hors dépôt et hors contexte de build.
- Journaux sans adresse ni secret (nature de l'erreur seulement).
- Client torrent : session réutilisée, une seule reconnexion par appel — il
  bannit une adresse après quelques échecs.
- Lecture seule à l'étape 1.
