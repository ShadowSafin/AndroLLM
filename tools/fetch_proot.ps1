# Fetches the real proot runtime for the coding agent's Linux base and stages it
# into feature/coding/src/main/jniLibs/arm64-v8a/.
#
# WHY: Android 10+ (targetSdk 29+) forbids executing files in app-writable
# storage, so proot must ship as a *native library* (extracted to the app's
# executable nativeLibraryDir). We use Termux's Android/aarch64 build of proot.
#
# WHAT THIS DOES:
#   1. Downloads proot + libtalloc + libandroid-shmem .deb packages from
#      packages.termux.dev.
#   2. Extracts the ar archive, then data.tar.xz (needs bsdtar with liblzma).
#   3. Patches proot's DT_NEEDED "libtalloc.so.2" -> "libtalloc.so" in-place so
#      it matches the jniLibs naming convention (lib*.so).
#   4. Copies proot, the guest loader, libtalloc and libandroid-shmem into
#      jniLibs as libproot.so / libproot-loader.so / libtalloc.so /
#      libandroid-shmem.so. (proot DT_NEEDs all three libs; Android's libc has
#      no System V shm, so libandroid-shmem is required at runtime.)
#
# The loader is the key piece: proot execs it, and it maps guest ELFs from the
# noexec rootfs INTO MEMORY, which is how guest programs run at all on Android.
#
# Run from the repo root:  pwsh ./tools/fetch_proot.ps1
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$tmp = Join-Path $PSScriptRoot "proot-fetch"
$jni = Join-Path $repoRoot "feature\coding\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Force -Path $tmp, $jni | Out-Null

$prootDebUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.92_aarch64.deb"
$tallocDebUrl = "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
$shmemDebUrl = "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"

function Get-Deb([string]$url, [string]$out) {
    Write-Output "fetching $([System.IO.Path]::GetFileName($url))"
    curl.exe -sL --retry 3 --max-time 180 -o $out $url
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $out)) { throw "download failed: $url" }
}

# Minimal BSD/GNU 'ar' extractor (deb = ar of debian-binary/control/data tars).
function Expand-Ar([string]$arPath, [string]$outDir) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $fs = [System.IO.File]::OpenRead($arPath)
    $br = New-Object System.IO.BinaryReader($fs)
    $magic = [System.Text.Encoding]::ASCII.GetString($br.ReadBytes(8))
    if ($magic -ne "!<arch>`n") { throw "not an ar archive: $arPath" }
    while ($fs.Position -lt $fs.Length) {
        $header = [System.Text.Encoding]::ASCII.GetString($br.ReadBytes(60))
        if ($header.Length -lt 60) { break }
        $name = $header.Substring(0, 16).Trim().TrimEnd('/')
        $size = [long]$header.Substring(48, 10).Trim()
        $data = $br.ReadBytes([int]$size)
        if ($size % 2 -eq 1) { [void]$br.ReadByte() }
        if ($name -and $name -ne "//") { [System.IO.File]::WriteAllBytes((Join-Path $outDir $name), $data) }
    }
    $fs.Close()
}

Get-Deb $prootDebUrl (Join-Path $tmp "proot.deb")
Get-Deb $tallocDebUrl (Join-Path $tmp "libtalloc.deb")
Get-Deb $shmemDebUrl (Join-Path $tmp "libandroid-shmem.deb")

Expand-Ar (Join-Path $tmp "proot.deb") (Join-Path $tmp "proot-ar")
Expand-Ar (Join-Path $tmp "libtalloc.deb") (Join-Path $tmp "talloc-ar")
Expand-Ar (Join-Path $tmp "libandroid-shmem.deb") (Join-Path $tmp "shmem-ar")
New-Item -ItemType Directory -Force -Path (Join-Path $tmp "proot-data"), (Join-Path $tmp "talloc-data"), (Join-Path $tmp "shmem-data") | Out-Null
# Exclude share/ (docs/man, contains symlinks bsdtar can't create on Windows).
$tarExcl = @("--exclude", "*share*")
$prevEAP = $ErrorActionPreference; $ErrorActionPreference = "Continue"
& tar -xJf (Join-Path $tmp "proot-ar\data.tar.xz") -C (Join-Path $tmp "proot-data") @tarExcl
& tar -xJf (Join-Path $tmp "talloc-ar\data.tar.xz") -C (Join-Path $tmp "talloc-data") @tarExcl
& tar -xJf (Join-Path $tmp "shmem-ar\data.tar.xz") -C (Join-Path $tmp "shmem-data") @tarExcl
$ErrorActionPreference = $prevEAP

$prootBin = Get-ChildItem -Path (Join-Path $tmp "proot-data") -Recurse -Filter proot | Select-Object -First 1
$loaderBin = Get-ChildItem -Path (Join-Path $tmp "proot-data") -Recurse -Filter loader | Where-Object { $_.FullName -match "libexec" } | Select-Object -First 1
$tallocLib = Get-ChildItem -Path (Join-Path $tmp "talloc-data") -Recurse -Filter "libtalloc.so.*" | Select-Object -First 1
$shmemLib = Get-ChildItem -Path (Join-Path $tmp "shmem-data") -Recurse -Filter "libandroid-shmem.so*" | Select-Object -First 1
if (-not $prootBin -or -not $loaderBin -or -not $tallocLib -or -not $shmemLib) { throw "extraction did not produce the expected binaries" }

# Patch DT_NEEDED "libtalloc.so.2" -> "libtalloc.so" (NUL-padded, same length).
$bytes = [System.IO.File]::ReadAllBytes($prootBin.FullName)
$old = [System.Text.Encoding]::ASCII.GetBytes("libtalloc.so.2")
$new = [System.Text.Encoding]::ASCII.GetBytes("libtalloc.so") + [byte[]](0, 0)
$patched = 0
for ($i = 0; $i -le $bytes.Length - $old.Length; $i++) {
    $match = $true
    for ($j = 0; $j -lt $old.Length; $j++) { if ($bytes[$i + $j] -ne $old[$j]) { $match = $false; break } }
    if ($match) { for ($j = 0; $j -lt $new.Length; $j++) { $bytes[$i + $j] = $new[$j] }; $patched++; $i += $old.Length - 1 }
}
if ($patched -eq 0) { throw "could not find libtalloc.so.2 to patch - was proot rebuilt?" }
[System.IO.File]::WriteAllBytes((Join-Path $jni "libproot.so"), $bytes)

Copy-Item $loaderBin.FullName (Join-Path $jni "libproot-loader.so") -Force
Copy-Item $tallocLib.FullName (Join-Path $jni "libtalloc.so") -Force
Copy-Item $shmemLib.FullName (Join-Path $jni "libandroid-shmem.so") -Force

Write-Output ""
Write-Output "Staged into $jni :"
Get-ChildItem $jni -Filter "libproot*" | ForEach-Object { Write-Output ("  {0}  ({1} bytes)" -f $_.Name, $_.Length) }
Get-ChildItem $jni -Filter "libtalloc*" | ForEach-Object { Write-Output ("  {0}  ({1} bytes)" -f $_.Name, $_.Length) }
Get-ChildItem $jni -Filter "libandroid-shmem*" | ForEach-Object { Write-Output ("  {0}  ({1} bytes)" -f $_.Name, $_.Length) }
Write-Output "Patched $patched DT_NEEDED occurrence(s)."
