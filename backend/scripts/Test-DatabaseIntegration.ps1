param([switch]$ValidateOnly)
$ErrorActionPreference='Stop'
# No credential loading, Maven or connection before the owner supplies every isolated IT input.
$required=@('SOUTHLEAF_SECURITY_IT_URL','SOUTHLEAF_SECURITY_IT_USERNAME','SOUTHLEAF_SECURITY_IT_PASSWORD','SOUTHLEAF_SECURITY_IT_EXPECTED_USER','SOUTHLEAF_SECURITY_IT_UUID','SOUTHLEAF_SECURITY_IT_TRUSTSTORE')
foreach($key in $required){
 if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key))){
  throw "Missing isolated management IT input: $key. Use verify-admin-isolated.py verify --manifest <owned-manifest> --with-reactor."
 }
}
$pattern='^jdbc:mysql://localhost:([0-9]{1,5})/southleaf_admin_s1b_it[?]sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true$'
$match=[regex]::Match($env:SOUTHLEAF_SECURITY_IT_URL,$pattern)
if(-not $match.Success){throw 'Isolated management IT URL rejected'}
$port=[int]$match.Groups[1].Value
if($port -lt 1 -or $port -gt 65535 -or $port -eq 3306){throw 'Original server is not a management test target'}
$uuid=[Guid]::Empty
if(-not [Guid]::TryParse($env:SOUTHLEAF_SECURITY_IT_UUID,[ref]$uuid)){throw 'Invalid isolated instance UUID'}
if($env:SOUTHLEAF_SECURITY_IT_EXPECTED_USER -cne ($env:SOUTHLEAF_SECURITY_IT_USERNAME+'@localhost')){throw 'Isolated management identity rejected'}
if(-not (Test-Path -LiteralPath $env:SOUTHLEAF_SECURITY_IT_TRUSTSTORE -PathType Leaf)){throw 'Isolated test truststore missing'}
if($ValidateOnly){Write-Output 'SECURITY_IT_ENVIRONMENT_VALIDATED_NO_CONNECTION';return}
$isolatedPublic=@('SOUTHLEAF_PUBLIC_IT_ISOLATED','SOUTHLEAF_PUBLIC_IT_TEST_URL','SOUTHLEAF_PUBLIC_IT_TEST_USERNAME','SOUTHLEAF_PUBLIC_IT_TEST_PASSWORD','SOUTHLEAF_PUBLIC_IT_RUNTIME_URL','SOUTHLEAF_PUBLIC_IT_RUNTIME_USERNAME','SOUTHLEAF_PUBLIC_IT_RUNTIME_PASSWORD','SOUTHLEAF_PUBLIC_IT_MEDIA_ROOT')
$provided=@($isolatedPublic | Where-Object { -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) })
if($provided.Count -ne 0 -and $provided.Count -ne $isolatedPublic.Count){throw 'Partial explicitly-owned public integration fixture input'}
if($provided.Count -eq $isolatedPublic.Count){
 if($env:SOUTHLEAF_PUBLIC_IT_ISOLATED -cne 'owned'){throw 'Explicit public fixture ownership marker rejected'}
 $testPattern='^jdbc:mysql://localhost:([1-9][0-9]{3,4})/southleaf_public_it[?]sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true$'
 $runtimePattern='^jdbc:mysql://localhost:([1-9][0-9]{3,4})/southleaf_public[?]sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true$'
 if(-not [regex]::IsMatch($env:SOUTHLEAF_PUBLIC_IT_TEST_URL,$testPattern) -or -not [regex]::IsMatch($env:SOUTHLEAF_PUBLIC_IT_RUNTIME_URL,$runtimePattern)){throw 'Explicit public fixture URL rejected'}
 if(([regex]::Match($env:SOUTHLEAF_PUBLIC_IT_TEST_URL,$testPattern)).Groups[1].Value -ne ([regex]::Match($env:SOUTHLEAF_PUBLIC_IT_RUNTIME_URL,$runtimePattern)).Groups[1].Value){throw 'Explicit public fixture ports differ'}
 $env:SOUTHLEAF_TEST_DB_URL=$env:SOUTHLEAF_PUBLIC_IT_TEST_URL
 $env:SOUTHLEAF_TEST_DB_USERNAME=$env:SOUTHLEAF_PUBLIC_IT_TEST_USERNAME
 $env:SOUTHLEAF_TEST_DB_PASSWORD=$env:SOUTHLEAF_PUBLIC_IT_TEST_PASSWORD
 $env:SOUTHLEAF_DB_URL=$env:SOUTHLEAF_PUBLIC_IT_RUNTIME_URL
 $env:SOUTHLEAF_DB_USERNAME=$env:SOUTHLEAF_PUBLIC_IT_RUNTIME_USERNAME
 $env:SOUTHLEAF_DB_PASSWORD=$env:SOUTHLEAF_PUBLIC_IT_RUNTIME_PASSWORD
 $env:SOUTHLEAF_MEDIA_ROOT=$env:SOUTHLEAF_PUBLIC_IT_MEDIA_ROOT
} else {
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
}
Push-Location "$PSScriptRoot/.."
try {
 & .\mvnw.cmd verify "-DskipTests=false" "-Dmaven.test.skip=false" "-DskipITs=false"
 if($LASTEXITCODE -ne 0){throw "Database verification exit $LASTEXITCODE"}
 & python scripts/check-module-boundaries.py
 if($LASTEXITCODE -ne 0){throw "Module boundary verification exit $LASTEXITCODE"}
}
finally { Pop-Location; Remove-Item Env:SOUTHLEAF_TEST_DB_PASSWORD,Env:SOUTHLEAF_DB_PASSWORD -ErrorAction SilentlyContinue; $test=$null; $reader=$null }
