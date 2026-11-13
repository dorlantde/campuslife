# Runs only the owned deployment database compatibility fixture. No developer config is read.
param([ValidateSet('All','Startup')][string]$Phase='All')
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskJavaHome=Get-ChildItem -LiteralPath (Join-Path $taskRoot '.tools/java21') -Directory |
 Where-Object {Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe')} | Sort-Object Name -Descending | Select-Object -First 1
if(-not $taskJavaHome){throw 'The project Java 21 toolchain is required'}
$taskSavedJava=$env:JAVA_HOME;$taskSavedPath=$env:PATH
try {
 $env:JAVA_HOME=$taskJavaHome.FullName;$env:PATH=(Join-Path $taskJavaHome.FullName 'bin')+[IO.Path]::PathSeparator+$taskSavedPath
 & python (Join-Path $PSScriptRoot 'mysql84-fixture.py') --phase $Phase
 $taskResult=$LASTEXITCODE
}finally{$env:JAVA_HOME=$taskSavedJava;$env:PATH=$taskSavedPath}
exit $taskResult
