# Assistant write-confirmation gate over the backend proxy: create space (optional) ->
# chat -> capture the confirmation_required frame -> POST /operations/confirm.
# Repro used for the 2026-10-17 real-model end-to-end verification.
# Keep this file ASCII-only (see the chat script header for why).
param(
  [Parameter(Mandatory=$true)][string]$PlanFile,  # UTF-8 JSON: { spaceName, message }
  [Parameter(Mandatory=$true)][string]$OutFile,   # UTF-8 file to receive the raw SSE frames
  [switch]$NoCreate,                              # reuse an existing space instead of creating one
  [string]$Base = 'http://localhost:8123/api',
  [string]$RedisCli = 'redis-cli',
  [string]$Account = '123456',
  [string]$Password = $env:HS_TEST_PASSWORD
)
$ErrorActionPreference = 'Stop'
if (-not $Password) { throw 'set HS_TEST_PASSWORD (test account password)' }
$log = New-Object System.Collections.ArrayList
function Note($t) { [void]$log.Add($t); Write-Host $t }

$plan = [System.IO.File]::ReadAllText($PlanFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$msg = $plan.message
$spaceName = $plan.spaceName

$cap = Invoke-RestMethod -Uri "$Base/user/captcha/img" -Method Get
$uuid = $cap.data.captchaUuid
$code = (& $RedisCli -n 1 GET "huoshantuku:auth:captcha:img:$uuid").Trim()
$loginBody = '{"userAccount":"' + $Account + '","userPassword":"' + $Password + '","captchaUuid":"' + $uuid + '","captchaCode":"' + $code + '"}'
$login = Invoke-RestMethod -Uri "$Base/user/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($loginBody)) -SessionVariable sess
Note ("LOGIN code=" + $login.code + " userId=" + $login.data.id)
if ($login.code -ne 0) { throw 'login failed' }

# one private space per user per type; reuse it on later runs with -NoCreate
if (-not $NoCreate) {
  $sbBody = '{"spaceName":"' + $spaceName + '","spaceLevel":0,"spaceType":0}'
  $sp = Invoke-RestMethod -Uri "$Base/space/add" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($sbBody)) -WebSession $sess
  Note ("SPACE code=" + $sp.code + " id=" + $sp.data + " msg=" + $sp.message)
}

$ticketResp = Invoke-RestMethod -Uri "$Base/ai/assistant/ticket" -Method Post -WebSession $sess
$ticket = $ticketResp.data
if (-not $ticket) { throw 'no ticket' }

# client-generated chatId, same contract as the frontend (crypto.randomUUID)
$chatId = [guid]::NewGuid().ToString()
Note ("CHATID " + $chatId)

$sb = New-Object System.Text.StringBuilder
foreach ($ch in $msg.ToCharArray()) {
  foreach ($b in [System.Text.Encoding]::UTF8.GetBytes([string]$ch)) { [void]$sb.Append('%' + $b.ToString('X2')) }
}
$url = "$Base/ai/assistant/chat?message=$($sb.ToString())&ticket=$ticket&chatId=$chatId"

$req = [System.Net.HttpWebRequest]::Create($url)
$req.Method = 'GET'
$req.CookieContainer = $sess.Cookies
$req.Timeout = 180000
$req.ReadWriteTimeout = 180000
$resp = $req.GetResponse()
$reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
$sw = New-Object System.IO.StreamWriter($OutFile, $false, (New-Object System.Text.UTF8Encoding($false)))
$start = Get-Date
$opId = ''; $digest = ''
while (-not $reader.EndOfStream) {
  $line = $reader.ReadLine()
  if ($null -ne $line) { $sw.WriteLine($line) }
  if ($null -ne $line -and $line.Contains('confirmation_required')) {
    $p = $line.Substring($line.IndexOf('{')) | ConvertFrom-Json
    $opId = $p.operationId; $digest = $p.digest
    Note ("CONFIRMATION type=" + $p.operationType + " op=" + $opId + " cardLines=" + $p.summary.Count)
    foreach ($s in $p.summary) { Note ("CARD| " + $s) }
  }
  if (((Get-Date) - $start).TotalSeconds -gt 170) { $sw.WriteLine('[TIMEOUT]'); break }
}
$sw.Close(); $reader.Close(); $resp.Close()
Note 'STREAM DONE'

if ($opId -ne '' -and $digest -ne '') {
  # POST via HttpWebRequest and save the raw response bytes: Invoke-RestMethod decodes a
  # JSON body without a charset as Latin-1, which mangles non-ASCII summary text.
  $cbody = '{"operationId":"' + $opId + '","chatId":"' + $chatId + '","digest":"' + $digest + '"}'
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($cbody)
  $creq = [System.Net.HttpWebRequest]::Create("$Base/ai/assistant/operations/confirm")
  $creq.Method = 'POST'
  $creq.ContentType = 'application/json; charset=utf-8'
  $creq.CookieContainer = $sess.Cookies
  $creq.Timeout = 180000
  $creq.ContentLength = $bytes.Length
  $s = $creq.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
  $cresp = $creq.GetResponse()
  $reader2 = New-Object System.IO.StreamReader($cresp.GetResponseStream(), [System.Text.Encoding]::UTF8)
  $raw = $reader2.ReadToEnd()
  $reader2.Close(); $cresp.Close()
  [System.IO.File]::WriteAllText(($OutFile + '.confirm.json'), $raw, (New-Object System.Text.UTF8Encoding($false)))
  $cr = $raw | ConvertFrom-Json
  Note ("CONFIRM code=" + $cr.code + " success=" + $cr.data.success + " requested=" + $cr.data.requestedCount + " succeeded=" + $cr.data.succeededCount + " failed=" + $cr.data.failedCount)
  Note ("CONFIRM items=" + ($cr.data.items | ConvertTo-Json -Compress))
} else {
  Note 'NO CONFIRMATION EVENT'
}

[System.IO.File]::WriteAllLines(($OutFile + '.log.txt'), $log, (New-Object System.Text.UTF8Encoding($false)))
