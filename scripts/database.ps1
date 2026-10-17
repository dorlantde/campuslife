param(
    [ValidateSet('Check', 'Run')]
    [string]$Action = 'Check',
    [ValidateSet('127.0.0.1', 'localhost')]
    [string]$DatabaseHost = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$DatabasePort = 3306,
    [ValidatePattern('^campuslife(?:_[a-z0-9]+)?$')]
    [string]$DatabaseName = 'campuslife',
    [ValidatePattern('^[a-zA-Z0-9_]+$')]
    [string]$DatabaseUser = 'campuslife'
)

$ErrorActionPreference = 'Stop'
$taskOriginalEnvironment = @{}
foreach ($taskName in @('DB_HOST','DB_PORT','DB_NAME','DB_USERNAME','DB_PASSWORD','SPRING_PROFILES_ACTIVE')) {
    $taskOriginalEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}
$taskSecurePassword = $null
$taskPointer = [IntPtr]::Zero
$taskExitCode = 0
try {
    $env:DB_HOST = $DatabaseHost
    $env:DB_PORT = [string]$DatabasePort
    $env:DB_NAME = $DatabaseName
    $env:DB_USERNAME = $DatabaseUser
    $env:SPRING_PROFILES_ACTIVE = 'mysql'
    if ([string]::IsNullOrEmpty($env:DB_PASSWORD)) {
        $taskSecurePassword = Read-Host 'Enter the campuslife account password (not the root password)' -AsSecureString
        $taskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecurePassword)
        $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer)
    }
    if ([string]::IsNullOrEmpty($env:DB_PASSWORD)) { throw 'Database password cannot be empty.' }
    $taskDevAction = if ($Action -eq 'Check') { 'DatabaseCheck' } else { 'Run' }
    & (Join-Path $PSScriptRoot 'dev.ps1') -Action $taskDevAction
    $taskExitCode = $LASTEXITCODE
} finally {
    foreach ($taskName in $taskOriginalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($taskName, $taskOriginalEnvironment[$taskName], 'Process')
    }
    if ($taskPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer) }
    if ($taskSecurePassword) { $taskSecurePassword.Dispose() }
}
exit $taskExitCode
