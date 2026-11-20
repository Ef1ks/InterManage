using System;
using System.IO;
using System.Text;

namespace Intermanage.Agent;

/// <summary>
/// Dziennik agenta w %LOCALAPPDATA%\Intermanage\Agent\logs, plik na dzień, 14 dni. Nigdy nie zapisujemy treści plików
/// ani klucza agenta, tylko nazwy plików, foldery i opisy błędów.
/// </summary>
internal static class Log
{
    private static readonly object Gate = new();
    private const int KeepDays = 14;
    private static DateTime cleanedOn = DateTime.MinValue;

    public static string Folder => Path.Combine(AgentSettings.Folder, "logs");

    public static void Info(string message) => Write("INFO", message);

    public static void Warn(string message) => Write("WARN", message);

    public static void Error(string message, Exception? error = null) =>
        Write("ERROR", error == null ? message : message + ": " + error.GetType().Name + ": " + error.Message);

    private static void Write(string level, string message)
    {
        try
        {
            lock (Gate)
            {
                Directory.CreateDirectory(Folder);
                var now = DateTime.Now;
                var file = Path.Combine(Folder, "agent-" + now.ToString("yyyy-MM-dd") + ".log");
                File.AppendAllText(file, now.ToString("HH:mm:ss.fff") + " " + level + " " + message + Environment.NewLine,
                    Encoding.UTF8);
                if (cleanedOn != now.Date)
                {
                    cleanedOn = now.Date;
                    Cleanup(now);
                }
            }
        }
        catch
        {
            // Dziennik nie może zatrzymać agenta (pełny dysk, brak uprawnień).
        }
    }

    private static void Cleanup(DateTime now)
    {
        foreach (var file in Directory.GetFiles(Folder, "agent-*.log"))
        {
            if (File.GetLastWriteTime(file) < now.Date.AddDays(-KeepDays))
            {
                try { File.Delete(file); } catch { /* następnym razem */ }
            }
        }
    }
}
