using System;
using System.IO;
using Microsoft.Win32;

namespace Intermanage.Agent;

/// <summary>
/// Położenie Mercalys, z którego odtwarzacz makr uruchamia wyłączony program (ta sama wartość rejestru co w player.ahk).
/// Wskazuje się je w ustawieniach agenta; nieznane agent bierze ze ścieżki sklepu podanej przez serwer
/// (<c>mercalysPath</c> w /hello, od 1.14.0) albo z domyślnego miejsca (<see cref="Default"/>). Agent nie zagląda do
/// działającego Mercalys (otwieranie cudzego procesu to dla antywirusa sklepów zachowanie podejrzane).
/// </summary>
internal static class MercalysPath
{
    private const string Key = @"Software\Intermanage\Makra";
    private const string Value = "MercalysPath";
    /// <summary>Miejsce Mercalys na dysku sieciowym, takie samo w każdym sklepie.</summary>
    private const string Default = @"N:\AvisoConfig\UTILITAIRES\ClientLourd\Mercalys.exe";

    /// <summary>Ścieżka sklepu z serwera (null = starszy serwer).</summary>
    public static volatile string? FromServer;

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
            var server = FromServer;
            var found = server != null && File.Exists(server) ? server : File.Exists(Default) ? Default : null;
            if (found != null) Save(found);
            return found;
        }
        catch (Exception error)
        {
            Log.Warn("Położenie Mercalys: " + error.Message);
            return null;
        }
    }
}
