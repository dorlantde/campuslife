# Run this yourself in a local interactive terminal. No password is written to a file or command argument.
$ErrorActionPreference = 'Stop'
$taskMysql = Get-Command mysql -ErrorAction Stop
$taskOriginalEncoding = $OutputEncoding
$taskSecurePassword = Read-Host 'Set the new campuslife database account password (at least 12 characters)' -AsSecureString
$taskPasswordPointer = [IntPtr]::Zero
$taskPlainPassword = $null
$taskSql = $null
try {
    if ($taskSecurePassword.Length -lt 12) { throw 'Use at least 12 characters and follow your MySQL password policy.' }
    $taskPasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecurePassword)
    $taskPlainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPasswordPointer)
    $taskEscapedPassword = $taskPlainPassword.Replace("'", "''")
    $taskSql = @"
SET SESSION sql_mode = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION,NO_BACKSLASH_ESCAPES';
CREATE DATABASE campuslife CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'campuslife'@'127.0.0.1' IDENTIFIED BY '$taskEscapedPassword';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON campuslife.* TO 'campuslife'@'127.0.0.1';
"@
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    Write-Host 'Next, mysql will ask for your existing root password. Enter it locally.'
    # Batch stdin carries SQL; mysql --password reads its password from the local console.
    $taskOutput = $taskSql | & $taskMysql.Source --protocol=TCP --host=127.0.0.1 --port=3306 --user=root --password --default-character-set=utf8mb4 --batch 2>&1
    if ($LASTEXITCODE -ne 0) {
        $taskErrorCode = [regex]::Match(($taskOutput | Out-String), 'ERROR (\d+)').Groups[1].Value
        throw "MySQL setup failed (error $taskErrorCode). Existing resources are never overwritten; inspect the setup guide before retrying."
    }
    Write-Host 'Created campuslife database and loopback-only account. Run scripts/database.ps1 -Action Check next.'
} finally {
    if ($taskPasswordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPasswordPointer) }
    $taskSecurePassword.Dispose()
    $taskPlainPassword = $null
    $taskEscapedPassword = $null
    $taskSql = $null
    $taskOutput = $null
    $OutputEncoding = $taskOriginalEncoding
}
