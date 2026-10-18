$ErrorActionPreference='Stop'
. "$PSScriptRoot/Local-Credentials.ps1"
$credential=Get-SouthLeafLocalCredential Reader
$jar=Join-Path $PSScriptRoot '../target/uniapp-api-0.1.0-SNAPSHOT.jar'
if(-not (Test-Path $jar)){throw 'Run the verified Maven package before startup guard tests'}
function Assert-StartupRefused([string]$Url,[string]$Arguments,[string]$Expected) {
    $start=New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName='java'
    $start.Arguments='-jar "'+$jar+'" --server.port=0 '+$Arguments
    $start.UseShellExecute=$false; $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    $start.EnvironmentVariables['SOUTHLEAF_DB_URL']=$Url
    $start.EnvironmentVariables['SOUTHLEAF_DB_USERNAME']=$credential.UserName
    $start.EnvironmentVariables['SOUTHLEAF_DB_PASSWORD']=$credential.GetNetworkCredential().Password
    $start.EnvironmentVariables['SOUTHLEAF_MEDIA_ROOT']=(Get-SouthLeafLocalMediaRoot)
    $process=[System.Diagnostics.Process]::Start($start)
    $output=$process.StandardOutput.ReadToEndAsync();$errors=$process.StandardError.ReadToEndAsync()
    if(-not $process.WaitForExit(45000)){$process.Kill();throw 'Negative startup test timed out'}
    $text=$output.Result+$errors.Result
    if($process.ExitCode -eq 0 -or $text -notmatch $Expected){throw 'Expected explicit startup refusal was not observed'}
}
Assert-StartupRefused 'jdbc:mysql://127.0.0.1:1/southleaf_public?connectTimeout=1000&socketTimeout=1000' '' 'Communications link failure|Connection refused'
Assert-StartupRefused (Get-SouthLeafLocalUrl 'southleaf_public') '--spring.profiles.active=production' 'Production requires sslMode=VERIFY_IDENTITY'
Write-Output 'STARTUP_GUARDS_PASS database-unavailable=no-fallback; production-refuses-unverified-database-TLS; existing API/MySQL processes unchanged'
