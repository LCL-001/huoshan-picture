# Login -> ticket -> chat. MsgFile is UTF-8; OutFile receives raw SSE.
# Local Redis captcha access only. PS5.1, ASCII source, UTF-8 without BOM.
param(
  [Parameter(Mandatory=$true)][string]$MsgFile,
  [Parameter(Mandatory=$true)][string]$OutFile,
  [string]$Base = 'http://localhost:8123/api',
  [string]$RedisCli = 'redis-cli',
  [string]$Account = '123456',
  [string]$Password = $env:HS_TEST_PASSWORD
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
$msg = [System.IO.File]::ReadAllText($MsgFile, [Text.Encoding]::UTF8).Trim()
if ([string]::IsNullOrWhiteSpace($msg)) { throw 'message must not be blank' }
$auth = Login
$chatId = [guid]::NewGuid().ToString()
Show-Conversation $auth.UserId $chatId
$ticketResp = Invoke-RestMethod -Uri "$Base/ai/assistant/ticket" -Method Post -WebSession $auth.Session
Assert-Api $ticketResp 'ticket'
if ([string]::IsNullOrWhiteSpace([string]$ticketResp.data)) { throw 'no ticket' }
$url = "$Base/ai/assistant/chat?message=$(Encode-Utf8 $msg)&ticket=$([Uri]::EscapeDataString([string]$ticketResp.data))&chatId=$chatId"
$events = @(Read-AssistantStream $url $auth.Session $OutFile)
Note 'DONE (received [DONE], no error event)'