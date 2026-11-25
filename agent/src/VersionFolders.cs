using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Agent;

/// <summary>
/// Wersje agenta w osobnych folderach (od 1.7.5): człowiek rozpakowuje paczkę do folderu <c>1.7.5</c> albo
/// <c>agent-1.7.5</c> w folderze agenta lub w <c>aktualizacja</c> („Wyodrębnij wszystkie” przy pobranej paczce), więc
/// nie nadpisuje plików działających agentów. Agent ze starszą wersją tylko o niej mówi; uruchamia ją człowiek dwuklikiem,
/// a nowa wersja przestawia autostart na siebie. Agent sam niczego nie rozpakowuje, nie uruchamia i nie zmienia w tle.
/// </summary>
internal static class VersionFolders
{
    public const string Exe = "IntermanageAgent.exe";
    private const string UpdateName = "aktualizacja";
    private static readonly Regex FolderName = new(@"^(agent[- ]?)?v?\d+\.\d+(\.\d+)?$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    /// <summary>Wspólny folder agenta: bez podfolderu wersji, z którego działa.</summary>
    public static string Root => RootOf(AppDomain.CurrentDomain.BaseDirectory);

    /// <summary>Folder na pobrane paczki, wspólny dla wszystkich wersji i komputerów.</summary>
    public static string UpdateFolder => Path.Combine(Root, UpdateName);

    internal static string RootOf(string baseDirectory)
    {
        var own = baseDirectory.TrimEnd('\\');
        if (!FolderName.IsMatch(Path.GetFileName(own))) return own;
        var parent = Path.GetDirectoryName(own);
        if (parent == null) return own;
        return string.Equals(Path.GetFileName(parent), UpdateName, StringComparison.OrdinalIgnoreCase)
            ? Path.GetDirectoryName(parent) ?? parent
            : parent;
    }

    /// <summary>Najnowsza wersja w folderze agenta i folderach wersji, nowsza od <paramref name="current"/>; null = brak.</summary>
    internal static (string Path, Version Version)? FindNewer(string root, Version current)
    {
        var folders = new List<string> { root };
        foreach (var place in new[] { root, Path.Combine(root, UpdateName) })
        {
            if (Directory.Exists(place))
            {
                folders.AddRange(Directory.GetDirectories(place).Where(f => FolderName.IsMatch(Path.GetFileName(f))));
            }
        }
        (string Path, Version Version)? best = null;
        foreach (var folder in folders)
        {
            var exe = Path.Combine(folder, Exe);
            if (!File.Exists(exe)) continue;
            // Wersja z pliku, nie z nazwy folderu.
            var info = FileVersionInfo.GetVersionInfo(exe);
            var version = new Version(info.FileMajorPart, info.FileMinorPart, info.FileBuildPart);
            if (version > current && (best == null || version > best.Value.Version)) best = (exe, version);
        }
        return best;
    }

    /// <summary>Pliki folderu z rozmiarami: ten sam opis w dwóch sprawdzeniach = rozpakowywanie skończone.</summary>
    internal static string Signature(string folder) => string.Join("|", new DirectoryInfo(folder).GetFiles()
        .OrderBy(f => f.Name, StringComparer.OrdinalIgnoreCase).Select(f => f.Name + ":" + f.Length));
}

/// <summary>Co minutę sprawdza, czy w folderze agenta jest nowsza wersja.</summary>
internal sealed class VersionWatch : IDisposable
{
    private static readonly TimeSpan CheckEvery = TimeSpan.FromMinutes(1);

    private readonly System.Threading.Timer timer;
    private int working;
    private string? lastSignature;
    private string? lastProblem;

    /// <summary>Gotowa nowsza wersja (pełna ścieżka exe) albo null.</summary>
    public string? NewerPath { get; private set; }
    public string? NewerVersion { get; private set; }

    public event Action? Changed;

    public VersionWatch() => timer = new System.Threading.Timer(_ => Check(), null, Timeout.Infinite, Timeout.Infinite);

    public void Start() => timer.Change(TimeSpan.FromSeconds(20), CheckEvery);

    private void Check()
    {
        if (Interlocked.Exchange(ref working, 1) == 1) return;
        try
        {
            var root = VersionFolders.Root;
            if (!Directory.Exists(root)) return; // dysk sieciowy chwilowo niedostępny
            var found = VersionFolders.FindNewer(root, new Version(Program.Version));
            if (found == null)
            {
                lastSignature = null;
                Set(null, null);
                return;
            }
            var signature = found.Value.Path + "|"
                + VersionFolders.Signature(Path.GetDirectoryName(found.Value.Path)!);
            var stable = signature == lastSignature;
            lastSignature = signature;
            if (!stable) return;
            Set(found.Value.Path, found.Value.Version.ToString(3));
            lastProblem = null;
        }
        catch (Exception error)
        {
            if (lastProblem != error.Message) Log.Warn("Sprawdzanie nowej wersji: " + error.Message);
            lastProblem = error.Message;
        }
        finally
        {
            Interlocked.Exchange(ref working, 0);
        }
    }

    private void Set(string? path, string? version)
    {
        if (NewerPath == path && NewerVersion == version) return;
        NewerPath = path;
        NewerVersion = version;
        if (path != null) Log.Info("W folderze agenta jest nowsza wersja " + version + ": " + path);
        Changed?.Invoke();
    }

    internal static bool Same(string a, string b) => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    /// <summary>Menu ikony „Pokaż nową wersję”: plik w Eksploratorze, uruchamia go człowiek (<see cref="Handover"/>).</summary>
    public void ShowFile()
    {
        var path = NewerPath;
        if (path == null) return;
        try
        {
            Process.Start(new ProcessStartInfo("explorer.exe", "/select,\"" + path + "\"") { UseShellExecute = true })
                ?.Dispose();
        }
        catch (Exception error)
        {
            Log.Error("Otwieranie folderu nowej wersji " + path, error);
        }
    }

    public void Dispose() => timer.Dispose();
}
