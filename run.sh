#!/bin/bash
# Script de lancement du micro-serveur de login Minestom

# Port par défaut : 25500
PORT=25500
# Secret Velocity (laisser vide pour désactiver le support Velocity)
VELOCITY_SECRET=""

# Chargement des variables si un fichier .env existe
if [ -f .env ]; then
    export $(grep -v '^#' .env | xargs)
fi

java -Dserver.port=$PORT -Dvelocity.secret=$VELOCITY_SECRET -jar target/login-server-1.0-SNAPSHOT.jar
