param([switch]$ConfirmIsolatedTestRestore,[string]$MySqlBin='D:\SoftWare\MySQL\bin')
$ErrorActionPreference='Stop'
if(-not $ConfirmIsolatedTestRestore){throw 'Explicit -ConfirmIsolatedTestRestore is mandatory; only southleaf_public_it will be restored'}
. "$PSScriptRoot/Local-Credentials.ps1"
$credential=Get-SouthLeafLocalCredential IntegrationTest
$database='southleaf_public_it'
$backup=Join-Path 'E:\SouthLeaf-data\backups' ('integration-drill-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
[void](New-Item -ItemType Directory $backup)
function Invoke-Client([string]$Executable,[string]$Arguments,[string]$InputText='') {
 $start=New-Object Diagnostics.ProcessStartInfo
 $start.FileName=Join-Path $MySqlBin $Executable
 $start.Arguments='--no-defaults --host=127.0.0.1 --port=3306 --ssl-mode=REQUIRED --default-character-set=utf8mb4 --user='+$credential.UserName+' '+$Arguments
 $start.UseShellExecute=$false;$start.RedirectStandardInput=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
 $start.StandardOutputEncoding=[Text.UTF8Encoding]::new($false)
 $start.EnvironmentVariables['MYSQL_PWD']=$credential.GetNetworkCredential().Password
 $p=[Diagnostics.Process]::Start($start)
 $stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync()
 if($InputText){$bytes=[Text.Encoding]::UTF8.GetBytes($InputText);$p.StandardInput.BaseStream.Write($bytes,0,$bytes.Length);$p.StandardInput.BaseStream.Flush()};$p.StandardInput.Close()
 if(-not $p.WaitForExit(45000)){$p.Kill();throw 'MySQL drill process timed out'}
 if($p.ExitCode -ne 0){throw ($Executable+' exit='+$p.ExitCode+' '+$stderr.Result)}
 return $stdout.Result.Trim()
}
function Read-Scalar([string]$Sql){Invoke-Client 'mysql.exe' ('--batch --skip-column-names '+$database) $Sql}
$identity=Read-Scalar 'SELECT DATABASE();'
if($identity -ne $database){throw 'Unexpected schema; refuse any changes'}
$before=Read-Scalar 'SELECT english_name FROM studio_brand ORDER BY release_id;'
$counts=Read-Scalar 'SELECT (SELECT COUNT(*) FROM public_work),(SELECT COUNT(*) FROM media_variant),(SELECT COUNT(*) FROM flyway_schema_history);'
$keys=(Read-Scalar 'SELECT DISTINCT object_key FROM media_variant ORDER BY object_key;') -split '\r?\n'
$dump=Join-Path $backup 'database.sql'
Invoke-Client 'mysqldump.exe' ('--single-transaction --skip-lock-tables --skip-add-locks --no-tablespaces --set-gtid-purged=OFF --skip-triggers --result-file="'+$dump+'" '+$database)|Out-Null
$media=Join-Path $backup 'media';[void](New-Item -ItemType Directory $media)
$restored=Join-Path $backup 'restored-media';[void](New-Item -ItemType Directory $restored)
$manifest=@()
foreach($key in $keys){
 if($key -notmatch '^[a-f0-9]{64}\.(png|jpg|jpeg)$'){throw 'Unexpected media object key'}
 $source=Join-Path 'E:\SouthLeaf-data\public-media' $key
 $sha=(Get-FileHash $source -Algorithm SHA256).Hash.ToLowerInvariant()
 if(-not $key.StartsWith($sha+'.')){throw 'Source object hash/name mismatch'}
 Copy-Item -LiteralPath $source -Destination (Join-Path $media $key)
 Copy-Item -LiteralPath (Join-Path $media $key) -Destination (Join-Path $restored $key)
 if((Get-FileHash (Join-Path $restored $key) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha){throw 'Restored media hash mismatch'}
 $manifest+=@{objectKey=$key;sha256=$sha;bytes=(Get-Item $source).Length}
}
# Mutation proves the restore was effective, not just a successful connection.
Read-Scalar "UPDATE studio_brand SET english_name='RESTORE_DRILL_MUTATION';"|Out-Null
if((Read-Scalar 'SELECT english_name FROM studio_brand LIMIT 1;') -ne 'RESTORE_DRILL_MUTATION'){throw 'Drill mutation failed'}
Invoke-Client 'mysql.exe' $database ([IO.File]::ReadAllText($dump,[Text.Encoding]::UTF8))|Out-Null
if((Read-Scalar 'SELECT english_name FROM studio_brand ORDER BY release_id;') -ne $before){throw ('Restored content differs; backup preserved at '+$backup)}
if((Read-Scalar 'SELECT (SELECT COUNT(*) FROM public_work),(SELECT COUNT(*) FROM media_variant),(SELECT COUNT(*) FROM flyway_schema_history);') -ne $counts){throw 'Restored row counts differ'}
$record=@{schema=$database;scope='isolated local integration drill, not production disaster recovery';dumpSha256=(Get-FileHash $dump -Algorithm SHA256).Hash.ToLowerInvariant();objects=$manifest;restoredMedia=$restored}
[IO.File]::WriteAllText((Join-Path $backup 'manifest.json'),($record|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
Write-Output ('BACKUP_RESTORE_PASS schema='+$database+' objects='+$manifest.Count+' mutation-reversed; backup='+$backup)
$credential=$null
