$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$tmp = Join-Path $env:TEMP "jl_probe\page.json"
if (-not (Test-Path $tmp)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $tmp) | Out-Null
    Set-Content -Path $tmp -Value '{"current":1,"pageSize":10}' -Encoding UTF8 -NoNewline
}
for ($i = 1; $i -le 3; $i++) {
    curl.exe -s -o NUL -w "health      : %{time_total}s`n" "https://lincode.online/api/health"
    curl.exe -s -o NUL -w "piclist-db  : %{time_total}s`n" -X POST -H "Content-Type: application/json" --data-binary "@$tmp" "https://lincode.online/api/picture/list/page/vo"
    curl.exe -s -o NUL -w "piclist-cache: %{time_total}s`n" -X POST -H "Content-Type: application/json" --data-binary "@$tmp" "https://lincode.online/api/picture/list/page/vo/cache"
    Write-Host "---"
}
