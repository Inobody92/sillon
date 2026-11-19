# Sillon

Un lecteur de musique Android moderne pour **Synology Audio Station**.

DS audio n'a plus reçu de mise à jour depuis longtemps : pas de vraie gestion des playlists,
interface vieillissante, fonctions manquantes. Sillon reprend le même serveur (ton NAS, tes
fichiers) avec une application neuve : Kotlin, Jetpack Compose, Media3.

> Projet indépendant, non affilié à Synology Inc. « Synology » et « Audio Station » sont des
> marques de leurs propriétaires. Sillon utilise uniquement l'API web de ton propre NAS.

## État

Lots 1 à 3 : connexion au NAS (OTP, device de confiance), albums avec pochettes, lecture en arrière-plan (Media3) et gestion complète des playlists. Le protocole est documenté dans [`docs/API.md`](docs/API.md).

## Feuille de route

- [x] Connexion (adresse, compte, OTP) et stockage sécurisé
- [x] Albums, détail d'album, pochettes (artistes à venir)
- [x] Lecteur : arrière-plan, notification, écran de verrouillage (file d'attente éditable à venir)
- [x] Playlists : création, ajout depuis un album ou un titre, retrait, réordonnancement par glisser-déposer, renommage, suppression
- [ ] Recherche, hors-ligne, Android Auto, paroles, tags

## Compiler

Prérequis : JDK 17+ et Android SDK (compileSdk 36).

```
./gradlew assembleDebug
```

## Licence

MIT, voir [`LICENSE`](LICENSE).
