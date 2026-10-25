<#
.SYNOPSIS
  Starts a disposable, isolated MySQL instance dedicated to the booking integration suite.

.DESCRIPTION
  The instance owns its own data directory, its own port and its own schema. It never reads or
  writes an existing MySQL service, its data directory, its schemas or its credentials, so it can
  be started and destroyed without touching anything else on the machine.

  The schema name and the port are enforced: the booking suite refuses any target that is not the
  isolated schema on a non default port.

.PARAMETER Port
  TCP port for the isolated instance. Must not be the default 3306.

.PARAMETER MySqlHome
  MySQL installation directory. Auto detected when omitted.

.PARAMETER DataRoot
  Owning directory for the instance data. Defaults to <repository>-data\booking-db, outside the
  repository so that no build output lands in version control.
#>
param(
  [int]$Port = 3307,
  [string]$MySqlHome = '',
  [string]$DataRoot = '',
  [string]$Schema = 'southleaf_booking_it'
)

$ErrorActionPreference = 'Stop'

if ($Port -eq 3306) { throw 'The isolated instance must not use the default port 3306.' }
if ($Schema -ne 'southleaf_booking_it') { throw 'The isolated schema name is fixed to southleaf_booking_it.' }

function Resolve-MySqlHome([string]$Explicit) {
  if ($Explicit) {
    if (-not (Test-Path (Join-Path $Explicit 'bin\mysqld.exe'))) { throw "mysqld.exe missing under $Explicit" }
    return $Explicit
  }
  $command = Get-Command 'mysqld.exe' -ErrorAction SilentlyContinue
  if ($command) { return (Split-Path (Split-Path $command.Source -Parent) -Parent) }
  foreach ($candidate in @('D:\SoftWare\MySQL', 'C:\Program Files\MySQL', 'C:\ProgramData\MySQL')) {
    if (Test-Path (Join-Path $candidate 'bin\mysqld.exe')) { return $candidate }
  }
  throw 'mysqld.exe not found. Pass -MySqlHome with the MySQL installation directory.'
}

$mySqlHomeResolved = Resolve-MySqlHome $MySqlHome
$mySqlBin = Join-Path $mySqlHomeResolved 'bin'
$mySqld = Join-Path $mySqlBin 'mysqld.exe'
$mysql = Join-Path $mySqlBin 'mysql.exe'
$mysqladmin = Join-Path $mySqlBin 'mysqladmin.exe'

if (-not $DataRoot) {
  $repository = Split-Path $PSScriptRoot -Parent
  $DataRoot = Join-Path ($repository + '-data') 'booking-db'
}
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
$dataDir = Join-Path $DataRoot 'data'
$logFile = Join-Path $DataRoot 'mysqld.err'
$pidFile = Join-Path $DataRoot 'mysqld.pid'
$connectionFile = Join-Path $DataRoot 'connection.env'
$ownerFile = Join-Path $DataRoot 'booking-it-owner.json'

if (Test-Path -LiteralPath $DataRoot) {
  $existing = @(Get-ChildItem -LiteralPath $DataRoot -Force)
  if ($existing.Count -gt 0 -and -not (Test-Path -LiteralPath $ownerFile)) {
    throw "DataRoot already contains files without the booking ownership marker: $DataRoot"
  }
  if (Test-Path -LiteralPath $ownerFile) {
    $previousOwner = Get-Content -LiteralPath $ownerFile -Raw | ConvertFrom-Json
    if ($previousOwner.schema -ne $Schema -or [int]$previousOwner.port -ne $Port -or
        -not [string]::Equals([string]$previousOwner.dataRoot, $DataRoot, [StringComparison]::OrdinalIgnoreCase)) {
      throw 'The existing booking ownership marker does not match this schema, port and data directory.'
    }
  }
} else {
  New-Item -ItemType Directory -Force -Path $DataRoot | Out-Null
}

function Test-InstanceRunning([int]$OnPort) {
  $listener = Get-NetTCPConnection -LocalPort $OnPort -State Listen -ErrorAction SilentlyContinue
  return ($null -ne $listener)
}

# Never attach to a listener that already exists: it may be a user's unrelated database.
# This launcher only operates on the process it starts itself, and must fail closed otherwise.
if (Test-InstanceRunning $Port) {
  throw "Port $Port is already in use. Refusing to touch an existing database listener. Choose another port with -Port."
}

