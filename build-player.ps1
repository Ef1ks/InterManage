# Kompiluje player.ahk do player-vNN.exe i kopiuje na player.exe.
# Numer wersji bierze z linii  global PlayerVersion := NN  w player.ahk.
#
# Uruchom w PowerShellu z katalogu projektu:
#   .\build-player.ps1            # bez kompresji (zalecane)
#   .\build-player.ps1 -Compress  # spakuj mpress/upx, jesli jest w katalogu kompilatora
#
# Git Bash psuje przelaczniki Ahk2Exe, wiec kompilujemy z PowerShella.

param(
    [switch]$Compress
)

$ErrorActionPreference = 'Stop'
$root    = $PSScriptRoot
$ahk     = Join-Path $root 'player.ahk'
$ahk2exe = 'C:\Program Files\AutoHotkey\Compiler\Ahk2Exe.exe'
$base    = 'C:\Program Files\AutoHotkey\v2\AutoHotkey32.exe'

if (-not (Test-Path $ahk2exe)) { throw "Brak kompilatora: $ahk2exe" }
if (-not (Test-Path $base))    { throw "Brak bazy: $base" }

# Wersja z player.ahk
$m = Select-String -Path $ahk -Pattern 'global PlayerVersion := (\d+)' | Select-Object -First 1
if (-not $m) { throw "Nie znalazlem 'global PlayerVersion := NN' w player.ahk" }
$ver = [int]$m.Matches[0].Groups[1].Value
$out = Join-Path $root "player-v$ver.exe"

# Zamknij dzialajacy odtwarzacz, bo trzyma plik zablokowany
Get-Process -Name 'player*' -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 300

# Kompresja: tylko gdy mpress/upx jest w katalogu kompilatora
$compressArg = ''
if ($Compress) {
    $compiler = Split-Path $ahk2exe
    if     (Test-Path (Join-Path $compiler 'mpress.exe')) { $compressArg = ' /compress 2'; Write-Host 'Kompresja: mpress' }
    elseif (Test-Path (Join-Path $compiler 'upx.exe'))    { $compressArg = ' /compress 1'; Write-Host 'Kompresja: upx' }
    else   { Write-Warning 'Brak mpress.exe/upx.exe w katalogu kompilatora - kompiluje bez kompresji.' }
}

# PS 5.1 rozjezdza argumenty ze spacja (C:\Program Files...) przy splatowaniu tablicy do .exe.
# Dlatego budujemy JEDEN ciag z recznie zacytowanymi sciezkami i podajemy go do Start-Process.
$argString = "/in `"$ahk`" /out `"$out`" /base `"$base`"$compressArg"
$p = Start-Process -FilePath $ahk2exe -ArgumentList $argString -Wait -PassThru -NoNewWindow
if ($p.ExitCode -ne 0)     { throw "Ahk2Exe zakonczyl sie bledem $($p.ExitCode)" }
if (-not (Test-Path $out)) { throw "Ahk2Exe nie utworzyl pliku $out" }

Copy-Item $out (Join-Path $root 'player.exe') -Force
$kb = [math]::Round((Get-Item $out).Length / 1KB)
Write-Host "OK: player-v$ver.exe ($kb KB) -> player.exe"
