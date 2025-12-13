# Script PowerShell pentru testare P2P
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " P2P File Sharing - Test Application" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Verifică Maven
Write-Host "Verificare Maven..." -ForegroundColor Yellow
$mvnPath = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnPath) {
    Write-Host "[EROARE] Maven nu este instalat!" -ForegroundColor Red
    exit 1
}
Write-Host "✓ Maven găsit" -ForegroundColor Green

# Verifică Java
Write-Host "Verificare Java..." -ForegroundColor Yellow
$javaVersion = java -version 2>&1 | Select-String "version"
if (-not $javaVersion) {
    Write-Host "[EROARE] Java nu este instalat!" -ForegroundColor Red
    exit 1
}
Write-Host "✓ Java găsit: $javaVersion" -ForegroundColor Green
Write-Host ""

# Compilează
Write-Host "Compilare proiect..." -ForegroundColor Yellow
mvn clean compile -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "[EROARE] Compilarea a eșuat!" -ForegroundColor Red
    exit 1
}
Write-Host "✓ Compilare reușită" -ForegroundColor Green
Write-Host ""

# Pornește aplicația
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Pornire aplicație P2P..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Aplicația va porni într-o fereastră nouă." -ForegroundColor Yellow
Write-Host "Așteaptă ~10 secunde pentru a vedea interfața." -ForegroundColor Yellow
Write-Host ""

mvn javafx:run
