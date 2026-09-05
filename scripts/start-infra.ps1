$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$infraRoot = Join-Path $projectRoot 'infra'
$envFile = Join-Path $infraRoot '.env'

Push-Location $infraRoot
try {
    if (Test-Path -LiteralPath $envFile) {
        docker compose --env-file $envFile up -d
    } else {
        docker compose up -d
    }
    docker compose ps
} finally {
    Pop-Location
}
