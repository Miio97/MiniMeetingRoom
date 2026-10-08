param([string]$CertificateHost = 'localhost')
. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location -LiteralPath $projectRoot
try {
    New-Item -ItemType Directory -Path 'config' -Force | Out-Null
    $keyStoreFile = 'config/auth-dev.p12'
    $keyStorePassword = 'changeit-local-only'
    $configFile = 'config/auth-server.properties'
    if (Test-Path -LiteralPath $configFile) {
        foreach ($configLine in Get-Content -LiteralPath $configFile) {
            if ($configLine -match '^\s*auth\.keystore\s*=\s*(.*)$') { $keyStoreFile = $Matches[1].Trim() }
            if ($configLine -match '^\s*auth\.keystorePassword\s*=\s*(.*)$') { $keyStorePassword = $Matches[1].Trim() }
        }
    }
    if ($env:MMR_AUTH_KEYSTORE) { $keyStoreFile = $env:MMR_AUTH_KEYSTORE }
    if ($env:MMR_AUTH_KEYSTORE_PASSWORD) { $keyStorePassword = $env:MMR_AUTH_KEYSTORE_PASSWORD }
    $certificateIp = $null
    if ([System.Net.IPAddress]::TryParse($CertificateHost, [ref]$certificateIp)) {
        $certificateIdentity = 'ip:' + $certificateIp.ToString()
    } elseif ($CertificateHost -match '^[A-Za-z0-9.-]+$') {
        $certificateIdentity = 'dns:' + $CertificateHost
    } else { throw 'Invalid certificate hostname or IP address.' }
    $certificateNames = (@($certificateIdentity, 'dns:localhost', 'ip:127.0.0.1') | Select-Object -Unique) -join ','
    $env:MMR_LOCAL_KEYSTORE_PASSWORD = $keyStorePassword
    $keytool = Resolve-Keytool
    if (-not (Test-Path -LiteralPath $keyStoreFile)) {
        & $keytool -genkeypair -alias auth-server -keyalg RSA -keysize 3072 -validity 365 `
            -storetype PKCS12 -keystore $keyStoreFile -storepass:env MMR_LOCAL_KEYSTORE_PASSWORD `
            -dname "CN=$CertificateHost" -ext "SAN=$certificateNames" -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Could not generate the local TLS keystore.' }
    }
    & $keytool -exportcert -rfc -alias auth-server -keystore $keyStoreFile `
        -storepass:env MMR_LOCAL_KEYSTORE_PASSWORD -file 'config/auth-server.cer'
    if ($LASTEXITCODE -ne 0) { throw 'Could not export the public TLS certificate.' }
    Write-Host 'Local TLS ready. Clients need only config/auth-server.cer.'
} finally {
    Remove-Item Env:MMR_LOCAL_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
