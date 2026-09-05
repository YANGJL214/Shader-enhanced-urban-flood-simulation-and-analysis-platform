$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$infraRoot = Join-Path $projectRoot 'infra'

Push-Location $infraRoot
try {
    docker compose down
} finally {
    Pop-Location
}
