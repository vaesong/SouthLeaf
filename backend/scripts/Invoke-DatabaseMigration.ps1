param([switch]$TestDatabase,[switch]$SeedDemo)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Local-Credentials.ps1"
$role=if($TestDatabase){'IntegrationTest'}else{'Migrator'}
$schema=if($TestDatabase){'southleaf_public_it'}else{'southleaf_public'}
$credential=Get-SouthLeafLocalCredential $role
$env:SOUTHLEAF_MIGRATION_URL=Get-SouthLeafLocalUrl $schema
$env:SOUTHLEAF_MIGRATION_USERNAME=$credential.Username
$env:SOUTHLEAF_MIGRATION_PASSWORD=$credential.GetNetworkCredential().Password
$env:SOUTHLEAF_MEDIA_ROOT=(Get-SouthLeafLocalMediaRoot)
Push-Location "$PSScriptRoot/.."
try {
    $arguments=@('-q','-DskipTests','compile','org.codehaus.mojo:exec-maven-plugin:3.5.0:java','-Dexec.mainClass=cn.zhinan.uniapp.tooling.DatabaseMigrationMain')
    if($SeedDemo){$arguments+='-Dexec.args=--seed-demo'}
    & .\mvnw.cmd @arguments
    if($LASTEXITCODE -ne 0){throw "Migration tool exit $LASTEXITCODE"}
} finally {
    Pop-Location
    Remove-Item Env:SOUTHLEAF_MIGRATION_PASSWORD -ErrorAction SilentlyContinue
    $credential=$null
}
