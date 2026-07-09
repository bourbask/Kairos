# Roadmap P3 — Jarvis Personal Assistant

> **Note de merge** : Ce document vient du projet "Jarvis" et sert de référence pour la roadmap long terme. Kairos (le projet réel) a une roadmap resserrée dans PLAN.md (§Phases). Certaines hypothèses sont obsolètes : l'infrastructure est VPS (pas PC maison), le modèle local est phi3:mini (pas mistral-nemo), la mémoire est SQLite (pas agentcairn). Les critères comportementaux et la règle 80/20 sont excellents et conservés.

**Horizon** : 24 mois | **Vélocité** : 5–10 h/semaine | **Infrastructure** : RTX 2070 · Ollama · Claude API | **Versions** : v0.1 → v2.0

---

## 1. Executive Summary — Vision 2 ans

Dans 24 mois, Jarvis est l'interface unique entre toi et le bruit quotidien : emails, finances, projets, décisions. Il ne pense pas à ta place — il réduit le coût cognitif d'arriver à une décision. La différence est fondamentale et ne doit jamais être perdue de vue.

La trajectoire va d'un outil CLI qui répond aux questions (v0.1) à un système qui connaît ton contexte, anticipe les frictions récurrentes, et s'intègre aux autres projets du labo (P1 finances, P2 investissement). À chaque étape, la valeur est réelle avant que la complexité augmente.

| Version | Horizon | Promesse centrale | Seuil d'arrêt |
|---------|---------|-------------------|---------------|
| v0.1 | Semaines 1–4 | Premier retour réel, zéro overhead | Utilisé ≥ 1×/jour pendant 5 jours consécutifs |
| v0.2 | Mois 2–3 | Jarvis se souvient de toi | Utilisé ≥ 3×/jour pendant 2 semaines |
| v0.3 | Mois 3–5 | Jarvis t'interrompt au bon moment | ≥ 1 suggestion proactive acceptée / semaine |
| v1.0 | Mois 6–12 | Système intégré, intégrations P1/P2 | Score de charge mentale hebdo stable ou en baisse |
| v2.0 | Mois 12–24 | Multi-modal, présence ambiante | Remplacement mesurable d'outils tiers |

> **Étape zéro — bloquante (ADR-007).** Avant toute ligne de code : écrire par écrit 3 à 5 objectifs personnels clairs pour les 6 prochains mois. Jarvis optimise dans la direction donnée. Sans direction écrite, il optimise vite dans la mauvaise.

---

## 2. Philosophie d'implémentation

### Pourquoi progressif ?

La principale cause d'échec des projets d'assistant personnel n'est pas technique — c'est l'abandon après la phase de construction initiale. Le builder construit indéfiniment ; l'utilisateur n'arrive jamais. L'approche progressive force un changement de rôle : à chaque version, tu es *d'abord* un utilisateur, *ensuite* un développeur.

### La règle 80/20 posée au départ

**80 % du temps hebdomadaire alloué = utilisation de Jarvis. 20 % = amélioration de Jarvis.** Toute semaine où le ratio s'inverse est un signal d'alerte, pas un progrès. La règle s'applique dès v0.1 et ne change jamais.

### Comment éviter le "projet parfait jamais terminé"

- **Critères Done comportementaux, pas techniques.** Jarvis est prêt pour la version suivante quand tu l'utilises d'une certaine façon, pas quand le code est propre.
- **Features gelées pendant l'usage.** En phase de validation d'une version, aucun ajout de feature n'est autorisé. Les idées vont dans un backlog froid.
- **Time-box de correction.** Si une friction dure plus de 2 sessions sans être résolue, elle va dans le backlog — elle ne bloque pas la prochaine étape.
- **Un seul chantier actif.** On ne commence pas v0.2 tant que v0.1 n'a pas atteint son seuil d'arrêt. Pas d'exception.

> **Risque principal — tinkering.** Construire Jarvis *est* une forme de procrastination pour un profil builder. Surveiller : sessions de dev sans usage réel, ajout de features non demandées par l'usage, refactoring avant que la valeur soit prouvée.

---

## 3. Versions & Milestones

### v0.1 — Jarvis CLI Minimal (Semaines 1–4)

**Objectif :** Premier retour réel. Jarvis répond à des questions en connaissant le contexte minimal : qui tu es, tes projets actifs, les objectifs écrits (étape zéro).

**Features**
- Script CLI `jarvis ask "..."`
- Fichier contexte statique YAML (profil, objectifs, projets)
- Routing modèle : `phi3:mini` local par défaut, `claude-sonnet` sur flag `--cloud`
- Log simple des interactions (SQLite ou fichier texte)
- Commande `jarvis log` — note rapide capturée en < 30 s

