param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskTargetRoot = [IO.Path]::GetFullPath((Join-Path $taskProjectRoot 'target'))
$taskRuntimePath = if ([IO.Path]::IsPathRooted($OutputPath)) {
    [IO.Path]::GetFullPath($OutputPath)
} else {
    [IO.Path]::GetFullPath((Join-Path $taskProjectRoot $OutputPath))
}
if (-not $taskRuntimePath.StartsWith($taskTargetRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Runtime output must be a new directory inside the project target directory.'
}
if (Test-Path -LiteralPath $taskRuntimePath) { throw 'Runtime output already exists; use a new output directory.' }
$taskJlink = Join-Path $JavaHome 'bin/jlink.exe'
if (-not (Test-Path -LiteralPath $taskJlink)) { throw 'JavaHome must point to a full JDK with jlink.' }
# Keep the modules required by JavaFX, WebRTC, TLS and HikariCP/JDBC.
$taskModules = 'java.se,jdk.jfr,jdk.crypto.ec,jdk.unsupported,jdk.unsupported.desktop,jdk.jsobject,jdk.zipfs'
& $taskJlink --add-modules $taskModules --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $taskRuntimePath
if ($LASTEXITCODE -ne 0) { throw 'Client runtime build failed.' }
& (Join-Path $taskRuntimePath 'bin/java.exe') --list-modules
if ($LASTEXITCODE -ne 0) { throw 'Client runtime verification failed.' }
