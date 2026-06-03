@echo off
setlocal

:: Port par défaut : 25500
set PORT=25500
:: Secret Velocity (laisser vide pour désactiver le support Velocity)
set VELOCITY_SECRET=

:: Chargement des variables si un fichier .env existe
if exist .env (
    for /f "usebackq tokens=*" %%i in (`findstr /v "^#" .env`) do (
        set %%i
    )
)

:: Vérification de la présence du JAR
if not exist target\login-server-1.0-SNAPSHOT.jar (
    echo [ERREUR] Le fichier target\login-server-1.0-SNAPSHOT.jar n'existe pas.
    echo Veuillez d'abord compiler le projet avec: mvn clean package
    pause
    exit /b 1
)

:: Lancement avec une limite de RAM
:: On utilise UseSerialGC pour minimiser l'empreinte mémoire
echo Lancement du serveur sur le port %PORT%...
java -Xmx48m -Xms32m -XX:+UseSerialGC -Dserver.port=%PORT% -Dvelocity.secret=%VELOCITY_SECRET% -jar target\login-server-1.0-SNAPSHOT.jar

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERREUR] Le serveur s'est arrete avec une erreur.
    pause
)
