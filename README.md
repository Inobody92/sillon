# Sillon

[![CI](https://github.com/Inobody92/sillon/actions/workflows/ci.yml/badge.svg)](https://github.com/Inobody92/sillon/actions/workflows/ci.yml)

Un lecteur de musique Android moderne pour **Synology Audio Station**.

DS audio, l'application officielle, n'est plus mise à jour : pas de vraie gestion des playlists, interface
vieillissante, des fonctions qui manquent. Sillon se connecte au même serveur (ton NAS, tes fichiers) avec une
application neuve, écrite en Kotlin avec Jetpack Compose et Media3.

> Projet indépendant, **non affilié à Synology Inc.** « Synology » et « Audio Station » sont des marques de leurs
> propriétaires. Sillon utilise uniquement l'API web de ton propre NAS.

## Fonctions

**Bibliothèque**
- Albums, artistes, genres, pochettes ; filtre par genre sur la grille d'albums
- Recherche instantanée et souple, grâce à un index local de la bibliothèque : sans accents ni casse, tolérante aux
  fautes de frappe, mots dans n'importe quel ordre répartis entre titre, artiste, album et genre
- Accueil : mix du jour, albums écoutés et ajoutés récemment

**Lecture**
- Lecture en arrière-plan avec notification et écran de verrouillage, file d'attente modifiable (lire ensuite,
  ajouter à la file, réordonner), aléatoire, répétition ; la file est conservée après un redémarrage
- Cache disque des titres écoutés et préchargement des deux titres suivants : l'enchaînement ne dépend plus du réseau
- Minuteur de sommeil : dans N minutes (le son baisse doucement), à la fin du titre ou de la file
- Radios par humeur sans fin (Énergie, Chill, Fête, Nostalgie), construites sur les genres de ta bibliothèque
- Le titre en cours est marqué par des barres d'égaliseur animées dans les albums et les playlists ; dans le lecteur,
  toucher la pochette ouvre l'album et toucher le nom de l'artiste ouvre sa discographie
- Qualité audio Wi-Fi / mobile, si le NAS sait convertir en MP3
- Ajout de musique depuis l'application (facultatif) : recherche d'une chanson ou d'un artiste, envoi vers la
  bibliothèque, suivi de l'avancement, notification à la fin de chaque téléchargement et bouton **Réessayer** en cas
  d'échec (avec la raison donnée par le serveur), via ton propre serveur de téléchargement (contrat décrit dans
  [`docs/DOWNLOAD_SERVER.md`](docs/DOWNLOAD_SERVER.md)). Invisible tant qu'aucune adresse n'est saisie dans les réglages
