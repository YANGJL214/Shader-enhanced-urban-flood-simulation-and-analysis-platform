$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot 'runtime\anuga_env\python.exe'

if (-not (Test-Path -LiteralPath $python)) {
    throw "Python runtime not found: $python"
}

$sources = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'runtime') -Filter '*.py' -File -Recurse |
    Where-Object { $_.FullName -notlike '*\runtime\anuga_env\*' }

foreach ($source in $sources) {
    & $python -m py_compile $source.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "Python syntax check failed: $($source.FullName)"
    }
}

& $python (Join-Path $projectRoot 'runtime\templates\adaptive_tin_generator.py') --help | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Adaptive TIN generator import check failed.'
}

& $python (Join-Path $projectRoot 'runtime\templates\swmm_out_extractor.py') --help | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'SWMM result extractor import check failed.'
}

Write-Host "[OK] Checked $($sources.Count) Python source files and both command-line entry points." -ForegroundColor Green
