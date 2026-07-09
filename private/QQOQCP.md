# QQOQCP — P3 Jarvis Personal Assistant

**Rôle** : Business Analyst senior | **Périmètre** : Usage personnel / labo IA local | **Date** : 30 juin 2026 | **Statut** : Cadrage initial

Analyse QQOQCP · Cadrage stratégique et fonctionnel d'un assistant IA personnel — organisation, décisions, charge mentale.

---

## Q1 — QUI : L'utilisateur et le rôle de Jarvis

### Profil utilisateur et patterns actuels

Développeur tech-savvy dans une legal tech, capable de concevoir, configurer et maintenir un assistant IA local. La compétence technique est un atout mais aussi un piège : elle génère une tendance à sur-architecturer avant de valider les usages réels. C'est un profil de "builder" — la construction du système devient potentiellement plus gratifiante que son utilisation quotidienne.

**Ce qui coince, aujourd'hui :**
- **Fragmentation de l'attention** : les outils ne communiquent pas entre eux (agenda, projets, finances, objectifs). Chaque silo exige un effort de re-contextualisation.
- **Charge de décision diffuse** : des micro-décisions (quoi faire maintenant ? est-ce prioritaire ?) consomment de l'énergie cognitive qui devrait aller sur le travail réel.
- **Trous dans le planning** : les créneaux disponibles existent mais ne sont pas connectés aux intentions — résultat, on reporte ce qui compte pour faire ce qui est visible.
- **Boucles d'évitement** : certains projets ou obligations sont bloqués par un "pas envie de penser à ça maintenant" non articulé.
- **5 à 10h/semaine perso** : contrainte forte qui exige une priorisation chirurgicale — chaque heure compte.

**Pourquoi ça résiste depuis des années** : Ce n'est pas un problème d'outils, c'est un problème de modèle mental. L'organisation exige de l'utilisateur de définir ce qui compte — or c'est exactement la décision qu'on évite quand on est dans l'urgence ou la dispersion. Un assistant IA ne résout pas ça seul ; il peut cependant rendre le coût de cette clarté beaucoup plus bas.

### Le rôle de Jarvis : assistant ou coach ?

| Jarvis comme assistant | Jarvis comme coach |
|-----------------------|--------------------|
| Exécute des requêtes explicites | Détecte des patterns sans être interrogé |
| Résume, organise, rappelle | Pose des questions inconfortables |
| Répond aux questions du moment | Signale les incohérences valeurs/actions |
| Réduit le coût d'accès à l'information | Maintient une vision long terme |
| Rôle réactif, déclenché par l'utilisateur | Rôle proactif, potentiellement intrusif |

> **Recommandation de positionnement** : Jarvis doit être **assistant par défaut, coach sur invitation explicite**. Il ne doit jamais initier une confrontation sur les objectifs sans que l'utilisateur ait ouvert ce registre. La ligne de démarcation : un assistant fait ce qu'on lui demande ; un coach remet en question si on lui en donne le mandat. Confondre les deux crée une relation anxiogène avec l'outil.

---

## Q2 — QUOI : Fonctionnalités, données et types de décisions

### Fonctionnalités core vs. nice-to-have

| Fonctionnalité | Priorité | Valeur débloquée |
|----------------|----------|-----------------|
| Briefing matinal (planning du jour + contexte) | **Core** | Réduction friction de démarrage |
| Réponse aux questions de priorisation ("quoi faire là ?") | **Core** | Décharge cognitive immédiate |
| Suivi des projets actifs (état, prochain pas) | **Core** | Pas de trous invisibles |
| Intégration agenda + liste de tâches | **Core** | Vue unifiée du temps disponible |
| Journal de décisions (log "j'ai décidé X car Y") | **Core** | Réduction des regrets et cycles |
| Suggestions de lecture selon objectifs actifs | Nice-to-have | Alignement apprentissage/intention |
| Suggestions repas / routines santé | Nice-to-have | Cohérence style de vie |
| Analyse des finances (CMB + Trade Republic) | Nice-to-have | Alignement dépenses/objectifs |
| Détection d'incohérences entre projets | Nice-to-have | Éviter les conflits de ressources |
| Revue hebdomadaire automatisée | Nice-to-have | Boucle de rétroaction lente |

