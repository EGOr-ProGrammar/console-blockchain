#Requires -Version 5.1
param([switch]$Clean)

$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$previousEnvironment = @{}
foreach ($name in @('JAVA_HOME', 'GRAALVM_HOME', 'GRADLE_USER_HOME', 'PATH', 'INCLUDE', 'LIB')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$logDirectory = Join-Path $repository '.gradle/build-logs'
$log = Join-Path $logDirectory ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid() + '.log')
$stage = 'platform'
$exitCode = 1
$transcribing = $false
$staging = $null
Push-Location $repository
try {
    New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
    Start-Transcript -LiteralPath $log | Out-Null
    $transcribing = $true
    if ([Environment]::OSVersion.Platform -ne 'Win32NT' -or -not [Environment]::Is64BitProcess -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
        throw 'Run this script in 64-bit PowerShell on Windows x64.'
    }

    $stage = 'dependencies'
    Write-Host '[1/6] Installing verified local GraalVM, MSVC and Windows SDK'
    & (Join-Path $PSScriptRoot 'install-local-toolchain.ps1')
    $packages = Get-Content (Join-Path $PSScriptRoot 'native-toolchain-packages.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $graalPackage = $packages | Where-Object file -eq 'graalvm21.zip'
    if (-not $graalPackage) { throw 'GraalVM package is missing from the toolchain manifest.' }
    $graal = Join-Path (Join-Path $repository '.gradle/native-tools') $graalPackage.destination
    if ((Get-Content (Join-Path $graal 'release') -Raw) -notmatch '(?m)^JAVA_VERSION="21\.') { throw 'The local GraalVM must use JDK 21.' }
    $env:JAVA_HOME = $graal
    $env:GRAALVM_HOME = $graal
    $env:GRADLE_USER_HOME = Join-Path $repository '.gradle/gradle-user-home'
    $env:PATH = "$graal\bin;$env:PATH"

    if ($Clean) {
        $stage = 'clean'
        Write-Host '[2/6] Cleaning Gradle build outputs (keeping dependency caches)'
        & (Join-Path $PSScriptRoot 'invoke-build-command.ps1') -FilePath (Join-Path $repository 'gradlew.bat') -ArgumentList @('clean', '--console=plain')
        if ($LASTEXITCODE -ne 0) { throw "Gradle clean failed: $LASTEXITCODE" }
    } else {
        Write-Host '[2/6] Keeping incremental build outputs'
    }

    $stage = 'native build'
    Write-Host '[3/6] Building JNI, running checks and compiling both native executables'
    & (Join-Path $PSScriptRoot 'build-native.ps1') -GraalVmHome $graal

    $stage = 'standalone verification'
    Write-Host '[4/6] Verifying the EXE without Java (demo and experiments)'
    $executable = Join-Path $repository 'build/native/nativeCompile/ConsoleBlockchain.exe'
    & (Join-Path $PSScriptRoot 'verify-native.ps1') -Executable $executable

    $stage = 'publish'
    Write-Host '[5/6] Publishing verified EXE to dist'
    $dist = Join-Path $repository 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $published = Join-Path $dist 'ConsoleBlockchain.exe'
    $staging = Join-Path $dist ([Guid]::NewGuid().ToString() + '.tmp')
    Copy-Item -LiteralPath $executable -Destination $staging
    $hash = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $staging -Algorithm SHA256).Hash -ne $hash) { throw 'EXE copy SHA256 mismatch.' }
    if (Test-Path -LiteralPath $published) {
        [IO.File]::Replace($staging, $published, [NullString]::Value)
    } else {
        [IO.File]::Move($staging, $published)
    }

    Write-Host '[6/6] BUILD VERIFIED'
    Write-Host "EXE: $published"
    Write-Host "Size: $((Get-Item -LiteralPath $published).Length) bytes"
    Write-Host "SHA256: $hash"
    Write-Host "Log: $log"
    Write-Host 'Color TUI: powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run-native.ps1'
    $exitCode = 0
} catch {
    Write-Host "FAIL [$stage]: $_"
    Write-Host "Log: $log"
    Write-Host $_.ScriptStackTrace
} finally {
    if ($staging -and (Test-Path -LiteralPath $staging)) { Remove-Item -LiteralPath $staging }
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    Pop-Location
    if ($transcribing) { Stop-Transcript | Out-Null }
}
exit $exitCode
