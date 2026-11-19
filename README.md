# Sillon

Un lecteur de musique Android moderne pour **Synology Audio Station**.

DS audio n'a plus reçu de mise à jour depuis longtemps : pas de vraie gestion des playlists,
interface vieillissante, fonctions manquantes. Sillon reprend le même serveur (ton NAS, tes
fichiers) avec une application neuve : Kotlin, Jetpack Compose, Media3.

> Projet indépendant, non affilié à Synology Inc. « Synology » et « Audio Station » sont des
> marques de leurs propriétaires. Sillon utilise uniquement l'API web de ton propre NAS.

## Fonctions

- Connexion au NAS (adresse, compte, double authentification, appareil de confiance), identifiants chiffrés
- Albums, artistes, genres, recherche, pochettes
- Lecture en arrière-plan (notification, écran de verrouillage), file d'attente éditable, aléatoire, répétition,
  file conservée après redémarrage
- Playlists : création, ajout, retrait, réordonnancement par glisser-déposer, renommage, suppression
- Titres likés : une vraie playlist du NAS, un seul exemplaire par titre, cœur sur chaque titre
- Notation par étoiles
- Accueil : mix du jour (stable dans la journée, évite les titres écoutés récemment) et radios par humeur sans fin
- Hors-ligne : albums et playlists téléchargés (Wi-Fi uniquement par défaut), lecture sans NAS
- Android Auto : navigation (radio, albums, artistes, playlists) et lecture, testé avec le simulateur et en voiture
- Mode sombre, qualité audio (si le NAS sait convertir en MP3)

Le protocole est documenté dans [`docs/API.md`](docs/API.md) et le test d'Android Auto dans
[`docs/ANDROID_AUTO.md`](docs/ANDROID_AUTO.md).

## À faire

- [ ] Édition des tags
- [ ] Recherche vocale dans Android Auto (non vérifiée)

## Compiler

Prérequis : JDK 17+ et Android SDK (compileSdk 36).

```
./gradlew assembleDebug
```

## Licence

MIT, voir [`LICENSE`](LICENSE).
