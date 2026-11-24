using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace Intermanage.Agent;

/// <summary>
/// Odtwarzacz makr (player.exe z player.ahk) jedzie w folderze agenta: <c>player\player.exe</c> razem z plikiem
/// <c>player\agent-mode</c> (odtwarzacz nie pokazuje wtedy własnej ikony i nie startuje sam z Windows). Agent uruchamia
/// go stamtąd i pilnuje, żeby działał. Pracownik dalej uruchamia makra skopiowane w przeglądarce przez Alt+E.
///
/// Odtwarzacz nie jest wypakowywany do %LOCALAPPDATA% (jak w 1.4.0-1.5.0): komputery sklepu pozwalają uruchamiać
/// programy tylko z dozwolonych folderów (np. P:\Production), a próba uruchomienia z AppData kończyła się odmową
/// dostępu i zabiciem agenta. Folder agenta jest dozwolony, skoro działa z niego agent.
///
/// Agent bez player\player.exe obok (zbudowany bez odtwarzacza) nic tu nie robi: działa wtedy osobny odtwarzacz.
/// </summary>
internal sealed class PlayerHost : IDisposable
{
    /// <summary>Produkt z <c>;@Ahk2Exe-SetName</c> w player.ahk.</summary>
    public const string Product = "Intermanage Makra";
    private static readonly TimeSpan CheckEvery = TimeSpan.FromSeconds(20);

