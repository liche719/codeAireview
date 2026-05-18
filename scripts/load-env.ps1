$ErrorActionPreference = "Stop"

if (-not (Test-Path ".env")) {
    Write-Host ".env not found. Please copy .env.example to .env first." -ForegroundColor Yellow
    exit 1
}

Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $name, $value = $line -split "=", 2
        if ($name -and $value -ne $null) {
            [System.Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
        }
    }
}

Write-Host ".env loaded into current process."
