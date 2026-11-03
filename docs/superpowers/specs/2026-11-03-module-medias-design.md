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

### Actions — étape 2 (livrée)

Chaque écriture se fait **en deux temps** : sans `confirm`, l'outil décrit ce
qu'il ferait et ne touche à rien ; avec `confirm=true` (seulement après accord
explicite de l'utilisateur), il exécute.

| Outil | Effet |
|---|---|
| `search` | Recherche d'une série / d'un film à ajouter (type, identifiant, état) — lecture |
| `request` | Demande (saisons au choix) ; suivie jusqu'au bout |
| `requests` | Demandes suivies et leur état — lecture |
| `follow` / `unfollow` | Reprendre / arrêter la surveillance, sans rien supprimer |
| `retry` | Relancer la recherche de versions et/ou de sous-titres |
| `blocklist_release` | Écarter les versions en échec de la file et relancer (la bibliothèque n'est pas touchée) |
| `downloads_control` | Pause / reprise (torrents, usenet), débit réduit / normal |
| `mark_watched` | Marquer vu / non vu (film, épisode, saison, série) |

**Suivi des demandes** (tâche de fond, base SQLite sur un volume) :

- **prêt** : le film, ou le premier épisode demandé, est dans le lecteur avec
  VF ou sous-titres français complets → notification avec lien ;
- **échec** : demande refusée ou en échec → notification ;
- **en retard** : sorti depuis plus de 72 h (réglable) sans être regardable →
  notification une fois, le suivi continue ;
- **abandon** : au-delà de 60 jours (réglable) → notification.

Alerte disque : sous le seuil, au plus une notification par 24 h.

### Calendrier des sorties — étape 3 (livrée)

Tâche de fond (toutes les heures, réglable) qui pousse vers un calendrier
CalDAV dédié, créé au besoin :

- épisodes suivis : heure de diffusion et durée, « ✓ » une fois téléchargés ;
- films suivis : une journée entière par date de sortie (cinéma, numérique,
  physique) ;
- fenêtre de 14 jours passés à 90 jours à venir ;
- événements « transparents » : ils n'occupent pas l'agenda (créneaux libres) ;
- incrémental : seul ce qui change est réécrit, ce qui disparaît est retiré ;
  un gestionnaire injoignable interrompt la synchronisation sans rien retirer.

Le calendrier vit sur le serveur CalDAV : il reste consultable machine éteinte.
`stack_health` indique la dernière synchronisation.

### Bilans et autres médias — étape 4 (livrée)

| Outil | Rôle |
|---|---|
| `abandoned` | Séries commencées puis délaissées, en signalant celles encore surveillées |
| `watch_stats` | Bilan de visionnage : épisodes, films, temps estimé, séries les plus regardées |
| `seeding_obligations` | Terminés encore dus au partage (trackers privés) / limites atteintes — lecture seule |
| `music_updates` | Albums sortis récemment et à venir des artistes suivis |
| `reading_updates` | Lectures en cours (progression), ajouts récents, livres demandés |
| `audiobooks` | Livres audio en cours : progression, temps restant |

`search` couvre aussi les artistes (`kind=musique`) et les livres
(`kind=livre`) ; `request` accepte `artist` (albums à venir, ou discographie
complète) et `book` / `audiobook`. Bibliothèques vides : chaque outil indique
comment commencer. Le suivi jusqu'à disponibilité reste limité aux séries et
films.

### Résumé et visios — étape 5 (livrée)

- `recap` : synopsis des épisodes **déjà vus** de la saison (jusqu'au dernier
  vu, jamais au-delà ; épisodes spéciaux exclus), pour un résumé
  « previously » sans spoiler rédigé par la tête.
- **Débit réduit pendant les visios** (opt-in) : toutes les 5 minutes, lecture
  de l'agenda personnel en CalDAV (récurrences dépliées par le serveur) ; un
  événement en cours dont le titre, le lieu, la description ou le lien contient
  un mot-clé de visio réduit le débit des torrents, rétabli à la fin. Un
  réglage manuel n'est jamais défait. Exception d'infrastructure assumée : le
  tentacule lit l'agenda directement (CalDAV), sans passer par le tentacule
  agenda. `stack_health` indique l'état.

## Sécurité

- Jeton porteur obligatoire ; port publié sur la boucle locale.
- Secrets dans `deploy/pc/medias.env`, hors dépôt et hors contexte de build.
- Journaux sans adresse ni secret (nature de l'erreur seulement).
- Client torrent : session réutilisée, une seule reconnexion par appel — il
  bannit une adresse après quelques échecs.
- Écritures en deux temps (aperçu, puis `confirm=true`) ; aucune suppression
  de fichier de la bibliothèque.
