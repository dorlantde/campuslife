# Starts an isolated native MySQL + real backend + Vite for browser checks. No user service or data is touched.
param([ValidateSet('Start','Status','Stop')][string]$Action='Status',[switch]$DropFirstPrintResponse)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskParent=Join-Path $taskRoot '.cache/frontend-it'
$taskPointer=Join-Path $taskParent 'current.json'
function Get-Preview {
    if(-not(Test-Path -LiteralPath $taskPointer)){throw 'No browser fixture is recorded.'}
    $taskRecord=Get-Content -LiteralPath $taskPointer -Raw | ConvertFrom-Json
    $taskFolder=[IO.Path]::GetFullPath($taskRecord.folder)
    if((Split-Path -Parent $taskFolder) -ne $taskParent -or (Split-Path -Leaf $taskFolder) -notmatch '^[a-f0-9]{32}$'){throw 'Invalid fixture folder.'}
    $taskRecord
}
if($Action -eq 'Start') {
    if(Test-Path -LiteralPath $taskPointer) {
        $taskPrevious=Get-Preview
        if(-not(Test-Path -LiteralPath (Join-Path $taskPrevious.folder 'completed.json'))){
            $taskPreviousReady=Join-Path $taskPrevious.folder 'ready.json'
            if(-not(Test-Path -LiteralPath (Join-Path $taskPrevious.folder 'failed.txt')) -or -not(Test-Path -LiteralPath $taskPreviousReady)){throw 'A previous fixture may still be running. Use Status/Stop first.'}
            if(Get-Process -Id $taskPrevious.runnerPid -ErrorAction SilentlyContinue){throw 'The previous fixture runner has not exited.'}
            if($taskPrevious.vitePid -gt 0 -and (Get-Process -Id $taskPrevious.vitePid -ErrorAction SilentlyContinue)){throw 'The previous Vite process has not exited.'}
            $taskOldData=[IO.Path]::GetFullPath((Get-Content -LiteralPath $taskPreviousReady -Raw | ConvertFrom-Json).fixtureDataDir).TrimEnd('\','/')
            $taskOldFixture=Split-Path -Parent $taskOldData
            if((Split-Path -Parent $taskOldFixture) -ne (Join-Path $taskRoot '.cache/mysql-it') -or (Split-Path -Leaf $taskOldFixture) -notmatch '^[a-f0-9]{32}$' -or (Split-Path -Leaf $taskOldData) -ne 'data'){throw 'Cannot validate the previous MySQL fixture.'}
            $taskOldWorkers=@(Get-CimInstance Win32_Process -Filter "Name = 'mysqld.exe'" | Where-Object { $_.CommandLine -match ([regex]::Escape('--datadir='+$taskOldData)+'(?=["\s]|$)') })
            if($taskOldWorkers.Count -gt 0){throw 'The previous owned MySQL instance is still running.'}
            Write-Host ('Previous failed fixture is stopped; diagnostics remain: '+$taskPrevious.folder)
        }
    }
    if(Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue){throw 'Frontend port 5173 is already occupied.'}
    New-Item -ItemType Directory -Force -Path $taskParent | Out-Null
    $taskFolder=Join-Path $taskParent ([Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskFolder | Out-Null
    $taskRunner=Join-Path $taskFolder 'runner.ps1'
    @'
param([string]$Root,[string]$Output)
$ErrorActionPreference='Stop'
$env:CAMPUSLIFE_FRONTEND_IT='isolated-browser-fixture'
$env:CAMPUSLIFE_FRONTEND_OUTPUT=$Output
$env:RATING_CACHE_ENABLED='false'
$env:MAVEN_ARGS='-Dit.test=FrontendBrowserFixture'
try {
    # OS-level stdout/stderr files are configured by Start-Process below. PowerShell 5.1's
    # *> redirection turns native Java warnings into terminating NativeCommandError records.
    & (Join-Path $Root 'scripts/test-mysql.ps1') -StartupTimeoutSeconds 90
    if($LASTEXITCODE -ne 0){throw 'Isolated backend check failed.'}
    [pscustomobject]@{finishedAt=[DateTime]::UtcNow.ToString('o');mysqlCleaned=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Output 'completed.json') -Encoding UTF8
} catch {
    $_.Exception.Message | Set-Content -LiteralPath (Join-Path $Output 'failed.txt') -Encoding UTF8
    exit 1
}
'@ | Set-Content -LiteralPath $taskRunner -Encoding UTF8
    $taskPowerShell=Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $taskProcess=Start-Process -FilePath $taskPowerShell -WindowStyle Hidden -PassThru -WorkingDirectory $taskRoot -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$taskRunner+'"'),'-Root',('"'+$taskRoot+'"'),'-Output',('"'+$taskFolder+'"')) -RedirectStandardOutput (Join-Path $taskFolder 'backend.log') -RedirectStandardError (Join-Path $taskFolder 'backend.stderr.log')
    $taskRecord=[pscustomobject]@{folder=$taskFolder;runnerPid=$taskProcess.Id;vitePid=0;viteStartedAt=''}
    $taskRecord | ConvertTo-Json | Set-Content -LiteralPath $taskPointer -Encoding UTF8
    $taskDeadline=[DateTime]::UtcNow.AddMinutes(4)
    $taskReady=Join-Path $taskFolder 'ready.json'
    while(-not(Test-Path -LiteralPath $taskReady)) {
        if($taskProcess.HasExited -or [DateTime]::UtcNow -gt $taskDeadline){throw "Fixture not ready. Inspect $taskFolder and use Stop for cleanup."}
        Start-Sleep -Milliseconds 500
    }
    $taskPort=(Get-Content -LiteralPath $taskReady -Raw | ConvertFrom-Json).backendPort
    if([string]$taskPort -notmatch '^\d{1,5}$'){throw 'Invalid backend port.'}
    $taskOldTarget=$env:CAMPUSLIFE_API_TARGET
    $taskOldFault=$env:CAMPUSLIFE_FRONTEND_FAULT_OUTPUT
    try {
        $env:CAMPUSLIFE_API_TARGET='http://127.0.0.1:'+$taskPort
        if($DropFirstPrintResponse){$env:CAMPUSLIFE_FRONTEND_FAULT_OUTPUT=$taskFolder}else{Remove-Item Env:CAMPUSLIFE_FRONTEND_FAULT_OUTPUT -ErrorAction SilentlyContinue}
        $taskNode=Join-Path $taskRoot '.tools/node/node-v24.22.2-win-x64/node.exe'
        $taskViteScript=Join-Path $taskRoot 'frontend/node_modules/vite/bin/vite.js'
        $taskVite=Start-Process -FilePath $taskNode -WindowStyle Hidden -PassThru -WorkingDirectory (Join-Path $taskRoot 'frontend') -ArgumentList @(('"'+$taskViteScript+'"')) -RedirectStandardOutput (Join-Path $taskFolder 'vite.stdout.log') -RedirectStandardError (Join-Path $taskFolder 'vite.stderr.log')
        $taskRecord.vitePid=$taskVite.Id
        $taskRecord.viteStartedAt=$taskVite.StartTime.ToUniversalTime().ToString('o')
        $taskRecord | ConvertTo-Json | Set-Content -LiteralPath $taskPointer -Encoding UTF8
    } finally { $env:CAMPUSLIFE_API_TARGET=$taskOldTarget; $env:CAMPUSLIFE_FRONTEND_FAULT_OUTPUT=$taskOldFault }
    Write-Host "Disposable browser fixture: http://127.0.0.1:5173 ; evidence: $taskFolder"
    Write-Host 'Use -Action Stop after browser checks; this fixture expires after 90 minutes.'
} else {
    $taskRecord=Get-Preview
    if($Action -eq 'Stop') {
        New-Item -ItemType File -Path (Join-Path $taskRecord.folder 'stop') -Force | Out-Null
        if($taskRecord.vitePid -gt 0) {
            $taskNode=Get-CimInstance Win32_Process -Filter ('ProcessId = '+[int]$taskRecord.vitePid)
            if($taskNode) {
                $taskExpected=Join-Path $taskRoot 'frontend/node_modules/vite/bin/vite.js'
                if($taskNode.CommandLine -notlike ('*'+$taskExpected+'*') -or [Math]::Abs(($taskNode.CreationDate.ToUniversalTime()-([DateTimeOffset]$taskRecord.viteStartedAt).UtcDateTime).TotalMilliseconds) -gt 1){throw 'Refusing to stop an unverified Vite process.'}
                Stop-Process -Id $taskRecord.vitePid
            }
        }
        Write-Host 'Stop requested. Backend tests finish jar smoke and clean only their generated MySQL directory.'
    }
    Write-Host ('Fixture folder: '+$taskRecord.folder)
    Write-Host ('Backend ready: '+(Test-Path -LiteralPath (Join-Path $taskRecord.folder 'ready.json')))
    Write-Host ('MySQL cleanup complete: '+(Test-Path -LiteralPath (Join-Path $taskRecord.folder 'completed.json')))
    if(Test-Path -LiteralPath (Join-Path $taskRecord.folder 'failed.txt')){throw 'Fixture failed; inspect retained logs.'}
}
