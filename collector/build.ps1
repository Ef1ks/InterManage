# Buduje program kolektora (Windows CE 6.0, .NET Compact Framework 2.0) bez Visual Studio 2008:
#   .\build.ps1                  # dist\IntermanageSetup.exe i pliki dla serwera (backend/src/main/resources/collector)
#   .\build.ps1 -TestVersion 1.0.9   # do obj\test, bez plikow dla serwera (proba aktualizacji ze starszej wersji)
#   .\build.ps1 -BouncyCastle    # najpierw przebudowuje lib\BouncyCastle.dll ze zrodel
#
# Kompilator C# to csc z .NET Framework 4 (jest w kazdym Windowsie), celowo nie Roslyn z dotnet SDK: .NET Compact
# Framework na kolektorze zle wykonywal jego kod (TLS konczyl sie unexpected_message po certyfikacie). Biblioteki referencyjne Compact Framework (ref\, poza gitem) bierze z instalatora
# Microsoftu NETCFSetupv2.msi: skrypt pobiera go raz i sprawdza podpis.

param(
    [switch]$BouncyCastle,
    [string]$TestVersion
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

$root = $PSScriptRoot
$ref  = Join-Path $root 'ref'
$lib  = Join-Path $root 'lib'
$dist = Join-Path $root 'dist'
$work = Join-Path $root 'obj'

# Wersja programu: wyzsza niz na kolektorach = kolektory pobiora ja po wdrozeniu. Zmiana w src = nowa wersja.
$version = '1.7.1'

if ($TestVersion) {
    $version = $TestVersion
    $dist = Join-Path $work 'test'
}

$netcfUrl  = 'https://archive.org/download/NETCF/NETCF.zip'
$netcfSha1 = '35D4DC9BC228B7EB830831CF88E308DE6F47EEAD'
$bcUrl     = 'https://codeload.github.com/bcgit/bc-csharp/zip/refs/tags/release-1.8.10'
# Moduly BouncyCastle, ktorych kolektor nie uzywa (poczta OpenPGP, algorytmy postkwantowe, znaczniki czasu).
$bcSkipped = 'openpgp', 'bcpg', 'pqc', 'tsp', 'ocsp', 'cmp'

function New-Folder([string]$path) {
    New-Item -ItemType Directory -Force $path | Out-Null
}

$csc = Join-Path $env:windir 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { throw "Brak kompilatora C#: $csc" }

function Invoke-Csc([string[]]$lines, [string]$name) {
    $rsp = Join-Path $work "$name.rsp"
    $lines | Out-File $rsp -Encoding utf8
    & $csc /noconfig /nologo "@$rsp"
    if ($LASTEXITCODE -ne 0) { throw "Blad kompilacji: $name" }
}

New-Folder $work
New-Folder $dist
New-Folder $lib

if (-not (Test-Path (Join-Path $ref 'mscorlib.dll'))) {
    Write-Output 'Biblioteki Compact Framework 2.0...'
    $zip = Join-Path $work 'netcf.zip'
    Invoke-WebRequest $netcfUrl -OutFile $zip -UseBasicParsing
    if ((Get-FileHash $zip -Algorithm SHA1).Hash -ne $netcfSha1) { throw 'Pobrany NETCF.zip ma inny skrot' }
    Expand-Archive $zip (Join-Path $work 'netcf') -Force
    $msi = (Get-ChildItem (Join-Path $work 'netcf') -Recurse -Filter 'NETCFSetupv2.msi').FullName
    $signature = Get-AuthenticodeSignature $msi
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') {
        throw 'Instalator Compact Framework nie jest podpisany przez Microsoft'
    }
    # Instalacja administracyjna (/a) tylko rozpakowuje pliki.
    $unpacked = Join-Path $work 'netcf-files'
    $process = Start-Process msiexec.exe -ArgumentList "/a `"$msi`" /qn TARGETDIR=`"$unpacked`"" -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "msiexec: $($process.ExitCode)" }
    $source = (Get-ChildItem $unpacked -Recurse -Filter 'System.Windows.Forms.dll' |
        Where-Object { $_.Directory.Name -eq 'WindowsCE' } | Select-Object -First 1).DirectoryName
    New-Folder $ref
    Copy-Item (Join-Path $source '*.dll') $ref
}