    private static string Folder => Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "player");
    private static string ExePath => Path.Combine(Folder, "player.exe");

    private readonly object gate = new();
    private Timer? timer;
    private bool missingBefore;
    private bool startedOnce;
    private bool othersStopped;
    private bool disposed;

    /// <summary>Czy agent ma obok siebie odtwarzacz.</summary>
    public static bool Embedded => File.Exists(ExePath);

    /// <summary>Wersja odtwarzacza (np. „0.63”) albo null.</summary>
    public string? Version { get; private set; }

    /// <summary>Windows nie pozwolił uruchomić odtwarzacza (zasady uruchamiania programów); agent nie ponawia.</summary>
    public string? Blocked { get; private set; }

    public event Action? Changed;

    public void Start()
    {
        if (!Embedded)
        {
            Log.Info("Odtwarzacz makr: brak " + ExePath + ", makra wykonuje osobny player.exe");
            return;
        }
        Version = FileVersionInfo.GetVersionInfo(ExePath).ProductVersion?.Trim();
        if (Version != null && Version.EndsWith(".0.0")) Version = Version.Substring(0, Version.Length - 4);
        EnsureAgentMode();
        RemoveOldCopy();
        Changed?.Invoke();
        timer = new Timer(_ => Check(), null, TimeSpan.Zero, CheckEvery);
    }

    private void Check()
    {
        lock (gate)
        {
            if (disposed || Blocked != null) return;
            try
            {
                if (!othersStopped)
                {
                    StopOthers();
                    othersStopped = true;
                }
                if (Running().Count > 0)
                {
                    missingBefore = false;
                    return;
                }
                // Esc w trakcie makra robi Reload: przez chwilę nie ma procesu. Uruchamiamy dopiero, gdy brak go dwa razy z rzędu,
                // a przy pierwszym sprawdzeniu od razu.
                if (missingBefore || !startedOnce)
                {
                    Process.Start(new ProcessStartInfo(ExePath)
                    {
                        UseShellExecute = false,
                        WorkingDirectory = Folder,
                    })?.Dispose();
                    startedOnce = true;
                    missingBefore = false;
                    Log.Info("Odtwarzacz makr " + Version + " uruchomiony z " + ExePath);
                }
                else
                {
                    missingBefore = true;
                }
            }
            catch (System.ComponentModel.Win32Exception error)
            {
                // Zasady uruchamiania programów: kolejne próby nic nie dadzą, a zabezpieczenia mogą je karać.
                Blocked = error.Message;
                timer?.Dispose();
                Log.Error("Odtwarzacz makr: Windows nie pozwolił uruchomić " + ExePath + " (" + error.Message
                    + "); nie ponawiam do ponownego uruchomienia agenta");
                Changed?.Invoke();
            }
            catch (Exception error)
            {
                Log.Error("Odtwarzacz makr", error);
            }
        }
    }

    /// <summary>Plik agent-mode jedzie w paczce; gdyby go brakowało, próbujemy go dopisać (folder bywa tylko do odczytu).</summary>
    private static void EnsureAgentMode()
    {
        var mode = Path.Combine(Folder, "agent-mode");
        if (File.Exists(mode)) return;
        try
        {
            File.WriteAllText(mode, "Odtwarzacz makr uruchamia agent sklepu Intermanage." + Environment.NewLine);
        }
        catch (Exception error)
        {
            Log.Warn("Odtwarzacz makr: brak pliku agent-mode obok odtwarzacza (" + error.Message
                + "), odtwarzacz pokaże własną ikonę");
        }
    }

    /// <summary>Kopia wypakowana przez agenta 1.4.0-1.5.0 do %LOCALAPPDATA% nie jest już potrzebna.</summary>
    private static void RemoveOldCopy()
    {
        var old = Path.Combine(AgentSettings.Folder, "player");
        if (!Directory.Exists(old)) return;
        try { Directory.Delete(old, true); }
        catch (Exception) { /* działa jeszcze stary odtwarzacz: następnym razem */ }
    }

    /// <summary>
    /// Osobny odtwarzacz (dawny player.exe, AutoJob.exe) obok tego z agenta wykonałby makro drugi raz, więc go zamykamy.
    /// Dawny wpis autostartu usuwa sam odtwarzacz z agenta.
    /// </summary>
    private static void StopOthers()
    {
        var others = new List<Process>();
        foreach (var process in SessionProcesses())
        {
            var path = ImagePath(process.Id);
            if (path == null || IsOurs(path) || !IsPlayer(process.ProcessName, path))
            {
                process.Dispose();
                continue;
            }
            Log.Info("Odtwarzacz makr: zamykam osobny odtwarzacz " + path);
            others.Add(process);
        }
        StopProcesses(others);
    }

    private static bool IsPlayer(string name, string path)
    {
        if (name.StartsWith("player", StringComparison.OrdinalIgnoreCase)
            || name.StartsWith("autojob", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }
        try
        {
            return string.Equals(FileVersionInfo.GetVersionInfo(path).ProductName?.Trim(), Product,
                StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static bool IsOurs(string path) =>
        string.Equals(Path.GetFullPath(path), Path.GetFullPath(ExePath), StringComparison.OrdinalIgnoreCase);

    /// <summary>Procesy odtwarzacza z agenta w tej sesji.</summary>
    private static List<Process> Running()
    {
        var found = new List<Process>();
        foreach (var process in SessionProcesses())
        {
            var path = ImagePath(process.Id);
            if (path != null && IsOurs(path)) found.Add(process);
            else process.Dispose();
        }
        return found;
    }

    private static IEnumerable<Process> SessionProcesses()
    {
        var session = Process.GetCurrentProcess().SessionId;
        foreach (var process in Process.GetProcesses())
        {
            bool mine;
            try { mine = process.SessionId == session; }
            catch (Exception) { mine = false; }
            if (mine) yield return process;
            else process.Dispose();
        }
    }

    private static void StopProcesses(List<Process> processes)
    {
        foreach (var process in processes)
        {
            using (process)
            {
                try
                {
                    process.Kill();
                    process.WaitForExit(5000);
                }
                catch (Exception error)
                {
                    Log.Warn("Odtwarzacz makr: nie zamknięto procesu " + process.Id + ": " + error.Message);
                }
            }
        }
    }

    /// <summary>Koniec agenta zamyka też jego odtwarzacz (bez agenta nie ma ikony, którą dałoby się go zamknąć).</summary>
    public void Dispose()
    {
        lock (gate)
        {
            if (disposed) return;
            disposed = true;
            timer?.Dispose();
            if (Embedded && startedOnce) StopProcesses(Running());
        }
    }

    private const uint QueryLimitedInformation = 0x1000;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, int processId);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool QueryFullProcessImageName(IntPtr process, int flags, System.Text.StringBuilder name,
        ref int size);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>Ścieżka pliku procesu, także 64-bitowego z 32-bitowego agenta (MainModule tego nie umie).</summary>
    public static string? ImagePath(int processId)
    {
        var handle = OpenProcess(QueryLimitedInformation, false, processId);
        if (handle == IntPtr.Zero) return null;
        try
        {
            var size = 1024;
            var name = new System.Text.StringBuilder(size);
            return QueryFullProcessImageName(handle, 0, name, ref size) ? name.ToString() : null;
        }
        finally
        {
            CloseHandle(handle);
        }
    }
}
