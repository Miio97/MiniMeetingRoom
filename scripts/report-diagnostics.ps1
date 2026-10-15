param(
    [string]$InputPath = 'logs/diagnostics',
    [string]$OutputPath = 'target/diagnostics-report.txt',
    [string]$RunId = ''
)
. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location -LiteralPath $projectRoot
try {
    $maven = Resolve-Maven
    $reportArguments = @(
        '-B',
        '-Dexec.mainClass=common.diagnostics.DiagnosticsReport',
        "-Dmmr.diagnostics.report.input=$InputPath",
        "-Dmmr.diagnostics.report.output=$OutputPath"
    )
    if ($RunId) { $reportArguments += "-Dmmr.diagnostics.report.runId=$RunId" }
    $reportArguments += @('compile', 'exec:java')
    & $maven @reportArguments
    if ($LASTEXITCODE -ne 0) { throw 'Diagnostics report could not be generated.' }
} finally { Pop-Location }
