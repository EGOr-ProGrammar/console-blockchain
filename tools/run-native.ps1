#Requires -Version 5.1
param(
    [string]$Executable = (Join-Path $PSScriptRoot '..\dist\ConsoleBlockchain.exe'),
    [string[]]$ArgumentList = @()
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
    throw "EXE not found: $Executable. Build it with tools/build.ps1."
}
$resolvedExecutable = (Resolve-Path -LiteralPath $Executable).Path
$previous = @{ NO_COLOR = $env:NO_COLOR; COLORTERM = $env:COLORTERM }
$process = New-Object Diagnostics.Process
$started = $false
try {
    $env:NO_COLOR = $null
    $env:COLORTERM = 'truecolor'
    # Windows CRT quoting preserves empty args, embedded quotes and trailing backslashes.
    # The PS 5.1 call operator loses these when constructing a native command line.
    $quotedArguments = foreach ($argument in $ArgumentList) {
        '"' + [regex]::Replace($argument, '(\\*)("|$)', {
            param($match)
            $slashes = $match.Groups[1].Value
            if ($match.Groups[2].Value -eq '"') { $slashes + $slashes + '\"' } else { $slashes + $slashes }
        }) + '"'
    }
    $process.StartInfo = New-Object Diagnostics.ProcessStartInfo
    $process.StartInfo.FileName = $resolvedExecutable
    $process.StartInfo.Arguments = $quotedArguments -join ' '
    $process.StartInfo.WorkingDirectory = (Get-Location).Path
    $process.StartInfo.UseShellExecute = $false
    # Inherit console handles: redirecting them would prevent Mosaic from binding to a TTY.
    $started = $process.Start()
    if (-not $started) { throw "Could not start $resolvedExecutable" }
    $process.WaitForExit()
    $applicationExitCode = $process.ExitCode
} finally {
    if ($started -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
    $process.Dispose()
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
exit $applicationExitCode
