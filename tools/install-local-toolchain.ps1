param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repository = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $repository '.gradle\native-tools'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$packages = Get-Content (Join-Path $PSScriptRoot 'native-toolchain-packages.json') -Raw | ConvertFrom-Json
foreach ($package in $packages) {
    $archivePath = Join-Path $cache $package.file
    if (-not (Test-Path -LiteralPath $archivePath) -or (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $package.sha256) {
        Invoke-WebRequest -UseBasicParsing $package.url -OutFile $archivePath
    }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $package.sha256) { throw "SHA256 mismatch: $($package.file)" }
    $destination = Join-Path $cache $package.destination
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($entry in $archive.Entries) {
            if (-not $entry.FullName.StartsWith($package.prefix) -or -not $entry.Name) { continue }
            $relative = $entry.FullName.Substring($package.prefix.Length).Replace('/', '\')
            $target = [IO.Path]::GetFullPath((Join-Path $destination $relative))
            if (-not $target.StartsWith($destination + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Archive entry outside tool directory' }
            if (Test-Path -LiteralPath $target) {
                $entryStream = $entry.Open()
                $hasher = [Security.Cryptography.SHA256]::Create()
                try { $expectedHash = [BitConverter]::ToString($hasher.ComputeHash($entryStream)).Replace('-', '') }
                finally { $hasher.Dispose(); $entryStream.Dispose() }
                if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $expectedHash) { continue }
            }
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally { $archive.Dispose() }
    Write-Host "Ready: $($package.file)"
}
Write-Host 'Local MSVC x64 and Windows SDK ready. No system installation was performed.'
