param([Parameter(Mandatory=$true)][string]$MySqlClientPath)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Local-Credentials.ps1"
if (-not $env:SOUTHLEAF_ADMIN_USERNAME -or -not $env:SOUTHLEAF_ADMIN_PASSWORD) { throw 'Explicit temporary admin environment variables are required' }
if ($env:SOUTHLEAF_ADMIN_USERNAME -notmatch '^[A-Za-z0-9_]+$') { throw 'Invalid admin username' }
$secrets = New-Object 'System.Collections.Generic.List[string]'
$secrets.Add($env:SOUTHLEAF_ADMIN_PASSWORD)
function Invoke-LocalMySql([string]$Sql) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $MySqlClientPath
    $start.Arguments = "--host=127.0.0.1 --port=3306 --user=$env:SOUTHLEAF_ADMIN_USERNAME --batch --skip-column-names --default-character-set=utf8mb4"
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.EnvironmentVariables['MYSQL_PWD'] = $env:SOUTHLEAF_ADMIN_PASSWORD
    $process = [System.Diagnostics.Process]::Start($start)
    $process.StandardInput.WriteLine($Sql); $process.StandardInput.Close()
    $output = $process.StandardOutput.ReadToEnd(); $errorText = $process.StandardError.ReadToEnd(); $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        foreach($secret in $secrets) { $errorText = $errorText.Replace($secret,'[REDACTED]') }
        throw "Local database provisioning failed: $errorText"
    }
    return $output.Trim()
}
$existing = Invoke-LocalMySql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ('southleaf_public','southleaf_public_it');"
if ($existing -ne '0') { throw 'New target database already exists. Refusing to reset or merge.' }
$existingUsers = Invoke-LocalMySql "SELECT COUNT(*) FROM mysql.user WHERE User IN ('southleaf_reader','southleaf_migrator','southleaf_it');"
if ($existingUsers -ne '0') { throw 'Target database account already exists. Refusing to overwrite.' }
$roles = @(
    @{ Role='Reader'; Username='southleaf_reader'; Schema='southleaf_public'; Privileges='SELECT' },
    @{ Role='Migrator'; Username='southleaf_migrator'; Schema='southleaf_public'; Privileges='SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES,LOCK TABLES' },
    @{ Role='IntegrationTest'; Username='southleaf_it'; Schema='southleaf_public_it'; Privileges='SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES,LOCK TABLES' }
)
$sql = "CREATE DATABASE southleaf_public CHARACTER SET utf8mb4 COLLATE utf8mb4_bin; CREATE DATABASE southleaf_public_it CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;"
foreach ($role in $roles) {
    $bytes=New-Object byte[] 36; $rng=[System.Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($bytes); $rng.Dispose()
    $password=[Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=')
    $role.Password=$password; $secrets.Add($password)
    $sql += " CREATE USER '$($role.Username)'@'127.0.0.1' IDENTIFIED BY '$password'; GRANT $($role.Privileges) ON $($role.Schema).* TO '$($role.Username)'@'127.0.0.1';"
}
$null = Invoke-LocalMySql $sql
foreach($role in $roles) { [SouthLeaf.CredentialStore]::Save("SouthLeaf/PublicDatabase/$($role.Role)",$role.Username,$role.Password) }
Write-Output 'PUBLIC_DATABASE_PROVISIONED schemas=2 accounts=3; old database unchanged; credentials saved to current Windows user Credential Manager'
