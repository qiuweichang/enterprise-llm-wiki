$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runDirectory = Join-Path $workspace ".run"
$logDirectory = Join-Path $workspace "logs"
New-Item -ItemType Directory -Force -Path $runDirectory, $logDirectory | Out-Null

# application.yml 已提供本地开发默认值，脚本可零配置启动；部署环境仍可用环境变量覆盖。

$pythonExecutable = Join-Path $workspace "python-worker\.venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $pythonExecutable)) { $pythonExecutable = "python" }

$python = Start-Process -FilePath $pythonExecutable -ArgumentList @("-m", "uvicorn", "llm_wiki_worker.main:app", "--host", "127.0.0.1", "--port", "8101") `
    -WorkingDirectory (Join-Path $workspace "python-worker") -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDirectory "python-worker.out.log") -RedirectStandardError (Join-Path $logDirectory "python-worker.error.log")
$python.Id | Set-Content -LiteralPath (Join-Path $runDirectory "python-worker.pid")

$server = Start-Process -FilePath "mvn" -ArgumentList @("-pl", "server", "spring-boot:run", "-Dspring-boot.run.profiles=dev") `
    -WorkingDirectory $workspace -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDirectory "server.out.log") -RedirectStandardError (Join-Path $logDirectory "server.error.log")
$server.Id | Set-Content -LiteralPath (Join-Path $runDirectory "server.pid")

$web = Start-Process -FilePath "npm.cmd" -ArgumentList @("run", "dev") `
    -WorkingDirectory (Join-Path $workspace "web") -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDirectory "web.out.log") -RedirectStandardError (Join-Path $logDirectory "web.error.log")
$web.Id | Set-Content -LiteralPath (Join-Path $runDirectory "web.pid")

Write-Host "Started LLM Wiki services. Java: $($server.Id), Python: $($python.Id), Web: $($web.Id)"
Write-Host "Open http://127.0.0.1:5173 after /actuator/health reports UP."
