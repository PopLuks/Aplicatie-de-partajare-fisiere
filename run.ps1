# Script PowerShell pentru testare P2P
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " P2P File Sharing - Test Application" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Verifică Maven
Write-Host "Verificare Maven..." -ForegroundColor Yellow
$localMaven = Join-Path $PSScriptRoot "maven\bin\mvn.cmd"
if (Test-Path $localMaven) {
    $mvnCmd = $localMaven
    Write-Host "✓ Maven local găsit" -ForegroundColor Green
} else {
    $mvnPath = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvnPath) {
        Write-Host "[EROARE] Maven nu este instalat!" -ForegroundColor Red
        exit 1
    }
    $mvnCmd = "mvn"
    Write-Host "✓ Maven global găsit" -ForegroundColor Green
}

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
& $mvnCmd clean compile -q
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

& $mvnCmd javafx:run