if (-not (Test-Path (Join-Path $dataDir 'mysql'))) {
  Write-Output "BOOKING_DB_INITIALIZING datadir=$dataDir"
  $initLog = Join-Path $DataRoot 'initialize.err'
  $init = Start-Process -FilePath $mySqld -ArgumentList @(
    '--no-defaults',
    '--initialize-insecure',
    ('--basedir="{0}"' -f $mySqlHomeResolved),
    ('--datadir="{0}"' -f $dataDir),
    ('--log-error="{0}"' -f $initLog)
  ) -WindowStyle Hidden -PassThru -Wait
  if ($init.ExitCode -ne 0) {
    $detail = if (Test-Path $initLog) { Get-Content $initLog -Raw } else { '' }
    throw "mysqld --initialize-insecure failed with exit $($init.ExitCode). $detail"
  }
}

Write-Output "BOOKING_DB_STARTING port=$Port"
$process = Start-Process -FilePath $mySqld -ArgumentList @(
  '--no-defaults',
  ('--basedir="{0}"' -f $mySqlHomeResolved),
  ('--datadir="{0}"' -f $dataDir),
  "--port=$Port",
  '--bind-address=127.0.0.1',
  '--mysqlx=0',
  '--skip-log-bin',
  "--server-id=$Port",
  ('--log-error="{0}"' -f $logFile),
  ('--pid-file="{0}"' -f $pidFile)
) -WindowStyle Hidden -PassThru
$process.Id | Out-File -FilePath $pidFile -Encoding ascii

$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
  if (-not (Test-InstanceRunning $Port)) { Start-Sleep -Seconds 1; continue }
  & $mysqladmin --protocol=tcp --host=127.0.0.1 --port=$Port -u root ping 2>&1 | Out-Null
  if ($LASTEXITCODE -eq 0) { $ready = $true; break }
  Start-Sleep -Seconds 1
}
if (-not $ready) {
  $detail = if (Test-Path $logFile) { Get-Content $logFile -Raw } else { '' }
  throw "The isolated instance did not become ready on port $Port. $detail"
}

# A dedicated identity for the suite. The password is generated per instance and stored outside
# the repository, next to the instance data, never in version control.
$raw = New-Object byte[] 24
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($raw) } finally { $random.Dispose() }
$password = [Convert]::ToBase64String($raw).Replace('+', 'a').Replace('/', 'b').Replace('=', 'q')
$user = 'booking_it'
$instanceId = [Guid]::NewGuid().ToString()

& $mysql --protocol=tcp --host=127.0.0.1 --port=$Port -u root -e "CREATE DATABASE IF NOT EXISTS $Schema CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" 2>&1 | Out-Null
$sqlUserHost = "'$user'" + [char]64 + "'127.0.0.1'"
$userSql = "CREATE USER IF NOT EXISTS $sqlUserHost IDENTIFIED BY '$password'; ALTER USER $sqlUserHost IDENTIFIED BY '$password'; GRANT ALL PRIVILEGES ON $Schema.* TO $sqlUserHost; FLUSH PRIVILEGES;"
& $mysql --protocol=tcp --host=127.0.0.1 --port=$Port -u root -e $userSql 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated booking schema and identity.' }
$ownerTable = "$($Schema).southleaf_booking_it_owner"
& $mysql --protocol=tcp --host=127.0.0.1 --port=$Port -u root -e "CREATE TABLE IF NOT EXISTS $ownerTable (owner_id TINYINT NOT NULL PRIMARY KEY, instance_id CHAR(36) NOT NULL); INSERT INTO $ownerTable (owner_id, instance_id) VALUES (1, '$instanceId') ON DUPLICATE KEY UPDATE instance_id = VALUES(instance_id);" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not establish the isolated booking ownership marker.' }

$url = "jdbc:mysql://127.0.0.1:$Port/$($Schema)?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
@(
  "SOUTHLEAF_BOOKING_IT_URL=$url",
  "SOUTHLEAF_BOOKING_IT_USERNAME=$user",
  "SOUTHLEAF_BOOKING_IT_PASSWORD=$password",
  "SOUTHLEAF_BOOKING_IT_INSTANCE_ID=$instanceId"
) | Out-File -FilePath $connectionFile -Encoding utf8
[ordered]@{
  schema = $Schema
  port = $Port
  dataRoot = $DataRoot
  instanceId = $instanceId
  processId = [int](Get-Content -LiteralPath $pidFile -Raw)
  launcherProcessId = $process.Id
} | ConvertTo-Json -Compress | Out-File -FilePath $ownerFile -Encoding ascii

# The generated test credential is local state; restrict it to the current user account.
$principal = "$($env:USERDOMAIN)\$($env:USERNAME):(F)"
& icacls.exe $connectionFile /inheritance:r /grant:r $principal | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict the isolated test credential file ACL.' }
$password = $null
$raw = $null

Write-Output "BOOKING_DB_READY port=$Port schema=$Schema user=$user connectionFile=$connectionFile"
