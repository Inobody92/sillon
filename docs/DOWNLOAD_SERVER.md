# Serveur de téléchargement (facultatif)

Sillon peut envoyer des demandes d'ajout de musique à un **serveur de téléchargement que tu héberges toi-même**
(souvent à côté du NAS). L'application ne télécharge rien elle-même : elle cherche, demande, puis suit l'avancement.
La fonction est invisible tant qu'aucune adresse n'est saisie dans *Réglages → Serveur de téléchargement*.

## Sécurité

- **HTTPS obligatoire** : l'adresse est refusée en `http://`, car le jeton circule dans chaque requête.
- **Jeton secret** : envoyé dans `Authorization: Bearer <jeton>`. Il est stocké chiffré (clé non exportable du Keystore).
- **Certificat auto-signé** : si le certificat n'est pas reconnu, l'application affiche son empreinte SHA-256 pour
  confirmation, puis n'accepte plus que ce certificat précis (comme pour le NAS).
- Le serveur doit répondre `401` à tout jeton incorrect et ne rien exécuter dans ce cas.

## Contrat

Toutes les routes sont relatives à l'adresse saisie (un préfixe de reverse proxy, par exemple `/musique`, est conservé).
Corps et réponses en JSON UTF-8. Une erreur est `{"erreur": "message lisible"}` avec un code HTTP adapté ;
l'application affiche ce message tel quel.

| Route | Rôle | Réponse |
|---|---|---|
| `GET /api/sante` | Test de connexion | `{"ok": true, "version": 1}` |
| `GET /api/chansons?q=` | Chercher une chanson | `{"resultats": [{id, titre, artiste, album, pochette, duree, fans, apercu, lien}]}` |
| `POST /api/chansons` `{id}` | Télécharger une chanson | `202` `{numero, nom, position}` ; `409` si déjà en file |
| `GET /api/artistes?q=` | Chercher un artiste | `{"resultats": [{id, nom, vrai_nom, groupe, albums, titres, fans, photo}]}` |
| `POST /api/artistes` `{id, nombre}` | Télécharger un artiste (`nombre` 0 = tout, sinon les N plus populaires) | `202` comme ci-dessus |
| `POST /api/liens` `{url}` | Télécharger un lien | `202` comme ci-dessus |
| `GET /api/file` | File d'attente et historique | voir ci-dessous |
| `DELETE /api/taches/<numero>` | Annuler (ou retirer de la file) | `{"message": "…"}` |

`GET /api/file` :

```json
{
  "pause_jusqua": null,
  "maintenant": 1760000000,
  "taches": [
    {"numero": 1, "nom": "Daft Punk", "etat": "Téléchargement", "en_cours": true, "position": null,
     "pourcentage": 42.5, "total": 12, "faites": 5, "morceau": "Around the World",
     "vitesse": 1450000, "eta": 35, "erreurs": 0}
  ],
  "terminees": [
    {"numero": 0, "nom": "MIKA", "statut": "termine", "telechargees": 14, "erreurs": 0, "total": 14,
     "erreur": null, "fin": 1759999000}
  ]
}
```

`statut` vaut `termine`, `annule` ou `echec`. `vitesse` est en octets par seconde, `eta` en secondes ; les champs
inconnus valent `null`. L'application relit cette route toutes les 2 à 3 secondes tant que l'écran de suivi est ouvert.

## Après un téléchargement

Quand une tâche se termine avec au moins un titre ajouté, Sillon redemande l'état de la bibliothèque au NAS
30 secondes puis 2 minutes plus tard : Audio Station indexe les nouveaux fichiers avec un peu de retard.

## Implémentation de référence

Le bot de l'auteur (Discord + API HTTP) suit ce contrat ; n'importe quel service qui le respecte convient.
