@echo off
echo ========================================
echo  P2P File Sharing - Pornire Aplicatie
echo ========================================
echo.

cd /d "%~dp0"

echo Verificare Maven...
where mvn >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [EROARE] Maven nu este instalat sau nu este in PATH!
    echo Instalare: https://maven.apache.org/download.cgi
    pause
    exit /b 1
)

echo Maven gasit!
echo.

echo Verificare Java...
java -version 2>&1 | findstr /R "version" >nul
if %ERRORLEVEL% NEQ 0 (
    echo [EROARE] Java nu este instalat!
    echo Instalare: https://adoptium.net/
    pause
    exit /b 1
)

echo Java gasit!
echo.

echo Compilare si pornire aplicatie...
echo.
mvn javafx:run

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [EROARE] Aplicatia nu a putut porni!
    echo Verifica erorile de mai sus.
    pause
)
