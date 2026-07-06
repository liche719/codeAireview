param(
    [string] $BaseUrl = "http://localhost:8080",
    [string] $ApiKey = $env:CODEPILOT_API_AUTH_API_KEY
)

$ErrorActionPreference = "Stop"

if (-not $ApiKey) {
    $ApiKey = "change-me-local-dev-key"
}

function Invoke-SmokeRequest {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Method,
        [Parameter(Mandatory = $true)]
        [string] $Uri,
        [hashtable] $Headers = @{},
        [string] $Body = $null,
        [int[]] $ExpectedStatus = @(200)
    )

    try {
        $params = @{
            Method = $Method
            Uri = $Uri
            Headers = $Headers
            UseBasicParsing = $true
        }
        if ($Body -ne $null) {
            $params.Body = $Body
            $params.ContentType = "application/json"
        }

        $response = Invoke-WebRequest @params
        $statusCode = [int] $response.StatusCode
    } catch {
        if ($_.Exception.Response -eq $null) {
            throw
        }
        $response = $_.Exception.Response
        $statusCode = [int] $response.StatusCode
    }

    if ($ExpectedStatus -notcontains $statusCode) {
        throw "Expected $Uri to return one of [$($ExpectedStatus -join ', ')], got $statusCode"
    }

    return $response
}

$apiHeaders = @{
    "X-CodePilot-Api-Key" = $ApiKey
}

Write-Host "CodePilot local smoke"
Write-Host "BaseUrl: $BaseUrl"

Write-Host "[1/5] Checking OpenAPI..."
Invoke-SmokeRequest -Method "GET" -Uri "$BaseUrl/v3/api-docs" | Out-Null

Write-Host "[2/5] Checking API auth rejection..."
Invoke-SmokeRequest -Method "GET" -Uri "$BaseUrl/api/reviews" -ExpectedStatus @(401) | Out-Null

Write-Host "[3/5] Checking protected API access and rate-limit headers..."
$reviewsResponse = Invoke-SmokeRequest -Method "GET" -Uri "$BaseUrl/api/reviews" -Headers $apiHeaders
if (-not $reviewsResponse.Headers["X-RateLimit-Limit"]) {
    throw "Missing X-RateLimit-Limit header on protected API response"
}

Write-Host "[4/5] Creating a rule document..."
$timestamp = Get-Date -Format "yyyyMMddHHmmss"
$ruleBody = @{
    title = "Smoke SQL Rule $timestamp"
    type = "SQL_RULE"
    source = "smoke-local"
    content = "Do not concatenate SQL strings. UPDATE and DELETE statements must include WHERE."
} | ConvertTo-Json -Compress
$createResponse = Invoke-SmokeRequest -Method "POST" -Uri "$BaseUrl/api/rules" -Headers $apiHeaders -Body $ruleBody
$createPayload = $createResponse.Content | ConvertFrom-Json
if ($createPayload.code -ne 0 -or -not $createPayload.data.id) {
    throw "Rule document was not created successfully: $($createResponse.Content)"
}

Write-Host "[5/5] Reading rule documents..."
$rulesResponse = Invoke-SmokeRequest -Method "GET" -Uri "$BaseUrl/api/rules" -Headers $apiHeaders
$rulesPayload = $rulesResponse.Content | ConvertFrom-Json
if ($rulesPayload.code -ne 0) {
    throw "Rule list returned a non-success payload: $($rulesResponse.Content)"
}

Write-Host ""
Write-Host "Smoke passed."
Write-Host "Created rule document id: $($createPayload.data.id)"
