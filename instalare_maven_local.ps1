$ErrorActionPreference = "Stop"

$mavenVersion = "3.9.6"
$mavenUrl = "https://archive.apache.org/dist/maven/maven-3/$mavenVersion/binaries/apache-maven-$mavenVersion-bin.zip"
$mavenZip = "maven.zip"
$mavenDir = "maven"

Write-Host "Descarcare Maven $mavenVersion..."
Invoke-WebRequest -Uri $mavenUrl -OutFile $mavenZip

Write-Host "Dezarhivare..."
Expand-Archive -Path $mavenZip -DestinationPath . -Force

# Redenumire folder extras in "maven"
$extractedDir = "apache-maven-$mavenVersion"
if (Test-Path $mavenDir) {
    Remove-Item -Path $mavenDir -Recurse -Force
}
Rename-Item -Path $extractedDir -NewName $mavenDir

# Curatenie
Remove-Item -Path $mavenZip

Write-Host "Maven a fost instalat local in folderul '$mavenDir'!"
Write-Host "Acum poti folosi 'maven\bin\mvn' pentru comenzi."
