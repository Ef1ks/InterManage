using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Intermanage.Agent;

/// <summary>
/// Automatyczna aktualizacja agenta (od 1.5.0). Serwer w każdej odpowiedzi /hello podaje wersję paczki agenta
/// z „Do pobrania” (pozycja oznaczona jako paczka agenta). Nowszą agent:
/// <list type="number">
/// <item>pobiera do %LOCALAPPDATA%\Intermanage\Agent\update i sprawdza rozmiar i SHA-256 z /hello;</item>
/// <item>rozpakowuje (tylko bezpieczne ścieżki), sprawdza wersję nowego exe i podpis Authenticode: gdy ten agent jest
/// podpisany, nowy musi mieć ważny podpis tego samego wystawcy (ten sam podmiot certyfikatu);</item>
/// <item>czeka, aż komputer jest bezczynny (2 min bez myszy i klawiatury, bez aktualizacji danych i otwartych okien
/// agenta), bo zamknięcie agenta zamyka też odtwarzacz makr;</item>
/// <item>podmienia pliki w swoim folderze (stare zostają obok jako *.old, działający exe da się przemianować),
/// uruchamia nową wersję z <c>--updated</c> i kończy się;</item>
/// <item>nowa wersja po minucie pracy uznaje aktualizację za udaną i usuwa *.old; gdy nie wystartuje, sama wraca do
/// poprzedniej i tej wersji już nie instaluje.</item>
/// </list>
/// Agent nie potrzebuje do tego administratora: folder agenta leży w profilu użytkownika.
/// </summary>
internal sealed class SelfUpdate : IDisposable
{
    public const string UpdatedArg = "--updated";
    public const string WaitArg = "--wait";

    private static readonly TimeSpan IdleNeeded = TimeSpan.FromMinutes(2);
    private static readonly TimeSpan RetryFailedAfter = TimeSpan.FromHours(1);
    private static readonly TimeSpan ConfirmAfter = TimeSpan.FromMinutes(1);

    private static string Folder => Path.Combine(AgentSettings.Folder, "update");
    private static string PendingPath => Path.Combine(Folder, "pending.json");
    private static string RejectedPath => Path.Combine(Folder, "rejected.txt");
    private static string AppFolder => AppDomain.CurrentDomain.BaseDirectory;
    private static string ExePath => Process.GetCurrentProcess().MainModule!.FileName;

    private readonly object gate = new();
    private readonly Func<bool> busy;
    private readonly Action exit;
    private Timer? timer;
    private Timer? confirm;
    private int preparing;
    private string? failedVersion;
    private DateTime failedAt;
    /// <summary>Wersja rozpakowana i sprawdzona, czeka na bezczynność.</summary>
    private Staged? staged;
    private bool applying;

    /// <param name="busy">Agent jest zajęty (aktualizacja danych, otwarte okno): nie podmieniamy go wtedy.</param>
    /// <param name="exit">Zamyka agenta po uruchomieniu nowej wersji.</param>
    public SelfUpdate(Func<bool> busy, Action exit)
    {
        this.busy = busy;
        this.exit = exit;
    }

    /// <summary>Opis stanu do menu ikony (null = nic się nie dzieje).</summary>
    public string? Status { get; private set; }

    public event Action? StatusChanged;

    private sealed class Staged
    {
        public string Version = "";
        public string Folder = "";
    }

    private sealed class Pending
    {
        public string From { get; set; } = "";
        public string To { get; set; } = "";
        public List<string> Files { get; set; } = new();
        public DateTime At { get; set; }
    }

    /// <summary>Start agenta: po udanej aktualizacji odlicza minutę do potwierdzenia, inaczej sprząta resztki.</summary>
    public void Start(bool afterUpdate)
    {
        var pending = ReadPending();
        if (pending != null && afterUpdate)
        {
            Log.Info("Aktualizacja agenta " + pending.From + " → " + pending.To + ": nowa wersja wystartowała");
            confirm = new Timer(_ => Confirm(), null, ConfirmAfter, Timeout.InfiniteTimeSpan);
        }
        else if (pending != null)
        {
            // Nowa wersja nie dotrwała do potwierdzenia (np. wyłączony komputer): skoro ten start działa, uznajemy ją.
            Confirm();
        }
        else
        {
            CleanOld();
        }
        timer = new Timer(_ => TryApply(), null, TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(30));
    }

