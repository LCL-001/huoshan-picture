$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jm = "D:\develop\idea_java_projects\yun-picture-backend\jmeter"

$runs = @(
    @{dir="report01-ano-20";  note="anon-read 20T 300s"},
    @{dir="report01-ano-50";  note="anon-read 50T 300s"},
    @{dir="report01-ano-100"; note="anon-read 100T 300s"},
    @{dir="report02-login-5"; note="login 5T 300s"},
    @{dir="report02-login-10";note="login 10T 300s"},
    @{dir="report02-login-20";note="login 20T 300s"},
    @{dir="report03-auth-5";  note="auth-read 5T 300s"},
    @{dir="report03-auth-20"; note="auth-read 20T 300s"},
    @{dir="report03-auth-50"; note="auth-read 50T 300s"},
    @{dir="report04-mix";     note="mix 600s"}
)

foreach ($run in $runs) {
    $f = Join-Path $jm "$($run.dir)\statistics.json"
    if (-not (Test-Path $f)) { Write-Host "MISSING $($run.dir)"; continue }
    Write-Host ""
    Write-Host ("##### {0}  ({1})" -f $run.dir, $run.note)
    $j = Get-Content $f -Encoding UTF8 -Raw | ConvertFrom-Json
    "{0,-26} {1,>8} {2,>8} {3,>7} {4,>8} {5,>8} {6,>8}" -f "api","count","tps","err%","avg","p95","max"
    foreach ($p in $j.PSObject.Properties) {
        $v = $p.Value
        $name = if ($p.Name.Length -gt 26) { $p.Name.Substring(0,26) } else { $p.Name }
        "{0,-26} {1,8} {2,8:N1} {3,7:N2} {4,8:N0} {5,8:N0} {6,8:N0}" -f `
            $name, $v.sampleCount, [double]$v.throughput, [double]$v.errorPct, `
            [double]$v.meanResTime, [double]$v.pct2ResTime, [double]$v.maxResTime
    }
}
