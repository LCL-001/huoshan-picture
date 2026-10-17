# Assistant write gate: optional space creation -> SSE card -> confirm.
# PlanFile: UTF-8 JSON {spaceName,message}; -NoCreate preserves reuse behavior.
# Full success is the default; partial URL results require explicit expectations.
# E.g. -ExpectedRequestedCount 1 -ExpectedSucceededCount 0 -ExpectedFailedCount 1 -ExpectedSuccess false
# PS5.1, ASCII source, UTF-8 without BOM.
param(
  [Parameter(Mandatory=$true)][string]$PlanFile,
  [Parameter(Mandatory=$true)][string]$OutFile,
  [switch]$NoCreate,
  [string]$Base = 'http://localhost:8123/api',
  [string]$RedisCli = 'redis-cli',
  [string]$Account = '123456',
  [string]$Password = $env:HS_TEST_PASSWORD,
  [Nullable[int]]$ExpectedRequestedCount,
  [Nullable[int]]$ExpectedSucceededCount,
  [Nullable[int]]$ExpectedFailedCount,
  [ValidateSet('true','false')][string]$ExpectedSuccess = 'true'
)
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$log = New-Object System.Collections.ArrayList
function Note($t) { [void]$log.Add($t); Write-Host $t }
function Assert-Api($r, $label) {
  if ($null -eq $r -or $null -eq $r.code -or $r.code -ne 0) { throw ($label + ' failed: ' + ($r | ConvertTo-Json -Compress -Depth 12)) }
}
function Assert-Inputs {
  if ([string]::IsNullOrWhiteSpace($Password)) { throw 'set HS_TEST_PASSWORD (test account password)' }
  if ([string]::IsNullOrWhiteSpace($Account) -or [string]::IsNullOrWhiteSpace($OutFile)) { throw 'Account and OutFile must not be blank' }
  $uri = $null
  if (-not [Uri]::TryCreate($Base, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http','https')) { throw 'Base must be an absolute HTTP(S) URL' }
}
function Login {
  $cap = Invoke-RestMethod -Uri "$Base/user/captcha/img" -Method Get
  Assert-Api $cap 'captcha'
  $uuid = $cap.data.captchaUuid
  if ([string]::IsNullOrWhiteSpace($uuid)) { throw 'captcha UUID missing' }
  $LASTEXITCODE = 0
  $answer = @(& $RedisCli -n 1 GET "huoshantuku:auth:captcha:img:$uuid")
  $code = ($answer -join '').Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($code)) { throw 'captcha answer not found in redis db 1' }
  $body = @{userAccount=$Account; userPassword=$Password; captchaUuid=$uuid; captchaCode=$code} | ConvertTo-Json -Compress
  $login = Invoke-RestMethod -Uri "$Base/user/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -SessionVariable session
  Assert-Api $login 'login'
  if ([string]$login.data.id -notmatch '^[1-9][0-9]*$') { throw 'login returned no valid userId' }
  Note ("LOGIN code=0 userId=" + $login.data.id)
  return @{Session=$session; UserId=[string]$login.data.id}
}
function Show-Conversation($userId, $chatId) {
  # Java UUID.nameUUIDFromBytes: raw MD5 bytes, v3 + RFC variant, network byte order.
  $md5 = [Security.Cryptography.MD5]::Create()
  try { $b = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes(('huoshan:' + $userId + ':' + $chatId.Trim()))) }
  finally { $md5.Dispose() }
  $b[6] = ($b[6] -band 15) -bor 48; $b[8] = ($b[8] -band 63) -bor 128
  $hex = ([BitConverter]::ToString($b) -replace '-', '').ToLowerInvariant()
  $id = $hex.Substring(0,8)+'-'+$hex.Substring(8,4)+'-'+$hex.Substring(12,4)+'-'+$hex.Substring(16,4)+'-'+$hex.Substring(20,12)
  Note ("CONVERSATION userId=$userId chatId=$chatId engineConversationId=$id")
  Note ("SQL SELECT COUNT(*) AS rows_total, COALESCE(SUM(metadata LIKE '%system_fact%'),0) AS facts FROM chat_message WHERE conversation_id = '$id';")
  Note ("SQL SELECT id, metadata FROM chat_message WHERE conversation_id = '$id' AND metadata LIKE '%system_fact%';")
}
function Encode-Utf8($text) {
  $sb = New-Object System.Text.StringBuilder
  foreach ($b in [Text.Encoding]::UTF8.GetBytes([string]$text)) { [void]$sb.Append('%' + $b.ToString('X2')) }
  return $sb.ToString()
}
function Read-AssistantStream($url, $session, $path) {
  $req = [System.Net.HttpWebRequest]::Create($url)
  $req.Method = 'GET'; $req.CookieContainer = $session.Cookies
  $req.Timeout = 180000; $req.ReadWriteTimeout = 180000
  $resp = $null; $reader = $null; $sw = $null
  $events = New-Object System.Collections.ArrayList
  $state = @{Done=$false; Error=$false}
  function Frame($data, $eventName) {
    if ($data -eq '[DONE]') { $state.Done=$true; return }
    if ($state.Done) { throw 'business frame after DONE' }
    $p = $data | ConvertFrom-Json
    if ($null -eq $p) { throw 'empty SSE JSON event' }
    if ($p.event -eq 'error' -or $eventName -eq 'error') { $state.Error=$true }
    [void]$events.Add($p)
  }
  try {
    $resp = $req.GetResponse()
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
    $sw = New-Object System.IO.StreamWriter($path, $false, $utf8)
    $start = Get-Date; $data = New-Object System.Collections.ArrayList; $eventName = ''
    while (-not $reader.EndOfStream) {
      $line = $reader.ReadLine(); $sw.WriteLine($line)
      if (((Get-Date)-$start).TotalSeconds -gt 170) { throw 'SSE timeout before completion' }
      if ($line -eq '') {
        if ($data.Count -gt 0) { Frame ($data -join "`n") $eventName }
        $data.Clear(); $eventName=''
      } elseif ($line.StartsWith('data:')) {
        [void]$data.Add(($line.Substring(5) -replace '^ ',''))
      } elseif ($line.StartsWith('event:')) { $eventName=$line.Substring(6).Trim() }
    }
    if ($data.Count -gt 0) { Frame ($data -join "`n") $eventName }
    if (-not $state.Done) { throw 'SSE ended without [DONE]' }
    if ($state.Error) { throw 'SSE contained an error event' }
  } finally {
    if ($null -ne $sw) { $sw.Close() }
    if ($null -ne $reader) { $reader.Close() }
    if ($null -ne $resp) { $resp.Close() }
  }
  return $events.ToArray()
}
Assert-Inputs
$plan = [System.IO.File]::ReadAllText($PlanFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
$msg = [string]$plan.message; $spaceName = [string]$plan.spaceName
if ([string]::IsNullOrWhiteSpace($msg)) { throw 'plan.message must not be blank' }
if (-not $NoCreate -and [string]::IsNullOrWhiteSpace($spaceName)) { throw 'plan.spaceName required when creating space' }
foreach ($n in @($ExpectedRequestedCount,$ExpectedSucceededCount,$ExpectedFailedCount)) {
  if ($null -ne $n -and $n -lt 0) { throw 'expected counts must be nonnegative' }
}
if ($null -ne $ExpectedRequestedCount -and $ExpectedRequestedCount -eq 0) { throw 'requested count must be positive' }
if ($null -ne $ExpectedRequestedCount -and $null -ne $ExpectedSucceededCount -and $null -ne $ExpectedFailedCount -and $ExpectedRequestedCount -ne ($ExpectedSucceededCount+$ExpectedFailedCount)) { throw 'expected counts do not add up' }
$expectSuccess = $ExpectedSuccess -eq 'true'
if (-not $expectSuccess -and ($null -eq $ExpectedRequestedCount -or $null -eq $ExpectedSucceededCount -or $null -eq $ExpectedFailedCount)) { throw 'partial failure requires all three explicit expected counts' }
try {
  $auth = Login
  if (-not $NoCreate) {
    $body = @{spaceName=$spaceName; spaceLevel=0; spaceType=0} | ConvertTo-Json -Compress
    $sp = Invoke-RestMethod -Uri "$Base/space/add" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -WebSession $auth.Session
    Assert-Api $sp 'space creation'
    if (-not $sp.data) { throw 'space creation returned no id' }
    Note ("SPACE code=0 id=" + $sp.data)
  }
  $chatId = [guid]::NewGuid().ToString()
  Note ("CHATID " + $chatId)
  Show-Conversation $auth.UserId $chatId
  $ticketResp = Invoke-RestMethod -Uri "$Base/ai/assistant/ticket" -Method Post -WebSession $auth.Session
  Assert-Api $ticketResp 'ticket'
  if ([string]::IsNullOrWhiteSpace([string]$ticketResp.data)) { throw 'no ticket' }
  $url = "$Base/ai/assistant/chat?message=$(Encode-Utf8 $msg)&ticket=$([Uri]::EscapeDataString([string]$ticketResp.data))&chatId=$chatId"
  $events = @(Read-AssistantStream $url $auth.Session $OutFile)
  $cards = @($events | Where-Object { $_.event -eq 'confirmation_required' })
  if ($cards.Count -ne 1) { throw ('expected exactly one confirmation_required event, received ' + $cards.Count) }
  $card = $cards[0]
  if ($card.operationId -notmatch '^[a-fA-F0-9]{32}$' -or $card.digest -notmatch '^[a-fA-F0-9]{40}$' -or $card.operationType -notin @('url_import','batch_edit')) { throw 'invalid confirmation event identity/type' }
  Note ("CONFIRMATION type=" + $card.operationType + ' op=' + $card.operationId + ' cardLines=' + @($card.summary).Count)
  foreach ($line in $card.summary) { Note ("CARD| " + $line) }
  Note 'STREAM DONE (received [DONE], no error event)'
  $body = @{operationId=$card.operationId; chatId=$chatId; digest=$card.digest} | ConvertTo-Json -Compress
  $bytes = [Text.Encoding]::UTF8.GetBytes($body)
  # Explicit UTF-8 reader preserves response text even when server omits charset.
  $req = [System.Net.HttpWebRequest]::Create("$Base/ai/assistant/operations/confirm")
  $req.Method='POST'; $req.ContentType='application/json; charset=utf-8'
  $req.CookieContainer=$auth.Session.Cookies; $req.Timeout=180000; $req.ReadWriteTimeout=180000; $req.ContentLength=$bytes.Length
  $rs=$null; $resp=$null; $reader=$null
  try {
    $rs=$req.GetRequestStream(); $rs.Write($bytes,0,$bytes.Length); $rs.Close(); $rs=$null
    $resp=$req.GetResponse()
    $reader=New-Object System.IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
    $raw=$reader.ReadToEnd()
  } finally {
    if ($null -ne $rs) { $rs.Close() }
    if ($null -ne $reader) { $reader.Close() }
    if ($null -ne $resp) { $resp.Close() }
  }
  [System.IO.File]::WriteAllText(($OutFile+'.confirm.json'), $raw, $utf8)
  $cr=$raw | ConvertFrom-Json
  Assert-Api $cr 'confirm'
  $r=$cr.data
  if ($null -eq $r -or $r.operationId -ne $card.operationId -or $r.operationType -ne $card.operationType) { throw 'confirm result identity/type mismatch' }
  if ($r.success -isnot [bool] -or $r.success -ne $expectSuccess) { throw 'confirm success does not match expectation' }
  if ($r.requestedCount -isnot [int] -or $r.requestedCount -le 0) { throw 'invalid requestedCount' }
  $counts=@{requestedCount=$ExpectedRequestedCount; succeededCount=$ExpectedSucceededCount; failedCount=$ExpectedFailedCount}
  foreach ($key in $counts.Keys) {
    if ($null -ne $counts[$key] -and ($null -eq $r.$key -or $r.$key -ne $counts[$key])) { throw ("unexpected $key : " + $r.$key) }
  }
  if ($card.operationType -eq 'url_import') {
    if ($r.succeededCount -isnot [int] -or $r.failedCount -isnot [int] -or $r.succeededCount -lt 0 -or $r.failedCount -lt 0 -or ($r.succeededCount+$r.failedCount) -ne $r.requestedCount) { throw 'URL result counts inconsistent' }
    if ($r.success -ne ($r.failedCount -eq 0)) { throw 'URL success/counts inconsistent' }
    $items=@($r.items)
    if ($items.Count -ne $r.requestedCount -or @($items | Where-Object { $_.ok -isnot [bool] }).Count -gt 0 -or @($items | Where-Object { $_.ok }).Count -ne $r.succeededCount) { throw 'URL items/counts inconsistent' }
  }
  Note ("CONFIRM code=0 success="+$r.success+' requested='+$r.requestedCount+' succeeded='+$r.succeededCount+' failed='+$r.failedCount)
  Note ("CONFIRM items="+($r.items | ConvertTo-Json -Compress -Depth 12))
} finally { [System.IO.File]::WriteAllLines(($OutFile+'.log.txt'), $log, $utf8) }