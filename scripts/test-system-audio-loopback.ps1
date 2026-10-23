. (Join-Path $PSScriptRoot 'common.ps1')
# Plays one short, quiet test tone from this process, outside the test JVM's excluded tree.
# The test keeps only block counts; no captured audio is written to disk or sent to a room.
$taskMaven = Resolve-Maven
$taskMarker = Join-Path $projectRoot 'target\system-audio-probe.ready'
$taskLog = Join-Path $projectRoot 'target\system-audio-loopback-probe.log'
if (Test-Path -LiteralPath $taskMarker) { Remove-Item -LiteralPath $taskMarker }
New-Item -ItemType Directory -Path (Join-Path $projectRoot 'target') -Force | Out-Null
$taskQuotedRoot = $projectRoot.Replace("'", "''")
$taskQuotedMaven = $taskMaven.Replace("'", "''")
$taskQuotedLog = $taskLog.Replace("'", "''")
$taskScript = "Set-Location -LiteralPath '$taskQuotedRoot'; & '$taskQuotedMaven' -B '-Pvideo-integration' '-Dtest=SystemAudioIntegrationTest#nativeLoopbackStartsWithoutMicrophoneAndDisposesItsHelper' '-Dmmr.test.systemAudioProbe=true' test *> '$taskQuotedLog'; exit `$LASTEXITCODE"
$taskEncoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($taskScript))
$taskProcess = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -ArgumentList @('-NoLogo','-NoProfile','-NonInteractive','-WindowStyle','Hidden','-EncodedCommand',$taskEncoded) -WindowStyle Hidden -PassThru
try {
    $taskDeadline = [DateTime]::UtcNow.AddSeconds(60)
    while (!(Test-Path -LiteralPath $taskMarker)) {
        if ($taskProcess.HasExited -or [DateTime]::UtcNow -gt $taskDeadline) { throw 'System audio probe did not become ready. Inspect target/system-audio-loopback-probe.log.' }
        Start-Sleep -Milliseconds 100
    }
    $taskStream = New-Object IO.MemoryStream
    $taskWriter = New-Object IO.BinaryWriter($taskStream)
    $taskFrameCount = 19200
    $taskWriter.Write([Text.Encoding]::ASCII.GetBytes('RIFF')); $taskWriter.Write([int](36 + $taskFrameCount * 4))
    $taskWriter.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt ')); $taskWriter.Write([int]16)
    $taskWriter.Write([int16]1); $taskWriter.Write([int16]2); $taskWriter.Write([int]48000); $taskWriter.Write([int]192000)
    $taskWriter.Write([int16]4); $taskWriter.Write([int16]16); $taskWriter.Write([Text.Encoding]::ASCII.GetBytes('data')); $taskWriter.Write([int]($taskFrameCount * 4))
    for ($taskSampleIndex = 0; $taskSampleIndex -lt $taskFrameCount; $taskSampleIndex++) {
        $taskValue = [int16](1200 * [Math]::Sin(2 * [Math]::PI * 997 * $taskSampleIndex / 48000))
        $taskWriter.Write($taskValue); $taskWriter.Write($taskValue)
    }
    $taskWriter.Flush(); $taskStream.Position = 0
    $taskPlayer = New-Object System.Media.SoundPlayer($taskStream)
    try { $taskPlayer.Load(); $taskPlayer.PlaySync() } finally { $taskPlayer.Dispose(); $taskWriter.Dispose(); $taskStream.Dispose() }
    if (!$taskProcess.WaitForExit(45000)) { throw 'System audio probe did not finish.' }
    if ($taskProcess.ExitCode -ne 0) { Get-Content -LiteralPath $taskLog -Tail 30; throw 'System audio probe failed.' }
    Get-Content -LiteralPath (Join-Path $projectRoot 'target\system-audio-native-evidence.txt')
    Get-Content -LiteralPath $taskLog -Tail 10
} finally {
    if (!$taskProcess.HasExited) { & taskkill.exe /PID $taskProcess.Id /T /F | Out-Null }
    if (Test-Path -LiteralPath $taskMarker) { Remove-Item -LiteralPath $taskMarker }
}
