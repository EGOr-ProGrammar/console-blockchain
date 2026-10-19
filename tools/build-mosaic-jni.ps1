param([string]$JdkHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$work = Join-Path $repository 'build/mosaic-jni'
$archive = Join-Path $work 'mosaic-0.18.0.zip'
$expected = '60B7C52147BF1D3D1742277E89B323474FA637B42D5063D66374F10798BAB12A'
New-Item -ItemType Directory -Force -Path $work | Out-Null
if (-not $JdkHome) { $JdkHome = Split-Path (Split-Path (Get-Command javac.exe).Source -Parent) -Parent }
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -UseBasicParsing 'https://github.com/JakeWharton/mosaic/archive/refs/tags/0.18.0.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) { throw 'Mosaic source SHA256 mismatch' }
Expand-Archive -LiteralPath $archive -DestinationPath $work -Force
$source = Join-Path $work 'mosaic-0.18.0/mosaic-tty/src'
$jni = Join-Path $source 'jvmMain/c/com_jakewharton_mosaic_tty_Jni.c'
$text = [IO.File]::ReadAllText($jni)
if ([regex]::Matches($text, [regex]::Escape('(*callback->env)->CallVoidMethod(')).Count -ne 4) { throw 'Unexpected JNI callback source' }
# A callback is invoked synchronously by ttyRead on an attached Java IO thread.
# JNIEnv belongs to that thread, while JavaVM can safely be shared.
$text = $text.Replace('JNIEnv *env;', 'JavaVM *vm;')
$text = $text.Replace('jniCallback->env = env;', '(*env)->GetJavaVM(env, &jniCallback->vm);')
$text = $text.Replace('(*callback->env)->CallVoidMethod(', "JNIEnv *env = NULL;`n`tif ((*callback->vm)->GetEnv(callback->vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return;`n`t(*env)->CallVoidMethod(")
$text = $text.Replace('callback->env,', 'env,')
[IO.File]::WriteAllText($jni, "#include <stdio.h>`n" + $text)
# MSVC equivalents of upstream GCC-only branch hints and unused annotation.
[IO.File]::WriteAllText((Join-Path $source 'commonMain/c/cutils.h'), "#define likely(x) (!!(x))`n#define unlikely(x) (!!(x))`n#define UNUSED`n")
$previous = @{ PATH = $env:PATH; INCLUDE = $env:INCLUDE; LIB = $env:LIB }
Push-Location $work
try {
    if (Test-Path (Join-Path $repository '.gradle/native-tools/portable-msvc')) { . (Join-Path $PSScriptRoot 'use-local-toolchain.ps1') }
    # Definitions already declare every JNI entry point; no generated prototypes are needed.
    [IO.File]::WriteAllText((Join-Path $work 'com_jakewharton_mosaic_tty_Jni.h'), "#include <jni.h>`n")
    & (Join-Path $PSScriptRoot 'invoke-build-command.ps1') -FilePath 'cl.exe' -ArgumentList @(
        '/nologo', '/LD', '/MT', '/O2', '/std:c11', '/experimental:c11atomics',
        "/I$work", "/I$source/commonMain/c", "/I$JdkHome/include", "/I$JdkHome/include/win32",
        $jni, (Join-Path $source 'commonMain/c/mosaic-tty-windows.c'), (Join-Path $source 'commonMain/c/mosaic-test-tty-windows.c'),
        '/link', '/OUT:mosaic.dll'
    )
    if ($LASTEXITCODE -ne 0) { throw 'Mosaic JNI compilation failed' }
    $resource = Join-Path $repository 'src/main/resources/com/jakewharton/mosaic/tty/jni/amd64'
    New-Item -ItemType Directory -Force -Path $resource | Out-Null
    Copy-Item -LiteralPath (Join-Path $work 'mosaic.dll') -Destination $resource -Force
    Copy-Item -LiteralPath (Join-Path $work 'mosaic-0.18.0/LICENSE.txt') -Destination (Join-Path $resource 'LICENSE.txt') -Force
} finally {
    Pop-Location
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
