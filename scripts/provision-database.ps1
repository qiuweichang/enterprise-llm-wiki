param(
    [string]$DatabaseName = "llm_wiki"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($env:LLM_WIKI_DB_PASSWORD)) {
    throw "LLM_WIKI_DB_PASSWORD must be set in the current process environment."
}

$dbUser = if ($env:LLM_WIKI_DB_USERNAME) { $env:LLM_WIKI_DB_USERNAME } else { "postgres" }
$dbHost = if ($env:LLM_WIKI_DB_HOST) { $env:LLM_WIKI_DB_HOST } else { "localhost" }
$dbPort = if ($env:LLM_WIKI_DB_PORT) { $env:LLM_WIKI_DB_PORT } else { "5432" }
$jdbcJar = Get-ChildItem (Join-Path $env:USERPROFILE ".m2\repository\org\postgresql\postgresql") `
    -Recurse -Filter "postgresql-*.jar" -ErrorAction SilentlyContinue |
    Sort-Object { [version]$_.Directory.Name } -Descending |
    Select-Object -First 1
if (-not $jdbcJar) {
    throw "PostgreSQL JDBC driver was not found. Run 'mvn -pl server compile' once to resolve server dependencies."
}

$provisioner = Join-Path $PSScriptRoot "ProvisionDatabase.java"
& java --class-path $jdbcJar.FullName $provisioner $dbHost $dbPort $dbUser $DatabaseName
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL database provisioning failed." }