**Critères Done**
- Utilisé ≥ 1×/jour pendant 5 jours consécutifs
- Au moins 3 décisions réelles assistées (notées dans le log)
- Temps de réponse < 3 s sur CPU (phi3:mini)
- Aucune fuite de données vers le réseau (audit manuel)
- Objectifs écrits présents dans le fichier contexte

**Métriques d'impact humain**
- Nb de questions posées par semaine (baseline)
- Score de charge mentale initial (1–10, check-in J1)
- Nb d'oublis auto-détectés en fin de semaine

**Effort :** 8–12 h · Maintenance/jour : ~0 min · Modèle : phi3:mini · Coût API : ≈ 0 €/mois

---

### v0.2 — Mémoire + Contexte enrichi (Mois 2–3)

**Objectif :** Jarvis se souvient. Les conversations précédentes, les décisions prises, les contextes récurrents alimentent les réponses. Le drift de mémoire est adressé structurellement dès le départ.

**Features**
- Mémoire épisodique (agentcairn) — DuckDB local
- Mémoire sémantique — embeddings sur notes Obsidian
- Rappel Calendar mensuel automatique "Révision mémoire 15 min"
- Commande `jarvis context` — affiche ce que Jarvis sait de toi
- TTL explicites : épisodique 90 j, sémantique 1 an, profil permanent
- Interface Markdown dans Obsidian (aucun vendor lock)

**Critères Done**
- Utilisé ≥ 3×/jour pendant 2 semaines complètes
- Jarvis cite correctement un contexte passé dans ≥ 50 % des sessions
- Révision mémoire effectuée au moins 1 fois
- Aucun context stale > 30 jours non flagué

**Métriques d'impact humain**
- Réduction oublis déclarée (semaine 1 vs semaine 8)
- Taux de réponses "pertinentes sans re-context"
- Score charge mentale — comparaison baseline v0.1

**Dépendances** : v0.1 seuil d'arrêt atteint · agentcairn installé · Obsidian configuré · mistral:7b ou :13b sur RTX 2070

**Effort :** 15–20 h · Maintenance/jour : < 2 min · Modèle : mistral:7b · Coût API : ≈ 1–3 €/mois

---

### v0.3 — Suggestions proactives (Mois 3–5)

**Objectif :** Jarvis initie au bon moment — jamais sur les objectifs sans mandat explicite, toujours sur les frictions opérationnelles récurrentes.

**Features**
- Digest quotidien optionnel (résumé contexte + alertes)
- Détection de patterns récurrents (emails non traités, tâches idle)
- Suggestions liées uniquement aux objectifs écrits en étape zéro
- Commande `jarvis weekly` — bilan de semaine structuré
- Flag `--coach-mode` à activer explicitement pour les questions d'alignement objectifs
- Budget tokens calculé et loggé par session

**Critères Done**
- ≥ 1 suggestion proactive acceptée par semaine pendant 4 semaines
- Zéro suggestion liée aux objectifs sans `--coach-mode` actif
- Taux d'acceptation des suggestions ≥ 40 %
- Score charge mentale en baisse vs baseline v0.2

**Métriques d'impact humain**
- Nb décisions assistées / semaine (comparaison v0.1)
- Taux suggestions acceptées (loggé automatiquement)
- Nb frictions récurrentes réduites (auto-déclaré mensuel)

**Dépendances** : v0.2 seuil d'arrêt atteint · Accès Gmail configuré · Pipeline anonymisation emails implémenté

**Effort :** 20–25 h · Maintenance/jour : < 2 min · Modèle : mistral:13b · Coût API : ≈ 3–8 €/mois

---

### v1.0 — Jarvis Complet (Mois 6–12)

**Objectif :** Système intégré. Jarvis reçoit du contexte de P1 (finances) et P2 (investissement), il a une interface plus accessible que le CLI pur, et constitue la colonne vertébrale du labo IA domestique.

