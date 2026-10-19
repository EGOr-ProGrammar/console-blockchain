param([ValidateSet('All', 'Installer', 'Build', 'Run', 'Verify')][string]$Group = 'All')

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repository = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$testRoot = Join-Path $repository ('.gradle\tool-tests\' + [Guid]::NewGuid() + ' with spaces')
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$failures = 0
$passed = 0

function Assert($condition, [string]$message) {
    if (-not $condition) { throw $message }
}

function Test-Case([string]$name, [scriptblock]$action) {
    try {
        & $action
        $script:passed++
        Write-Host "PASS $name"
    } catch {
        $script:failures++
        Write-Host "FAIL $name`: $_"
    }
}

function Write-File([string]$path, [string]$text) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
    if ($path.EndsWith('.bat')) { $text = $text.Replace("`r`n", "`n").Replace("`n", "`r`n") + "`r`n" }
    [IO.File]::WriteAllText($path, $text)
}

function New-Fixture([string]$name, [string[]]$scripts) {
    $root = Join-Path $testRoot $name
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'tools') | Out-Null
    foreach ($script in $scripts) {
        $source = Join-Path $repository "tools\$script"
        Assert (Test-Path -LiteralPath $source) "Missing implementation: $script"
        Copy-Item -LiteralPath $source -Destination (Join-Path $root "tools\$script")
    }
    return $root
}

