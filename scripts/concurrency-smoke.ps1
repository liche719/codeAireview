param(
    [string] $BaseUrl = "http://localhost:8080",
    [string] $EnvFile = ".env",
    [string] $WebhookSecret = $env:CODEPILOT_GITHUB_WEBHOOK_SECRET,
    [string] $Owner = "liche719",
    [string] $Repo = "codeAireview",
    [int] $PullNumber = 12,
    [string] $HeadSha = "",
    [int] $RequestCount = 16,
    [int] $TimeoutSec = 15
)

$ErrorActionPreference = "Stop"

function Import-DotEnv {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    if (-not (Test-Path $Path)) {
        return
    }

    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            $name, $value = $line -split "=", 2
            if ($name -and $value -ne $null) {
                [System.Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
            }
        }
    }
}

function New-GitHubWebhookSignature {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Secret,
        [Parameter(Mandatory = $true)]
        [string] $Payload
    )

    $keyBytes = [System.Text.Encoding]::UTF8.GetBytes($Secret)
    $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($Payload)
    $hmac = New-Object System.Security.Cryptography.HMACSHA256 -ArgumentList (, $keyBytes)
    try {
        $hash = $hmac.ComputeHash($payloadBytes)
        $hex = -join ($hash | ForEach-Object { $_.ToString("x2") })
        return "sha256=$hex"
    } finally {
        $hmac.Dispose()
    }
}

if (-not $WebhookSecret) {
    Import-DotEnv -Path $EnvFile
    $WebhookSecret = $env:CODEPILOT_GITHUB_WEBHOOK_SECRET
}

if (-not $WebhookSecret) {
    throw "CODEPILOT_GITHUB_WEBHOOK_SECRET is required. Set it in .env or pass -WebhookSecret."
}

if ($RequestCount -lt 2) {
    throw "RequestCount must be at least 2."
}

if (-not $HeadSha) {
    $HeadSha = "concurrency-smoke-" + (Get-Date -Format "yyyyMMddHHmmssfff")
}

$BaseUrl = $BaseUrl.TrimEnd("/")
$payloadObject = [ordered]@{
    action = "synchronize"
    repository = [ordered]@{
        name = $Repo
        owner = [ordered]@{
            login = $Owner
        }
    }
    pull_request = [ordered]@{
        number = $PullNumber
        html_url = "https://github.com/$Owner/$Repo/pull/$PullNumber"
        title = "Concurrency smoke PR"
        head = [ordered]@{
            sha = $HeadSha
        }
    }
}
$payload = $payloadObject | ConvertTo-Json -Depth 10 -Compress
$signature = New-GitHubWebhookSignature -Secret $WebhookSecret -Payload $payload

Write-Host "CodePilot webhook concurrency smoke"
Write-Host "BaseUrl: $BaseUrl"
Write-Host "Target PR: $Owner/$Repo#$PullNumber"
Write-Host "Head SHA: $HeadSha"
Write-Host "Requests: $RequestCount"
Write-Host ""

