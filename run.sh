#!/bin/bash
# Script de lancement du micro-serveur de login Minestom

# Port par defaut : 25500
PORT=25500
# Secret Velocity (laisser vide pour desactiver le support Velocity)
VELOCITY_SECRET=""

# Chargement des variables si un fichier .env existe
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
fi

# Verification de Maven et Compilation
if command -v mvn &> /dev/null; then
    echo "[INFO] Maven detecte. Compilation en cours..."
    mvn clean package -DskipTests
    if [ $? -ne 0 ]; then
        echo "[AVERTISSEMENT] La compilation a echoue."
    fi
else
    echo "[INFO] Maven n'est pas installe. On tente de lancer le JAR existant..."
fi

# Utilisation de Java (priorite au path systeme, puis au chemin specifique s'il existe)
JAVA_BIN="java"
SPECIFIC_JAVA="/opt/homebrew/opt/openjdk/bin/java"
if [ -f "$SPECIFIC_JAVA" ]; then
    JAVA_BIN="$SPECIFIC_JAVA"
fi

# Verification de la presence du JAR
if [ ! -f "target/login-server-1.0-SNAPSHOT.jar" ]; then
    echo "[ERREUR] Le fichier target/login-server-1.0-SNAPSHOT.jar n'existe pas."
    exit 1
fi

# Limite a 48Mo
echo "Lancement du serveur sur le port $PORT..."
$JAVA_BIN -Xmx48m -Xms32m -XX:+UseSerialGC -Dserver.port=$PORT -Dvelocity.secret=$VELOCITY_SECRET -jar target/login-server-1.0-SNAPSHOT.jar
