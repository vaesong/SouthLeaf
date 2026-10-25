param([int]$Port=0)
$ErrorActionPreference='Stop'
if($Port -lt 0 -or $Port -gt 65535){throw 'Port out of range'}
. "$PSScriptRoot/Local-Credentials.ps1"
$credential=Get-SouthLeafLocalCredential 'Reader'
$env:SOUTHLEAF_DB_URL=Get-SouthLeafLocalUrl 'southleaf_public'
$env:SOUTHLEAF_DB_USERNAME=$credential.Username
$env:SOUTHLEAF_DB_PASSWORD=$credential.GetNetworkCredential().Password
$env:SOUTHLEAF_MEDIA_ROOT=(Get-SouthLeafLocalMediaRoot)
Push-Location "$PSScriptRoot/.."
try {
    $arguments=@('-f','public-api/pom.xml','spring-boot:run')
    if($Port -gt 0){$arguments+="-Dspring-boot.run.arguments=--server.port=$Port"}
    & .\mvnw.cmd @arguments
    if($LASTEXITCODE -ne 0){throw "API process exit $LASTEXITCODE"}
} finally { Pop-Location; Remove-Item Env:SOUTHLEAF_DB_PASSWORD -ErrorAction SilentlyContinue; $credential=$null }
