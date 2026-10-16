# Assistant chat over the backend SSE proxy (login -> ticket -> chat).
# Repro used for the 2026-10-17 real-model end-to-end verification.
# Notes:
#   - Image captcha is required by login. This script fetches a captcha and reads the
#     plaintext answer from Redis (local verification only; the backend stores it at
#     huoshantuku:auth:captcha:img:<uuid> in DB 1).
#   - Keep this file ASCII-only: Windows PowerShell 5.1 reads non-ASCII source as ANSI and
#     a mis-decoded multi-byte comment can swallow the following newline.
param(
  [Parameter(Mandatory=$true)][string]$MsgFile,   # UTF-8 text file with the user message
  [Parameter(Mandatory=$true)][string]$OutFile,   # UTF-8 file to receive the raw SSE frames
  [string]$Base = 'http://localhost:8123/api',
  [string]$RedisCli = 'redis-cli',
  [string]$Account = '123456',
  [string]$Password = $env:HS_TEST_PASSWORD
)
$ErrorActionPreference = 'Stop'
if (-not $Password) { throw 'set HS_TEST_PASSWORD (test account password)' }
$msg = [System.IO.File]::ReadAllText($MsgFile, [System.Text.Encoding]::UTF8).Trim()

$cap = Invoke-RestMethod -Uri "$Base/user/captcha/img" -Method Get
$uuid = $cap.data.captchaUuid
$code = (& $RedisCli -n 1 GET "huoshantuku:auth:captcha:img:$uuid").Trim()
if (-not $code) { throw 'captcha answer not found in redis db 1' }
Write-Host ("CAPTCHA uuid=" + $uuid)

$loginBody = '{"userAccount":"' + $Account + '","userPassword":"' + $Password + '","captchaUuid":"' + $uuid + '","captchaCode":"' + $code + '"}'
$login = Invoke-RestMethod -Uri "$Base/user/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($loginBody)) -SessionVariable sess
Write-Host ("LOGIN code=" + $login.code + " userId=" + $login.data.id + " role=" + $login.data.userRole)
if ($login.code -ne 0) { throw ('login failed: ' + $login.message) }

$ticketResp = Invoke-RestMethod -Uri "$Base/ai/assistant/ticket" -Method Post -WebSession $sess
$ticket = $ticketResp.data
if (-not $ticket) { throw ('no ticket: ' + $ticketResp.message) }
Write-Host ("TICKET code=" + $ticketResp.code)

# percent-encode the message (do not rely on UrlEncode in this shell version)
$sb = New-Object System.Text.StringBuilder
foreach ($ch in $msg.ToCharArray()) {
  foreach ($b in [System.Text.Encoding]::UTF8.GetBytes([string]$ch)) { [void]$sb.Append('%' + $b.ToString('X2')) }
}
$url = "$Base/ai/assistant/chat?message=$($sb.ToString())&ticket=$ticket"

$req = [System.Net.HttpWebRequest]::Create($url)
$req.Method = 'GET'
$req.CookieContainer = $sess.Cookies
$req.Timeout = 180000
$req.ReadWriteTimeout = 180000
$resp = $req.GetResponse()
$reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
$sw = New-Object System.IO.StreamWriter($OutFile, $false, (New-Object System.Text.UTF8Encoding($false)))
$start = Get-Date
while (-not $reader.EndOfStream) {
  $line = $reader.ReadLine()
  if ($null -ne $line) { $sw.WriteLine($line) }
  if (((Get-Date) - $start).TotalSeconds -gt 170) { $sw.WriteLine('[TIMEOUT]'); break }
}
$sw.Close(); $reader.Close(); $resp.Close()
Write-Host 'DONE'
