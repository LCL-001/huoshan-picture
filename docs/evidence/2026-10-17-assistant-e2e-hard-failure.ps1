# T66 deterministic local integration: real prepare/confirm, Redis and engine MySQL.
# No model, COS, business data creation or deletion. One failed fact is retained.
# MYSQL_PWD is inherited by mysql; HS_TEST_PASSWORD is used only for login.
param(
  [string]$OutFile,
  [string]$Base = 'http://localhost:8123/api',
  [string]$RedisCli = 'redis-cli',
  [string]$Mysql = 'mysql',
  [string]$MysqlHost = '127.0.0.1',
  [int]$MysqlPort = 3306,
  [string]$MysqlUser = 'root',
  [string]$BackendDb = 'yu_picture',
  [string]$EngineDb = 'huoshan_ai_agent',
  [string]$Account = '123456',
  [string]$Password = $env:HS_TEST_PASSWORD,
  [switch]$SelfTest
)
$ErrorActionPreference = 'Stop'
$log = New-Object System.Collections.ArrayList
function Note($text) { [void]$log.Add($text); Write-Host $text }
function Require($condition, [string]$message) { if (-not $condition) { throw $message } }
function Sql([string]$query) {
  $rows = @(& $Mysql --host=$MysqlHost --port=$MysqlPort --user=$MysqlUser --default-character-set=utf8mb4 --batch --raw --skip-column-names --execute=$query)
  Require ($LASTEXITCODE -eq 0) 'mysql query failed'
  return $rows
}
function Scalar([string]$query) {
  $rows = @(Sql $query)
  Require ($rows.Count -eq 1 -and $rows[0] -match '^\d+$') 'expected exactly one numeric SQL result'
  return [long]$rows[0]
}
function Post([string]$path, $body) {
  $json = $body | ConvertTo-Json -Depth 12 -Compress
  # Decode UTF-8 explicitly, including responses without an HTTP charset (PS 5.1).
  $r = Invoke-WebRequest -UseBasicParsing -Uri "$Base$path" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json)) -WebSession $script:sess
  $raw = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
  return ($raw | ConvertFrom-Json)
}
function ConversationId([string]$userId, [string]$chatId) {
  $md5 = [Security.Cryptography.MD5]::Create()
  try { $bytes = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes('huoshan:' + $userId + ':' + $chatId.Trim())) }
  finally { $md5.Dispose() }
  $bytes[6] = ($bytes[6] -band 15) -bor 48
  $bytes[8] = ($bytes[8] -band 63) -bor 128
  $hex = -join ($bytes | ForEach-Object { $_.ToString('x2') })
  return $hex.Insert(8,'-').Insert(13,'-').Insert(18,'-').Insert(23,'-')
}
function CheckFailure($response, $facts, [string]$opId) {
  Require ($response.code -eq 40400) 'expected original business error 40400'
  # Unicode escapes keep the source ASCII for Windows PowerShell 5.1.
  $originalMessage = '"\u7a7a\u95f4\u4e0d\u5b58\u5728"' | ConvertFrom-Json
  Require ($response.message -eq $originalMessage) 'original business error message changed'
  $facts = @($facts)
  Require ($facts.Count -eq 1) 'expected exactly one persisted fact'
  $meta = $facts[0].metadata | ConvertFrom-Json
  Require ($meta.huoshanSource -eq 'system_fact') 'wrong source marker'
  Require ($meta.operationId -eq $opId) 'wrong operationId'
  Require ($meta.operationType -eq 'batch_edit') 'wrong operationType'
  Require ($meta.outcome -eq 'failed') 'hard failure must not be recorded as executed'
  Require ($facts[0].messageType -eq 'USER') 'wrong fact message role'
  Require ($facts[0].content.Contains($opId)) 'fact does not identify the authenticated operation'
  Require ($facts[0].content.Contains($originalMessage)) 'fact lost the safe business error'
}
if ($SelfTest) {
  $op = 'a' * 32
  $msg = '"\u7a7a\u95f4\u4e0d\u5b58\u5728"' | ConvertFrom-Json
  $response = @{code=40400;message=$msg}
  $fact = @{messageType='USER';content=($op + ' ' + $msg);metadata=(@{huoshanSource='system_fact';operationId=$op;operationType='batch_edit';outcome='failed'} | ConvertTo-Json -Compress)}
  CheckFailure $response @($fact) $op
  Require ((ConversationId '2045072064452816897' '4104f92f-51f7-4401-930e-fa0274d440d9') -eq '19424278-dde2-3003-a6d0-f69df0ea94dc') 'Java UUID vector mismatch'
  $bad = @(
    @{response=@{code=0;message=$msg};facts=@($fact)},
    @{response=@{code=40400;message='changed'};facts=@($fact)},
    @{response=$response;facts=@()},
    @{response=$response;facts=@($fact,$fact)},
    @{response=$response;facts=@(@{messageType='USER';content=$fact.content;metadata=$fact.metadata.Replace('failed','executed')})},
    @{response=$response;facts=@(@{messageType='USER';content=$fact.content;metadata=$fact.metadata.Replace($op,('b'*32))})}
  )
  foreach ($case in $bad) {
    $rejected = $false
    try { CheckFailure $case.response $case.facts $op } catch { $rejected = $true }
    Require $rejected 'negative control unexpectedly passed'
  }
  Note 'SELFTEST PASS: valid failure + UUID vector + 6 negative controls'
  return
}
Require ([bool]$Password) 'set HS_TEST_PASSWORD'
Require (-not [string]::IsNullOrWhiteSpace($OutFile)) 'OutFile is required'
Require (([uri]$Base).IsLoopback) 'this verification is local-only'
Require ($BackendDb -match '^\w+$' -and $EngineDb -match '^\w+$') 'invalid schema name'
$utf8 = New-Object Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
try {
  # A permanently unused long ID: abort before login if it exists, even soft-deleted.
  $missingSpace = '9223372036854775806'
  $missingPicture = '9223372036854775805'
  Require ((Scalar "SELECT COUNT(*) FROM $BackendDb.space WHERE id=$missingSpace") -eq 0) 'sentinel space exists; abort'
  Require ((Scalar "SELECT COUNT(*) FROM $BackendDb.picture WHERE id=$missingPicture") -eq 0) 'sentinel picture exists; abort'
  Note 'PREFLIGHT sentinel space=0 picture=0; no business rows will be created'
  $cap = Invoke-RestMethod -Uri "$Base/user/captcha/img" -Method Get
  Require ($cap.code -eq 0 -and [bool]$cap.data.captchaUuid) 'captcha request failed'
  $uuid = $cap.data.captchaUuid
  $code = (& $RedisCli -n 1 GET "huoshantuku:auth:captcha:img:$uuid").Trim()
  Require ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($code)) 'captcha read failed'
  $loginBody = @{userAccount=$Account;userPassword=$Password;captchaUuid=$uuid;captchaCode=$code} | ConvertTo-Json -Compress
  $login = Invoke-RestMethod -Uri "$Base/user/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($loginBody)) -SessionVariable sess
  Require ($login.code -eq 0 -and [bool]$login.data.id) 'login failed'
  Note ('LOGIN code=0 userId=' + $login.data.id)
  $chatId = [guid]::NewGuid().ToString()
  $wrongChatId = [guid]::NewGuid().ToString()
  $conversationId = ConversationId ([string]$login.data.id) $chatId
  $wrongConversationId = ConversationId ([string]$login.data.id) $wrongChatId
  $scope = "conversation_id IN ('$conversationId','$wrongConversationId')"
  Note ('CHATID ' + $chatId + ' CONVERSATIONID ' + $conversationId)
  Note ('WRONG_CHATID ' + $wrongChatId + ' CONVERSATIONID ' + $wrongConversationId)
  Require ((Scalar "SELECT COUNT(*) FROM $EngineDb.chat_message WHERE $scope") -eq 0) 'fresh conversations are not empty'
  $plan = @{operationType='batch_edit';batchEdit=@{spaceId=$missingSpace;pictureIdList=@($missingPicture);category='e2e-t66-no-write'}}
  $prepared = Post '/ai/assistant/operations/prepare' @{chatId=$chatId;plan=$plan}
  Require ($prepared.code -eq 0) 'prepare failed'
  $opId = [string]$prepared.data.operationId
  $digest = [string]$prepared.data.digest
  Require ($opId -match '^[a-f0-9]{32}$' -and $digest -match '^[a-f0-9]{40}$') 'invalid prepared identity'
  $key = 'huoshantuku:ai:assistant:operation:' + $opId
  $stored = (& $RedisCli -n 1 HGETALL $key) -join "`n"
  Require ($LASTEXITCODE -eq 0 -and [bool]$stored) 'prepared plan missing in Redis'
  Require ((Scalar "SELECT COUNT(*) FROM $EngineDb.chat_message WHERE $scope") -eq 0) 'prepare wrote a fact'
  Note ('PREPARE code=0 operationId=' + $opId + ' type=batch_edit facts=0')
  $wrong = Post '/ai/assistant/operations/confirm' @{operationId=$opId;digest=$digest;chatId=$wrongChatId}
  Require ($wrong.code -eq 40400) 'wrong-chat binding unexpectedly accepted'
  $afterWrong = (& $RedisCli -n 1 HGETALL $key) -join "`n"
  Require ($LASTEXITCODE -eq 0 -and $afterWrong -eq $stored) 'binding rejection changed the stored plan'
  Require ((Scalar "SELECT COUNT(*) FROM $EngineDb.chat_message WHERE $scope") -eq 0) 'binding rejection wrote a fact'
  Note 'WRONG_CHAT code=40400 plan=unchanged facts=0'
  $result = Post '/ai/assistant/operations/confirm' @{operationId=$opId;digest=$digest;chatId=$chatId}
  $remaining = & $RedisCli -n 1 EXISTS $key
  Require ($LASTEXITCODE -eq 0 -and [string]$remaining -eq '0') 'authenticated plan was not consumed'
  $factSql = "SELECT JSON_OBJECT('messageType',message_type,'content',content,'metadata',metadata) FROM $EngineDb.chat_message WHERE conversation_id='$conversationId' ORDER BY id"
  $facts = @(Sql $factSql | ForEach-Object { $_ | ConvertFrom-Json })
  CheckFailure $result $facts $opId
  Require ((Scalar "SELECT COUNT(*) FROM $EngineDb.chat_message WHERE $scope") -eq 1) 'extra fact in wrong conversation'
  Note ('CONFIRM code=' + $result.code + ' message=' + $result.message + ' plan=consumed facts=1 outcome=failed')
  Note ('FACT ' + ($facts[0] | ConvertTo-Json -Depth 8 -Compress))
  $replay = Post '/ai/assistant/operations/confirm' @{operationId=$opId;digest=$digest;chatId=$chatId}
  Require ($replay.code -eq 40400) 'consumed operation replay unexpectedly accepted'
  $afterReplay = @(Sql $factSql | ForEach-Object { $_ | ConvertFrom-Json })
  CheckFailure $result $afterReplay $opId
  Require (($afterReplay | ConvertTo-Json -Depth 8 -Compress) -eq ($facts | ConvertTo-Json -Depth 8 -Compress)) 'replay changed the original failure fact'
  Require ((Scalar "SELECT COUNT(*) FROM $EngineDb.chat_message WHERE $scope") -eq 1) 'replay wrote another fact'
  Note 'REPLAY code=40400 facts=1 original-fact=unchanged'
  Require ((Scalar "SELECT COUNT(*) FROM $BackendDb.space WHERE id=$missingSpace") -eq 0) 'sentinel space changed'
  Require ((Scalar "SELECT COUNT(*) FROM $BackendDb.picture WHERE id=$missingPicture") -eq 0) 'sentinel picture changed'
  Note 'PASS: authenticated hard failure, wrong-chat rejection and replay verified against real Redis + engine MySQL'
} finally {
  [IO.File]::WriteAllLines($OutFile, [string[]]$log, $utf8)
}
