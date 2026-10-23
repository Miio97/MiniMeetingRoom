param([switch]$Benchmark, [ValidateRange(1025,65535)][int]$MySqlPort)
. (Join-Path $PSScriptRoot 'common.ps1')
Push-Location -LiteralPath $projectRoot
try {
    $arguments = @('-q', '-Dtest=ImmediateRoomCleanupTest,FileTransferIntegrationTest,FileStorageServiceTest,FileTransferClientTest,RoomCleanupRoutineIntegrationTest,LegacyRoomServiceTest,UnifiedRoomCompatibilityTest,MeetingSignalingTest')
    if ($Benchmark) {
        $arguments = @('-q', '-Dtest=FileTransferIntegrationTest#cleanupBenchmark', '-Dmmr.cleanup.benchmark=true', '-Dmmr.cleanup.report=docs/benchmarks/cleanup-20261023-candidate.csv')
    }
    if ($MySqlPort) { $arguments += "-Dmmr.test.cleanup.mysqlPort=$MySqlPort" }
    $arguments += 'test'
    & (Resolve-Maven) @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Room cleanup checks failed; see target/surefire-reports.' }
} finally { Pop-Location }
