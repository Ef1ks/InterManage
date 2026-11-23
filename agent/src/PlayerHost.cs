using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Threading;

namespace Intermanage.Agent;

/// <summary>
/// Odtwarzacz makr (player.exe z player.ahk) schowany w agencie: jedzie w IntermanageAgent.exe jako zasób, agent
/// wypakowuje go do %LOCALAPPDATA%\Intermanage\Agent\player (tylko gdy wersja się zmieniła), kładzie obok plik
/// agent-mode (odtwarzacz nie pokazuje wtedy własnej ikony i nie startuje sam z Windows), uruchamia go i pilnuje,
/// żeby działał. Pracownik dalej uruchamia makra skopiowane w przeglądarce przez Alt+E.
///
/// Agent zbudowany bez player.exe (brak pliku przy budowaniu) nic tu nie robi: działa wtedy osobny odtwarzacz jak dawniej.
/// </summary>
internal sealed class PlayerHost : IDisposable
{
    private const string Resource = "Intermanage.Agent.player.exe";
    /// <summary>Produkt z <c>;@Ahk2Exe-SetName</c> w player.ahk.</summary>
    public const string Product = "Intermanage Makra";
    private static readonly TimeSpan CheckEvery = TimeSpan.FromSeconds(20);

    private static string Folder => Path.Combine(AgentSettings.Folder, "player");
    private static string ExePath => Path.Combine(Folder, "player.exe");

    private readonly object gate = new();
    private Timer? timer;
    private bool missingBefore;
    private bool startedOnce;
    private bool disposed;

    /// <summary>Czy agent ma w sobie odtwarzacz.</summary>
    public static bool Embedded => Assembly.GetExecutingAssembly().GetManifestResourceInfo(Resource) != null;

    /// <summary>Wersja wypakowanego odtwarzacza (np. „0.63”) albo null.</summary>
    public string? Version { get; private set; }

    public event Action? Changed;

    public void Start()
    {
        if (!Embedded)
        {
            Log.Info("Odtwarzacz makr: agent bez odtwarzacza w środku, makra wykonuje osobny player.exe");
            return;
        }
        timer = new Timer(_ => Check(), null, TimeSpan.Zero, CheckEvery);
    }

    private void Check()
    {
        lock (gate)
        {
            if (disposed) return;
            try
            {
                if (Version == null)
                {
                    Extract();
                    StopOthers();
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
                    Log.Info("Odtwarzacz makr " + Version + " uruchomiony");
                }
                else
                {
                    missingBefore = true;
                }
            }
            catch (Exception error)
            {
                Log.Error("Odtwarzacz makr", error);
            }
        }
    }

    /// <summary>Wypakowuje odtwarzacz, gdy na dysku go nie ma albo jest inny niż w agencie (porównanie SHA-256).</summary>
    private void Extract()
    {
        Directory.CreateDirectory(Folder);
        byte[] embedded;
        using (var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(Resource)!)
        using (var copy = new MemoryStream())
        {
            stream.CopyTo(copy);
            embedded = copy.ToArray();
        }
        var same = File.Exists(ExePath) && Hash(File.ReadAllBytes(ExePath)) == Hash(embedded);
        if (!same)
        {
            // Stara wersja trzyma plik zablokowany.
            StopProcesses(Running());
            var temp = ExePath + ".intermanage-tmp";
            File.WriteAllBytes(temp, embedded);
            if (File.Exists(ExePath)) File.Delete(ExePath);
            File.Move(temp, ExePath);
            Log.Info("Odtwarzacz makr wypakowany do " + ExePath);
        }
        var mode = Path.Combine(Folder, "agent-mode");
        if (!File.Exists(mode)) File.WriteAllText(mode, "Odtwarzacz makr uruchamia agent sklepu Intermanage.\r\n");
        Version = FileVersionInfo.GetVersionInfo(ExePath).ProductVersion?.Trim();
        if (Version != null && Version.EndsWith(".0.0")) Version = Version.Substring(0, Version.Length - 4);
        Changed?.Invoke();
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

    private static string Hash(byte[] bytes)
    {
        using var sha = SHA256.Create();
        return Convert.ToBase64String(sha.ComputeHash(bytes));
    }

    /// <summary>Koniec agenta zamyka też jego odtwarzacz (bez agenta nie ma ikony, którą dałoby się go zamknąć).</summary>
    public void Dispose()
    {
        lock (gate)
        {
            if (disposed) return;
            disposed = true;
            timer?.Dispose();
            if (Embedded && Version != null) StopProcesses(Running());
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
