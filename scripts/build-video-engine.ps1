. (Join-Path $PSScriptRoot 'common.ps1')
& (Join-Path $PSScriptRoot 'init-go-toolchain.ps1')
$taskGo = Join-Path $projectRoot '.tools\go1.27.2\go\bin\go.exe'
$taskBinary = Join-Path $projectRoot '.tools\bin\mmr-video.exe'
New-Item -ItemType Directory -Path (Split-Path -Parent $taskBinary) -Force | Out-Null
$env:GOTOOLCHAIN = 'local'
$env:GOCACHE = Join-Path $projectRoot '.tools\go-cache'
$env:GOMODCACHE = Join-Path $projectRoot '.tools\go-modules'
Push-Location (Join-Path $projectRoot 'media\video')
try {
    & $taskGo build '-ldflags=-H=windowsgui' -o $taskBinary .
    if ($LASTEXITCODE -ne 0) { throw 'Video engine build failed.' }
} finally { Pop-Location }