### Ce que Jarvis doit savoir / ne doit pas savoir

| Données à donner | Données à exclure ou limiter |
|-----------------|------------------------------|
| Projets actifs et leur état | Données bancaires brutes (numéros, soldes) |
| Créneaux disponibles (agenda) | Informations santé sensibles |
| Objectifs déclarés (court et long terme) | Communications confidentielles (emails, messages) |
| Contraintes récurrentes (horaires, engagements) | Données tierces (famille, collègues) sans consentement |
| Résumés de décisions passées | Données professionnelles employeur sous NDA |
| Centres d'intérêt et lectures en cours | |

### Types de décisions : assistées vs. autonomes

La frontière est celle de la réversibilité et de l'impact :

- **Décisions autonomes** (Jarvis peut agir seul) : créer un rappel, ajouter une note, générer un résumé, classer une information, proposer un créneau.
- **Décisions assistées** (Jarvis propose, l'utilisateur valide) : réorganiser une journée, reporter une tâche, suggérer une priorisation, recommander une ressource.
- **Décisions hors périmètre** (Jarvis ne touche pas) : envoyer un email, modifier un agenda partagé, effectuer une action financière, prendre des engagements en ton nom.

---

## Q3 — OÙ : Infrastructure et points de contact

### Stockage des données personnelles

| Type | Mode | Détail |
|------|------|--------|
| Données sensibles | 100% local | PC maison RTX 2070. Aucune donnée personnelle en clair vers un cloud externe. Ollama + RAG local sur fichiers Markdown/JSON. |
| Mémoire long terme | RAG local | Base vectorielle locale (ChromaDB ou équivalent). Fichiers texte versionnés git. Pas de provider cloud pour l'indexation. |
| Inférences LLM | Hybride contrôlé | Modèles locaux 7B-13B pour les requêtes avec contexte personnel. Claude API pour les tâches génériques sans données sensibles. |
| Synchronisation | Pull manuel | Pas de sync automatique. L'utilisateur choisit quand mettre à jour la base de connaissance de Jarvis. |

### Points de contact

- **CLI principal (PC maison)** : canal natif pour interactions complexes, revues, briefings. Claude Code ou interface custom. Accès complet à la base de connaissance.
- **CLI léger (PC pro / CPU)** : requêtes sans données sensibles via Claude API. Contexte minimal injecté manuellement si nécessaire.
- **Notifications push locales** : rappels déclenchés localement (cron + script), pas de push cloud. Idéalement via ntfy.sh auto-hébergé.
- **Fichier journal quotidien** : Markdown dans git — le "carnet de bord" que Jarvis lit et enrichit. Permet un audit humain complet.

---

## Q4 — QUAND : Fréquences d'interaction et moments clés

### Architecture temporelle d'une journée type

```
Briefing matinal (~7h — 5 min, proactif)
         ↓
Sessions de travail (Réactif sur demande) · Check mi-journée (Optionnel, ~12h30)
         ↓
Journal du soir (~21h — capture + 3 items demain)
         ↓
Revue hebdomadaire (Dimanche soir — 15 min, bilan + projections)
```

> **Principe clé** : Jarvis ne doit jamais interrompre une session de travail. Toutes les interactions proactives se situent dans des créneaux de transition naturelle (réveil, déjeuner, fin de soirée). Les notifications pendant une session brisent le flux cognitif — le seul bien non renouvelable.

### Rythmes d'interaction

| Mode | Fréquence | Règle |
|------|-----------|-------|
| Proactif | 2×/jour max | Briefing matin + journal soir. Rien d'autre sauf urgence explicitement définie par l'utilisateur. |
| Réactif | À la demande | Disponible 24/7 mais ne s'impose jamais. Le seuil d'accès doit être bas (commande courte). |
| Revue lente | 1×/semaine | Boucle de rétroaction longue. Dimanche, 15 min. Permet de voir les patterns qui échappent à la vue quotidienne. |
| Mise à jour mémoire | Manuel | L'utilisateur décide quand re-indexer. Évite une dérive silencieuse de la base de connaissance. |

---

## Q5 — COMMENT : Architecture mémoire et workflow d'interaction

### Architecture mémoire à deux niveaux

**Mémoire court terme (contextuelle)**
- Fenêtre de contexte active : journal des 7 derniers jours + tâches actives du jour
- Format : Markdown structuré injecté en début de prompt (≤2000 tokens)
- Contenu : projets en cours, décisions récentes, humeur/énergie signalée, créneaux du jour
- Durée de vie : session par session, regénéré à chaque interaction

**Mémoire long terme (RAG)**
- Base vectorielle locale : ChromaDB ou qdrant
- Sources indexées : journaux passés, décisions archivées, projets terminés, objectifs historiques
- Requête contextuelle : triggered uniquement quand la question porte sur le passé ou des patterns
- Mise à jour : hebdomadaire, manuelle

### Stratégie anti-saturation de contexte

La fenêtre de contexte est la ressource la plus précieuse. Elle se sature vite avec des données non filtrées.

- **Hierarchical summarization** : chaque fin de semaine, le journal quotidien est compressé en un résumé de 200 tokens. Les journaux bruts restent dans RAG mais ne chargent jamais directement en contexte.
- **Contexte minimum viable** : uniquement ce dont Jarvis a besoin pour répondre à la question posée — ne pas injecter tout le profil si la question est "quoi faire ce matin".
- **Séparation des couches** : les données statiques (objectifs annuels, contraintes permanentes) sont dans un fichier séparé chargé une fois ; les données dynamiques (agenda du jour) sont fraîches à chaque requête.
- **Décision de routing** : un pré-prompt court classe la requête (quotidien / stratégique / anecdotique) et détermine quel contexte charger.

### Workflow type : de l'input à la suggestion

```
Input utilisateur
  "Quoi faire ce matin ?" — "J'ai 2h, qu'est-ce qui bloque ?"
          ↓
Classification de la requête
  Opérationnel / Stratégique / Réflexif
          ↓
Chargement du contexte approprié
  Journée actuelle + projets actifs + décisions récentes (≤2k tokens)
          ↓
Raisonnement LLM local (7B-13B)
  Cohérence avec objectifs + contraintes d'énergie + urgences réelles
          ↓
Suggestion concrète + justification courte
  Max 3 options, avec "pourquoi maintenant"
          ↓
Feedback utilisateur (optionnel)
  Logué dans journal pour apprentissage différé
```

---

## Q6 — POURQUOI : Le vrai problème et les tentatives passées

### Le vrai problème : trois couches

| Couche | Problème | Nature |
|--------|---------|--------|
| **Surface** | Organisation | Les tâches ne sont pas trackées, les projets dérivent, les créneaux sont perdus. Visible, actionnable. |
| **Milieu** | Charge décisionnelle | Chaque choix consomme de l'énergie. Sans système de confiance, on repousse les décisions — y compris les petites. |
| **Profond** | Clarté sur ce qui compte | Le vrai blocage : on n'a pas défini ce qu'on veut vraiment. Aucun outil ne peut substituer cette clarté — mais Jarvis peut forcer cette conversation. |

> **Hypothèse centrale** : Jarvis ne résout pas le problème profond. Il réduit le coût d'accès à la clarté — en posant les bonnes questions, en tenant le contexte, en rendant la réflexion moins coûteuse à initier. Le problème reste humain. L'outil crée les conditions.

### Pourquoi les tentatives passées ont échoué

Les systèmes d'organisation personnelle échouent systématiquement pour les mêmes raisons — indépendamment de la qualité de leur conception :

1. **Coût de maintenance trop élevé** : dès qu'un système exige plus de 5 minutes/jour pour rester à jour, il meurt dans les premières semaines de surcharge.
2. **Granularité inadaptée** : trop détaillé = épuisant. Trop vague = inutile. Le bon niveau est celui qu'on maintient sans y penser.
3. **Pas de boucle de rétroaction** : on construit un système, on l'abandonne, mais on ne sait jamais pourquoi. Jarvis doit logger les moments d'abandon pour apprendre.
4. **Addiction à la configuration** (risque spécifique au profil tech) : construire le système est satisfaisant — le tinkering remplace l'usage réel. Règle : pas de reconfiguration majeure pendant les 90 premiers jours.
5. **Le système ne s'adapte pas à l'énergie disponible** : un lundi avec 8h devant soi ≠ un vendredi épuisé. Un système rigide échoue les jours difficiles, et c'est précisément là qu'il est le plus utile.

---

## Dimensions éthiques

### Over-reliance : le risque de délégation cognitive

L'over-reliance sur un assistant IA pour les décisions personnelles est un risque réel, documenté en psychologie cognitive sous le nom d'automation bias. La dépendance s'installe progressivement :

- Phase 1 : Jarvis suggère, l'utilisateur valide consciemment.
- Phase 2 : l'utilisateur valide par défaut, sans évaluation réelle.
- Phase 3 : l'utilisateur ne sait plus prioriser sans Jarvis.

**Garde-fous recommandés** : une session hebdomadaire sans Jarvis (revue "à la main"), un journal de désaccord ("j'avais une meilleure intuition que Jarvis sur X"), et une règle explicite : Jarvis ne décide jamais des priorités — il les éclaire.

### Privacy : des données de vie dans un LLM

Le risque fondamental n'est pas technique mais psychologique : l'utilisateur va progressivement confier plus d'informations à Jarvis parce que c'est pratique. Sans intention claire, la base de données personnelle s'élargit au-delà de ce qui était prévu.

- **Principe de minimisation** : ne donner à Jarvis que ce dont il a besoin pour répondre à la question du moment.
- **Séparation des espaces** : données perso ≠ données pro. L'employeur reste entièrement hors scope. Une configuration sépare les deux contextes physiquement.
- **Droit à l'oubli opérationnel** : mécanisme de purge des journaux passé un certain délai (journaux quotidiens bruts → archivés après 90 jours, supprimés après 365 jours sauf si explicitement conservés).

### Gouvernance : contrôle et auditabilité

- **Tout est lisible** : aucune donnée dans un format opaque. Markdown + JSON versionnés git. L'utilisateur peut lire exactement ce que Jarvis sait.
- **Pas d'actions irréversibles** : Jarvis ne peut pas modifier l'agenda, envoyer un message ou supprimer un fichier sans confirmation explicite.
- **Log d'activité** : chaque requête et réponse est archivée avec timestamp. Permet d'auditer les patterns d'utilisation et de détecter une dérive.
- **Mise à jour contrôlée** : quand le modèle change (nouvelle version d'Ollama), une période de validation avant de lui confier la mémoire complète.

---

## Mesure du succès — KPIs d'impact réel

La tentation est de mesurer l'usage (nombre de requêtes, temps de réponse). Ce sont des métriques techniques, pas d'impact. Les vrais indicateurs mesurent si la vie de l'utilisateur a changé.

| KPI | Définition | Cible |
|-----|-----------|-------|
| Projets livrés / mois | Nombre de projets personnels terminés | +50% vs baseline (M0) |
| Temps de démarrage matinal | Durée entre réveil et première tâche intentionnelle | < 20 min (vs baseline mesurée) |
| Taux de réalisation des intentions | % tâches planifiées le matin réalisées le soir | ≥ 70% sur 8 semaines |
| Décisions non reportées | Nb de décisions tranchées dans la journée vs. reportées | ratio ≥ 0.8 |
| Charge mentale perçue | Score subjectif hebdomadaire (1-10, journal) | -2 pts vs M0 à 3 mois |
| Taux d'utilisation soutenue | Semaines avec ≥5 interactions sur les 12 dernières | ≥ 9/12 (pas de décrochage) |
| Alignement valeurs/actions | % du temps alloué aux projets déclarés prioritaires | ≥ 60% du temps perso dispo |
| Désaccords productifs | Fois où l'utilisateur a pris une autre décision que Jarvis | 20-30% (signe d'autonomie maintenue) |

> **Méta-indicateur** : Le signal ultime de succès : dans 6 mois, l'utilisateur dit "je me souviens pas comment je faisais avant" — non pas parce qu'il est dépendant, mais parce que la clarté est devenue la norme.

---

## Hypothèses, contraintes & risques

| Risque / Hypothèse | Sévérité | Description |
|-------------------|----------|-------------|
| **Drift de la base mémoire** | Critique | Jarvis apprend une version obsolète de l'utilisateur. Si la mise à jour de la mémoire est manuelle et irrégulière, les suggestions deviennent incohérentes avec la réalité actuelle. Le système perd en pertinence sans qu'on sache pourquoi. |
| **Tinkering infini (trap du builder)** | Critique | Le plaisir de construire Jarvis devient lui-même une forme de procrastination déguisée en productivité. Des semaines passent à configurer ce qui n'est jamais vraiment utilisé. Règle de protection : 80% du temps sur l'usage, 20% sur la configuration. |
| **Saturation de la fenêtre de contexte** | Modéré | Un modèle 7B-13B local a une fenêtre de contexte limitée. Sans stratégie de compression stricte, les requêtes complexes dégradent silencieusement la qualité des réponses. Mitigation : hierarchical summarization + routing intelligent. |
| **Faux positifs d'urgence** | Modéré | Jarvis sur-priorise certaines tâches basé sur des signaux textuels (mots comme "urgent", "deadline") sans comprendre le poids réel. L'utilisateur développe une méfiance et cesse de faire confiance aux suggestions. |
| **Qualité modèle local insuffisante** | Modéré | Les modèles 7B-13B locaux ont des limites de raisonnement complexe. Pour des décisions stratégiques nuancées, ils peuvent générer des suggestions plausibles mais superficielles. Mitigation : réserver Claude API pour les requêtes à enjeu élevé. |
| **Résistance psychologique à la transparence** | Faible | Avoir un système qui "sait tout" sur soi peut devenir anxiogène. L'utilisateur commence à s'autocensurer dans le journal pour ne pas que Jarvis en tire des conclusions gênantes. |
| **Hypothèse : objectifs clairs en prérequis** | Hypothèse | Toute l'architecture suppose que l'utilisateur peut exprimer ses objectifs en texte structuré. Si les objectifs eux-mêmes sont flous ou contradictoires, Jarvis optimise dans la mauvaise direction. La clarté des objectifs doit précéder le déploiement. |

**Contrainte structurelle** : 5 à 10h/semaine de disponibilité perso est à la fois la contrainte la plus forte et la raison d'être de Jarvis. Ce budget ne permet pas un système complexe à maintenir. La simplicité n'est pas un compromis — c'est une exigence fonctionnelle. Chaque composante qui exige plus de 2 min/jour de maintenance doit être justifiée ou supprimée.

---

*Cadrage initial · v0.1 · 2026-06-30 · Projet P3 Jarvis Personal Assistant*  
*Dépendances : P1-Personal-Finance-Hub (données), P2-AI-Investment-Portfolio (signaux)*

---

<!-- Résumé exécutif original (conservé en référence) -->

## Résumé exécutif

L'analyse est publiée. Voici les points que je considère les plus importants de ce cadrage :

**Le vrai problème n'est pas technique.** Les trois couches du "pourquoi" sont : organisation (surface), charge décisionnelle (milieu), et clarté sur ce qui compte (profond). Jarvis peut attaquer les deux premières — la troisième reste un travail humain que l'outil rend seulement moins coûteux à initier.

**Deux risques critiques à surveiller dès le départ :**
1. Le tinkering infini — construire Jarvis *est* une forme de procrastination pour un profil builder. La règle 80/20 (usage/config) doit être posée avant de commencer.
2. Le drift de mémoire — si la mise à jour est manuelle et irrégulière, le système devient rapidement un oracle du passé qui parle du présent.

**Le positionnement assistant/coach est la décision architecturale la plus importante.** Jarvis ne doit jamais initier une confrontation sur les objectifs sans mandat explicite. Un outil qui se comporte comme un coach non sollicité devient anxiogène et finit abandonné.

**La contrainte 5-10h/semaine est une exigence fonctionnelle, pas un compromis.** Tout composant qui exige plus de 2 min/jour de maintenance doit être justifié ou supprimé. La complexité technique est l'ennemi principal ici.

**L'étape zéro avant tout déploiement :** définir par écrit 3 à 5 objectifs personnels clairs pour les 6 prochains mois. Jarvis ne peut pas optimiser dans la bonne direction si la direction n'est pas définie — il optimise juste plus vite dans la mauvaise.