if ($Group -in @('All', 'Installer')) {
    Test-Case 'toolchain selects pinned versions despite newer incomplete cache directories' {
        $root = New-Fixture 'pinned-toolchain' @('use-local-toolchain.ps1', 'native-toolchain-packages.json')
        $packages = Get-Content (Join-Path $root 'tools/native-toolchain-packages.json') -Raw | ConvertFrom-Json
        foreach ($package in $packages) {
            foreach ($required in $package.requiredFiles) { Write-File (Join-Path $root ('.gradle/native-tools/' + $package.destination + '/' + $required)) '' }
        }
        New-Item -ItemType Directory -Force -Path (Join-Path $root '.gradle/native-tools/portable-msvc/VC/Tools/MSVC/99.0'), (Join-Path $root '.gradle/native-tools/portable-sdk/Include/99.0') | Out-Null
        $before = @{ PATH = $env:PATH; INCLUDE = $env:INCLUDE; LIB = $env:LIB }
        try {
            . (Join-Path $root 'tools/use-local-toolchain.ps1')
            Assert ($env:PATH -match 'MSVC\\14\.44\.35207\\bin') 'Unpinned MSVC selected'
            Assert ($env:INCLUDE -match 'Include\\10\.0\.26100\.0\\ucrt') 'Unpinned SDK selected'
        } finally {
            foreach ($name in $before.Keys) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
        }
    }
    Test-Case 'installer downloads atomically, reuses cache and repairs extracted files' {
        $root = New-Fixture 'installer' @('install-local-toolchain.ps1')
        $fixtureArchive = Join-Path $root 'source.zip'
        $zip = [IO.Compression.ZipFile]::Open($fixtureArchive, 'Create')
        try {
            $writer = New-Object IO.StreamWriter($zip.CreateEntry('jdk/bin/java.exe').Open())
            try { $writer.Write('verified-java') } finally { $writer.Dispose() }
        } finally { $zip.Dispose() }
        $package = @{
            file = 'jdk.zip'; url = 'https://example.invalid/jdk.zip'
            sha256 = (Get-FileHash $fixtureArchive).Hash
            prefix = 'jdk/'; destination = 'jdk'; requiredFiles = @('bin/java.exe')
        }
        ConvertTo-Json -InputObject @($package) | Set-Content (Join-Path $root 'tools/native-toolchain-packages.json')
        $downloadCalls = New-Object 'Collections.Generic.List[string]'
        function Invoke-WebRequest($Uri, $OutFile, [switch]$UseBasicParsing) {
            Assert ($OutFile -ne (Join-Path $root '.gradle/native-tools/jdk.zip')) 'Download must not overwrite the cache before verification'
            $downloadCalls.Add($OutFile)
            Copy-Item -LiteralPath $fixtureArchive -Destination $OutFile
        }
        & (Join-Path $root 'tools/install-local-toolchain.ps1')
        $installed = Join-Path $root '.gradle/native-tools/jdk/bin/java.exe'
        Assert ([IO.File]::ReadAllText($installed) -eq 'verified-java') 'Downloaded archive was not installed'
        $unchanged = (Get-Item $installed).LastWriteTimeUtc
        & (Join-Path $root 'tools/install-local-toolchain.ps1')
        Assert ($downloadCalls.Count -eq 1) 'An intact cache was downloaded again'
        Assert ((Get-Item $installed).LastWriteTimeUtc -eq $unchanged) 'An intact installation was rewritten'
        [IO.File]::WriteAllText($installed, 'broken')
        & (Join-Path $root 'tools/install-local-toolchain.ps1')
        Assert ([IO.File]::ReadAllText($installed) -eq 'verified-java') 'Damaged installation was not repaired'
        Remove-Item -LiteralPath $installed
        & (Join-Path $root 'tools/install-local-toolchain.ps1')
        Assert (Test-Path -LiteralPath $installed) 'Incomplete installation was not repaired'
    }

    Test-Case 'installer rejects a corrupt download without publishing or extracting it' {
        $root = New-Fixture 'bad-download' @('install-local-toolchain.ps1')
        $package = @{ file = 'bad.zip'; url = 'https://example.invalid/bad.zip'; sha256 = ('0' * 64); prefix = 'jdk/'; destination = 'jdk' }
        ConvertTo-Json -InputObject @($package) | Set-Content (Join-Path $root 'tools/native-toolchain-packages.json')
        function Invoke-WebRequest($Uri, $OutFile, [switch]$UseBasicParsing) { [IO.File]::WriteAllText($OutFile, 'corrupt') }
        $message = ''
        try { & (Join-Path $root 'tools/install-local-toolchain.ps1') } catch { $message = $_.ToString() }
        Assert ($message -match 'SHA256') 'Expected a checksum error'
        Assert (-not (Test-Path (Join-Path $root '.gradle/native-tools/bad.zip'))) 'Corrupt download was published as a cached archive'
        Assert (-not (Test-Path (Join-Path $root '.gradle/native-tools/jdk'))) 'Corrupt download was extracted'
    }

    Test-Case 'installer reports a failed download and missing required archive content' {
        $root = New-Fixture 'missing-download' @('install-local-toolchain.ps1')
        $package = @{ file = 'missing.zip'; url = 'https://example.invalid/missing.zip'; sha256 = ('0' * 64); prefix = 'jdk/'; destination = 'jdk' }
        ConvertTo-Json -InputObject @($package) | Set-Content (Join-Path $root 'tools/native-toolchain-packages.json')
        function Invoke-WebRequest($Uri, $OutFile, [switch]$UseBasicParsing) { throw 'Network unavailable' }
        $message = ''
        try { & (Join-Path $root 'tools/install-local-toolchain.ps1') } catch { $message = $_.ToString() }
        Assert ($message -match 'Network unavailable') 'Download error was swallowed'
        $cache = Join-Path $root '.gradle/native-tools/missing.zip'
        $zip = [IO.Compression.ZipFile]::Open($cache, 'Create')
        try { $null = $zip.CreateEntry('jdk/README.txt') } finally { $zip.Dispose() }
        $package.sha256 = (Get-FileHash $cache).Hash
        $package.requiredFiles = @('bin/java.exe')
        ConvertTo-Json -InputObject @($package) | Set-Content (Join-Path $root 'tools/native-toolchain-packages.json')
        $message = ''
        try { & (Join-Path $root 'tools/install-local-toolchain.ps1') } catch { $message = $_.ToString() }
        Assert ($message -match 'java.exe') 'Missing required files were accepted'
    }
}

