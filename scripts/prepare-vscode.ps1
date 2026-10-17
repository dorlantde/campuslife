# Prepare local signing configuration before VS Code launches the application.
# No database credentials are read or persisted by this script.
param([string]$ProjectRoot = (Join-Path $PSScriptRoot '..'))

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
. (Join-Path $PSScriptRoot 'jwt-env.ps1')
$taskSecret = Get-CampusLifeLocalJwtSecret -ProjectRoot $taskRoot
$taskConfigDirectory = Join-Path $taskRoot '.cache\vscode'
New-Item -ItemType Directory -Path $taskConfigDirectory -Force | Out-Null
$taskConfigPath = Join-Path $taskConfigDirectory 'runtime.properties'
# Base64 has no characters that require Java properties escaping.
[IO.File]::WriteAllText($taskConfigPath, ('JWT_SECRET_BASE64=' + $taskSecret + [Environment]::NewLine), [Text.Encoding]::ASCII)
$taskSecret = $null
Write-Host 'VS Code local JWT configuration is ready. No database password was saved.'
