#!/bin/sh
# Envoie la clé de signature de Sillon dans les secrets GitHub du dépôt, pour que le workflow « Release »
# puisse signer l'APK. À lancer UNE FOIS, depuis ton Mac : la clé ne passe jamais par ce dépôt.
# Prérequis : `gh auth login` fait, et ~/.sillon-signing/keystore.properties (voir README).
set -eu

PROPS="${1:-$HOME/.sillon-signing/keystore.properties}"
[ -f "$PROPS" ] || { echo "Fichier introuvable : $PROPS"; exit 1; }

lire() { sed -n "s/^$1=//p" "$PROPS" | head -1; }
STORE_FILE="$(lire storeFile)"
[ -f "$STORE_FILE" ] || { echo "Keystore introuvable : $STORE_FILE"; exit 1; }
DEPOT="$(gh repo view --json nameWithOwner -q .nameWithOwner)"

echo "Ceci va envoyer ta clé de signature et ses mots de passe dans les secrets chiffrés de :"
echo "  $DEPOT"
printf "Continuer ? [o/N] "
read -r reponse
[ "$reponse" = "o" ] || { echo "Annulé."; exit 1; }

base64 < "$STORE_FILE" | tr -d '\n' | gh secret set SIGNING_KEYSTORE_BASE64
lire storePassword | tr -d '\n' | gh secret set SIGNING_STORE_PASSWORD
lire keyAlias      | tr -d '\n' | gh secret set SIGNING_KEY_ALIAS
lire keyPassword   | tr -d '\n' | gh secret set SIGNING_KEY_PASSWORD
echo "Secrets enregistrés. Pour publier : git tag vX.Y.Z && git push origin vX.Y.Z"
