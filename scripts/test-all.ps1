$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$startedServices = $false

# 使用 TCP 健康前置检查区分“全部未启动”和危险的部分启动状态。
function Test-ServicePort([int]$Port) {
    return Test-NetConnection -ComputerName "127.0.0.1" -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
}

# 等待真实 HTTP 健康端点，避免浏览器在迁移或管理员引导尚未完成时抢跑。
function Wait-Healthy([string]$Uri, [string]$Name) {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $response = Invoke-RestMethod -Uri $Uri -TimeoutSec 2
            if ($response.status -eq "UP") { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become healthy within 60 seconds."
}

if ([string]::IsNullOrWhiteSpace($env:LLM_WIKI_DB_PASSWORD)) {
    throw "LLM_WIKI_DB_PASSWORD must be set for the isolated PostgreSQL integration test."
}
if ([string]::IsNullOrWhiteSpace($env:LLM_WIKI_E2E_EMAIL)) { $env:LLM_WIKI_E2E_EMAIL = "1" }
if ([string]::IsNullOrWhiteSpace($env:LLM_WIKI_E2E_PASSWORD)) { $env:LLM_WIKI_E2E_PASSWORD = "1" }

$servicePorts = @((Test-ServicePort 8123), (Test-ServicePort 8101), (Test-ServicePort 5173))
if ($servicePorts -contains $true -and $servicePorts -contains $false) {
    throw "Only part of the LLM Wiki stack is running. Stop stale services, then rerun the test suite."
}
if ($servicePorts -notcontains $true) {
    & (Join-Path $PSScriptRoot "start-local.ps1")
    $startedServices = $true
    Wait-Healthy "http://127.0.0.1:8123/actuator/health" "Java server"
    Wait-Healthy "http://127.0.0.1:8101/health" "Python worker"
}

Push-Location $workspace
try {
    mvn test
    Push-Location (Join-Path $workspace "python-worker")
    try { & .\.venv\Scripts\python -m pytest } finally { Pop-Location }
    Push-Location (Join-Path $workspace "web")
    try {
        npm run test
        npm run build
        npm run test:e2e
    } finally { Pop-Location }
} finally {
    Pop-Location
    if ($startedServices) {
        & (Join-Path $PSScriptRoot "stop-local.ps1")
    }
}
