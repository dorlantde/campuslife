param([ValidateSet('All','Native','Business','Regression')][string]$Phase='All')
$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'test_deploy_storage.py') --phase $Phase
exit $LASTEXITCODE
