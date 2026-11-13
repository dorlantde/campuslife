param([ValidateSet('Inspect','Replay')][string]$Action='Inspect',[Parameter(Mandatory=$true)][string]$MessageId,[string]$Reason='')
$ErrorActionPreference='Stop'
if($MessageId -notmatch '^[a-f0-9]{32}$'){throw 'MessageId must be a 32-character hex identity.'}
if($Action -eq 'Replay' -and [string]::IsNullOrWhiteSpace($Reason)){throw 'Replay requires a reason.'}
$taskNames=@('DB_PASSWORD','MESSAGE_ACTION','MESSAGE_ID','MESSAGE_REASON','MAVEN_ARGS');$taskSaved=@{}
foreach($taskName in $taskNames){$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
 if(-not $env:DB_PASSWORD){$taskPassword=Read-Host 'Enter the campuslife database account password locally' -AsSecureString;$env:DB_PASSWORD=[Net.NetworkCredential]::new('',$taskPassword).Password}
 $env:MESSAGE_ACTION=$Action;$env:MESSAGE_ID=$MessageId;$env:MESSAGE_REASON=$Reason;$env:MAVEN_ARGS='-Dcampuslife.main-class=com.campuslife.messaging.bootstrap.MessageMaintenance'
 & (Join-Path $PSScriptRoot 'dev.ps1') -Action Run
 if($LASTEXITCODE -ne 0){throw 'Local message maintenance failed.'}
}finally{foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}}
