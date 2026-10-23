param([string[]]$Reports = @('docs/benchmarks/cleanup-20261023-baseline.csv','docs/benchmarks/cleanup-20261023-candidate.csv'))
$ErrorActionPreference = 'Stop'
function Percentile($values, [double]$p) {
    $ordered = @($values | Sort-Object)
    if (!$ordered.Count) { return [double]::NaN }
    return $ordered[[Math]::Max(0,[Math]::Ceiling($ordered.Count*$p)-1)]
}
function Median($values) {
    $ordered = @($values | Sort-Object)
    if (!$ordered.Count) { return [double]::NaN }
    $middle = [int][Math]::Floor($ordered.Count/2)
    if ($ordered.Count % 2) { return $ordered[$middle] }
    return ($ordered[$middle-1]+$ordered[$middle])/2.0
}
foreach ($report in $Reports) {
    foreach ($group in (Import-Csv -LiteralPath $report | Group-Object scenario)) {
        $times = @($group.Group | ForEach-Object { [double]::Parse($_.elapsed_ms,[Globalization.CultureInfo]::InvariantCulture) })
        [PSCustomObject]@{
            report = Split-Path -Leaf $report
            scenario = $group.Name
            samples = $times.Count
            median_ms = Median $times
            p95_ms = Percentile $times 0.95
            median_db_calls = Median @($group.Group | ForEach-Object { [int]$_.db_calls })
            errors = ($group.Group | Measure-Object -Property errors -Sum).Sum
        }
    }
}
