. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $PSScriptRoot 'init-local-tls.ps1')
    & (Join-Path $PSScriptRoot 'init-go-toolchain.ps1')
    $maven = Resolve-Maven
    & $maven -B '-Dexec.mainClass=server.Main' compile exec:java
    if ($LASTEXITCODE -ne 0) { throw 'Meeting server failed; no migration was automatically applied.' }
} finally { Pop-Location }
