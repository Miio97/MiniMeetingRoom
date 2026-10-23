param(
    [ValidatePattern('^[A-Za-z0-9_-]{1,48}$')][string]$Label = 'current',
    [ValidateRange(1, 200)][int]$Samples = 50,
    [ValidateRange(1, 10)][int]$Rounds = 3,
    [switch]$SkipEngineBuild,
    [switch]$Offline
)
. (Join-Path $PSScriptRoot 'common.ps1')
if (-not $SkipEngineBuild) { & (Join-Path $PSScriptRoot 'build-video-engine.ps1') }
Push-Location $projectRoot
try {
    $taskMaven = Resolve-Maven
    $taskExistingSfuLogs = @{}
    Get-ChildItem -LiteralPath 'target/video-diagnostics' -Filter '*.txt' -File -ErrorAction SilentlyContinue | ForEach-Object { $taskExistingSfuLogs[$_.Name] = $true }
    $taskArguments = @('-B', '-Pvideo-integration', '-Dtest=MediaToggleBenchmarkTest',
        "-Dmmr.benchmark.label=$Label", "-Dmmr.benchmark.samples=$Samples", "-Dmmr.benchmark.rounds=$Rounds", 'test')
    if ($Offline) { $taskArguments = @('-o') + $taskArguments }
    & $taskMaven @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Media activation benchmark failed; partial CSV is not a completed result.' }
    $taskSfuOutput = Join-Path $projectRoot "target/media-toggle-sfu-$Label"
    New-Item -ItemType Directory -Path $taskSfuOutput -Force | Out-Null
    Get-ChildItem -LiteralPath 'target/video-diagnostics' -Filter '*.txt' -File -ErrorAction SilentlyContinue | Where-Object { -not $taskExistingSfuLogs.ContainsKey($_.Name) } | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $taskSfuOutput $_.Name)
    }
    Write-Output "Measurements: $(Join-Path $projectRoot "target\media-toggle-$Label.csv")"
    Write-Output "Diagnostics: $(Join-Path $projectRoot "target\media-toggle-diagnostics-$Label")"
    Write-Output "SFU diagnostics: $taskSfuOutput"
} finally { Pop-Location }
