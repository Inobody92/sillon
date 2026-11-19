# Règles de réduction du code (R8) pour la variante release.
# Les bibliothèques utilisées (Media3, OkHttp, Coil, WorkManager, Compose) fournissent leurs propres règles.

# Conserve les numéros de ligne dans les traces d'erreur, sans révéler les noms de fichiers sources.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
