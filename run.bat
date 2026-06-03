@echo off
setlocal

:: Port par defaut : 25500
set PORT=25500
:: Secret Velocity (laisser vide pour desactiver le support Velocity)
set VELOCITY_SECRET=

:: Chargement des variables si un fichier .env existe
if exist .env (
    for /f "usebackq tokens=*" %%i in (`findstr /v "^#" .env`) do (
        set %%i
    )
)

:: Verification de Maven et Compilation
where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    echo [INFO] Maven detecte. Compilation en cours...
    call mvn clean package -DskipTests
    if %ERRORLEVEL% neq 0 (
        echo [AVERTISSEMENT] La compilation a echoue.
    )
) else (
    echo [INFO] Maven n'est pas installe. On tente de lancer le JAR existant...
)

:: Verification de la presence du JAR
if not exist target\login-server-1.0-SNAPSHOT.jar (
    echo [ERREUR] Le fichier target\login-server-1.0-SNAPSHOT.jar n'existe pas et n'a pas pu etre compile.
    echo Veuillez installer Maven ou compiler le projet manuellement.
    pause
    exit /b 1
)

:: Verification de Java
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERREUR] Java n'est pas installe ou n'est pas dans le PATH.
    pause
    exit /b 1
)

:: Lancement avec une limite de RAM
echo Lancement du serveur sur le port %PORT%...
java -Xmx48m -Xms32m -XX:+UseSerialGC -Dserver.port=%PORT% -Dvelocity.secret=%VELOCITY_SECRET% -jar target\login-server-1.0-SNAPSHOT.jar

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERREUR] Le serveur s'est arrete avec une erreur.
    pause
)
