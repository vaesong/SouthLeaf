$ErrorActionPreference='Stop'
. "$PSScriptRoot/Local-Credentials.ps1"
$test=Get-SouthLeafLocalCredential 'IntegrationTest'
$reader=Get-SouthLeafLocalCredential 'Reader'
$env:SOUTHLEAF_TEST_DB_URL=Get-SouthLeafLocalUrl 'southleaf_public_it'
$env:SOUTHLEAF_TEST_DB_USERNAME=$test.Username
$env:SOUTHLEAF_TEST_DB_PASSWORD=$test.GetNetworkCredential().Password
$env:SOUTHLEAF_DB_URL=Get-SouthLeafLocalUrl 'southleaf_public'
$env:SOUTHLEAF_DB_USERNAME=$reader.Username
$env:SOUTHLEAF_DB_PASSWORD=$reader.GetNetworkCredential().Password
$env:SOUTHLEAF_MEDIA_ROOT=(Get-SouthLeafLocalMediaRoot)
Push-Location "$PSScriptRoot/.."
try { & .\mvnw.cmd verify; if($LASTEXITCODE -ne 0){throw "Database verification exit $LASTEXITCODE"} }
finally { Pop-Location; Remove-Item Env:SOUTHLEAF_TEST_DB_PASSWORD,Env:SOUTHLEAF_DB_PASSWORD -ErrorAction SilentlyContinue; $test=$null; $reader=$null }
