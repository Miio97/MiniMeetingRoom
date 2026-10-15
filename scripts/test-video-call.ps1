. (Join-Path $PSScriptRoot 'common.ps1')
& (Join-Path $PSScriptRoot 'build-video-engine.ps1')
Push-Location $projectRoot
try {
    $taskMaven = Resolve-Maven
    & $taskMaven -B '-Pvideo-integration' '-Dtest=VideoCallIntegrationTest,AudioCallIntegrationTest,ScreenCallIntegrationTest' test
    if ($LASTEXITCODE -ne 0) { throw 'Native video integration failed.' }
} finally { Pop-Location }
