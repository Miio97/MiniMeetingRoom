. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location -LiteralPath $projectRoot
try {
    $maven = Resolve-Maven
    & $maven -B process-classes javafx:run
    if ($LASTEXITCODE -ne 0) { throw 'Client stopped with an error.' }
} finally { Pop-Location }
