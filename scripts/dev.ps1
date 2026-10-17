param(
    [ValidateSet('Test', 'Build', 'Run', 'DatabaseCheck')]
    [string]$Action = 'Test'
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskOriginalJavaHome = $env:JAVA_HOME
$taskOriginalPath = $env:PATH
$taskOriginalJwtSecret = $env:JWT_SECRET_BASE64
$taskExitCode = 0

Push-Location -LiteralPath $taskRoot
try {
    if ($Action -in @('Run', 'DatabaseCheck') -and $env:SPRING_PROFILES_ACTIVE -ne 'web-only' `
        -and [string]::IsNullOrEmpty($env:JWT_SECRET_BASE64)) {
        . (Join-Path $PSScriptRoot 'jwt-env.ps1')
        $env:JWT_SECRET_BASE64 = Get-CampusLifeLocalJwtSecret -ProjectRoot $taskRoot
    }
    $taskLocalJavaRoot = Join-Path $taskRoot '.tools\java21'
    $taskLocalJdk = Get-ChildItem -LiteralPath $taskLocalJavaRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($taskLocalJdk) {
        $env:JAVA_HOME = $taskLocalJdk.FullName
        $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $taskOriginalPath
    } elseif ($env:JAVA_HOME) {
        $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $taskOriginalPath
    }

    $taskJava = Get-Command java -ErrorAction Stop
    # --version writes to stdout; -version becomes NativeCommandError on Windows PowerShell 5.1.
    $taskJavaVersion = (& $taskJava.Source --version | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot query the Java version.' }
    if ($taskJavaVersion -notmatch '(?m)^(?:openjdk|java) 21(?:[.\s]|$)') {
        throw 'Java 21 is required. Set JAVA_HOME to a JDK 21, or place a JDK 21 under .tools/java21/<jdk-directory>.'
    }
    $taskMaven = Get-Command mvn -ErrorAction Stop
    Write-Host "Java: $($taskJava.Source)"
    Write-Host "Maven: $($taskMaven.Source)"
    # Preserve an array even for Run's single goal; scalar string splatting splits it into characters.
    $taskMavenArgs = @(switch ($Action) {
        'Test' { @('-B', 'test') }
        'Build' { @('-B', 'verify') }
        'Run' { @('spring-boot:run') }
        'DatabaseCheck' { @('-B', '-Pmysql-check', 'verify') }
    })
    & $taskMaven.Source @taskMavenArgs
    $taskExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $taskOriginalJavaHome
    $env:PATH = $taskOriginalPath
    $env:JWT_SECRET_BASE64 = $taskOriginalJwtSecret
    Pop-Location
}
exit $taskExitCode
