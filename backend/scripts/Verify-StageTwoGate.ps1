# Starts the disposable booking instance, runs the stage-two engineering gate, then removes
# only that instance. Does not target an existing MySQL service.
$ErrorActionPreference = 'Stop'
$backend = Split-Path $PSScriptRoot -Parent
$repository = Split-Path $backend -Parent
$dataRoot = Join-Path ($backend + '-data') 'booking-db'
$connectionFile = Join-Path $dataRoot 'connection.env'
$port = 3307
$evidencePath = Join-Path $repository 'docs\开发与演示\证据\stage-two-engineering-close.json'
$started = $false
$gateExit = 1

function Stop-OwnedBooking([int]$OnPort) {
  & (Join-Path $PSScriptRoot 'Start-BookingDatabase.ps1') -Port $OnPort -StopOwned
}

function Set-EvidenceField([string]$Name, $Value) {
  if (-not (Test-Path -LiteralPath $evidencePath)) { return }
  $evidence = Get-Content -LiteralPath $evidencePath -Raw -Encoding utf8 | ConvertFrom-Json
  $evidence | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
  $evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidencePath -Encoding utf8
}

try {
  & (Join-Path $PSScriptRoot 'Start-BookingDatabase.ps1') -Port $port
  $started = $true
  foreach ($line in Get-Content -LiteralPath $connectionFile) {
    if ($line -match '^([^=]+)=(.*)$') {
      Set-Item -Path ("Env:" + $Matches[1]) -Value $Matches[2]
    }
  }
  & python (Join-Path $PSScriptRoot 'verify-stage-two-gate.py') --confirm-isolated-gate
  $gateExit = $LASTEXITCODE
  if ($gateExit -ne 0) { throw "Stage-two gate exit $gateExit" }

  Push-Location $backend
  & .\mvnw.cmd -pl public-api -am package -DskipTests
  if ($LASTEXITCODE -ne 0) { throw "Public API package exit $LASTEXITCODE" }
  & .\mvnw.cmd -pl admin-runtime -am package -DskipTests
  if ($LASTEXITCODE -ne 0) { throw "Admin runtime package exit $LASTEXITCODE" }
  Pop-Location

  Push-Location (Join-Path $repository 'admin-web')
  npm run build
  if ($LASTEXITCODE -ne 0) { throw "Admin web build exit $LASTEXITCODE" }
  Pop-Location

  & python (Join-Path $PSScriptRoot 'build-admin-runtime.py') `
    --jar (Join-Path $backend 'admin-runtime\target\admin-runtime-0.1.0-SNAPSHOT.jar') `
    --dist (Join-Path $repository 'admin-web\dist')
  if ($LASTEXITCODE -ne 0) { throw "Admin runtime static pack exit $LASTEXITCODE" }

  Set-EvidenceField 'publicApiJar' 'backend/public-api/target/uniapp-api-0.1.0-SNAPSHOT.jar'
  Set-EvidenceField 'adminRuntimeJar' 'backend/admin-runtime/target/admin-runtime-0.1.0-SNAPSHOT.jar'
  Set-EvidenceField 'publicApiPackage' 'mvnw -pl public-api -am package -DskipTests'
  Set-EvidenceField 'adminRuntimePackage' 'mvnw -pl admin-runtime -am package -DskipTests; npm run build; build-admin-runtime.py'
}
finally {
  Remove-Item Env:SOUTHLEAF_BOOKING_IT_PASSWORD,Env:SOUTHLEAF_BOOKING_IT_USERNAME,Env:SOUTHLEAF_BOOKING_IT_URL,Env:SOUTHLEAF_BOOKING_IT_INSTANCE_ID -ErrorAction SilentlyContinue
  if ($started) {
    Stop-OwnedBooking $port
    Set-EvidenceField 'bookingDatadirRemoved' $true
  }
}
