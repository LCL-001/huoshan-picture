# Permanent offline regression. PS 5.1; ASCII source; no service, process or file deletion.
# AST-validated scripts run in memory with all HTTP, Redis and evidence IO substituted.
$ErrorActionPreference = 'Stop'
$script:Root = $PSScriptRoot
$script:Passed = 0
$script:Failed = 0
$script:Chat = '11111111-1111-4111-8111-111111111111'
$script:Operation = 'a123456789abcdef0123456789abcdef'
$script:Digest = '0123456789abcdef0123456789abcdef01234567'
$script:Emoji = [char]::ConvertFromUtf32(0x1f30b)
function Assert($condition, $message) { if (-not $condition) { throw "ASSERT: $message" } }
function Case($name, [scriptblock]$body) {
  try { & $body; $script:Passed++; Write-Host "PASS $name" }
  catch { $script:Failed++; Write-Host "FAIL $name | $($_.Exception.Message)" }
}
function Reset-State {
  $script:S = @{
    Calls = (New-Object System.Collections.ArrayList); Lines = (New-Object System.Collections.ArrayList)
    Files = @{}; Writers = @{}; LoginCode = 0; LoginId = '123'; CaptchaCode = 0
    Redis = 'abcd'; TicketCode = 0; Ticket = 'ticket+/?'; LoginPassword = 'offline-password'
    ProbeCode = 40400; ProbeHttp = $false; ProbeMessage = 'offline rejection'; ConfirmCode = 0; SpaceCode = 0
    Stream = "data:{`"event`":`"answer`",`"content`":`"ok`"}`n`ndata:[DONE]`n`n"
    Result = @{ operationId = $script:Operation; operationType = 'url_import'; success = $true
      requestedCount = 1; succeededCount = 1; failedCount = 0; items = @(@{ok = $true}) }
  }
  $script:S.Files['message.txt'] = 'hello'
  $script:S.Files['plan.json'] = (@{spaceName = 'offline-space'; message = 'import one'} | ConvertTo-Json)
}
function Read-TestText($a) {
  $path = [string]$a[0]
  if (-not $script:S.Files.ContainsKey($path)) { throw "Missing offline input: $path" }
  return $script:S.Files[$path]
}
function Save-TestText($a) { $script:S.Files[[string]$a[0]] = [string]$a[1] }
function Save-TestLines($a) { $script:S.Files[[string]$a[0]] = ($a[1] -join "`n") }
function New-TestWriter($a) {
  $writer = New-Object System.IO.StringWriter
  $script:S.Writers[[string]$a[0]] = $writer
  return $writer
}
function OfflineRedis { [void]$script:S.Calls.Add(@{Kind='redis'}); $global:LASTEXITCODE = 0; return $script:S.Redis }
function Invoke-RestMethod {
  param($Uri, $Method, $ContentType, $Body, $SessionVariable, $WebSession)
  [void]$script:S.Calls.Add(@{Kind='rest'; Uri=[string]$Uri; Body=$Body})
  $payload = $null
  if ($null -ne $Body) { $payload = [Text.Encoding]::UTF8.GetString([byte[]]$Body) | ConvertFrom-Json }
  switch -Regex ([string]$Uri) {
    '/captcha/img$' { return @{code=$script:S.CaptchaCode; data=@{captchaUuid='captcha'}} }
    '/user/login$' {
      Assert ($payload.userPassword -ceq $script:S.LoginPassword) 'JSON login password did not round-trip'
      if ($SessionVariable) { Set-Variable -Name $SessionVariable -Scope 1 -Value ([pscustomobject]@{Cookies=(New-Object Net.CookieContainer)}) }
      return @{code=$script:S.LoginCode; data=@{id=$script:S.LoginId; userRole='user'}}
    }
    '/ticket$' { return @{code=$script:S.TicketCode; data=$script:S.Ticket} }
    '/space/add$' { return @{code=$script:S.SpaceCode; data='456'} }
    '/operations/confirm$' {
      if ($script:S.ProbeHttp) { throw 'OFFLINE HTTP failure' }
      # PS5.1 fallback when application/json has no charset: decode UTF-8 as Latin-1.
      $json = @{code=$script:S.ProbeCode; message=$script:S.ProbeMessage} | ConvertTo-Json -Compress
      return ([Text.Encoding]::GetEncoding(28591).GetString([Text.Encoding]::UTF8.GetBytes($json)) | ConvertFrom-Json)
    }
    default { throw "Unmocked REST URI: $Uri" }
  }
}
function New-TestRequest($url) {
  $request = [pscustomobject]@{Uri=[string]$url; Method=''; CookieContainer=$null; Timeout=0
    ReadWriteTimeout=0; ContentType=''; ContentLength=0; Body=(New-Object IO.MemoryStream)}
  $request | Add-Member ScriptMethod GetRequestStream { return $this.Body }
  $request | Add-Member ScriptMethod GetResponse { return (Get-TestResponse $this) }
  return $request
}
function Get-TestResponse($request) {
  [void]$script:S.Calls.Add(@{Kind='request'; Uri=$request.Uri; Body=$request.Body.ToArray()})
  if ($request.Uri -match '/chat\?') { $text = $script:S.Stream }
  elseif ($request.Uri -match '/operations/confirm$') {
    $payload = [Text.Encoding]::UTF8.GetString($request.Body.ToArray()) | ConvertFrom-Json
    if ($script:S.RunKind -eq 'probe-t66') {
      if ($script:S.ProbeHttp) { throw 'OFFLINE HTTP failure' }
      $text = @{code=$script:S.ProbeCode; message=$script:S.ProbeMessage} | ConvertTo-Json -Compress
    } else {
      Assert ($payload.operationId -eq $script:Operation -and $payload.digest -eq $script:Digest) 'wrong confirmation identity'
      $text = @{code=$script:S.ConfirmCode; data=$script:S.Result} | ConvertTo-Json -Depth 10
    }
  } else { throw "Unmocked request URI: $($request.Uri)" }
  $response = [pscustomobject]@{Bytes=[Text.Encoding]::UTF8.GetBytes($text); ContentType='application/json'}
  $response | Add-Member ScriptMethod GetResponseStream { return (New-Object IO.MemoryStream(,$this.Bytes)) }
  $response | Add-Member ScriptMethod Close {}
  return $response
}
function Invoke-Offline($kind, $extra = @{}) {
  $path = Join-Path $script:Root "2026-10-17-assistant-e2e-$kind.ps1"
  $tokens = $null; $errors = $null
  $ast = [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
  Assert ($errors.Count -eq 0) "parser errors: $path"
  $source = $ast.Extent.Text
  $seams = @{
    'System.Net.HttpWebRequest::Create'='New-TestRequest'
    'System.IO.File::ReadAllText'='Read-TestText'
    'System.IO.File::WriteAllText'='Save-TestText'
    'System.IO.File::WriteAllLines'='Save-TestLines'
  }
  $nodes = $ast.FindAll({param($n) $n -is [Management.Automation.Language.InvokeMemberExpressionAst] -and $n.Static}, $true)
  foreach ($node in ($nodes | Sort-Object { $_.Extent.StartOffset } -Descending)) {
    $key = $node.Expression.TypeName.FullName + '::' + $node.Member.Value
    if ($seams.ContainsKey($key)) {
      $argsText = ($node.Arguments | ForEach-Object { $_.Extent.Text }) -join ','
      $replacement = '(' + $seams[$key] + ' @(' + $argsText + '))'
      $start = $node.Extent.StartOffset; $length = $node.Extent.EndOffset - $start
      $source = $source.Substring(0,$start) + $replacement + $source.Substring($start+$length)
    }
  }
  $source = $source.Replace('New-Object System.IO.StreamWriter', 'New-TestWriter')
  Assert (-not ($source -match 'HttpWebRequest\]::Create|\[System.IO.File\]::|Invoke-WebRequest|Start-Process')) 'unmocked IO seam'
  function Write-Host { param($Object) [void]$script:S.Lines.Add([string]$Object) }
  $p = @{OutFile='output'; RedisCli='OfflineRedis'; Password=$script:S.LoginPassword; Base='http://offline.invalid/api'}
  if ($kind -eq 'chat') { $p.MsgFile = 'message.txt' }
  if ($kind -eq 'confirm') { $p.PlanFile = 'plan.json'; $p.NoCreate = $true }
  foreach ($key in $extra.Keys) { $p[$key] = $extra[$key] }
  $script:S.RunKind = $kind
  & ([scriptblock]::Create($source)) @p
}
function Throws([scriptblock]$body) {
  $thrown = $false
  try { & $body } catch { $thrown = $true }
  Assert $thrown 'expected a terminating failure'
}
function Confirm-Stream {
  $script:S.Stream = 'data:' + (@{event='confirmation_required'; operationId=$script:Operation; digest=$script:Digest
    operationType='url_import'; summary=@('one URL')} | ConvertTo-Json -Compress) + "`n`ndata:[DONE]`n`n"
}
function Confirm-Calls { return @($script:S.Calls | Where-Object { $_.Uri -match '/operations/confirm$' }) }
function All-Logs { return ($script:S.Lines -join "`n") }
Case 'sources are ASCII without BOM and parse on PS5.1' {
  foreach ($kind in @('chat','confirm','probe-t66','scripts-test')) {
    $path = Join-Path $script:Root "2026-10-17-assistant-e2e-$kind.ps1"
    $bytes = [IO.File]::ReadAllBytes($path)
    Assert (@($bytes | Where-Object { $_ -gt 127 }).Count -eq 0) "non ASCII/BOM: $kind"
    $tokens=$null; $errors=$null
    [void][Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    Assert ($errors.Count -eq 0) "parse error: $kind"
  }
}
Case 'probe requires successful login before confirms' {
  Reset-State; $script:S.LoginCode=40100
  Throws { Invoke-Offline 'probe-t66' }; Assert ((Confirm-Calls).Count -eq 0) 'confirm after bad login'
}
Case 'probe rejects missing login identity' {
  Reset-State; $script:S.LoginId=$null
  Throws { Invoke-Offline 'probe-t66' }; Assert ((Confirm-Calls).Count -eq 0) 'confirm without user id'
}
Case 'probe HTTP failures terminate' { Reset-State; $script:S.ProbeHttp=$true; Throws { Invoke-Offline 'probe-t66' } }
Case 'probe unexpected business success terminates' { Reset-State; $script:S.ProbeCode=0; Throws { Invoke-Offline 'probe-t66' } }
Case 'probe unexpected business error terminates' { Reset-State; $script:S.ProbeCode=50000; Throws { Invoke-Offline 'probe-t66' } }
Case 'probe default three real cases plus delegated binding/replay SKIPs' {
  Reset-State; Invoke-Offline 'probe-t66'; $calls=Confirm-Calls
  Assert ($calls.Count -eq 3) 'default must send exactly three cases'
  $bodies=@($calls | ForEach-Object { [Text.Encoding]::UTF8.GetString($_.Body) | ConvertFrom-Json })
  Assert ($bodies[1].operationId.Contains("`n")) 'newline case must carry a real LF'
  Assert ($bodies[2].operationId -eq '' -and $bodies[2].digest -eq '') 'empty field case'
  Assert ((All-Logs) -match 'SKIP.*binding' -and (All-Logs) -match 'SKIP.*replay') 'missing explicit SKIP'
  Assert ((All-Logs) -notmatch 'ALL.*PASS|four.*pass') 'misleading coverage claim'
}
Case 'probe decodes no-charset UTF8 rejection bytes without Latin1 mojibake' {
  Reset-State
  $message = (-join ([char[]]@(0x64cd,0x4f5c,0x4e0d,0x5b58,0x5728))) + ' ' + $script:Emoji
  $script:S.ProbeMessage = $message
  Invoke-Offline 'probe-t66'
  $cases = @($script:S.Lines | Where-Object { $_ -like 'CASE|*' })
  Assert ($cases.Count -eq 3) 'expected three decoded rejection cases'
  foreach ($line in $cases) {
    Assert ($line.Contains('|code=40400|msg=' + $message)) 'UTF8 rejection message was decoded as Latin1'
  }
  Assert ($script:S.Files['output'].Contains($message)) 'saved evidence lost UTF8 rejection text'
}
Case 'probe cannot accept or consume real binding/replay plans' {
  $tokens=$null; $errors=$null
  $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $script:Root '2026-10-17-assistant-e2e-probe-t66.ps1'),[ref]$tokens,[ref]$errors)
  $names=@($ast.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
  Assert ('BindingPlanFile' -notin $names -and 'ConsumedPlanFile' -notin $names) 'dangerous real-plan input still exposed'
  Reset-State; Invoke-Offline 'probe-t66'
  Assert ((All-Logs) -match 'SKIP.*binding.*hard-failure.ps1' -and (All-Logs) -match 'SKIP.*replay.*hard-failure.ps1') 'delegated coverage must name hard-failure script'
}
Case 'Java UUID v3 fixed vector and SQL use derived ID, not chatId' {
  foreach ($kind in @('chat','confirm','probe-t66')) {
    Reset-State
    $tokens=$null; $errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $script:Root "2026-10-17-assistant-e2e-$kind.ps1"),[ref]$tokens,[ref]$errors)
    $fn=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Show-Conversation'},$true)
    Assert ($null -ne $fn) "missing conversation helper: $kind"
    function Note($text) { [void]$script:S.Lines.Add($text) }
    . ([scriptblock]::Create($fn.Extent.Text))
    Show-Conversation '123' ("  " + $script:Chat + "  ")
    Assert ((All-Logs) -match 'userId=123.*engineConversationId=d61333aa-c35c-3670-9eb2-cbf83c272f19') "Java UUID mismatch: $kind"
    $sql=@($script:S.Lines | Where-Object { $_ -like 'SQL *' })
    Assert ($sql.Count -gt 0 -and @($sql | Where-Object { $_ -notmatch 'd61333aa-c35c-3670-9eb2-cbf83c272f19' -or $_ -match $script:Chat }).Count -eq 0) "SQL used external chatId: $kind"
  }
}
Case 'all login JSON supports quotes backslash newline and emoji' {
  foreach ($kind in @('chat','confirm','probe-t66')) {
    Reset-State; Confirm-Stream; $script:S.LoginPassword = 'a"\' + "`n" + $script:Emoji
    Invoke-Offline $kind
  }
}
Case 'chat encodes whole UTF8 message including emoji and ticket' {
  Reset-State; $script:S.Files['message.txt']='A' + $script:Emoji + '&?'
  Invoke-Offline 'chat'
  $call=@($script:S.Calls | Where-Object { $_.Uri -match '/chat\?' })[0]
  Assert ($call.Uri -match '%F0%9F%8C%8B') 'surrogate pair encoding corrupted'
  Assert ($call.Uri -match 'ticket=ticket%2B%2F%3F') 'ticket must be URL encoded'
  Assert ($call.Uri -match 'chatId=') 'chat must choose explicit chatId for evidence'
  Assert ((All-Logs) -match 'engineConversationId=' -and (All-Logs) -match 'SELECT.*conversation_id') 'chat missing engine SQL'
}
Case 'chat EOF without DONE fails' { Reset-State; $script:S.Stream='data:{"event":"answer","content":"ok"}'; Throws { Invoke-Offline 'chat' } }
Case 'chat error even followed by DONE fails' { Reset-State; $script:S.Stream="data:{`"event`":`"error`",`"content`":`"bad`"}`n`ndata:[DONE]`n"; Throws { Invoke-Offline 'chat' } }
Case 'chat blank message rejected before login' { Reset-State; $script:S.Files['message.txt']='  '; Throws { Invoke-Offline 'chat' }; Assert ($script:S.Calls.Count -eq 0) 'blank message caused IO' }
Case 'confirm missing confirmation event fails' { Reset-State; Throws { Invoke-Offline 'confirm' }; Assert ((Confirm-Calls).Count -eq 0) 'confirmed without event' }
Case 'confirm incomplete SSE fails before confirmation' { Reset-State; Confirm-Stream; $script:S.Stream=$script:S.Stream.Replace('data:[DONE]',''); Throws { Invoke-Offline 'confirm' }; Assert ((Confirm-Calls).Count -eq 0) 'confirmed incomplete stream' }
Case 'confirm API failure terminates' { Reset-State; Confirm-Stream; $script:S.ConfirmCode=50000; Throws { Invoke-Offline 'confirm' } }
Case 'confirm default expects full execution success' { Reset-State; Confirm-Stream; $script:S.Result.success=$false; $script:S.Result.succeededCount=0; $script:S.Result.failedCount=1; Throws { Invoke-Offline 'confirm' } }
Case 'confirm explicit partial failure and counts accepted' {
  Reset-State; Confirm-Stream; $script:S.Result.success=$false; $script:S.Result.succeededCount=0; $script:S.Result.failedCount=1; $script:S.Result.items=@(@{ok=$false})
  Invoke-Offline 'confirm' @{ExpectedRequestedCount=1; ExpectedSucceededCount=0; ExpectedFailedCount=1; ExpectedSuccess='false'}
}
Case 'confirm explicit requested count mismatch terminates' { Reset-State; Confirm-Stream; Throws { Invoke-Offline 'confirm' @{ExpectedRequestedCount=2} } }
Case 'confirm inconsistent URL counts terminate' { Reset-State; Confirm-Stream; $script:S.Result.failedCount=1; Throws { Invoke-Offline 'confirm' } }
Case 'confirm wrong result operation identity terminates' { Reset-State; Confirm-Stream; $script:S.Result.operationId='wrong'; Throws { Invoke-Offline 'confirm' } }
Case 'confirm URL item requires protocol ok field, not success' {
  Reset-State; Confirm-Stream; $script:S.Result.items=@(@{success=$true})
  Throws { Invoke-Offline 'confirm' }
}
Case 'confirm positive full result persists raw and log' {
  Reset-State; Confirm-Stream; Invoke-Offline 'confirm'
  Assert ($script:S.Files.ContainsKey('output.confirm.json') -and $script:S.Files.ContainsKey('output.log.txt')) 'lost original evidence output'
  Assert ((All-Logs) -match 'engineConversationId=' -and (All-Logs) -match 'SELECT.*conversation_id') 'confirm missing SQL'
}
Case 'confirm batch result permits nullable per-item counts' {
  Reset-State; Confirm-Stream; $script:S.Stream=$script:S.Stream.Replace('url_import','batch_edit')
  $script:S.Result.operationType='batch_edit'; $script:S.Result.succeededCount=$null; $script:S.Result.failedCount=$null; $script:S.Result.items=@()
  Invoke-Offline 'confirm' @{ExpectedRequestedCount=1}
}
Case 'confirm missing message rejected before creating space' {
  Reset-State; $script:S.Files['plan.json']='{"spaceName":"x"}'
  Throws { Invoke-Offline 'confirm' @{NoCreate=$false} }; Assert ($script:S.Calls.Count -eq 0) 'invalid message caused IO'
}
Case 'confirm invalid expected counts rejected before login' {
  Reset-State; Confirm-Stream; Throws { Invoke-Offline 'confirm' @{ExpectedRequestedCount=-1} }; Assert ($script:S.Calls.Count -eq 0) 'invalid count caused IO'
}
Case 'confirm create space API failure prevents chat' {
  Reset-State; Confirm-Stream; $script:S.SpaceCode=50000
  Throws { Invoke-Offline 'confirm' @{NoCreate=$false} }
  Assert (@($script:S.Calls | Where-Object { $_.Uri -match '/chat\?' }).Count -eq 0) 'chat after space failure'
}
Case 'confirm whole message UTF8 and JSON space names' {
  Reset-State; Confirm-Stream; $script:S.Files['plan.json']=(@{spaceName=('a"\' + $script:Emoji); message=('import ' + $script:Emoji)} | ConvertTo-Json)
  Invoke-Offline 'confirm' @{NoCreate=$false}
  $call=@($script:S.Calls | Where-Object { $_.Uri -match '/chat\?' })[0]
  Assert ($call.Uri -match '%F0%9F%8C%8B') 'confirm corrupts emoji'
}
Case 'all scripts reject failed captcha before Redis' {
  foreach ($kind in @('chat','confirm','probe-t66')) {
    Reset-State; $script:S.CaptchaCode=50000
    Throws { Invoke-Offline $kind }; Assert ($script:S.Calls.Count -eq 1) "bad captcha ignored: $kind"
  }
}
Case 'all scripts reject absent captcha answers' {
  foreach ($kind in @('chat','confirm','probe-t66')) {
    Reset-State; $script:S.Redis=''
    Throws { Invoke-Offline $kind }; Assert ($script:S.Calls.Count -eq 2) "empty Redis answer ignored: $kind"
  }
}
Case 'chat and confirm reject nonzero ticket even with data' {
  foreach ($kind in @('chat','confirm')) {
    Reset-State; Confirm-Stream; $script:S.TicketCode=50000
    Throws { Invoke-Offline $kind }; Assert (@($script:S.Calls | Where-Object { $_.Kind -eq 'request' }).Count -eq 0) 'bad ticket opened stream'
  }
}
Write-Host ("OFFLINE RESULT passed={0} failed={1} total={2}" -f $script:Passed,$script:Failed,($script:Passed+$script:Failed))
if ($script:Failed -gt 0) { throw 'Offline script regressions failed (no real services were called).' }