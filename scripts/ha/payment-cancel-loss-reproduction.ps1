param(
    [Parameter(Mandatory = $true)] [long] $ReservationId,
    [Parameter(Mandatory = $true)] [long] $PaymentId,
    [Parameter(Mandatory = $true)] [long] $SellerId,
    [Parameter(Mandatory = $true)] [long] $EventId,
    [Parameter(Mandatory = $true)] [string] $SettlementDate,
    [Parameter(Mandatory = $true)] [string] $LoginEmail,
    [string] $Spring1BaseUrl = 'http://localhost:8081',
    [string] $Database = 'ticketing',
    [string] $MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    [switch] $Execute
)

$ErrorActionPreference = 'Stop'

if (-not $Execute) {
    throw '이 스크립트는 결제를 실제 취소하고 Spring1을 종료합니다. 검증 후 -Execute를 지정하세요.'
}
if (-not $env:TICKETON_TEST_PASSWORD) {
    throw '로그인 비밀번호를 TICKETON_TEST_PASSWORD 환경변수로 지정하세요.'
}
if (-not $env:MYSQL_PWD) {
    throw 'MySQL 비밀번호를 MYSQL_PWD 환경변수로 지정하세요.'
}

function Invoke-Mysql([string] $Sql) {
    & $MysqlExe --batch --raw -h 127.0.0.1 -P 3306 -u root -D $Database -e $Sql
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 명령 실행 실패' }
}

Write-Host '[1/7] 대상 상태 확인'
Invoke-Mysql "SELECT p.id,p.status,r.status reservation_status FROM payment p JOIN reservation r ON r.id=p.reservation_id WHERE p.id=$PaymentId AND r.id=$ReservationId;"

Write-Host '[2/7] Dirty UNIQUE Key 잠금 트랜잭션 시작'
$lockSql = "START TRANSACTION; INSERT INTO settlement_dirty_date (seller_id,event_id,settlement_date,created_at,updated_at,created_by,updated_by) VALUES ($SellerId,$EventId,'$SettlementDate',NOW(6),NOW(6),'ha-experiment','ha-experiment'); SELECT SLEEP(600); ROLLBACK;"
$lockProcess = Start-Process -FilePath $MysqlExe -ArgumentList @('--batch','--raw','-h','127.0.0.1','-P','3306','-u','root','-D',$Database,'-e',$lockSql) -PassThru -WindowStyle Hidden

try {
    Start-Sleep -Seconds 1

    Write-Host '[3/7] Spring1에 로그인 후 취소 요청 시작'
    $cancelJob = Start-Job -ScriptBlock {
        param($BaseUrl, $Email, $Password, $TargetReservationId)
        $loginBody = @{ email = $Email; password = $Password } | ConvertTo-Json
        $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" -ContentType 'application/json' -Body $loginBody -TimeoutSec 15
        $headers = @{ Authorization = "Bearer $($login.data.accessToken)" }
        $cancelBody = @{ cancelReason = 'CHANGE_OF_MIND'; detail = 'HA 장애 주입 실험' } | ConvertTo-Json
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/reservations/$TargetReservationId/cancel" -Headers $headers -ContentType 'application/json' -Body $cancelBody -TimeoutSec 120
    } -ArgumentList $Spring1BaseUrl, $LoginEmail, $env:TICKETON_TEST_PASSWORD, $ReservationId

    Write-Host '[4/7] Payment 커밋 대기'
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $status = (& $MysqlExe --batch --raw --skip-column-names -h 127.0.0.1 -P 3306 -u root -D $Database -e "SELECT status FROM payment WHERE id=$PaymentId;").Trim()
        if ($status -eq 'CANCELED') { break }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    if ($status -ne 'CANCELED') { throw '30초 안에 Payment CANCELED 커밋을 확인하지 못했습니다.' }

    Write-Host '[5/7] Spring1 종료'
    docker compose -f docker-compose.ha.yml stop spring1
    if ($LASTEXITCODE -ne 0) { throw 'Spring1 종료 실패' }
}
finally {
    if ($lockProcess -and -not $lockProcess.HasExited) {
        Stop-Process -Id $lockProcess.Id -Force
        $lockProcess.WaitForExit()
    }
}

Write-Host '[6/7] Spring1 재기동 및 health 확인'
docker compose -f docker-compose.ha.yml up -d spring1
if ($LASTEXITCODE -ne 0) { throw 'Spring1 재기동 실패' }

$deadline = (Get-Date).AddSeconds(60)
do {
    try { $health = (Invoke-RestMethod -Uri "$Spring1BaseUrl/actuator/health" -TimeoutSec 3).status } catch { $health = 'DOWN' }
    if ($health -eq 'UP') { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if ($health -ne 'UP') { throw 'Spring1이 60초 안에 복구되지 않았습니다.' }

Write-Host '[7/7] 복구 후 Payment / Dirty / Settlement 확인'
Invoke-Mysql "SELECT p.id,p.status,r.status reservation_status FROM payment p JOIN reservation r ON r.id=p.reservation_id WHERE p.id=$PaymentId; SELECT COUNT(*) dirty_count FROM settlement_dirty_date WHERE seller_id=$SellerId AND event_id=$EventId AND settlement_date='$SettlementDate'; SELECT gross_amount,net_amount FROM settlement WHERE seller_id=$SellerId AND event_id=$EventId AND settlement_date='$SettlementDate';"

if ($cancelJob) {
    Receive-Job -Job $cancelJob -ErrorAction SilentlyContinue
    Remove-Job -Job $cancelJob -Force
}
