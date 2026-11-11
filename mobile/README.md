# Kairos — application Android

Application compagnon de Kairos : fil du jour, décisions, conversation.
Kotlin + Jetpack Compose, identifiant `app.kairos.mobile`, Android 14 (API 34) minimum.

## Compiler

Prérequis : JDK 21 et le SDK Android (plateforme 36, build-tools 36).

```bash
export JAVA_HOME=/chemin/vers/jdk-21
export ANDROID_HOME=/chemin/vers/Android/Sdk   # ou sdk.dir dans local.properties (non versionné)
cd mobile
./gradlew testDebugUnitTest assembleDebug
```

L'APK de debug sort dans `app/build/outputs/apk/debug/app-debug.apk`.

## Installer

Téléphone en mode développeur, débogage USB activé :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Appairer

L'application ne parle qu'à la tête de Kairos, avec un jeton propre à l'appareil.
Les commandes ci-dessous sont celles de la tête (`tete pair|revoke|devices`).

1. Sur l'hôte de la tête : `tete pair <nom-de-l-appareil>` affiche un QR code
   (adresse de l'API + jeton). En conteneur :
   `docker compose exec tete /app/tete pair <nom>`.
2. Au premier lancement, l'application propose de scanner ce QR code
   (ou de coller l'adresse `kairos://pair?...` affichée).
3. Pour retirer un appareil : `tete revoke <nom>` ; `tete devices` les liste.

Côté serveur, l'API de l'application doit être activée et `TETE_APP_PUBLIC_URL`
renseignée (l'adresse que le téléphone utilisera) avant `pair` ; les autres
variables `TETE_APP_*` (écoute, port) sont décrites dans la documentation de
déploiement de la tête.

Le téléphone doit pouvoir joindre l'adresse de l'API (réseau privé).

## Fonctionnalités

- **Aujourd'hui** : fil du jour chronologique, glisser pour marquer fait / plus tard
  (annulable), pincer pour la semaine, actualisation et dernier état hors ligne.
- **Décisions** : pile de cartes à trancher d'un geste.
- **Parler à Kairos** : bouton central, appui long pour dicter, verrou pour les mains
  libres, ou saisie au clavier ; notification si la réponse arrive hors de l'application.
- **Réglages** (icône ⚙ en haut d'Aujourd'hui) : thème Clair / Sombre / Auto
  (défaut Clair), lecture vocale des réponses, appareil appairé (adresse masquée en
  partie) et « Se déconnecter » (efface le jeton et revient à l'appairage).

Le mode Auto écoute le capteur de lumière uniquement au premier plan, avec deux seuils
et un délai pour éviter le clignotement ; sans capteur il suit le thème du téléphone.

## Espace Mimir

Affiche dans l'application un site web (« Le Crible 2027 ») à partir d'un lien de
partage personnel, sans navigateur à ouvrir. L'adresse n'est jamais dans le code :
elle se colle sur l'appareil.

1. Onglet Espaces → **Mimir** : au premier passage, coller le lien de partage
   (`https://exemple.org/...?k=...`) puis **Enregistrer**. Seuls les liens `https://`
   sont acceptés.
2. Le lien porte un jeton : il est chiffré par la clé de l'appareil (comme le jeton
   d'appairage) et n'apparaît dans aucun journal.
3. L'icône ⚙ de l'espace permet de recharger la page, de modifier le lien ou de
   l'effacer (cookies et stockage du site sont alors oubliés).

Le site est chargé avec l'agent utilisateur suffixé de `KairosApp`. Le bouton retour
revient à la page précédente du site, puis ferme l'espace. Les liens vers un autre
domaine, les nouvelles fenêtres, `mailto:` et `tel:` s'ouvrent hors de l'application.

## Limites connues

- Pas encore testée sur un appareil réel : compilation et tests unitaires seulement.
- Pas d'écran Espaces, pas de saisie hors ligne : une demande exige le serveur.
- L'écran de démarrage suit le thème du système, pas le réglage de l'application.
