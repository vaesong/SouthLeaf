<#
.SYNOPSIS
  Runs the booking module against a disposable isolated MySQL instance.

.DESCRIPTION
  Starts the isolated instance owned by Start-BookingDatabase.ps1, exports the explicit integration
  variables and runs the admin-booking verification, which executes the unit tests and the real
  database integration suite. The instance is destroyed afterwards unless -KeepRunning is given.

  The instance never touches an existing MySQL service or its schemas.
#>
param(
  [switch]$KeepRunning,
  [int]$Port = 3307
)

$ErrorActionPreference = 'Stop'

$backend = Split-Path $PSScriptRoot -Parent
$repository = Split-Path $backend -Parent
$dataRoot = Join-Path ($backend + '-data') 'booking-db'
$connectionFile = Join-Path $dataRoot 'connection.env'

& (Join-Path $PSScriptRoot 'Start-BookingDatabase.ps1') -Port $Port
if ($LASTEXITCODE -ne 0) { throw "Isolated instance start failed with exit $LASTEXITCODE" }

if (-not (Test-Path $connectionFile)) { throw "The isolated connection file is missing: $connectionFile" }
foreach ($line in Get-Content $connectionFile) {
  if ($line -match '^([^=]+)=(.*)$') {
    Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2]
  }
}
$instanceId = $env:SOUTHLEAF_BOOKING_IT_INSTANCE_ID

Push-Location (Join-Path $repository 'backend')
try {
  & .\mvnw.cmd -o -pl admin-booking verify '-DskipTests=false' '-Dmaven.test.skip=false' '-DskipITs=false'
  if ($LASTEXITCODE -ne 0) { throw "Booking verification exit $LASTEXITCODE" }
  & python scripts/check-module-boundaries.py
  if ($LASTEXITCODE -ne 0) { throw "Module boundary verification exit $LASTEXITCODE" }
  & python (Join-Path $repository 'backend/scripts/check-booking-sql-columns.py') `
      (Join-Path $repository 'backend/admin-migration-cli/src/main/resources/db/admin-migration/V6__admin_identity_booking.sql') `
      (Join-Path $repository 'backend/admin-migration-cli/src/main/resources/db/admin-migration/V8__admin_package_fact.sql') `
      (Join-Path $repository 'backend/admin-booking/src/main/java/cn/zhinan/adminbooking/adapter/jdbc')
  if ($LASTEXITCODE -ne 0) { throw "Booking SQL/schema check exit $LASTEXITCODE" }
}
finally {
  Pop-Location
  Remove-Item Env:SOUTHLEAF_BOOKING_IT_PASSWORD,Env:SOUTHLEAF_BOOKING_IT_USERNAME,Env:SOUTHLEAF_BOOKING_IT_URL,Env:SOUTHLEAF_BOOKING_IT_INSTANCE_ID -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $connectionFile -Force -ErrorAction SilentlyContinue
  if (-not $KeepRunning) {
    $ownerFile = Join-Path $dataRoot 'booking-it-owner.json'
    if (-not (Test-Path -LiteralPath $ownerFile)) {
      throw 'Refusing to remove the isolated datadir without its ownership marker.'
    }
    $owner = Get-Content -LiteralPath $ownerFile -Raw | ConvertFrom-Json
    $expectedRoot = [System.IO.Path]::GetFullPath($dataRoot)
    if ($owner.schema -ne 'southleaf_booking_it' -or [int]$owner.port -ne $Port -or
        $owner.instanceId -ne $instanceId -or
        -not [string]::Equals([string]$owner.dataRoot, $expectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
      throw 'Refusing cleanup: booking ownership marker does not match this run.'
    }

    $pidFile = Join-Path $dataRoot 'mysqld.pid'
    if (Test-Path -LiteralPath $pidFile) {
      $instancePid = [int](Get-Content -LiteralPath $pidFile -Raw)
      $process = Get-CimInstance Win32_Process -Filter "ProcessId = $instancePid"
      $expectedDataDir = (Join-Path $dataRoot 'data').TrimEnd('\\')
      $commandLine = if ($process) { $process.CommandLine.Replace('"', '') } else { '' }
      $ownsInstance =
        $process -and
        $process.ExecutablePath -match '\\mysqld\.exe$' -and
        $commandLine.IndexOf("--datadir=$expectedDataDir", [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        [int]$owner.processId -eq $instancePid
      if ($ownsInstance) {
        $mysqladmin = Join-Path (Split-Path $process.ExecutablePath -Parent) 'mysqladmin.exe'
        & $mysqladmin --protocol=tcp --host=127.0.0.1 --port=$Port -u root shutdown 2>&1 | Out-Null
        for ($attempt = 0; $attempt -lt 30 -and (Get-Process -Id $instancePid -ErrorAction SilentlyContinue); $attempt++) {
          Start-Sleep -Seconds 1
        }
        $remaining = Get-Process -Id $instancePid -ErrorAction SilentlyContinue
        if ($remaining) {
          $fresh = Get-CimInstance Win32_Process -Filter "ProcessId = $instancePid"
          $freshCommandLine = if ($fresh) { $fresh.CommandLine.Replace('"', '') } else { '' }
          $stillOwned = $fresh -and $fresh.ExecutablePath -match '\\mysqld\.exe$' -and
            $freshCommandLine.IndexOf("--datadir=$expectedDataDir", [StringComparison]::OrdinalIgnoreCase) -ge 0
          if (-not $stillOwned) { throw 'Refusing to stop a PID that no longer matches the isolated instance.' }
          Stop-Process -Id $instancePid -Force -ErrorAction Stop
        }

        $launcherPid = [int]$owner.launcherProcessId
        if ($launcherPid -ne $instancePid) {
          $launcher = Get-CimInstance Win32_Process -Filter "ProcessId = $launcherPid"
          $launcherCommandLine = if ($launcher) { $launcher.CommandLine.Replace('"', '') } else { '' }
          $launcherOwned = $launcher -and $launcher.ExecutablePath -match '\\mysqld\.exe$' -and
            $launcherCommandLine.IndexOf("--datadir=$expectedDataDir", [StringComparison]::OrdinalIgnoreCase) -ge 0
          if ($launcherOwned) { Stop-Process -Id $launcherPid -Force -ErrorAction Stop }
          elseif ($launcher) { throw 'Refusing to stop a launcher PID that does not match the isolated datadir.' }
        }
      } elseif ($process) {
        throw 'Refusing to stop the PID: it is not the isolated booking database process.'
      }
    }

    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { throw "Refusing to remove the datadir while port $Port still has a listener." }
    Remove-Item -LiteralPath $dataRoot -Recurse -Force
    Write-Output 'BOOKING_DB_STOPPED_AND_OWNED_DATADIR_REMOVED'
  }
}
