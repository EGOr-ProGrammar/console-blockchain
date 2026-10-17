# Dot-source this script to configure only the current PowerShell process.
$localToolRoot = Join-Path (Split-Path $PSScriptRoot -Parent) '.gradle\native-tools'
$localMsvc = Get-ChildItem (Join-Path $localToolRoot 'portable-msvc\VC\Tools\MSVC') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
$localSdk = Join-Path $localToolRoot 'portable-sdk'
$localSdkInclude = Get-ChildItem (Join-Path $localSdk 'Include') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $localMsvc -or -not $localSdkInclude) { throw 'Run tools/install-local-toolchain.ps1 first.' }
$env:PATH = "$($localMsvc.FullName)\bin\Hostx64\x64;$localSdk\bin\$($localSdkInclude.Name)\x64;$env:PATH"
$env:INCLUDE = "$($localMsvc.FullName)\include;$($localSdkInclude.FullName)\ucrt;$($localSdkInclude.FullName)\shared;$($localSdkInclude.FullName)\um"
$env:LIB = "$($localMsvc.FullName)\lib\x64;$localSdk\um\x64;$localSdk\ucrt\x64"
