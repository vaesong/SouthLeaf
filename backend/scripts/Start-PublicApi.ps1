$ErrorActionPreference='Stop'
. "$PSScriptRoot/Local-Credentials.ps1"
$credential=Get-SouthLeafLocalCredential 'Reader'
$env:SOUTHLEAF_DB_URL=Get-SouthLeafLocalUrl 'southleaf_public'
$env:SOUTHLEAF_DB_USERNAME=$credential.Username
$env:SOUTHLEAF_DB_PASSWORD=$credential.GetNetworkCredential().Password
$env:SOUTHLEAF_MEDIA_ROOT=(Get-SouthLeafLocalMediaRoot)
Push-Location "$PSScriptRoot/.."
try {
    & .\mvnw.cmd spring-boot:run
    if($LASTEXITCODE -ne 0){throw "API process exit $LASTEXITCODE"}
} finally { Pop-Location; Remove-Item Env:SOUTHLEAF_DB_PASSWORD -ErrorAction SilentlyContinue; $credential=$null }
