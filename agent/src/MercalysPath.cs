using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using Microsoft.Win32;

namespace Intermanage.Agent;

/// <summary>
/// Położenie Mercalys, z którego odtwarzacz makr uruchamia wyłączony program (ta sama wartość rejestru co w player.ahk).
/// Wskazuje się je w ustawieniach agenta; nieznane agent bierze sam z działającego Mercalys albo z domyślnego miejsca
/// (<see cref="Default"/>).
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
            // Dysk z wybranym programem chwilowo niedostępny: nie zamieniamy wyboru na inny.
            if (saved != null && !Directory.Exists(Path.GetPathRoot(saved))) return null;
            var found = FromRunning() ?? (File.Exists(Default) ? Default : null);
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
                var path = MacroRunner.ImagePath(process.Id);
                if (path != null) return path;
            }
        }
        return null;
    }
}
