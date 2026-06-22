$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
    $testName = "ReviewReliabilityBaselineTest"
    $reportPath = Join-Path $repoRoot "target\codepilot-baseline\review-reliability-baseline.json"

    Write-Host "Running CodePilot local reliability baseline..."
    mvn "-Dtest=$testName" test

    if (-not (Test-Path $reportPath)) {
        throw "Baseline report was not generated: $reportPath"
    }

    Write-Host ""
    Write-Host "Baseline report: $reportPath"
    Get-Content -Raw $reportPath
} finally {
    Pop-Location
}
