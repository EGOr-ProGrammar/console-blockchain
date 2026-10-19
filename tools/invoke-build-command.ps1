param([Parameter(Mandatory = $true)][string]$FilePath, [string[]]$ArgumentList = @())

# PowerShell 5.1 transcripts miss native console output unless it passes through the host.
# Native stderr is diagnostic text; success is determined by the process exit code.
$ErrorActionPreference = 'Stop'
$command = (Get-Command -Name $FilePath -CommandType Application -ErrorAction Stop).Source
$global:LASTEXITCODE = $null
try {
    $ErrorActionPreference = 'Continue'
    & $command @ArgumentList 2>&1 | ForEach-Object { Write-Host $_.ToString() }
    $commandExitCode = $global:LASTEXITCODE
} finally {
    $ErrorActionPreference = 'Stop'
}
if ($null -eq $commandExitCode) { throw "Could not execute $FilePath" }
exit $commandExitCode
