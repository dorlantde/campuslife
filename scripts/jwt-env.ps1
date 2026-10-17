# Local development signing key. Never print the returned value or pass it on a command line.
function New-CampusLifeJwtSecret {
    $taskBytes = New-Object byte[] 32
    $taskRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $taskRandom.GetBytes($taskBytes)
        return [Convert]::ToBase64String($taskBytes)
    } finally {
        $taskRandom.Dispose()
        [Array]::Clear($taskBytes, 0, $taskBytes.Length)
    }
}

function Get-CampusLifeLocalJwtSecret {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)
    $taskSecretDirectory = Join-Path $ProjectRoot '.cache\secrets'
    $taskSecretPath = Join-Path $taskSecretDirectory 'jwt-secret.base64'
    New-Item -ItemType Directory -Path $taskSecretDirectory -Force | Out-Null
    if (-not [IO.File]::Exists($taskSecretPath)) {
        try {
            $taskStream = [IO.File]::Open($taskSecretPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            try {
                $taskSecretBytes = [Text.Encoding]::ASCII.GetBytes((New-CampusLifeJwtSecret))
                $taskStream.Write($taskSecretBytes, 0, $taskSecretBytes.Length)
            } finally { $taskStream.Dispose() }
        } catch [IO.IOException] {
            if (-not [IO.File]::Exists($taskSecretPath)) { throw 'Cannot create the local JWT key file.' }
        }
    }
    for ($taskAttempt = 0; $taskAttempt -lt 10; $taskAttempt++) {
        try {
            $taskSecret = [IO.File]::ReadAllText($taskSecretPath).Trim()
            if ([Convert]::FromBase64String($taskSecret).Length -lt 32) { throw 'Invalid local JWT key length.' }
            return $taskSecret
        } catch [IO.IOException] { Start-Sleep -Milliseconds 100 }
          catch { throw 'Invalid local JWT key file. Restore it or intentionally rotate it; never paste its contents into chat.' }
    }
    throw 'Local JWT key file is busy. Retry after the other process finishes.'
}
