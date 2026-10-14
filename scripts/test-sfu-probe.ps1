# Generated media only; starts a private loopback Pion process and stops it afterwards.
. (Join-Path $PSScriptRoot 'common.ps1')
& (Join-Path $PSScriptRoot 'init-go-toolchain.ps1')
$taskGo = Join-Path $projectRoot '.tools\go1.27.2\go\bin\go.exe'
$taskExecutable = Join-Path $projectRoot 'target\sfu-probe.exe'
$taskProcess = $null
$taskOldSecret = $env:MMR_MEDIA_PROBE_SECRET
$taskOldPort = $env:MMR_MEDIA_PROBE_PORT
$taskOldToolchain = $env:GOTOOLCHAIN
$taskOldGoCache = $env:GOCACHE
$taskOldModuleCache = $env:GOMODCACHE
$taskSecretBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($taskSecretBytes)
$env:MMR_MEDIA_PROBE_SECRET = ([BitConverter]::ToString($taskSecretBytes)).Replace('-', '').ToLowerInvariant()
$taskListener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
$taskListener.Start()
$env:MMR_MEDIA_PROBE_PORT = [string]$taskListener.LocalEndpoint.Port
$taskListener.Stop()
$env:GOTOOLCHAIN = 'local'
$env:GOCACHE = Join-Path $projectRoot '.tools\go-cache'
$env:GOMODCACHE = Join-Path $projectRoot '.tools\go-modules'
try {
    New-Item -ItemType Directory -Path (Join-Path $projectRoot 'target') -Force | Out-Null
    Push-Location -LiteralPath (Join-Path $projectRoot 'media\probe')
    try {
        & $taskGo test ./...
        if ($LASTEXITCODE -ne 0) { throw 'Pion probe tests failed.' }
        & $taskGo build -o $taskExecutable .
        if ($LASTEXITCODE -ne 0) { throw 'Pion probe build failed.' }
    } finally { Pop-Location }
    $taskDbEnvironment = @{}
    try {
        foreach ($taskName in @('MMR_DB_URL', 'MMR_DB_USER', 'MMR_DB_PASSWORD')) {
            $taskDbEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
            [Environment]::SetEnvironmentVariable($taskName, $null, 'Process')
        }
        $taskProcess = Start-Process -FilePath $taskExecutable -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $projectRoot 'target\sfu-probe.stdout.txt') `
            -RedirectStandardError (Join-Path $projectRoot 'target\sfu-probe.stderr.txt')
    } finally {
        foreach ($taskName in $taskDbEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($taskName, $taskDbEnvironment[$taskName], 'Process')
        }
    }
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 40; $taskAttempt++) {
        if ($taskProcess.HasExited) { throw 'Pion probe stopped before readiness.' }
        try {
            $taskResponse = Invoke-WebRequest -UseBasicParsing -TimeoutSec 1 `
                -Uri "http://127.0.0.1:$env:MMR_MEDIA_PROBE_PORT/health" `
                -Headers @{ 'X-MMR-Probe-Key' = $env:MMR_MEDIA_PROBE_SECRET }
            if ($taskResponse.StatusCode -eq 200) { $taskReady = $true; break }
        } catch { Start-Sleep -Milliseconds 100 }
    }
    if (-not $taskReady) { throw 'Pion probe readiness deadline exceeded.' }
    Push-Location -LiteralPath $projectRoot
    try {
        $taskMaven = Resolve-Maven
        & $taskMaven -B '-Pmedia-probe' '-Dtest=WebRtcClientProbeTest#generatedMediaCrossesPionWithoutTranscoding' test
        if ($LASTEXITCODE -ne 0) { throw 'Generated media SFU proof failed; inspect target/surefire-reports.' }
    } finally { Pop-Location }
} finally {
    if ($taskProcess -and -not $taskProcess.HasExited) {
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Method Post `
                -Uri "http://127.0.0.1:$env:MMR_MEDIA_PROBE_PORT/shutdown" `
                -Headers @{ 'X-MMR-Probe-Key' = $env:MMR_MEDIA_PROBE_SECRET } | Out-Null
        } catch { }
        if (-not $taskProcess.WaitForExit(3000)) {
            Stop-Process -Id $taskProcess.Id
            $taskProcess.WaitForExit(3000) | Out-Null
            Write-Warning 'PROBE_SHUTDOWN_FORCED'
        }
    }
    $env:MMR_MEDIA_PROBE_SECRET = $taskOldSecret
    $env:MMR_MEDIA_PROBE_PORT = $taskOldPort
    $env:GOTOOLCHAIN = $taskOldToolchain
    $env:GOCACHE = $taskOldGoCache
    $env:GOMODCACHE = $taskOldModuleCache
}
