# Uses generated PCM/I420 only. Does not open a camera, microphone or speaker.
. (Join-Path $PSScriptRoot 'common.ps1')
if (-not [Environment]::Is64BitProcess -or $env:OS -ne 'Windows_NT') {
    throw 'This feasibility probe currently targets Windows x64.'
}
Push-Location -LiteralPath $projectRoot
try {
    $maven = Resolve-Maven
    & $maven -B '-Pmedia-probe' '-Dtest=WebRtcClientProbeTest#generatedOpusVp8MediaAndStatsSurviveTwoCleanLifecycles' test
    if ($LASTEXITCODE -ne 0) { throw 'WebRTC client feasibility probe failed; check target/surefire-reports.' }
} finally { Pop-Location }
