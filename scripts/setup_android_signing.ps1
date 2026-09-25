param(
    [string]$Repository = "Gokuencinar/TVBGoneAndroid",
    [string]$Alias = "tvbgoneandroid"
)

$ErrorActionPreference = "Stop"

function Read-Secret([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw "No se encuentra keytool. Instala/usa JDK 17 antes de ejecutar este script."
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "No se encuentra GitHub CLI (gh). Instálalo y ejecuta 'gh auth login' antes de continuar."
}

gh auth status *> $null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI no está autenticado. Ejecuta 'gh auth login' y repite."
}

$storePassword = Read-Secret "Contraseña nueva para la keystore y la clave"
if ([string]::IsNullOrWhiteSpace($storePassword)) {
    throw "La contraseña no puede estar vacía."
}
$keyPassword = $storePassword

$outDir = Join-Path $PSScriptRoot ".signing-private"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$keystore = Join-Path $outDir "TVBGoneAndroid-release.p12"

if (Test-Path $keystore) {
    throw "Ya existe $keystore. No se sobrescribe para evitar perder la identidad de firma."
}

Write-Host "Generando identidad de firma permanente..."
$keytoolArgs = @(
    "-genkeypair",
    "-keystore", $keystore,
    "-storetype", "PKCS12",
    "-storepass", $storePassword,
    "-keypass", $keyPassword,
    "-alias", $Alias,
    "-keyalg", "RSA",
    "-keysize", "4096",
    "-validity", "10000",
    "-dname", "CN=Gokuencinar, OU=TVBGoneAndroid, O=Gokuencinar, C=ES"
)
& keytool @keytoolArgs
if ($LASTEXITCODE -ne 0) {
    throw "keytool no pudo generar la keystore."
}

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))

Write-Host "Subiendo secretos a GitHub Actions..."
$secrets = [ordered]@{
    ANDROID_SIGNING_KEYSTORE_BASE64 = $base64
    ANDROID_SIGNING_STORE_PASSWORD = $storePassword
    ANDROID_SIGNING_KEY_ALIAS = $Alias
    ANDROID_SIGNING_KEY_PASSWORD = $keyPassword
}

foreach ($entry in $secrets.GetEnumerator()) {
    $entry.Value | gh secret set $entry.Key --repo $Repository
    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo guardar el secreto $($entry.Key) en GitHub."
    }
}

Write-Host ""
Write-Host "Firma configurada."
Write-Host "IMPORTANTE: conserva una copia privada de:"
Write-Host "  $keystore"
Write-Host "Si se pierde esta keystore, no se podrán firmar futuras actualizaciones compatibles."
