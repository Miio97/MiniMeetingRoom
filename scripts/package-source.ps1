# Source-only handover. Runtime output, private configuration and user data are excluded.
. (Join-Path $PSScriptRoot 'common.ps1')
$taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$taskOutput = Join-Path $projectRoot "target/source-$taskStamp"
$taskStage = Join-Path $taskOutput 'MiniMeetingRoom-Source'
New-Item -ItemType Directory -Path $taskStage -Force | Out-Null

foreach ($taskDirectory in @('src/main', 'media/video', 'database', 'scripts', 'docs')) {
    New-Item -ItemType Directory -Path (Split-Path -Parent (Join-Path $taskStage $taskDirectory)) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot $taskDirectory) -Destination (Join-Path $taskStage $taskDirectory) -Recurse -Force
}
foreach ($taskName in @('README.md', 'pom.xml', 'nbactions.xml', '.gitignore')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $taskName) -Destination (Join-Path $taskStage $taskName)
}
$taskConfig = Join-Path $taskStage 'config'
New-Item -ItemType Directory -Path $taskConfig -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'config') -Filter '*.example' -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $taskConfig $_.Name)
}

$taskUnexpected = Get-ChildItem -LiteralPath $taskStage -Recurse -File | Where-Object {
    $_.Name -match '(?i)(Test\.java$|_test\.go$|\.p12$|\.jks$|\.cer$|\.private\.properties$|^\.env($|\.))'
}
if ($taskUnexpected) { throw 'Unexpected test or private file in source package.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip = Join-Path $taskOutput 'MiniMeetingRoom-Source.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($taskStage, $taskZip, [IO.Compression.CompressionLevel]::Optimal, $true)
Get-FileHash -LiteralPath $taskZip -Algorithm SHA256 | Format-List
Write-Output "SOURCE_PACKAGE=$taskZip"
