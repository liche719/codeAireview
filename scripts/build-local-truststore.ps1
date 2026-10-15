# Build a dedicated JVM truststore so the application can reach a GitHub API that a
# local network accelerator is intercepting.
#
# WHY THIS IS NEEDED
# ------------------
# Accelerators such as Watt Toolkit (formerly Steam++) listen on local port 443 and
# man-in-the-middle github.com / api.github.com using a self-signed root certificate.
#
# Browsers and .NET work fine because they trust the Windows certificate store. The JVM
# does not read that store - it only trusts its own cacerts - so the application fails
# with:
#     SSLHandshakeException: unable to find valid certification path to requested target
#
# This script does NOT modify the JDK-wide cacerts. It:
#   1. exports the accelerator root certificate from the Windows trusted roots
#   2. copies the JDK cacerts to a dedicated truststore (keeping every default CA)
#   3. appends the accelerator certificate to that copy
#
# Pass it to the JVM at startup:
#     -Djavax.net.ssl.trustStore=<store> -Djavax.net.ssl.trustStorePassword=changeit
#
# SECURITY NOTE
# -------------
# This means trusting a man-in-the-middle CA for the duration of the run. Only do it for
# local debugging where that is acceptable. Do not use it for production, and do not
# commit the resulting truststore (it is written under target/, which is ignored).
#
# USAGE
# -----
#     powershell -ExecutionPolicy Bypass -File scripts/build-local-truststore.ps1
#     powershell -ExecutionPolicy Bypass -File scripts/build-local-truststore.ps1 -SubjectPattern 'SteamTools|Watt'
#
# Note: scripts here are intentionally ASCII-only. Windows PowerShell 5.1 reads .ps1 files
# as the system ANSI codepage, so non-ASCII text can break parsing.

param(
    [string] $SubjectPattern = 'SteamTools|Watt',
    [string] $OutputPath = "",
    [string] $StorePassword = "changeit"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if (-not $OutputPath) {
    $OutputPath = Join-Path $repoRoot "target\codepilot-truststore.p12"
}
$workDir = Join-Path $repoRoot "target\truststore-build"

$javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
if (-not $javaExe) {
    throw "java not found on PATH. Install JDK 21 first."
}
$jdkHome = Split-Path -Parent (Split-Path -Parent $javaExe)
$keytool = Join-Path $jdkHome "bin\keytool.exe"
$cacerts = Join-Path $jdkHome "lib\security\cacerts"
if (-not (Test-Path $keytool)) { throw "keytool not found: $keytool" }
if (-not (Test-Path $cacerts)) { throw "JDK cacerts not found: $cacerts" }

Write-Host "JDK: $jdkHome"

# keytool writes even its success message to stderr, and Windows PowerShell 5.1 turns any
# native stderr output into a NativeCommandError, which aborts the script under
# ErrorActionPreference = Stop. Redirect and relax the preference around those calls.
function Invoke-Keytool {
    param([string[]] $Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return @(& $keytool @Arguments 2>&1)
    } finally {
        $ErrorActionPreference = $previous
    }
}

# ---- 1) export accelerator root certificates ----
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$certs = Get-ChildItem Cert:\LocalMachine\Root |
    Where-Object { $_.Subject -match $SubjectPattern }

if (-not $certs -or @($certs).Count -eq 0) {
    throw "No certificate matching '$SubjectPattern' in the Windows trusted roots. Is the accelerator installed and has it run at least once?"
}

Write-Host ""
Write-Host "Matched $(@($certs).Count) certificate(s):"
$pemFiles = @()
$index = 0
foreach ($cert in $certs) {
    $base64 = [Convert]::ToBase64String($cert.RawData, 'InsertLineBreaks')
    $pem = "-----BEGIN CERTIFICATE-----`n$base64`n-----END CERTIFICATE-----`n"
    $path = Join-Path $workDir "ca-$index.pem"
    Set-Content -Path $path -Value $pem -Encoding ascii -NoNewline
    $pemFiles += $path
    $state = if ($cert.NotAfter -lt (Get-Date)) { " [EXPIRED]" } else { "" }
    Write-Host ("  [{0}] {1}  notAfter={2:yyyy-MM-dd}{3}" -f $index, $cert.Thumbprint, $cert.NotAfter, $state)
    $index++
}

# ---- 2) copy JDK cacerts as the dedicated truststore ----
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputPath) | Out-Null
Copy-Item $cacerts $OutputPath -Force
Write-Host ""
Write-Host "Copied JDK cacerts to: $OutputPath"

# ---- 3) import the accelerator certificates ----
$aliasIndex = 0
foreach ($pemFile in $pemFiles) {
    Invoke-Keytool @(
        '-importcert', '-noprompt', '-trustcacerts',
        '-alias', "local-accelerator-$aliasIndex",
        '-file', $pemFile,
        '-keystore', $OutputPath,
        '-storepass', $StorePassword
    ) | Out-Null
    $aliasIndex++
}

$imported = @(Invoke-Keytool @('-list', '-keystore', $OutputPath, '-storepass', $StorePassword) |
    Select-String -Pattern 'local-accelerator')
Write-Host "Imported entries: $($imported.Count)"
if ($imported.Count -eq 0) {
    throw "Import failed: no local-accelerator entry in the truststore."
}

Write-Host ""
Write-Host "Done. Start the application with:"
Write-Host "  -Djavax.net.ssl.trustStore=$OutputPath -Djavax.net.ssl.trustStorePassword=$StorePassword"
Write-Host ""
Write-Host "If the accelerator is upgraded or its certificate rotates, re-run this script."
