# Project-local official Node.js runtime; does not install globally or change the user's PATH.
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskDir=Join-Path $taskRoot '.tools/node/node-v24.22.2-win-x64'
$taskNode=Join-Path $taskDir 'node.exe'
$taskNodeHash='1C331340FA6CAC5E4294725959070A143FA0507195A78AD77EED7943F719AAC9'
if(Test-Path -LiteralPath $taskNode) {
    if((Get-FileHash -LiteralPath $taskNode -Algorithm SHA256).Hash -ne $taskNodeHash){throw 'Existing Node executable does not match the verified project runtime.'}
    Write-Host 'Project Node 24.22.2 is already available.'
    exit 0
}
if(Test-Path -LiteralPath $taskDir){throw 'Incomplete runtime directory exists; inspect it before trying setup again.'}
$taskDownload=Join-Path $taskRoot '.cache/node-download'
New-Item -ItemType Directory -Path $taskDownload -Force | Out-Null
$taskArchive=Join-Path $taskDownload 'node-v24.22.2-win-x64.zip'
$taskExpected='CA1BFF5D8EAD13C9F2EA404A176DB46BD430704D5B5113734049FA365E7799E2'
# Pinned against https://nodejs.org/download/release/v24.22.2/SHASUMS256.txt on 2026-10-29.
if(-not(Test-Path -LiteralPath $taskArchive)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://nodejs.org/download/release/v24.22.2/node-v24.22.2-win-x64.zip' -OutFile $taskArchive
}
if((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash -ne $taskExpected){throw 'Official Node archive checksum mismatch; refusing to extract.'}
Expand-Archive -LiteralPath $taskArchive -DestinationPath (Join-Path $taskRoot '.tools/node')
if((Get-FileHash -LiteralPath $taskNode -Algorithm SHA256).Hash -ne $taskNodeHash){throw 'Unexpected extracted Node executable.'}
Write-Host 'Verified project Node 24.22.2 with npm is ready. Run scripts/frontend.ps1 -Action Install next.'
