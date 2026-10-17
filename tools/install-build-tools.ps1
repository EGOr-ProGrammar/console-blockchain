param()

$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$toolDirectory = Join-Path $repository '.gradle\native-tools'
New-Item -ItemType Directory -Force -Path $toolDirectory | Out-Null
$installer = Join-Path $toolDirectory 'vs_BuildTools.exe'
if (-not (Test-Path -LiteralPath $installer)) {
    Invoke-WebRequest -UseBasicParsing 'https://aka.ms/vs/17/release/vs_BuildTools.exe' -OutFile $installer
}
$target = Join-Path $toolDirectory 'msvc'
$arguments = @(
    '--quiet', '--wait', '--norestart', '--nocache',
    '--installPath', ('"' + $target + '"'),
    '--add', 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64',
    '--add', 'Microsoft.VisualStudio.Component.Windows11SDK.26100'
)
Write-Host 'Confirm the Windows UAC prompt. Installing MSVC x64 and Windows SDK.'
$process = Start-Process -FilePath $installer -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -Wait -PassThru
if ($process.ExitCode -notin @(0, 3010)) { throw "Build Tools installation failed: $($process.ExitCode)" }
if (-not (Test-Path -LiteralPath (Join-Path $target 'VC\Auxiliary\Build\vcvarsall.bat'))) {
    throw 'Installation did not create vcvarsall.bat. Inspect the Visual Studio installer log.'
}
Write-Host "Build Tools ready: $target"
