param(
    [string]$Executable = (Join-Path $PSScriptRoot '..\build\native\nativeCompile\ConsoleBlockchain.exe'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\build\native-verification'),
    [ValidateRange(1, 3600)][int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
$sourceExecutable = (Resolve-Path -LiteralPath $Executable).Path
$runDirectory = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) ([Guid]::NewGuid().ToString())
$isolated = Join-Path $runDirectory 'isolated'
$sessionsRoot = Join-Path $runDirectory 'sessions'
New-Item -ItemType Directory -Force -Path $isolated | Out-Null
$copy = Join-Path $isolated 'ConsoleBlockchain.exe'
Copy-Item -LiteralPath $sourceExecutable -Destination $copy

$previousEnvironment = @{ PATH = $env:PATH; JAVA_HOME = $env:JAVA_HOME; GRAALVM_HOME = $env:GRAALVM_HOME }
Push-Location $isolated
try {
    $env:JAVA_HOME = $null
    $env:GRAALVM_HOME = $null
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"
    if (Get-Command java -ErrorAction SilentlyContinue) { throw 'java is still visible on PATH' }
    foreach ($mode in @('--demo', '--experiments')) {
        $stdoutLog = Join-Path $runDirectory ($mode.TrimStart('-') + '.stdout.log')
        $stderrLog = Join-Path $runDirectory ($mode.TrimStart('-') + '.stderr.log')
        $process = New-Object Diagnostics.Process
        $process.StartInfo = New-Object Diagnostics.ProcessStartInfo
        $process.StartInfo.FileName = $copy
        $process.StartInfo.Arguments = "$mode --output-dir `"$sessionsRoot`""
        $process.StartInfo.WorkingDirectory = $isolated
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        $process.StartInfo.StandardOutputEncoding = [Text.Encoding]::UTF8
        $process.StartInfo.StandardErrorEncoding = [Text.Encoding]::UTF8
        try {
            if (-not $process.Start()) { throw "Could not start $copy" }
            # Drain both pipes concurrently so a full stderr pipe cannot deadlock stdout.
            $stdout = $process.StandardOutput.ReadToEndAsync()
            $stderr = $process.StandardError.ReadToEndAsync()
            $timedOut = -not $process.WaitForExit($TimeoutSeconds * 1000)
            if ($timedOut) {
                if (-not $process.HasExited) { $process.Kill() }
                $process.WaitForExit()
            }
            [IO.File]::WriteAllText($stdoutLog, $stdout.GetAwaiter().GetResult())
            [IO.File]::WriteAllText($stderrLog, $stderr.GetAwaiter().GetResult())
            Write-Host ([IO.File]::ReadAllText($stdoutLog))
            if ((Get-Item -LiteralPath $stderrLog).Length) { Write-Host ([IO.File]::ReadAllText($stderrLog)) }
            if ($timedOut) { throw "$mode timed out after $TimeoutSeconds seconds. Logs: $runDirectory" }
            if ($process.ExitCode -ne 0) { throw "$mode exited with $($process.ExitCode). Logs: $runDirectory" }
        } finally {
            $process.Dispose()
        }
    }
} finally {
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    Pop-Location
}

$sessions = @(Get-ChildItem -LiteralPath $sessionsRoot -Directory)
if ($sessions.Count -ne 2) { throw 'Expected two separate sessions' }
$required = @('metadata.json', 'events.jsonl', 'journal.txt', 'summary.md', 'candidates.csv', 'balances.csv', 'experiments.csv')
foreach ($session in $sessions) {
    foreach ($name in $required) {
        $file = Join-Path $session.FullName $name
        if (-not (Test-Path -LiteralPath $file)) { throw "Missing $file" }
        $content = [IO.File]::ReadAllText($file)
        if (-not $content.Trim() -or $content -match '(?i)private_key|\bseed\b') { throw "Invalid public material: $file" }
        if ($name.EndsWith('.csv')) {
            $bytes = [IO.File]::ReadAllBytes($file)
            if ($bytes.Length -lt 3 -or $bytes[0] -ne 239 -or $bytes[1] -ne 187 -or $bytes[2] -ne 191) { throw "Missing UTF8 BOM: $file" }
        }
    }
    $events = @(Get-Content -LiteralPath (Join-Path $session.FullName 'events.jsonl') -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
    $experiments = @($events | Where-Object operation -eq 'experiment')
    if ($experiments.Count -ne 13 -or @($experiments | Where-Object { $_.result.passed -ne $true }).Count -ne 0) { throw 'An experiment failed or is missing' }
    $accepted = @($events | Where-Object { $_.operation -eq 'consensus' -and $_.result.status -eq 'ACCEPTED' })
    if ($accepted.Count -ne 1) { throw 'Expected exactly one accepted block' }
    $state = $accepted[0].result.after
    $accounts = @($state.accounts | Sort-Object name)
    if (($accounts.balance -join '/') -ne '95/90/115' -or ($accounts.next_nonce -join '/') -ne '1/1/1' -or $state.pending.Count -ne 0 -or $state.chain.Count -ne 2) {
        throw 'Control state differs'
    }
}
if (@(Get-ChildItem -LiteralPath $isolated -File).Count -ne 1) { throw 'Standalone run created extra files beside the EXE' }
Write-Host "PASS standalone EXE, no Java, two scenarios, 13 experiments each, complete public materials: $runDirectory"
