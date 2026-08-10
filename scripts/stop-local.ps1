$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runDirectory = Join-Path $workspace ".run"

# 先停止子进程再停止父进程，确保 Maven/npm 启动器不会遗留 Java 或 Vite 服务。
function Stop-ProcessTree([int]$RootProcessId) {
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $RootProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree ([int]$child.ProcessId)
    }
    if (Get-Process -Id $RootProcessId -ErrorAction SilentlyContinue) {
        # 子进程退出可能同步带走启动器；停止动作必须幂等，避免竞态中断整套服务重启。
        Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
    }
}

foreach ($service in @("web", "server", "python-worker")) {
    $pidFile = Join-Path $runDirectory "$service.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) { continue }
    $processId = [int](Get-Content -Raw -LiteralPath $pidFile)
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process) {
        Stop-ProcessTree $processId
        Write-Host "Stopped $service process $processId."
    }
    Remove-Item -LiteralPath $pidFile
}
