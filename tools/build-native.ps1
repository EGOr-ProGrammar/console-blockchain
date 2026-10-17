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
$previousGraalVm = $env:GRAALVM_HOME
$previousNativeEnvironment = @{ PATH = $env:PATH; INCLUDE = $env:INCLUDE; LIB = $env:LIB }
Push-Location $repository
try {
    $env:GRAALVM_HOME = (Resolve-Path -LiteralPath $GraalVmHome).Path
    if (Test-Path -LiteralPath (Join-Path $repository '.gradle\native-tools\portable-msvc')) {
        . (Join-Path $PSScriptRoot 'use-local-toolchain.ps1')
    }
    & (Join-Path $PSScriptRoot 'build-mosaic-jni.ps1') -JdkHome $env:GRAALVM_HOME
    & .\gradlew.bat test verifyComposeClasspath nativeProbeCompile nativeCompile
    if ($LASTEXITCODE -ne 0) { throw "Native build failed: $LASTEXITCODE" }
} finally {
    $env:GRAALVM_HOME = $previousGraalVm
    foreach ($name in $previousNativeEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousNativeEnvironment[$name], 'Process') }
    Pop-Location
}
