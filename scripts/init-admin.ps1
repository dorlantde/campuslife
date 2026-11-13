# Explicit local business-admin creation; never stores credentials or changes existing accounts.
param(
    [ValidateSet('127.0.0.1', 'localhost')][string]$DatabaseHost = '127.0.0.1',
    [ValidateRange(1, 65535)][int]$DatabasePort = 3306,
    [ValidatePattern('^campuslife(?:_[a-z0-9]+)?$')][string]$DatabaseName = 'campuslife',
    [ValidatePattern('^[a-zA-Z0-9_]+$')][string]$DatabaseUser = 'campuslife'
)

$ErrorActionPreference = 'Stop'
$taskOriginal = @{}
foreach ($taskName in @('CAMPUSLIFE_ADMIN_USERNAME','CAMPUSLIFE_ADMIN_NICKNAME','CAMPUSLIFE_ADMIN_PASSWORD')) {
    $taskOriginal[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}
$taskPasswords = @()
$taskPointers = @()
$taskPlain = @()
$taskExitCode = 1
try {
    $env:CAMPUSLIFE_ADMIN_USERNAME = Read-Host 'New platform ADMIN username (not a MySQL user)'
    $env:CAMPUSLIFE_ADMIN_NICKNAME = Read-Host 'New platform ADMIN nickname'
    $taskPasswords += Read-Host 'New platform ADMIN password' -AsSecureString
    $taskPasswords += Read-Host 'Confirm new platform ADMIN password' -AsSecureString
    foreach ($taskPassword in $taskPasswords) {
        $taskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskPassword)
        $taskPointers += $taskPointer
        $taskPlain += [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer)
    }
    if ([string]::IsNullOrEmpty($taskPlain[0]) -or $taskPlain[0] -cne $taskPlain[1]) { throw 'Passwords are empty or do not match.' }
    $env:CAMPUSLIFE_ADMIN_PASSWORD = $taskPlain[0]
    & (Join-Path $PSScriptRoot 'database.ps1') -Action AdminInit -DatabaseHost $DatabaseHost `
        -DatabasePort $DatabasePort -DatabaseName $DatabaseName -DatabaseUser $DatabaseUser
    $taskExitCode = $LASTEXITCODE
} finally {
    foreach ($taskName in $taskOriginal.Keys) { [Environment]::SetEnvironmentVariable($taskName,$taskOriginal[$taskName],'Process') }
    foreach ($taskPointer in $taskPointers) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer) }
    foreach ($taskPassword in $taskPasswords) { $taskPassword.Dispose() }
    $taskPlain = $null
}
exit $taskExitCode
