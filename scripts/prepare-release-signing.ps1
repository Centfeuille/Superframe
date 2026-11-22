# Run this script from the repository root in Windows PowerShell.
# It creates a permanent signing key and a Base64 file for a GitHub Actions secret.
# Keep both files private. Never commit or share the keystore.
$ErrorActionPreference = "Stop"

$keystore = Join-Path (Get-Location) "superframe-release.jks"
$encodedFile = Join-Path (Get-Location) "superframe-keystore-base64.txt"

if (Test-Path $keystore) {
    throw "superframe-release.jks already exists. Keep using the existing key; do not overwrite it."
}
if (Test-Path $encodedFile) {
    throw "superframe-keystore-base64.txt already exists. Move it securely before generating another key."
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    throw "keytool was not found. Install a JDK (Java 17 is recommended) and make sure its bin folder is on PATH."
}

Write-Host "You are creating Superframe's permanent signing identity. Back up the resulting .jks file securely."
Write-Host "Use the alias superframe and use the SAME password for the keystore and key when prompted."
& $keytool.Source -genkeypair -v -keystore $keystore -alias superframe -keyalg RSA -keysize 2048 -validity 10000
if ($LASTEXITCODE -ne 0) {
    Remove-Item $keystore -Force -ErrorAction SilentlyContinue
    throw "keytool failed; no signing key was created."
}

$bytes = [System.IO.File]::ReadAllBytes($keystore)
$base64 = [Convert]::ToBase64String($bytes)
[System.IO.File]::WriteAllText($encodedFile, $base64, [System.Text.Encoding]::ASCII)
Write-Host "Created $keystore and $encodedFile. Keep both private and back up the .jks file offline."
Write-Host "Add the Base64 file contents and the passwords as GitHub Actions repository secrets."
