param(
    [ValidateSet('Install','Test','Build','Run','Preview')][string]$Action = 'Run',
    [string]$ApiTarget = 'http://127.0.0.1:8080'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskNodeDir = Join-Path $taskRoot '.tools/node/node-v24.22.2-win-x64'
$taskNpm = Join-Path $taskNodeDir 'npm.cmd'
if (-not (Test-Path -LiteralPath $taskNpm)) { throw 'Project Node 24.22.2 is missing. See docs/frontend.md for official download and checksum instructions.' }
$taskUri = [Uri]$ApiTarget
if ($taskUri.Scheme -ne 'http' -or $taskUri.Host -ne '127.0.0.1' -or $taskUri.UserInfo -or $taskUri.AbsolutePath -ne '/' -or $taskUri.Query -or $taskUri.Fragment) { throw 'ApiTarget must be an HTTP loopback origin.' }
$taskOldPath = $env:PATH
$taskOldTarget = $env:CAMPUSLIFE_API_TARGET
$taskExitCode = 1
Push-Location -LiteralPath (Join-Path $taskRoot 'frontend')
try {
    $env:PATH = $taskNodeDir + [IO.Path]::PathSeparator + $taskOldPath
    $env:CAMPUSLIFE_API_TARGET = $ApiTarget
    $taskVersion = (& (Join-Path $taskNodeDir 'node.exe') --version | Out-String).Trim()
    if ($taskVersion -ne 'v24.22.2') { throw 'Unexpected project Node version.' }
    $taskArgs = @(switch ($Action) { 'Install' { @('ci','--no-fund','--cache',(Join-Path $taskRoot '.cache/npm')) }; 'Test' { @('test') }; 'Build' { @('run','build') }; 'Run' { @('run','dev') }; 'Preview' { @('run','preview') } })
    & $taskNpm @taskArgs
    $taskExitCode = $LASTEXITCODE
} finally {
    $env:PATH = $taskOldPath
    $env:CAMPUSLIFE_API_TARGET = $taskOldTarget
    Pop-Location
}
exit $taskExitCode
