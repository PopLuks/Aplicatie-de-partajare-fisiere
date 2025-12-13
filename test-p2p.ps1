# Script PowerShell pentru testare cu 2 instanțe P2P
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Test P2P - Multiple Instanțe" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "Acest script va porni 2 instanțe ale aplicației." -ForegroundColor Yellow
Write-Host "Cele 2 noduri se vor descoperi automat." -ForegroundColor Yellow
Write-Host ""

$continue = Read-Host "Continui? (Da/Nu)"
if ($continue -notmatch "^[DdYy]") {
    Write-Host "Anulat." -ForegroundColor Red
    exit 0
}

Write-Host ""
Write-Host "Compilare proiect..." -ForegroundColor Yellow
mvn clean compile -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "[EROARE] Compilarea a eșuat!" -ForegroundColor Red
    exit 1
}
Write-Host "✓ Compilare reușită" -ForegroundColor Green
Write-Host ""

# Pornește prima instanță
Write-Host "Pornesc prima instanță (Nod 1)..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$PWD'; Write-Host 'NOD 1 - P2P File Sharing' -ForegroundColor Green; mvn javafx:run"

# Așteaptă 5 secunde
Write-Host "Aștept 5 secunde..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

# Pornește a doua instanță
Write-Host "Pornesc a doua instanță (Nod 2)..." -ForegroundColor Cyan
Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$PWD'; Write-Host 'NOD 2 - P2P File Sharing' -ForegroundColor Blue; mvn javafx:run"

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host " Ambele noduri au fost pornite!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "Următorii pași:" -ForegroundColor Yellow
Write-Host "1. Așteaptă ~10 secunde pentru descoperire automată" -ForegroundColor White
Write-Host "2. Vezi 'Peers conectați: 1' în ambele ferestre" -ForegroundColor White
Write-Host "3. În Nod 1: Adaugă un fișier (➕ Adaugă Fișier)" -ForegroundColor White
Write-Host "4. În Nod 2: Vezi fișierul în lista de rețea" -ForegroundColor White
Write-Host "5. În Nod 2: Descarcă fișierul (⬇ Descarcă)" -ForegroundColor White
Write-Host ""
Write-Host "Fișierele descărcate vor fi în: C:\Users\$env:USERNAME\P2P-Downloads" -ForegroundColor Cyan
Write-Host ""
