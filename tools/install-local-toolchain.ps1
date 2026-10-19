param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repository = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $repository '.gradle\native-tools'
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$packages = Get-Content (Join-Path $PSScriptRoot 'native-toolchain-packages.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($package in $packages) {
    $archivePath = Join-Path $cache $package.file
    if (-not (Test-Path -LiteralPath $archivePath) -or (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $package.sha256) {
        $download = $archivePath + '.' + [Guid]::NewGuid() + '.download'
        try {
            Write-Host "Downloading: $($package.file)"
            Invoke-WebRequest -UseBasicParsing -Uri $package.url -OutFile $download
            if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $package.sha256) { throw "SHA256 mismatch: $($package.file)" }
            Move-Item -LiteralPath $download -Destination $archivePath -Force
        } finally {
            if (Test-Path -LiteralPath $download) { Remove-Item -LiteralPath $download }
        }
    }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $package.sha256) { throw "SHA256 mismatch: $($package.file)" }
    $destination = [IO.Path]::GetFullPath((Join-Path $cache $package.destination))
    if (-not $destination.StartsWith($cache + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Package destination outside tool directory' }
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    $matched = 0
    try {
        foreach ($entry in $archive.Entries) {
            if (-not $entry.FullName.StartsWith($package.prefix) -or -not $entry.Name) { continue }
            $matched++
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
    if ($matched -eq 0) { throw "No files matched archive prefix: $($package.file)" }
    foreach ($required in $package.requiredFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $destination $required) -PathType Leaf)) { throw "Missing required file after installation: $required ($($package.file))" }
    }
    Write-Host "Ready: $($package.file)"
}
Write-Host 'Local native toolchain ready. No system installation was performed.'
