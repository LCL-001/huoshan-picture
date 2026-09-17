<#
.SYNOPSIS
  Run the paginated-list load test once per (path, concurrency) pair.

.DESCRIPTION
  The DB path and the cache path are measured in SEPARATE JMeter runs. Running
  them in one JVM makes them compete for the same threads, connection pool and
  CPU, so the cache path would carry the DB path's queuing cost and the A/B
  comparison would be meaningless.

  Each run is a fresh JVM, so there is also no JIT / connection warm-up carry
  over between runs, and no sampler-order bias inside a run.

  Runs performed (defaults):
    list-page-DB    x 20, 50, 100
    list-page-Cache x 20, 50, 100

  Each run writes:
    reports/<tag>.jtl              raw samples (NOT committed, can be ~40MB)
    reports/<tag>/statistics.json  JMeter HTML report data
    statistics-ab/<tag>.json       copy, consumed by compare-ab.py

  At the end compare-ab.py prints a DB-vs-cache table per concurrency level and
  a staircase table showing whether throughput is still rising.

.EXAMPLE
  # Public network (the usual case). ~2 runs per level.
  .\run-ab-staircase.ps1 -Levels 20,50,100

.EXAMPLE
  # Inner network / local instance: strips most of the public RTT away, so the
  # DB-vs-cache gap becomes a server-side gap. Shorter duration is usually
  # enough locally.
  .\run-ab-staircase.ps1 -TargetHost 127.0.0.1 -Port 8123 -Protocol http `
                        -Levels 20,50,100 -Duration 120

.EXAMPLE
  # Only the cache path, one level
  .\run-ab-staircase.ps1 -Paths Cache -Levels 100

.NOTES
  Keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI unless it
  has a BOM, so non-ASCII text here turns into mojibake on the console.

  The plan is anonymous-only (no login), so account/password parameters are not
  needed. -Jt_base keeps the baseline thread group light: it exists to measure
  network RTT, and giving it many threads would make it contend with the
  measured path and pollute it.
#>
param(
    # Comma-separated, not [string[]], for the same reason as -Levels.
    [string] $Paths    = "DB,Cache",
    # Comma-separated string, not [int[]]: when this script is invoked via
    # `powershell -File`, "20,50" arrives as one string and an [int[]] cast
    # silently mangles it (20,50 became 2050). Splitting ourselves works for
    # both `-Levels 20,50` and `-Levels @(20,50)`.
    [string] $Levels   = "20,50,100",
    [int]    $Ramp     = 30,
    [int]    $Duration = 300,
    [int]    $Warmup   = 20,
    [int]    $BaseThreads = 1,
    [string] $TargetHost = "lincode.online",
    [int]    $Port       = 443,
    [string] $Protocol   = "https",
    # Path to the jmeter launcher. Spaces are fine: it is passed as a single
    # argument to the call operator, not concatenated into a command line.
    [string] $Jmeter     = "jmeter",
    # Arguments placed BEFORE the plan's own arguments, comma-separated.
    # Kept as a string for the same reason as -Levels: an array parameter
    # collapses into one comma-joined token when the script is started via
    # `powershell -File`. Example: -PrefixArgs "-Xmx4g,-Xms4g"
    #
    # Named -PrefixArgs rather than -JmeterArgs on purpose: PowerShell variable
    # names are case-insensitive, so a parameter named $JmeterArgs and a local
    # named $jmeterArgs are the SAME variable - and the [string] type constraint
    # would silently coerce any array assigned to it into one string.
    [string] $PrefixArgs = ""
)

$ErrorActionPreference = "Stop"

$pathMap = @{
    "DB"    = @{ Path = "/api/picture/list/page/vo";       Label = "list-page-DB" }
    "Cache" = @{ Path = "/api/picture/list/page/vo/cache"; Label = "list-page-Cache" }
}

$levelList = @(
    $Levels -split '[,;\s]+' |
        Where-Object { $_ -ne '' } |
        ForEach-Object { [int]$_ }
)
if ($levelList.Count -eq 0) { throw "-Levels is empty; expected e.g. -Levels 20,50,100" }

$pathKeys = @(
    $Paths -split '[,;\s]+' |
        Where-Object { $_ -ne '' } |
        ForEach-Object { $_.Trim() }
)
if ($pathKeys.Count -eq 0) { throw "-Paths is empty; expected e.g. -Paths DB,Cache" }
foreach ($key in $pathKeys) {
    if (-not $pathMap.ContainsKey($key)) {
        throw "-Paths contains '$key'; valid values are: $($pathMap.Keys -join ', ')"
    }
}

# [System.Collections.Generic.List[string]] rather than @()+@(): when $PrefixArgs
# is empty its split yields a scalar string, and @($scalar) + @(array) performs
# STRING CONCATENATION, collapsing the whole argument vector into one token that
# cmd then shreds character by character. A typed list cannot degrade that way.
$extraArgs = [System.Collections.Generic.List[string]]::new()
foreach ($a in ($PrefixArgs -split '[,;\s]+' | Where-Object { $_ -ne '' })) {
    $extraArgs.Add($a)
}

$jmeterDir  = Split-Path -Parent $PSScriptRoot          # backend/jmeter
$jmx        = Join-Path $jmeterDir "lincode-ab.jmx"
$reportRoot = Join-Path $jmeterDir "reports"
$statsDir   = Join-Path $jmeterDir "statistics-ab"

if (-not (Test-Path $jmx)) { throw "plan not found: $jmx" }
New-Item -ItemType Directory -Force -Path $reportRoot, $statsDir | Out-Null

Write-Host "plan     : $jmx"
Write-Host "target   : ${Protocol}://${TargetHost}:$Port"
Write-Host "paths    : $($pathKeys -join ', ')"
Write-Host "levels   : $($levelList -join ', ')"
Write-Host "ramp/dur : ${Ramp}s / ${Duration}s"
Write-Host "runs     : $($pathKeys.Count * $levelList.Count) separate JVMs"
Write-Host ""

foreach ($level in $levelList) {
    foreach ($key in $pathKeys) {

        $spec = $pathMap[$key]
        $tag  = "$($spec.Label)-t$level"

        $jtl       = Join-Path $reportRoot "$tag.jtl"
        $reportDir = Join-Path $reportRoot $tag

        # JMeter refuses to write into a non-empty report dir, and each run must
        # start from a clean jtl so the two paths never share one file.
        if (Test-Path $reportDir) { Remove-Item -Recurse -Force $reportDir }
        if (Test-Path $jtl)       { Remove-Item -Force $jtl }

        Write-Host "== $tag =="

        $runArgs = [System.Collections.Generic.List[string]]::new()
        $runArgs.AddRange($extraArgs)
        foreach ($a in @(
            "-n",
            "-t", $jmx,
            "-Jhost=$TargetHost",
            "-Jport=$Port",
            "-Jprotocol=$Protocol",
            "-Jsampler_path=$($spec.Path)",
            "-Jpath_label=$($spec.Label)",
            "-Jwarmup=$Warmup",
            "-Jt1=$level",
            "-Jramp1=$Ramp",
            "-Jdur=$Duration",
            "-Jt_base=$BaseThreads",
            "-l", $jtl,
            "-e", "-o", $reportDir
        )) {
            $runArgs.Add($a)
        }

        & $Jmeter $runArgs.ToArray()
        if ($LASTEXITCODE -ne 0) { throw "jmeter exited $LASTEXITCODE on $tag" }

        $statsJson = Join-Path $reportDir "statistics.json"
        if (-not (Test-Path $statsJson)) { throw "no statistics.json produced for $tag" }
        Copy-Item $statsJson (Join-Path $statsDir "$tag.json") -Force

        Write-Host ""
    }
}

Write-Host "== comparison =="
python (Join-Path $PSScriptRoot "compare-ab.py") $statsDir
