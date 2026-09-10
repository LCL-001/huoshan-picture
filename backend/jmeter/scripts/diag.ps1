$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jm = "D:\develop\idea_java_projects\yun-picture-backend\jmeter"

Write-Host "--- jmeter.log tail ---"
Get-Content "$jm\jmeter.log" -Tail 40 -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "--- jmx thread params ---"
$lines = Get-Content "$jm\lincode-loadtest-v3.jmx" -Encoding UTF8
foreach ($l in $lines) {
    if ($l -match 'num_threads|ramp_time|ThreadGroup.duration|testname="0[123]-') {
        Write-Host $l.Trim()
    }
}
