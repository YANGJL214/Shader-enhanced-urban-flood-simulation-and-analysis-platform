$ErrorActionPreference = 'Stop'

function Show-CommandVersion {
    param(
        [string]$Name,
        [scriptblock]$VersionCommand
    )

    if (Get-Command $Name -ErrorAction SilentlyContinue) {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $version = & $VersionCommand 2>&1 | Select-Object -First 1
        $ErrorActionPreference = $previousPreference
        Write-Host "[OK] $Name - $version" -ForegroundColor Green
    } else {
        Write-Host "[MISSING] $Name" -ForegroundColor Yellow
    }
}

Show-CommandVersion 'docker' { docker --version }
Show-CommandVersion 'node' { node --version }
Show-CommandVersion 'npm' { npm --version }
Show-CommandVersion 'java' { java -version }

$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimePython = Join-Path $projectRoot 'runtime\anuga_env\python.exe'
$modelExecutable = Join-Path $projectRoot 'runtime\Release\Project1.exe'

if (Test-Path -LiteralPath $runtimePython) {
    $pythonVersion = & $runtimePython --version 2>&1
    Write-Host "[OK] bundled model Python - $pythonVersion" -ForegroundColor Green
} else {
    Write-Host "[MISSING] bundled model Python: $runtimePython" -ForegroundColor Yellow
}

if (Test-Path -LiteralPath $modelExecutable) {
    Write-Host "[OK] model executable - $modelExecutable" -ForegroundColor Green
} else {
    Write-Host "[MISSING] model executable: $modelExecutable" -ForegroundColor Yellow
}
