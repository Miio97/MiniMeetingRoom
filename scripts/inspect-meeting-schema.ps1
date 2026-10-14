. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location -LiteralPath $projectRoot
try {
    $maven = Resolve-Maven
    # Uses existing server configuration. Reads JDBC schema metadata only; no DDL or row data.
    # Exit 0: required known structures present; 1: schema review needed; 2: inspection failed.
    & $maven -B -q '-Dexec.mainClass=server.database.InspectMeetingSchema' compile exec:java
    if ($LASTEXITCODE -ne 0) { throw 'Schema inspection requires review. No migration was applied.' }
} finally { Pop-Location }
