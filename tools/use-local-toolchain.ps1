# Dot-source this script to configure only the current PowerShell process.
$localToolRoot = Join-Path (Split-Path $PSScriptRoot -Parent) '.gradle\native-tools'
$localPackages = Get-Content (Join-Path $PSScriptRoot 'native-toolchain-packages.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$localMsvcVersion = ($localPackages | Where-Object { $_.msvcVersion }).msvcVersion
$localSdkVersion = ($localPackages | Where-Object { $_.sdkVersion }).sdkVersion
if (-not $localMsvcVersion -or -not $localSdkVersion) { throw 'Pinned MSVC/SDK versions are missing from the toolchain manifest.' }
$localMsvc = Get-Item -LiteralPath (Join-Path $localToolRoot "portable-msvc\VC\Tools\MSVC\$localMsvcVersion") -ErrorAction SilentlyContinue
$localSdk = Join-Path $localToolRoot 'portable-sdk'
$localSdkInclude = Get-Item -LiteralPath (Join-Path $localSdk "Include\$localSdkVersion") -ErrorAction SilentlyContinue
if (-not $localMsvc -or -not $localSdkInclude) { throw 'Run tools/install-local-toolchain.ps1 first.' }
foreach ($required in @(
    "$($localMsvc.FullName)\bin\Hostx64\x64\cl.exe", "$($localMsvc.FullName)\bin\Hostx64\x64\link.exe",
    "$($localMsvc.FullName)\lib\x64\libcmt.lib", "$($localSdkInclude.FullName)\um\Windows.h",
    "$localSdk\bin\$($localSdkInclude.Name)\x64\rc.exe", "$localSdk\um\x64\kernel32.lib", "$localSdk\ucrt\x64\ucrt.lib"
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Incomplete local toolchain: $required. Run tools/install-local-toolchain.ps1." }
}
$env:PATH = "$($localMsvc.FullName)\bin\Hostx64\x64;$localSdk\bin\$($localSdkInclude.Name)\x64;$env:PATH"
$env:INCLUDE = "$($localMsvc.FullName)\include;$($localSdkInclude.FullName)\ucrt;$($localSdkInclude.FullName)\shared;$($localSdkInclude.FullName)\um"
$env:LIB = "$($localMsvc.FullName)\lib\x64;$localSdk\um\x64;$localSdk\ucrt\x64"
