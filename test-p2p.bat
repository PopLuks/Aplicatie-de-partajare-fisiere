@echo off
echo ========================================
echo  Testare P2P cu Multiple Instante
echo ========================================
echo.

cd /d "%~dp0"

echo Aceasta va porni 2 instante ale aplicatiei.
echo Vei vedea 2 ferestre separate care vor comunica intre ele.
echo.
echo Apasa orice tasta pentru a continua...
pause >nul

echo.
echo Pornesc prima instanta...
start "P2P Instance 1" cmd /c "mvn javafx:run"

timeout /t 5 /nobreak >nul

echo Pornesc a doua instanta...
start "P2P Instance 2" cmd /c "mvn javafx:run"

echo.
echo Ambele instante au fost pornite!
echo Asteapta ~10 secunde sa se descopere reciproc.
echo.
