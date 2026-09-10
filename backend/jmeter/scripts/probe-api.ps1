$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$tmp = Join-Path $env:TEMP "jl_probe"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Set-Content -Path "$tmp\register.json" -Value '{"userAccount":"loadtest01","userPassword":"12345678","checkPassword":"12345678","email":"loadtest01@test.com","emailCode":"000000"}' -Encoding UTF8 -NoNewline
Set-Content -Path "$tmp\login.json"    -Value '{"userAccount":"loadtest01","userPassword":"12345678"}' -Encoding UTF8 -NoNewline

function Post($name, $url, $bodyFile) {
    $r = curl.exe -s -X POST -H "Content-Type: application/json" --data-binary "@$bodyFile" $url
    Write-Host "== $name"
    if ($r) { Write-Host ($r.Substring(0, [Math]::Min(400, $r.Length))) }
    Write-Host ""
}

Post "register loadtest01 (expect code 0 + new id)" "https://lincode.online/api/user/register" "$tmp\register.json"
Post "login loadtest01 without captcha (expect code 0)" "https://lincode.online/api/user/login" "$tmp\login.json"
