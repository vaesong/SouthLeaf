param([Parameter(Mandatory=$true)][ValidateSet('Validate','Migrate')][string]$Action)
$ErrorActionPreference='Stop'
$module=Join-Path $PSScriptRoot '../admin-migration-cli'
$jar=Join-Path $module 'target/admin-migration-cli-0.1.0-SNAPSHOT.jar'
$dependencies=Join-Path $module 'target/dependency'
if(-not (Test-Path $jar) -or -not (Test-Path $dependencies)){
 throw 'Build the isolated CLI first: mvnw.cmd -pl admin-db-policy,admin-migration-cli package dependency:copy-dependencies -DincludeScope=runtime'
}
foreach($name in @('SOUTHLEAF_ADMIN_MIGRATION_SCHEMA','SOUTHLEAF_ADMIN_MIGRATION_URL','SOUTHLEAF_ADMIN_MIGRATION_USERNAME','SOUTHLEAF_ADMIN_MIGRATION_PASSWORD')){
 if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))){throw ('Required environment variable: '+$name)}
}
# Credentials are inherited from the controlled parent environment, never passed as Java arguments.
$classpath=$jar+[IO.Path]::PathSeparator+(Join-Path $dependencies '*')
$argument=if($Action -eq 'Migrate'){'--migrate'}else{'--validate'}
& java -cp $classpath cn.zhinan.adminmigration.AdminDatabaseMigrationMain $argument
if($LASTEXITCODE -ne 0){throw 'Explicit management migration command failed'}