    /// <summary>Serwer podał wersję paczki agenta: nowszą pobieramy i przygotowujemy w tle.</summary>
    public void Offer(ApiClient api, AgentUpdateInfo info)
    {
        if (!IsNewer(info.Version, Program.Version)) return;
        lock (gate)
        {
            if (staged?.Version == info.Version || applying) return;
            if (failedVersion == info.Version && DateTime.UtcNow - failedAt < RetryFailedAfter) return;
        }
        if (Rejected().Contains(info.Version)) return;
        if (Interlocked.Exchange(ref preparing, 1) == 1) return;
        Task.Run(async () =>
        {
            try
            {
                SetStatus("Pobieram aktualizację " + info.Version + "…");
                var folder = await PrepareAsync(api, info).ConfigureAwait(false);
                lock (gate) staged = new Staged { Version = info.Version, Folder = folder };
                Log.Info("Aktualizacja agenta " + info.Version + " gotowa, czeka na bezczynność komputera");
                SetStatus("Aktualizacja " + info.Version + " zainstaluje się, gdy komputer będzie bezczynny");
            }
            catch (Exception error)
            {
                lock (gate)
                {
                    failedVersion = info.Version;
                    failedAt = DateTime.UtcNow;
                }
                Log.Error("Aktualizacja agenta " + info.Version + " nieudana", error);
                SetStatus("Aktualizacja " + info.Version + " nieudana, ponowię za godzinę");
            }
            finally
            {
                Interlocked.Exchange(ref preparing, 0);
            }
        });
    }

    /// <summary>Instaluje przygotowaną wersję od razu (menu ikony), bez czekania na bezczynność.</summary>
    public bool CanInstallNow
    {
        get { lock (gate) return staged != null && !applying; }
    }

    public void InstallNow() => TryApply(force: true);

