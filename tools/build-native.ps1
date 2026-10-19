param([string]$GraalVmHome = $env:GRAALVM_HOME)

$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
if (-not $GraalVmHome) {
    $candidate = Get-ChildItem (Join-Path $repository '.gradle\native-tools') -Directory -Filter 'graalvm-jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($candidate) { $GraalVmHome = $candidate.FullName }
}
if (-not $GraalVmHome -or -not (Test-Path -LiteralPath (Join-Path $GraalVmHome 'bin\native-image.cmd'))) {
    throw 'Set GRAALVM_HOME to GraalVM for JDK 21 or pass -GraalVmHome.'
}
$previousNativeEnvironment = @{ GRAALVM_HOME = $env:GRAALVM_HOME; JAVA_HOME = $env:JAVA_HOME; PATH = $env:PATH; INCLUDE = $env:INCLUDE; LIB = $env:LIB }
Push-Location $repository
try {
    $env:GRAALVM_HOME = (Resolve-Path -LiteralPath $GraalVmHome).Path
    $env:JAVA_HOME = $env:GRAALVM_HOME
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    if (Test-Path -LiteralPath (Join-Path $repository '.gradle\native-tools\portable-msvc')) {
        . (Join-Path $PSScriptRoot 'use-local-toolchain.ps1')
    }
    Write-Host 'JNI: building patched Mosaic DLL'
    & (Join-Path $PSScriptRoot 'build-mosaic-jni.ps1') -JdkHome $env:GRAALVM_HOME
    Write-Host 'Checks: tests, ktlint and Compose classpaths'
    & (Join-Path $PSScriptRoot 'invoke-build-command.ps1') -FilePath (Join-Path $repository 'gradlew.bat') -ArgumentList @('check', 'verifyComposeClasspath', '--console=plain')
    if ($LASTEXITCODE -ne 0) { throw "Gradle checks failed: $LASTEXITCODE" }
    Write-Host 'Native Image: MosaicProbe and ConsoleBlockchain'
    & (Join-Path $PSScriptRoot 'invoke-build-command.ps1') -FilePath (Join-Path $repository 'gradlew.bat') -ArgumentList @('nativeProbeCompile', 'nativeCompile', '--console=plain')
    if ($LASTEXITCODE -ne 0) { throw "Native build failed: $LASTEXITCODE" }
    foreach ($artifact in @('build/native/nativeProbeCompile/MosaicProbe.exe', 'build/native/nativeCompile/ConsoleBlockchain.exe')) {
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) { throw "Native build did not create $artifact" }
    }
} finally {
    foreach ($name in $previousNativeEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousNativeEnvironment[$name], 'Process') }
    Pop-Location
}
