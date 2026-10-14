# Project-local build tool; does not install Go globally or modify PATH.
. (Join-Path $PSScriptRoot 'common.ps1')
$taskGoVersion = 'go1.27.2'
$taskGoSha256 = '1314008898bd40df77af4b014f777f08873dbdfbcd3d92308728ee03304fe04f'
$taskToolDirectory = Join-Path $projectRoot ".tools\$taskGoVersion"
$taskGoExecutable = Join-Path $taskToolDirectory 'go\bin\go.exe'
if (-not (Test-Path -LiteralPath $taskGoExecutable)) {
    New-Item -ItemType Directory -Path $taskToolDirectory -Force | Out-Null
    $taskArchive = Join-Path $taskToolDirectory "$taskGoVersion.windows-amd64.zip"
    Invoke-WebRequest -UseBasicParsing -Uri "https://go.dev/dl/$taskGoVersion.windows-amd64.zip" -OutFile $taskArchive
    if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskGoSha256) {
        throw 'Go toolchain checksum mismatch.'
    }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskToolDirectory -Force
}
& $taskGoExecutable version
if ($LASTEXITCODE -ne 0) { throw 'Project-local Go toolchain could not start.' }