    private async Task<string> PrepareAsync(ApiClient api, AgentUpdateInfo info)
    {
        Directory.CreateDirectory(Folder);
        var zip = Path.Combine(Folder, "agent-" + Safe(info.Version) + ".zip");
        var target = Path.Combine(Folder, "agent-" + Safe(info.Version));
        if (File.Exists(zip)) File.Delete(zip);
        await api.DownloadUpdateAsync(zip, CancellationToken.None).ConfigureAwait(false);
        var size = new FileInfo(zip).Length;
        if (info.SizeBytes > 0 && size != info.SizeBytes)
        {
            throw new InvalidDataException("Paczka ma " + size + " B zamiast " + info.SizeBytes + " B");
        }
        if (!string.Equals(Sha256(zip), info.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("Suma SHA-256 paczki nie zgadza się z serwerem");
        }
        if (Directory.Exists(target)) Directory.Delete(target, true);
        Extract(zip, target);
        File.Delete(zip);

        var exe = Path.Combine(target, Path.GetFileName(ExePath));
        if (!File.Exists(exe)) throw new InvalidDataException("W paczce nie ma " + Path.GetFileName(ExePath));
        var version = FileVersionInfo.GetVersionInfo(exe).FileVersion ?? "";
        if (!SameVersion(version, info.Version))
        {
            throw new InvalidDataException("Exe w paczce ma wersję " + version + ", a serwer podał " + info.Version);
        }
        CheckSignature(exe);
        return target;
    }

    /// <summary>Rozpakowanie bez wychodzenia poza folder; wspólny folder na górze archiwum (spakowany net48) pomijamy.</summary>
    private static void Extract(string zip, string target)
    {
        using var archive = ZipFile.OpenRead(zip);
        var files = archive.Entries.Where(entry => !string.IsNullOrEmpty(entry.Name)).ToList();
        var exeName = Path.GetFileName(ExePath);
        var exe = files.FirstOrDefault(entry => entry.Name.Equals(exeName, StringComparison.OrdinalIgnoreCase))
            ?? throw new InvalidDataException("W paczce nie ma " + exeName);
        var prefix = exe.FullName.Substring(0, exe.FullName.Length - exe.Name.Length).Replace('\\', '/');
        var root = Path.GetFullPath(target) + Path.DirectorySeparatorChar;
        foreach (var entry in files)
        {
            var name = entry.FullName.Replace('\\', '/');
            if (!name.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) continue;
            var relative = name.Substring(prefix.Length);
            var path = Path.GetFullPath(Path.Combine(target, relative));
            if (!path.StartsWith(root, StringComparison.OrdinalIgnoreCase) || relative.Contains(".."))
            {
                throw new InvalidDataException("Niedozwolona ścieżka w paczce: " + entry.FullName);
            }
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            entry.ExtractToFile(path, true);
        }
    }

    private void TryApply(bool force = false)
    {
        Staged? ready;
        lock (gate)
        {
            if (staged == null || applying) return;
            if (!force && (IdleTime() < IdleNeeded || busy())) return;
            applying = true;
            ready = staged;
        }
        try
        {
            Apply(ready);
        }
        catch (Exception error)
        {
            Log.Error("Instalacja aktualizacji agenta " + ready.Version, error);
            lock (gate)
            {
                staged = null;
                applying = false;
                failedVersion = ready.Version;
                failedAt = DateTime.UtcNow;
            }
            SetStatus("Instalacja aktualizacji " + ready.Version + " nieudana, ponowię za godzinę");
        }
    }

    /// <summary>Podmiana plików: stary plik dostaje nazwę *.old (działający exe też), nowy trafia na jego miejsce.</summary>
    private void Apply(Staged ready)
    {
        var app = AppFolder;
        var files = Directory.GetFiles(ready.Folder, "*", SearchOption.AllDirectories)
            .Select(path => path.Substring(ready.Folder.Length).TrimStart(Path.DirectorySeparatorChar))
            .ToList();
        var done = new List<string>();
        try
        {
            foreach (var relative in files)
            {
                var target = Path.Combine(app, relative);
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                var old = target + ".old";
                if (File.Exists(old)) File.Delete(old);
                if (File.Exists(target)) File.Move(target, old);
                done.Add(relative);
                File.Copy(Path.Combine(ready.Folder, relative), target);
            }
        }
        catch
        {
            Restore(app, done);
            throw;
        }
        WritePending(new Pending { From = Program.Version, To = ready.Version, Files = files, At = DateTime.Now });
        Log.Info("Aktualizacja agenta " + Program.Version + " → " + ready.Version + ": pliki podmienione, uruchamiam nową wersję");
        try
        {
            Process.Start(new ProcessStartInfo(Path.Combine(app, Path.GetFileName(ExePath)), WaitArg + " " + UpdatedArg)
            {
                UseShellExecute = false,
                WorkingDirectory = app,
            })?.Dispose();
        }
        catch
        {
            // Nowa wersja się nie uruchomiła: ten agent działa dalej na starych plikach.
            Restore(app, files);
            TryDelete(PendingPath);
            throw;
        }
        exit();
    }

    /// <summary>Nowa wersja działa od minuty: kopie *.old i paczka nie są już potrzebne.</summary>
    private void Confirm()
    {
        var pending = ReadPending();
        if (pending == null) return;
        Log.Info("Aktualizacja agenta do " + pending.To + " udana");
        TryDelete(PendingPath);
        CleanOld();
    }

    /// <summary>
    /// Nowa wersja nie wystartowała (wołane z <see cref="Program"/> przy błędzie startu): wracamy do plików *.old,
    /// zapamiętujemy wersję jako odrzuconą i uruchamiamy poprzednią. Zwraca, czy coś cofnięto.
    /// </summary>
    public static bool RollBackAfterFailedStart()
    {
        try
        {
            var pending = ReadPending();
            if (pending == null) return false;
            Log.Warn("Aktualizacja agenta do " + pending.To + " nie wystartowała, wracam do " + pending.From);
            Restore(AppFolder, pending.Files);
            File.AppendAllText(RejectedPath, pending.To + Environment.NewLine);
            TryDelete(PendingPath);
            Process.Start(new ProcessStartInfo(Path.Combine(AppFolder, Path.GetFileName(ExePath)), WaitArg)
            {
                UseShellExecute = false,
                WorkingDirectory = AppFolder,
            })?.Dispose();
            return true;
        }
        catch (Exception error)
        {
            Log.Error("Cofanie aktualizacji agenta", error);
            return false;
        }
    }

    private static void Restore(string app, IEnumerable<string> relatives)
    {
        foreach (var relative in relatives)
        {
            var target = Path.Combine(app, relative);
            var old = target + ".old";
            try
            {
                if (File.Exists(target))
                {
                    var bad = target + ".bad";
                    if (File.Exists(bad)) File.Delete(bad);
                    File.Move(target, bad);
                }
                if (File.Exists(old)) File.Move(old, target);
            }
            catch (Exception error)
            {
                Log.Error("Cofanie pliku " + relative, error);
            }
        }
    }

    /// <summary>Kopie *.old i *.bad w folderze agenta i rozpakowane paczki (zablokowane zostają do następnego razu).</summary>
    private static void CleanOld()
    {
        try
        {
            foreach (var pattern in new[] { "*.old", "*.bad" })
            {
                foreach (var file in Directory.GetFiles(AppFolder, pattern, SearchOption.AllDirectories)) TryDelete(file);
            }
            if (Directory.Exists(Folder))
            {
                foreach (var dir in Directory.GetDirectories(Folder, "agent-*"))
                {
                    try { Directory.Delete(dir, true); } catch (Exception) { }
                }
                foreach (var zip in Directory.GetFiles(Folder, "agent-*.zip")) TryDelete(zip);
            }
        }
        catch (Exception error)
        {
            Log.Warn("Sprzątanie po aktualizacji agenta: " + error.Message);
        }
    }

    /// <summary>
    /// Podpis nowego exe: gdy ten agent jest podpisany, nowy musi mieć ważny podpis (WinVerifyTrust) i ten sam podmiot
    /// certyfikatu. Niepodpisany agent (zbudowany lokalnie) przyjmuje wersję na podstawie samej sumy SHA-256 z serwera.
    /// </summary>
    private static void CheckSignature(string exe)
    {
        var mine = Signer(ExePath);
        if (mine == null)
        {
            Log.Warn("Agent nie jest podpisany: aktualizację sprawdza tylko suma SHA-256 z serwera");
            return;
        }
        if (!VerifyTrust(exe)) throw new InvalidDataException("Nowy exe nie ma ważnego podpisu cyfrowego");
        var theirs = Signer(exe) ?? throw new InvalidDataException("Nowy exe nie ma podpisu cyfrowego");
        if (!string.Equals(mine.Subject, theirs.Subject, StringComparison.Ordinal))
        {
            throw new InvalidDataException("Nowy exe podpisał ktoś inny: " + theirs.Subject);
        }
    }

    private static X509Certificate2? Signer(string path)
    {
        try { return new X509Certificate2(X509Certificate.CreateFromSignedFile(path)); }
        catch (Exception) { return null; }
    }

    private void SetStatus(string? text)
    {
        Status = text;
        StatusChanged?.Invoke();
    }

    private static Pending? ReadPending()
    {
        try
        {
            return File.Exists(PendingPath)
                ? JsonSerializer.Deserialize<Pending>(File.ReadAllText(PendingPath, Encoding.UTF8))
                : null;
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static void WritePending(Pending pending)
    {
        Directory.CreateDirectory(Folder);
        File.WriteAllText(PendingPath, JsonSerializer.Serialize(pending), Encoding.UTF8);
    }

    private static HashSet<string> Rejected()
    {
        try
        {
            return File.Exists(RejectedPath)
                ? new HashSet<string>(File.ReadAllLines(RejectedPath).Select(line => line.Trim()).Where(line => line != ""))
                : new HashSet<string>();
        }
        catch (Exception)
        {
            return new HashSet<string>();
        }
    }

    /// <summary>Czy <paramref name="offered"/> (np. 1.5.0, „v1.5”) jest nowsza od <paramref name="current"/>.</summary>
    public static bool IsNewer(string offered, string current) =>
        Parse(offered) is { } a && Parse(current) is { } b && a > b;

    private static bool SameVersion(string fileVersion, string offered) =>
        Parse(fileVersion) is { } a && Parse(offered) is { } b && a == b;

    private static Version? Parse(string text)
    {
        var clean = text.Trim().TrimStart('v', 'V');
        if (!System.Version.TryParse(clean.IndexOf('.') >= 0 ? clean : clean + ".0", out var version)) return null;
        // 1.5 = 1.5.0 = 1.5.0.0
        return new Version(version.Major, version.Minor, Math.Max(0, version.Build), Math.Max(0, version.Revision));
    }

    private static string Safe(string version) =>
        new string(version.Where(c => char.IsLetterOrDigit(c) || c == '.' || c == '-').ToArray());

    private static string Sha256(string path)
    {
        using var sha = SHA256.Create();
        using var stream = File.OpenRead(path);
        return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "");
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch (Exception) { }
    }

    public void Dispose()
    {
        timer?.Dispose();
        confirm?.Dispose();
    }

    // Bezczynność: czas od ostatniego ruchu myszy albo klawisza w tej sesji Windows (także od klawiszy makra).
    [StructLayout(LayoutKind.Sequential)]
    private struct LastInputInfo
    {
        public uint Size;
        public uint Time;
    }

    [DllImport("user32.dll")]
    private static extern bool GetLastInputInfo(ref LastInputInfo info);

    private static TimeSpan IdleTime()
    {
        var info = new LastInputInfo { Size = (uint)Marshal.SizeOf(typeof(LastInputInfo)) };
        if (!GetLastInputInfo(ref info)) return TimeSpan.Zero;
        return TimeSpan.FromMilliseconds(unchecked((uint)Environment.TickCount - info.Time));
    }

    // Weryfikacja podpisu Authenticode (WinVerifyTrust), bez sprawdzania odwołań: komputer sklepu bywa bez dostępu
    // do list CRL, a certyfikat porównujemy i tak z certyfikatem działającego agenta.
    private static readonly Guid GenericVerifyV2 = new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WinTrustFileInfo
    {
        public uint Size;
        public string FilePath;
        public IntPtr File;
        public IntPtr KnownSubject;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WinTrustData
    {
        public uint Size;
        public IntPtr PolicyCallbackData;
        public IntPtr SipClientData;
        public uint UiChoice;
        public uint RevocationChecks;
        public uint UnionChoice;
        public IntPtr FileInfo;
        public uint StateAction;
        public IntPtr StateData;
        public IntPtr UrlReference;
        public uint ProvFlags;
        public uint UiContext;
        public IntPtr SignatureSettings;
    }

    [DllImport("wintrust.dll", CharSet = CharSet.Unicode)]
    private static extern int WinVerifyTrust(IntPtr window, [MarshalAs(UnmanagedType.LPStruct)] Guid action,
        ref WinTrustData data);

    private static bool VerifyTrust(string path)
    {
        var file = new WinTrustFileInfo { Size = (uint)Marshal.SizeOf(typeof(WinTrustFileInfo)), FilePath = path };
        var filePtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(WinTrustFileInfo)));
        try
        {
            Marshal.StructureToPtr(file, filePtr, false);
            var data = new WinTrustData
            {
                Size = (uint)Marshal.SizeOf(typeof(WinTrustData)),
                UiChoice = 2, // WTD_UI_NONE
                RevocationChecks = 0, // WTD_REVOKE_NONE
                UnionChoice = 1, // WTD_CHOICE_FILE
                FileInfo = filePtr,
                StateAction = 0,
                ProvFlags = 0x1000, // WTD_CACHE_ONLY_URL_RETRIEVAL
            };
            return WinVerifyTrust(new IntPtr(-1), GenericVerifyV2, ref data) == 0;
        }
        finally
        {
            Marshal.DestroyStructure(filePtr, typeof(WinTrustFileInfo));
            Marshal.FreeHGlobal(filePtr);
        }
    }
}
