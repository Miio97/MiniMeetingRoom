. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $PSScriptRoot 'init-local-tls.ps1')
    $maven = Resolve-Maven
    & $maven -B '-Dexec.mainClass=server.AuthServer' compile exec:java
    if ($LASTEXITCODE -ne 0) { throw 'Authentication server stopped with an error.' }
} finally { Pop-Location }
