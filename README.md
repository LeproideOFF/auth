# Micro-serveur de Login Minestom

Ce projet est un micro-serveur Minecraft ultra-léger développé avec [Minestom](https://github.com/Minestom/Minestom). Il est conçu pour servir de serveur d'authentification derrière un proxy (comme Velocity).

## Caractéristiques

- **Port par défaut** : 25500
- **Léger** : Utilise Minestom pour une consommation de ressources minimale.
- **Sécurité** : Support natif de Velocity (via secret key).
- **Anti-mouvement** : Les joueurs ne peuvent pas bouger tant qu'ils ne sont pas authentifiés.
- **Auto-kick** : Kick automatique après 30 secondes sans login ou en cas de mauvais mot de passe.

## Installation

### Prérequis
- Java 26 (ou version compatible Minestom 2026)
- Maven 3.9+

### Compilation
```bash
mvn package
```

### Lancement
Vous pouvez utiliser le script fourni :
```bash
./run.sh
```

Ou manuellement :
```bash
java -Dserver.port=25500 -Dvelocity.secret=VOTRE_SECRET -jar target/login-server-1.0-SNAPSHOT.jar
```

## Configuration

- `server.port` : Définit le port d'écoute (défaut: 25500).
- `velocity.secret` : Active le support Velocity avec la clé spécifiée.

## Utilisation en jeu

Une fois connecté, le joueur doit entrer :
`/login secret123` (mot de passe par défaut pour le test)

Si le login réussit, le joueur est marqué comme authentifié. Si le login échoue ou prend trop de temps, le joueur est déconnecté du proxy.

---
*Note: Ce serveur est une base. Pour une utilisation en production, connectez la logique de la commande `/login` à votre base de données ou API d'authentification.*