- Deux widgets d'écran d'accueil (Réglages → Widget) : un grand 4×2 avec pochette, titre, artiste et boutons précédent /
  lecture / suivant (il s'adapte aussi en bandeau ou en format haut), et un petit carré 2×2 avec la pochette en plein cadre
  et une barre de commandes flottante. Toucher le fond ramène sur l'écran de lecture
- Égaliseur avec 11 préréglages (basses, voix, rock, jazz, loudness…) et réglage fin sur 5 fréquences, appliqué à toute
  la lecture (haut-parleur, Bluetooth, voiture)
- Écran de lecture au choix : platine vinyle (45 tours, bras de lecture qui sert de curseur, disque coloré façon
  vinyle splatter, spirale, marbré ou anneaux, aux couleurs et proportions de la pochette), égaliseur à segments
  piloté par le son réellement joué (analyse en direct, rien n'est envoyé nulle part), ou pochette seule

**Playlists et favoris**
- Création, ajout, retrait, réordonnancement par glisser-déposer, renommage, suppression
- **Titres likés** : une vraie playlist du NAS (donc visible partout, y compris dans la voiture), un seul exemplaire
  par titre, un cœur sur chaque titre, dans le lecteur, la notification et Android Auto
- Notation par étoiles

**Goûts et titres masqués**
- **Le mix du jour et les radios s'adaptent à toi.** Le tirage est pondéré : on favorise les artistes et les genres les
  mieux notés (moyenne lissée, un like compte pour 5 étoiles), on évite les titres déjà tombés (de moins en moins avec
  le temps) et ceux écoutés récemment, avec au plus 3 titres par artiste. Un titre écouté en entier ou passé après
  quelques secondes affine aussi le profil, plus faiblement que les notes. L'écran **Mes goûts** (Réglages) montre ce
  que l'application a compris
- **Titres masqués** : ne plus jamais entendre un titre, un album ou un artiste entier (mix, radios, aléatoire, files,
  Android Auto). Les titres masqués sont une playlist personnelle du NAS, propre à chaque compte
- **Sauvegarde sur le NAS** : l'exposition des titres, les statistiques d'écoute, les règles de masquage d'albums et
  d'artistes et les couleurs choisies sont enregistrées dans `/home/.sillon/profil.json` (dossier personnel de
  l'utilisateur, via File Station) : un nouveau téléphone ou une réinstallation reprennent où tu en étais

**Découvertes du jour** (avec le serveur de téléchargement)
- Chaque jour, 5 suggestions sur l'Accueil, à télécharger d'un geste (rien n'est téléchargé sans ton accord) : les
  **sorties récentes** de tes artistes préférés, des **artistes proches**, et deux **pépites** peu connues (artistes
  proches des tiens mais à moins de 100 000 fans, cherchés aussi à deux pas de tes favoris)
- Calculées sur le téléphone à partir de tes goûts et de ta bibliothèque ; seuls les noms de tes artistes préférés
  sont envoyés à Deezer (API publique) pour trouver des artistes proches. Écarté : ce que tu as déjà, ce que tu as
  masqué, les rééditions, ce que tu as refusé (« Non merci ») ou qui a déjà été proposé récemment
- Notification quotidienne facultative ; tout se règle dans Réglages → Découvertes

**Thème**
- Clair, sombre ou système ; couleurs du système (Material You, Android 12+) ; **couleurs personnalisées** :
  choix d'une couleur principale et d'une secondaire, les fonds en prennent la teinte et les contrastes de lecture
  sont garantis pour toute couleur

**Hors-ligne**
- Albums, playlists et titres likés téléchargeables (Wi-Fi uniquement par défaut), lecture sans le NAS,
  mise à jour automatique des playlists téléchargées, gestion de l'espace

**Android Auto**
- Navigation (radio, albums, artistes, playlists) et lecture dans la voiture, avec le bouton « liker »

**Connexion**
- Adresse du NAS ou **ID QuickConnect**, double authentification (appareil de confiance mémorisé)
- Certificat auto-signé : l'empreinte SHA-256 est affichée pour confirmation puis **épinglée** ; tout autre
  certificat est refusé

## Installer

### Depuis une version publiée
Télécharge l'APK signé dans les [Releases](https://github.com/Inobody92/sillon/releases), puis ouvre-le sur ton
téléphone (Android 8 ou plus). Android te demandera d'autoriser l'installation depuis cette source.

### Depuis les sources
Prérequis : JDK 17 ou plus, Android SDK (plateforme 36).

```
./gradlew assembleDebug              # APK de développement
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Se connecter

| Champ | Exemple |
|---|---|
| Adresse | `monnas.synology.me:5001` ou `https://monnas.exemple.fr` |
| ID QuickConnect | `mon-nas` (un mot, sans point) |

- Seul **HTTPS** est accepté.
- Avec un **ID QuickConnect**, Sillon cherche d'abord une adresse directe (rapide) et ne retombe sur le relais de
  Synology qu'en dernier recours, car le relais est bridé en débit. Si l'adresse du NAS change, elle est retrouvée
  automatiquement.
- Si ton NAS a un **certificat auto-signé**, active l'option dédiée : l'empreinte s'affiche, compare-la avec celle de
  DSM (Panneau de configuration, Sécurité, Certificat) avant d'accepter. Si le certificat change plus tard, la
  connexion est refusée jusqu'à ce que tu le confirmes à nouveau.

## Android Auto

Sillon apparaît dans Android Auto. Pour le tester sans voiture, voir [`docs/ANDROID_AUTO.md`](docs/ANDROID_AUTO.md).
Une application installée hors du Play Store demande d'activer « Sources inconnues » dans les paramètres
développeur d'Android Auto.

## Comment ça marche

```
app/src/main/java/io/github/inobody92/sillon/
├── data/       Client de l'API Audio Station, session, modèles, index de recherche, téléchargements, likes
├── playback/   Service de lecture (Media3), cache, file d'attente, arborescence pour Android Auto
└── ui/         Écrans Jetpack Compose
```

- **Un seul service de lecture** (`PlaybackService`, un `MediaLibraryService`) sert l'application, la notification,
  les boutons Bluetooth et Android Auto. Il garde la file d'attente, le minuteur et la radio : ils survivent à la
  fermeture de l'écran.
- **Chemin de lecture** : un titre téléchargé est lu directement depuis le téléphone ; sinon depuis le cache disque ;
  sinon depuis le NAS. L'adresse de streaming (qui contient un identifiant de session qui expire) est calculée au
  dernier moment, jamais stockée dans la file.
- **Protocole** : le NAS expose une API web non documentée. Elle a été reconstituée par rétro-ingénierie et est décrite
  dans [`docs/API.md`](docs/API.md), avec les comportements vérifiés sur un vrai NAS.

## Confidentialité et sécurité

- Aucune télémétrie, aucun compte. L'application parle à ton NAS (et, si tu utilises QuickConnect, au service de
  résolution de Synology pour retrouver son adresse), plus, si tu l'as configuré, à ton serveur de téléchargement.
- **Vérification de mise à jour** : au lancement, au plus toutes les 12 h, Sillon demande à GitHub (adresse
  `api.github.com`) si une version plus récente est publiée. La requête est anonyme et GitHub y voit seulement ton
  adresse IP. Rien n'est installé sans ta confirmation. Tu peux la couper dans *Réglages → Mises à jour*.
- Le mot de passe est chiffré avec une clé non exportable du Keystore Android.
- Les sauvegardes Android sont désactivées (`allowBackup=false`).
- L'historique d'écoute reste sur le téléphone.

## Développement

```
./gradlew testDebugUnitTest          # tests unitaires
./gradlew assembleDebug
```

Les tests couvrent la recherche, le client (contre un faux NAS qui reproduit le comportement observé), l'épinglage
de certificat (contre un vrai serveur TLS auto-signé), le magasin hors-ligne et les likes. Ils tournent à chaque push
via GitHub Actions.

**Version signée.** Les identifiants de signature ne sont jamais dans le dépôt. Place un `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) à la racine ou dans `~/.sillon-signing/`, puis :

```
./gradlew assembleRelease            # app/build/outputs/apk/release/app-release.apk
```

Sans ces identifiants, la variante release est construite non signée. Garde une sauvegarde de ta clé : sans elle, tu
ne pourras plus publier de mise à jour installable par-dessus l'ancienne version.

**Publier une version.** Une release GitHub se crée toute seule quand on pousse un tag :

1. Monte `versionCode` et `versionName` dans `app/build.gradle.kts`, puis commite.
2. Une seule fois : `scripts/configurer-secrets-release.sh` envoie ta clé de signature dans les secrets chiffrés du
   dépôt (`gh auth login` d'abord). Elle ne passe jamais par le dépôt lui-même.
3. `git tag v1.6.0 && git push origin v1.6.0` : le workflow [`release.yml`](.github/workflows/release.yml) vérifie que
   le tag correspond à `versionName`, lance les tests, construit et signe l'APK, contrôle sa signature, puis publie la
   release avec `Sillon-1.6.0.apk` et son empreinte SHA-256. Sans les secrets de signature, il s'arrête sans rien publier.

Les applications déjà installées proposent alors la mise à jour au prochain lancement.

## Limites connues

- **QuickConnect** : le protocole de résolution n'est pas documenté officiellement ; il a été reconstitué et testé
  uniquement sur des réponses simulées. À valider sur un vrai compte.
- **Qualité audio** : la conversion en MP3 dépend de la configuration d'Audio Station ; si le NAS ne l'annonce pas,
  les titres sont lus en qualité originale.
- **Recherche vocale** dans Android Auto : non vérifiée.
- **Édition des tags** : pas encore, l'API correspondante du NAS n'est pas documentée.

## Licence

MIT, voir [`LICENSE`](LICENSE).