$references = 'mscorlib', 'System', 'System.Drawing', 'System.Windows.Forms' |
    ForEach-Object { "-r:`"$(Join-Path $ref "$_.dll")`"" }

$bcDll = Join-Path $lib 'BouncyCastle.dll'
if ($BouncyCastle -or -not (Test-Path $bcDll)) {
    Write-Output 'BouncyCastle 1.8.10...'
    $zip = Join-Path $work 'bc.zip'
    if (-not (Test-Path $zip)) { Invoke-WebRequest $bcUrl -OutFile $zip -UseBasicParsing }
    Expand-Archive $zip (Join-Path $work 'bc') -Force
    $sources = Join-Path $work 'bc\bc-csharp-release-1.8.10\crypto\src'
    $files = Get-ChildItem $sources -Recurse -Filter '*.cs' | Where-Object {
        $first = $_.FullName.Substring($sources.Length + 1).Split('\')[0]
        $_.Name -ne 'AssemblyInfo.cs' -and $_.Name -ne 'OpenBsdBCrypt.cs' -and $bcSkipped -notcontains $first
    } | ForEach-Object { "`"$($_.FullName)`"" }
    # Compact Framework nie ma Interlocked.Increment(ref long).
    foreach ($file in 'security\SecureRandom.cs', 'crypto\tls\AbstractTlsContext.cs') {
        $path = Join-Path $sources $file
        $text = [IO.File]::ReadAllText($path)
        $patched = $text.Replace('return Interlocked.Increment(ref counter);',
            'lock (typeof(Interlocked)) { return ++counter; }')
        if ($patched -eq $text) { throw "Nie znalazlem licznika w $file" }
        [IO.File]::WriteAllText($path, $patched)
    }
    # "throw e" gubi slad wywolan, a bez niego nie widac na kolektorze, gdzie powstal blad.
    $path = Join-Path $sources 'crypto\tls\TlsProtocol.cs'
    [IO.File]::WriteAllText($path, [IO.File]::ReadAllText($path).Replace('throw e;', 'throw;'))
    Invoke-Csc (@('-nostdlib', '-langversion:3', '-optimize+', '-target:library', '-warn:0', 
            '-define:NETCF_2_0;NETCF', "-out:`"$bcDll`"") + $references[0..1] + $files) 'BouncyCastle'
}

Write-Output "Program $version..."
$builtAt = (Get-Date).ToUniversalTime()
@"
using System;
using System.Reflection;

[assembly: AssemblyTitle("Intermanage - kolektor")]
[assembly: AssemblyProduct("Intermanage")]
[assembly: AssemblyVersion("$version.0")]

namespace Intermanage.Collector
{
    internal static class BuildInfo
    {
        public const string Version = "$version";

        /// <summary>Dzien zbudowania: wczesniejsza data na kolektorze znaczy, ze zegar nie jest ustawiony.</summary>
        public static readonly DateTime BuiltAt = new DateTime($($builtAt.Year), $($builtAt.Month), $($builtAt.Day), 0, 0, 0, DateTimeKind.Utc);
    }
}
"@ | Out-File (Join-Path $work 'BuildInfo.cs') -Encoding utf8

function Get-Sources([string]$folder) {
    Get-ChildItem $folder -Filter '*.cs' | ForEach-Object { "`"$($_.FullName)`"" }
}

$src = Join-Path $root 'src'
$common = @(Get-Sources (Join-Path $src 'shared')) + "`"$(Join-Path $work 'BuildInfo.cs')`""
$options = '-nostdlib', '-langversion:3', '-optimize+', '-target:winexe', '-warn:4', '-filealign:512'
$program = Join-Path $dist 'Kolektor.exe'
$launcher = Join-Path $dist 'Intermanage.exe'
$setup = Join-Path $dist 'IntermanageSetup.exe'

Invoke-Csc ($options + "-resource:`"$(Join-Path $root 'korzenie.pem')`",korzenie.pem" + "-out:`"$program`"" +
    "-r:`"$bcDll`"" + $references + $common + (Get-Sources $src)) 'Kolektor'
Invoke-Csc ($options + "-out:`"$launcher`"" + $references + $common + (Get-Sources (Join-Path $src 'launcher'))) 'Intermanage'
Invoke-Csc ($options + "-out:`"$setup`"" + "-resource:`"$program`",Kolektor.exe" + "-resource:`"$launcher`",Intermanage.exe" +
    "-resource:`"$bcDll`",BouncyCastle.dll" + $references + $common + (Get-Sources (Join-Path $src 'setup'))) 'IntermanageSetup'
Copy-Item $bcDll $dist -Force

if ($TestVersion) { return }

# Pliki dla serwera: instalator do pobrania i program do aktualizacji kolektorow.
$server = Join-Path (Split-Path $root) 'backend\src\main\resources\collector'
New-Folder $server
Copy-Item $program, $bcDll, $setup $server -Force
[IO.File]::WriteAllText((Join-Path $server 'program.properties'), "version=$version`nfiles=Kolektor.exe,BouncyCastle.dll`n")
Get-ChildItem $dist | Select-Object Name, Length | Format-Table -AutoSize
