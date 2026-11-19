# Tester Android Auto sans voiture

Sillon expose sa bibliothèque à Android Auto via `PlaybackService` (un `MediaLibraryService`).
Arborescence : Radio (Mix du jour, humeurs), Albums, Artistes, Playlists, plus la recherche.

## Mise en place (une fois)
1. Installer le simulateur : `sdkmanager "extras;google;auto"` (outils en ligne de commande du SDK Android).
2. Sur le téléphone, dans Android Auto : taper 10 fois sur « Version » pour activer le mode développeur,
   puis dans « Paramètres pour développeurs » cocher **Sources inconnues** (Sillon est installé hors Play Store).

## À chaque session de test
1. Téléphone : réglages d'Android Auto, menu ⋮, **Démarrer serveur unité princ.**
2. Mac : `adb forward tcp:5277 tcp:5277` puis lancer `~/Library/Android/sdk/extras/google/auto/desktop-head-unit`.
3. Dans le lanceur d'applications de la voiture, ouvrir Sillon (réglages d'Android Auto, « Personnaliser le lanceur »,
   « Ordre personnalisé » pour le remonter en tête).

Le simulateur accepte des commandes dans son terminal : `tap x y`, `screenshot fichier.png`, `dpad …`, `keycode media`.
Android Auto tue parfois son processus : si `screenshot` répond « no video focus », relancer le serveur sur le téléphone.

## Pièges rencontrés
- Android Auto ne renvoie que l'identifiant du titre choisi (pas ses métadonnées) : le service retient le dossier
  d'origine de chaque titre affiché (`LibraryTree.parentOf`) pour continuer l'album ou la playlist.
- Tant que la voiture est connectée, le téléphone refuse d'ouvrir les réglages d'Android Auto.

## Bouton « Liker »
Le service ajoute à la session un bouton cœur (`sillon.TOGGLE_LIKE`) qui bascule le titre en cours dans la playlist
« Titres likés ». La notification, l'écran de verrouillage et Android Auto l'affichent sans code d'interface
supplémentaire ; son icône (cœur plein ou vide) suit l'état du titre en cours.