if ($Group -in @('All', 'Build', 'Run', 'Verify')) {
    # Real Windows console executable, used instead of slow native compilation in boundary tests.
    $sample = Join-Path $testRoot 'sample.exe'
    Add-Type -OutputAssembly $sample -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.IO;
using System.Threading;
public static class ToolSample {
    public static int Main(string[] args) {
        if (args.Length > 0 && args[0] == "--demo") {
            string pid = Environment.GetEnvironmentVariable("TOOL_TEST_PID_FILE");
            if (pid != null) File.WriteAllText(pid, System.Diagnostics.Process.GetCurrentProcess().Id.ToString());
            Console.Error.WriteLine("sample diagnostics");
            if (Environment.GetEnvironmentVariable("TOOL_TEST_HANG") == "1") Thread.Sleep(30000);
            return 13;
        }
        string report = "NO_COLOR=" + Environment.GetEnvironmentVariable("NO_COLOR") + "\n";
        report += "COLORTERM=" + Environment.GetEnvironmentVariable("COLORTERM") + "\n";
        foreach (string arg in args) report += "ARG=" + arg + "\n";
        string reportFile = Environment.GetEnvironmentVariable("TOOL_TEST_REPORT");
        if (reportFile != null) File.WriteAllText(reportFile, report); else Console.Write(report);
        return args.Length > 0 && args[0] == "exit23" ? 23 : 0;
    }
}
'@
}