$jobs = @()
for ($i = 1; $i -le $RequestCount; $i++) {
    $delivery = "concurrency-smoke-$HeadSha-$i"
    $jobs += Start-Job -ArgumentList $BaseUrl, $payload, $signature, $delivery, $TimeoutSec -ScriptBlock {
        param($JobBaseUrl, $JobPayload, $JobSignature, $JobDelivery, $JobTimeoutSec)

        $headers = @{
            "X-GitHub-Event" = "pull_request"
            "X-GitHub-Delivery" = $JobDelivery
            "X-Hub-Signature-256" = $JobSignature
        }

        try {
            $response = Invoke-WebRequest `
                -Method "POST" `
                -Uri "$JobBaseUrl/api/github/webhook" `
                -Headers $headers `
                -Body $JobPayload `
                -ContentType "application/json" `
                -TimeoutSec $JobTimeoutSec `
                -UseBasicParsing

            [PSCustomObject]@{
                Delivery = $JobDelivery
                StatusCode = [int] $response.StatusCode
                Body = $response.Content
                Error = $null
            }
        } catch {
            $statusCode = $null
            $body = $null
            if ($_.Exception.Response -ne $null) {
                $statusCode = [int] $_.Exception.Response.StatusCode
                try {
                    if ($_.Exception.Response.Content -and $_.Exception.Response.Content.ReadAsStringAsync) {
                        $body = $_.Exception.Response.Content.ReadAsStringAsync().Result
                    } else {
                        $stream = $_.Exception.Response.GetResponseStream()
                        if ($stream -ne $null) {
                            $reader = New-Object System.IO.StreamReader($stream)
                            try {
                                $body = $reader.ReadToEnd()
                            } finally {
                                $reader.Dispose()
                            }
                        }
                    }
                } catch {
                    $body = $null
                }
            }

            [PSCustomObject]@{
                Delivery = $JobDelivery
                StatusCode = $statusCode
                Body = $body
                Error = $_.Exception.Message
            }
        }
    }
}

Wait-Job -Job $jobs -Timeout ($TimeoutSec + 30) | Out-Null
$unfinishedJobs = @($jobs | Where-Object { $_.State -eq "Running" })
if ($unfinishedJobs.Count -gt 0) {
    $unfinishedJobs | Stop-Job | Out-Null
    $jobs | Remove-Job -Force | Out-Null
    throw "Timed out waiting for concurrent webhook requests."
}

$rawResults = @($jobs | Receive-Job)
$jobs | Remove-Job -Force | Out-Null

$results = @()
foreach ($result in $rawResults) {
    $message = $null
    $reason = $null
    $taskId = $null
    $parseError = $null

    if ($result.Body) {
        try {
            $body = $result.Body | ConvertFrom-Json
            $message = $body.message
            $reason = $body.data.reason
            $taskId = $body.data.taskId
        } catch {
            $parseError = $_.Exception.Message
        }
    }

    $results += [PSCustomObject]@{
        Delivery = $result.Delivery
        StatusCode = $result.StatusCode
        Message = $message
        Reason = $reason
        TaskId = $taskId
        Error = $result.Error
        ParseError = $parseError
    }
}

$badResponses = @($results | Where-Object {
    $_.StatusCode -ne 200 -or $_.ParseError -or ($_.Message -notin @("success", "ignored"))
})
if ($badResponses.Count -gt 0) {
    $badResponses | Format-Table Delivery, StatusCode, Message, Reason, Error, ParseError -AutoSize
    throw "Concurrency smoke failed: at least one request returned an unexpected response."
}

$processed = @($results | Where-Object { $_.Message -eq "success" -and $_.TaskId })
$duplicates = @($results | Where-Object { $_.Message -eq "ignored" -and $_.Reason -eq "duplicate event" })
$otherIgnored = @($results | Where-Object { $_.Message -eq "ignored" -and $_.Reason -ne "duplicate event" })

if ($otherIgnored.Count -gt 0) {
    $otherIgnored | Format-Table Delivery, StatusCode, Message, Reason -AutoSize
    throw "Concurrency smoke failed: webhook was ignored for a reason other than duplicate event. Check CODEPILOT_GITHUB_WEBHOOK_ENABLED, secret, and allowed repositories."
}

if ($processed.Count -ne 1) {
    $results | Format-Table Delivery, StatusCode, Message, Reason, TaskId -AutoSize
    throw "Concurrency smoke failed: expected exactly 1 processed request, got $($processed.Count)."
}

if ($duplicates.Count -ne ($RequestCount - 1)) {
    $results | Format-Table Delivery, StatusCode, Message, Reason, TaskId -AutoSize
    throw "Concurrency smoke failed: expected $($RequestCount - 1) duplicate events, got $($duplicates.Count)."
}

Write-Host "Concurrency smoke passed."
Write-Host "Processed requests: 1"
Write-Host "Duplicate events ignored: $($duplicates.Count)"
Write-Host "Task id: $($processed[0].TaskId)"