**Features**
- Interface web locale légère (FastAPI + HTMX ou Streamlit)
- Intégration P1 — alertes budget et résumés financiers anonymisés hebdomadaires
- Intégration P2 — signaux d'alerte portefeuille injectés en contexte
- API MCP exposée pour Claude Code et autres agents
- Workflow révision objectifs trimestrielle guidée
- Export données complètes (droit à l'oubli self-service)
- Dashboard métriques d'usage personnel

**Critères Done**
- Score charge mentale hebdo stable ou en baisse sur 6 semaines
- Au moins 1 intégration P1 ou P2 active et utile quotidiennement
- Jarvis utilisé sans friction lors d'une session de travail typique
- Coût API total ≤ budget défini
- Audit privacy complété

**Métriques d'impact humain**
- Nb décisions assistées — objectif : +50 % vs v0.1
- Score charge mentale — objectif : −2 points vs baseline
- Outils tiers remplacés par Jarvis (comptage)
- Taux d'acceptation suggestions ≥ 55 %

**Effort :** 60–80 h · Maintenance/jour : < 5 min · Modèles : phi3 + mistral:13b + claude · Coût API : ≈ 10–20 €/mois

---

### v2.0 — Multi-modal + Intégrations complètes (Mois 12–24)

**Objectif :** Présence ambiante. Jarvis traite des inputs vocaux ou visuels selon les opportunités matérielles, s'intègre dans les flux de travail legal2digital si pertinent.

**Features (conditionnelles)**
- Input vocal — Whisper local sur RTX 2070
- Analyse documents visuels (PDF contrats, relevés)
- Intégration calendrier natif (lecture/écriture)
- Agents autonomes pour tâches récurrentes déléguées
- Mode professionnel — contexte legal2digital isolé
- Sync multi-device via serveur local (optionnel)

**Critères Done**
- Au moins 1 outil tiers payant remplacé
- Jarvis actif sur au moins 2 surfaces différentes
- Score charge mentale stable sur 3 mois consécutifs
- Checkpoint éthique M24 passé

> **Note v2.0 :** Les features de cette version sont intentionnellement conditionnelles. Elles dépendent des modèles locaux disponibles à 12–24 mois (évolution rapide). Ne pas planifier v2.0 en détail avant le mois 9.

**Effort estimé :** 80–120 h · Coût API : À réévaluer en mois 9

---

## 4. Timeline visuelle

```
MOIS     01   02   03   04   05   06   07   08   09   10   11   12   18   24
         ╔════╦════╦════╦════╦════╦════╦════╦════╦════╦════╦════╦════╦════╦════╗
v0.1     ║████║    ║    ║    ║    ║    ║    ║    ║    ║    ║    ║    ║    ║    ║
v0.2     ║    ║████║████║    ║    ║    ║    ║    ║    ║    ║    ║    ║    ║    ║
v0.3     ║    ║    ║    ║████║████║    ║    ║    ║    ║    ║    ║    ║    ║    ║
v1.0     ║    ║    ║    ║    ║    ║████║████║████║████║████║████║████║    ║    ║
v2.0     ║    ║    ║    ║    ║    ║    ║    ║    ║    ║    ║    ║    ║████║████║
         ╚════╩════╩════╩════╩════╩════╩════╩════╩════╩════╩════╩════╩════╩════╝

ÉTAPE ZÉRO ────► S0  (bloquante avant tout démarrage)
CHECKPOINTS ────► M3 ───────────────── M6 ─────────────────── M12 ────── M24
RÉVISIONS MEM ──► M1─M2─M3─M4─M5─M6─M7─M8─M9─M10─M11─M12 (mensuel, 15 min)
INTÉGR P1/P2 ───────────────────────────────────► M6 ─────────► M12
```

### Jalons clés

| Jalon | Date cible | Livrable | Décision |
|-------|-----------|---------|----------|
| J0 — Étape zéro | Avant J+7 | Fichier objectifs 6 mois écrit et versionné | Aller / no-go v0.1 |
| J1 — v0.1 Done | Semaine 4 | Log 5 jours d'usage, 3 décisions assistées | Aller / no-go v0.2 |
| J2 — v0.2 Done | Mois 3 | Mémoire active, révision complétée | Aller / no-go v0.3 |
| J3 — Checkpoint M3 | Mois 3 | Revue charge mentale + patterns usage | Ajustement priorités ou arrêt |
| J4 — v0.3 Done | Mois 5 | Suggestions actives, taux acceptation ≥ 40 % | Aller / no-go v1.0 |
| J5 — Checkpoint M6 | Mois 6 | Revue éthique + coûts + objectifs | Périmètre v1.0 confirmé |
| J6 — v1.0 Done | Mois 12 | Intégrations P1/P2, dashboard métriques | Aller / no-go v2.0 + scope |
| J7 — Checkpoint M24 | Mois 24 | Revue globale 2 ans | Continuation, refonte, ou consolidation |

---

## 5. Indicateurs de progression

Les métriques techniques (latence, tokens, uptime) sont des conditions nécessaires, pas des indicateurs de succès. Les vrais indicateurs mesurent ce que Jarvis change dans la vie quotidienne.

| Métrique | Cible | Signal d'alerte |
|---------|-------|----------------|
| Décisions assistées / semaine | ≥ 5 (v0.3), ≥ 10 (v1.0) | < 2 pendant 2 semaines |
| Taux suggestions acceptées | 40–60 % | < 25 % → arrêt suggestions proactives 1 semaine |
| Charge mentale (1–10) | −2 pts vs baseline à M12 | Stable ou hausse sur 3 semaines |
| Oublis / semaine | −50 % vs baseline | Augmentation vs mois précédent |

### Méthode de collecte

| Métrique | Comment | Fréquence | Outil |
|---------|---------|-----------|-------|
| Décisions assistées | Log auto après chaque `jarvis ask` avec tag `--decision` | Temps réel | SQLite local |
| Taux acceptation | Feedback `y/n` après chaque suggestion | À la suggestion | SQLite local |
| Charge mentale | Check-in hebdo — `jarvis weekly` pose la question | Hebdo (dimanche) | Fichier Obsidian |
| Oublis | Auto-déclaré lors du check-in hebdo | Hebdo | Fichier Obsidian |
| Cohérence objectifs | Revue trimestrielle guidée | Trimestriel | Session coach-mode |

---

## 6. Gouvernance des données personnelles

### Matrice de stockage

| Donnée | Stockage | TTL | Réseau |
|--------|---------|-----|--------|
| Objectifs personnels | Fichier YAML local | Permanent (révision trimestrielle) | Jamais |
| Log conversations | SQLite local | 90 jours glissants | Jamais |
| Mémoire sémantique (notes) | DuckDB local (agentcairn) | 1 an | Jamais |
| Soldes CMB | Fichier local chiffré (AES) | 30 jours | Jamais |
| Portefeuille Trade Republic | Fichier local chiffré (AES) | 30 jours | Jamais |
| Emails (contenu brut) | Jamais stockés | — | Jamais |
| Emails (anonymisés) | Mémoire session uniquement | Session | API seulement |
| Métriques d'usage | SQLite local | 2 ans | Jamais |

### Pattern d'anonymisation emails

Avant tout envoi vers l'API Claude :
- Noms propres → pseudonymes génériques : `Personne_A`, `Entreprise_X`
- Montants exacts → catégories : `<1k€`, `1k–10k€`, `>10k€`
- Adresses email → `[EMAIL_REDACTED]`
- Numéros de référence → `[REF_XXX]`

### Droit à l'oubli — self-service (v1.0)

- `jarvis forget --all` — supprime toutes les données persistantes + rapport de suppression horodaté conservé 30 jours
- `jarvis forget --before 2025-01-01` — purge sélective par période

---

## 7. Checkpoints éthiques

La question centrale à chaque checkpoint : **Jarvis aide-t-il ou crée-t-il une dépendance problématique ?**

| Moment | Question | Signal sain | Signal d'alerte | Action |
|--------|----------|------------|----------------|--------|
| M3 | Est-ce que je peux fonctionner une semaine sans Jarvis sans anxiété ? | Oui, avec légère friction | Non, ou résistance forte | Semaine "Jarvis off" délibérée |
| M6 | Mes décisions sont-elles encore les miennes ? | Jarvis informe, je décide | "Jarvis m'a dit de faire ça" sans raisonnement propre | Basculer en mode purement réactif |
| M6 | Le coût de construction est-il proportionnel à la valeur ? | Ratio usage/dev ≥ 50 % | Ratio usage/dev < 50 % sur le mois | Pause du projet |
| M12 | Jarvis amplifie ce qui compte ou me distrait ? | 5 usages les plus fréquents couvrent mes objectifs écrits | Usages sans rapport avec les objectifs | Recalibration |
| M12 | La confidentialité est-elle intacte ? | Aucune donnée nominale sur le réseau | Donnée financière ou legal2digital dans les logs | Audit complet + correction |
| M24 | Ce projet mérite-t-il de continuer sous cette forme ? | Valeur > coût maintenance | Alternatives simples disponibles sans Jarvis | Go/no-go sans biais de continuité |

---

## 8. Plan de risques

| Risque | Type | Probabilité | Impact | Mitigation |
|--------|------|------------|--------|------------|
| **Tinkering infini** — construction sans usage réel | Humain | Élevée | Critique | Règle 80/20 non négociable. Features gelées en phase validation |
| **Drift mémoire** — contexte obsolète | Technique | Moyenne | Élevé | TTL explicites + rappel Calendar mensuel bloquant |
| **Fuite données personnelles** vers API Cloud | Privacy | Faible | Critique | Pipeline anonymisation systématique. Audit à chaque checkpoint |
| **Dérive coût API** | Financier | Moyenne | Modéré | Budget mensuel explicite. Alertes automatiques. Priorité Ollama local |
| **Dépendance P1/P2** non livrés en temps | Projet | Moyenne | Modéré | v0.1–v0.3 fonctionnels sans P1/P2. Intégration optionnelle jusqu'en v1.0 |
| **Obsolescence des modèles locaux** | Technique | Faible | Faible | Couche `llm_call()` isole la logique du choix de modèle |