if ($Group -in @('All', 'Run')) {
    Test-Case 'color launcher forwards spaced arguments and exit code, restores environment on success and failure' {
        $root = New-Fixture 'runner' @('run-native.ps1')
        $before = @{ NO_COLOR = $env:NO_COLOR; COLORTERM = $env:COLORTERM; TOOL_TEST_REPORT = $env:TOOL_TEST_REPORT }
        try {
            $env:NO_COLOR = '1'; $env:COLORTERM = 'original'
            $env:TOOL_TEST_REPORT = Join-Path $root 'report.txt'
            & (Join-Path $root 'tools/run-native.ps1') -Executable $sample -ArgumentList @('exit23', 'space value') | Out-Null
            Assert ($LASTEXITCODE -eq 23) 'Application exit code was lost'
            $output = [IO.File]::ReadAllText($env:TOOL_TEST_REPORT)
            Assert ($output -match '(?m)^NO_COLOR=\r?$') 'NO_COLOR reached the color launch'
            Assert ($output -match 'COLORTERM=truecolor') 'Truecolor was not requested'
            Assert ($output -match 'ARG=space value') 'Argument with spaces was split'
            Assert ($env:NO_COLOR -eq '1' -and $env:COLORTERM -eq 'original') 'Caller environment was changed'
            try { & (Join-Path $root 'tools/run-native.ps1') -Executable (Join-Path $root 'missing.exe') } catch { }
            Assert ($env:NO_COLOR -eq '1' -and $env:COLORTERM -eq 'original') 'Failure changed the environment'
        } finally {
            foreach ($name in $before.Keys) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
        }
    }
    Test-Case 'color launcher preserves trailing backslashes, empty arguments and quotes' {
        $root = New-Fixture 'runner-quoting' @('run-native.ps1')
        $before = $env:TOOL_TEST_REPORT
        try {
            $env:TOOL_TEST_REPORT = Join-Path $root 'arguments.txt'
            & (Join-Path $root 'tools/run-native.ps1') -Executable $sample -ArgumentList @('--output-dir', 'D:\lab results\', '', 'literal"quote', 'after') | Out-Null
            $arguments = @(Get-Content $env:TOOL_TEST_REPORT | Where-Object { $_.StartsWith('ARG=') })
            Assert (($arguments -join '|') -eq 'ARG=--output-dir|ARG=D:\lab results\|ARG=|ARG=literal"quote|ARG=after') "Arguments corrupted: $($arguments -join '|')"
        } finally { $env:TOOL_TEST_REPORT = $before }
    }
}

if ($Group -in @('All', 'Verify')) {
    Test-Case 'verification enforces timeout, kills the child and restores environment' {
        $root = New-Fixture 'timeout' @('verify-native.ps1')
        $before = @{ PATH = $env:PATH; JAVA_HOME = $env:JAVA_HOME; TOOL_TEST_HANG = $env:TOOL_TEST_HANG; TOOL_TEST_PID_FILE = $env:TOOL_TEST_PID_FILE }
        $location = (Get-Location).Path
        try {
            $env:TOOL_TEST_HANG = '1'; $env:TOOL_TEST_PID_FILE = Join-Path $root 'pid.txt'
            $watch = [Diagnostics.Stopwatch]::StartNew()
            $message = ''
            try { & (Join-Path $root 'tools/verify-native.ps1') -Executable $sample -OutputDirectory (Join-Path $root 'results') -TimeoutSeconds 1 } catch { $message = $_.ToString() }
            Assert ($message -match 'timed out') "Expected timeout, got: $message"
            Assert ($watch.Elapsed.TotalSeconds -lt 15) 'Timeout did not bound the process runtime'
            $childId = [int][IO.File]::ReadAllText($env:TOOL_TEST_PID_FILE)
            Assert (-not (Get-Process -Id $childId -ErrorAction SilentlyContinue)) 'Timed-out child is still running'
            Assert ($env:PATH -eq $before.PATH -and $env:JAVA_HOME -eq $before.JAVA_HOME) 'Verification changed Java environment'
            Assert ((Get-Location).Path -eq $location) 'Verification changed the working directory'
        } finally {
            foreach ($name in $before.Keys) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
        }
    }
    Test-Case 'verification reports a failing EXE and captures diagnostics' {
        $root = New-Fixture 'exit-failure' @('verify-native.ps1')
        $message = ''
        try { & (Join-Path $root 'tools/verify-native.ps1') -Executable $sample -OutputDirectory (Join-Path $root 'results') } catch { $message = $_.ToString() }
        Assert ($message -match '13') 'Nonzero EXE exit was swallowed'
        $logs = Get-ChildItem (Join-Path $root 'results') -Recurse -Filter '*.stderr.log'
        Assert ($logs -and ((Get-Content $logs.FullName -Raw) -match 'sample diagnostics')) 'Child diagnostics were not saved'
    }
}

if ($Group -in @('All', 'Build')) {
    foreach ($failureStage in @('', 'install', 'jni', 'check', 'native', 'verify')) {
        Test-Case "full build gates dist publication (failure=$failureStage) and restores caller state" {
            $root = New-Fixture ('build-' + $failureStage) @('build.ps1', 'build-native.ps1', 'invoke-build-command.ps1')
            $nativeRoot = Join-Path $root '.gradle/native-tools/graalvm-jdk-21-test'
            Write-File (Join-Path $root 'tools/native-toolchain-packages.json') '[{"file":"graalvm21.zip","destination":"graalvm-jdk-21-test"}]'
            Write-File (Join-Path $root 'tools/install-local-toolchain.ps1') @'
if ($env:TOOL_TEST_FAIL -eq 'install') { throw 'installation failed' }
$jdk = Join-Path (Split-Path $PSScriptRoot -Parent) '.gradle/native-tools/graalvm-jdk-21-test'
New-Item -ItemType Directory -Force -Path (Join-Path $jdk 'bin') | Out-Null
Copy-Item $env:TOOL_TEST_SAMPLE (Join-Path $jdk 'bin/java.exe')
Copy-Item $env:TOOL_TEST_SAMPLE (Join-Path $jdk 'bin/javac.exe')
Set-Content (Join-Path $jdk 'bin/native-image.cmd') '@exit /b 0'
Set-Content (Join-Path $jdk 'release') 'JAVA_VERSION="21.0.12"'
'@
            Write-File (Join-Path $root 'tools/build-mosaic-jni.ps1') 'param($JdkHome); if ($env:TOOL_TEST_FAIL -eq "jni") { throw "JNI failed" }'
            Write-File (Join-Path $root 'tools/verify-native.ps1') 'param($Executable); if ($env:TOOL_TEST_FAIL -eq "verify") { throw "verification failed" }; if (-not (Test-Path $Executable)) { throw "Missing EXE" }'
            Write-File (Join-Path $root 'gradlew.bat') @'
@echo off
echo NATIVE_STDOUT
echo NATIVE_STDERR 1>&2
echo %*>>"%~dp0trace.txt"
if "%~1"=="clean" exit /b 0
if "%TOOL_TEST_FAIL%"=="check" if "%~1"=="check" exit /b 11
if "%~1"=="check" exit /b 0
if "%TOOL_TEST_FAIL%"=="native" exit /b 12
if not exist "%~dp0build\native\nativeCompile" mkdir "%~dp0build\native\nativeCompile"
if not exist "%~dp0build\native\nativeProbeCompile" mkdir "%~dp0build\native\nativeProbeCompile"
copy /y "%TOOL_TEST_SAMPLE%" "%~dp0build\native\nativeCompile\ConsoleBlockchain.exe" >nul
copy /y "%TOOL_TEST_SAMPLE%" "%~dp0build\native\nativeProbeCompile\MosaicProbe.exe" >nul
exit /b 0
'@
            $dist = Join-Path $root 'dist/ConsoleBlockchain.exe'
            Write-File $dist 'previous-good-exe'
            $names = @('JAVA_HOME', 'GRAALVM_HOME', 'GRADLE_USER_HOME', 'PATH', 'INCLUDE', 'LIB', 'TOOL_TEST_SAMPLE', 'TOOL_TEST_FAIL')
            $before = @{}
            foreach ($name in $names) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
            $location = (Get-Location).Path
            try {
                $env:JAVA_HOME = 'missing-java'; $env:GRAALVM_HOME = 'missing-graal'
                $env:TOOL_TEST_SAMPLE = $sample; $env:TOOL_TEST_FAIL = $failureStage
                $output = & (Join-Path $root 'tools/build.ps1') -Clean *>&1 | Out-String -Width 4096
                $code = $LASTEXITCODE
                Assert ($env:JAVA_HOME -eq 'missing-java' -and $env:GRAALVM_HOME -eq 'missing-graal') 'Build did not restore Java environment'
                Assert ($env:PATH -eq $before.PATH -and $env:INCLUDE -eq $before.INCLUDE -and $env:LIB -eq $before.LIB -and $env:GRADLE_USER_HOME -eq $before.GRADLE_USER_HOME) 'Build changed caller environment'
                Assert ((Get-Location).Path -eq $location) 'Build changed caller directory'
                if ($failureStage) {
                    Assert ($code -eq 1) "Failure did not exit 1: $output"
                    $expected = @{ install = 'installation failed'; jni = 'JNI failed'; check = 'Gradle checks failed: 11'; native = 'Native build failed: 12'; verify = 'verification failed' }
                    Assert ($output -match [regex]::Escape($expected[$failureStage])) "Did not reach intended failure: $output"
                    Assert ([IO.File]::ReadAllText($dist) -eq 'previous-good-exe') 'Failure overwrote the last good EXE'
                    if ($failureStage -eq 'check') {
                        Assert (-not (Test-Path (Join-Path $root 'build/native/nativeCompile/ConsoleBlockchain.exe'))) 'Native build ran after failed checks'
                    }
                } else {
                    Assert ($code -eq 0) "Successful build failed: $output"
                    Assert ((Get-FileHash $dist).Hash -eq (Get-FileHash $sample).Hash) 'Published EXE differs from the verified one'
                    $trace = @(Get-Content (Join-Path $root 'trace.txt'))
                    Assert ($trace.Count -eq 3 -and $trace[0] -match '^clean' -and $trace[1] -match '^check verifyComposeClasspath' -and $trace[2] -match '^nativeProbeCompile nativeCompile') 'Missing or repeated build stages'
                    Assert ($output -match 'SHA256' -and $output -match 'ConsoleBlockchain.exe') 'Missing artifact summary'
                }
                Assert (@(Get-ChildItem (Join-Path $root '.gradle/build-logs') -Filter '*.log').Count -eq 1) 'Build log was not retained'
                if (-not $failureStage) {
                    $savedLog = Get-ChildItem (Join-Path $root '.gradle/build-logs') -Filter '*.log'
                    $logText = [IO.File]::ReadAllText($savedLog.FullName)
                    Assert ($logText -match 'NATIVE_STDOUT' -and $logText -match 'NATIVE_STDERR') 'Saved log omitted native diagnostics'
                }
            } finally {
                foreach ($name in $before.Keys) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
            }
        }
    }
}

Write-Host "$passed passed, $failures failed. Fixtures: $testRoot"
if ($failures) { exit 1 }
exit 0
