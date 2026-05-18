$ErrorActionPreference = "Stop"

if (-not (Test-Path ".env")) {
    Write-Host ".env not found. Please copy .env.example to .env and fill your local secrets." -ForegroundColor Yellow
    exit 1
}

Write-Host "Loading .env..."

Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $name, $value = $line -split "=", 2
        if ($name -and $value -ne $null) {
            [System.Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
        }
    }
}

Write-Host "Starting Docker services..."
docker compose up -d

Write-Host "Packaging application..."
mvn -DskipTests package

Write-Host "Starting CodePilot AI..."
java -jar target/codepilot-ai-review-0.0.1-SNAPSHOT.jar
