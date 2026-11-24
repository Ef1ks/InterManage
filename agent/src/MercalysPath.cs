using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using Microsoft.Win32;

namespace Intermanage.Agent;

/// <summary>
/// Położenie Mercalys, z którego odtwarzacz makr uruchamia wyłączony program (ta sama wartość rejestru co w player.ahk).
/// Wskazuje się je w ustawieniach agenta; nieznane agent bierze sam z działającego Mercalys, z domyślnego miejsca
/// (<see cref="Default"/>) albo ze skrótu na pulpicie, w menu Start lub na pasku zadań.
/// </summary>
internal static class MercalysPath
{
    private const string Key = @"Software\Intermanage\Makra";
    private const string Value = "MercalysPath";
    private const string ProcessName = "Mercalys";
    /// <summary>Miejsce Mercalys na dysku sieciowym, takie samo w każdym sklepie.</summary>
    private const string Default = @"N:\AvisoConfig\UTILITAIRES\ClientLourd\Mercalys.exe";

    public static string? Saved
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(Key, false);
            var path = key?.GetValue(Value) as string;
            return string.IsNullOrWhiteSpace(path) ? null : path;
        }
    }

    public static void Save(string path)
    {
        using var key = Registry.CurrentUser.CreateSubKey(Key, true);
        key?.SetValue(Value, path);
        Log.Info("Położenie Mercalys: " + path);
    }

    /// <summary>Zapisane położenie, o ile plik istnieje, a bez niego znalezione i zapisane; null = nieznane.</summary>
    public static string? Ensure()
    {
        try
        {
            var saved = Saved;
            if (saved != null && File.Exists(saved)) return saved;
            // Dysk z wybranym programem chwilowo niedostępny: nie zamieniamy wyboru na znaleziony skrót.
            if (saved != null && !Directory.Exists(Path.GetPathRoot(saved))) return null;
            var found = FromRunning() ?? (File.Exists(Default) ? Default : FromShortcuts());
            if (found != null) Save(found);
            return found;
        }
        catch (Exception error)
        {
            Log.Warn("Położenie Mercalys: " + error.Message);
            return null;
        }
    }

    private static string? FromRunning()
    {
        foreach (var process in Process.GetProcessesByName(ProcessName))
        {
            using (process)
            {
                var path = PlayerHost.ImagePath(process.Id);
                if (path != null) return path;
            }
        }
        return null;
    }

    private static string? FromShortcuts()
    {
        var found = new List<string>();
        Collect(found, Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), false);
        Collect(found, Environment.GetFolderPath(Environment.SpecialFolder.CommonDesktopDirectory), false);
        Collect(found, Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            @"Microsoft\Internet Explorer\Quick Launch\User Pinned\TaskBar"), false);
        Collect(found, Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), true);
        Collect(found, Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), true);
        // Najkrótsza nazwa: „Mercalys” przed „Mercalys - pomoc”.
        return found.OrderBy(path => Path.GetFileName(path).Length).FirstOrDefault();
    }

    private static void Collect(List<string> found, string folder, bool subfolders)
    {
        if (folder.Length == 0 || !Directory.Exists(folder)) return;
        try
        {
            found.AddRange(Directory.GetFiles(folder, "*" + ProcessName + "*.lnk"));
        }
        catch (UnauthorizedAccessException)
        {
            return;
        }
        if (!subfolders) return;
        foreach (var child in Directory.GetDirectories(folder))
        {
            // Bez zapętlonych łączy (stare „Programy” w menu Start).
            if ((File.GetAttributes(child) & FileAttributes.ReparsePoint) == 0) Collect(found, child, true);
        }
    }
}
