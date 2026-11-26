using System;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Agent;

internal static class Program
{
    /// <summary>Ta sama nazwa co we wcześniejszych wersjach, żeby stara i nowa nie działały naraz.</summary>
    private const string InstanceName = @"Local\Intermanage.Agent";

    public static readonly string Version =
        Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0";

    [STAThread]
    private static void Main()
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        // Jedna instancja na użytkownika. Od 1.14.0 nowa wersja nie przejmuje pracy po działającej (agent nie zamyka
        // innych programów ani nie przestawia autostartu): podmieniony plik rusza przy następnym włączeniu komputera.
        using var mutex = new Mutex(true, InstanceName, out var created);
        if (!created)
        {
            Log.Info("Agent " + Version + " nie wystartował: na tym koncie działa już agent");
            MessageBox.Show("Agent sklepu już działa (ikona przy zegarze). Nowa wersja ruszy po ponownym włączeniu komputera.",
                "Intermanage – agent sklepu", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        Application.ThreadException += (_, e) => Log.Error("Nieobsłużony błąd okna", e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            Log.Error("Nieobsłużony błąd", e.ExceptionObject as Exception);
            if (e.IsTerminating) ShowStartError(e.ExceptionObject as Exception);
        };
        Log.Info("Start agenta " + Version + " na " + Environment.MachineName + " z " + AppDomain.CurrentDomain.BaseDirectory);
        try
        {
            Application.Run(new TrayApp());
        }
        catch (Exception error)
        {
            // Bez tego agent znikał bez słowa (np. brak bibliotek obok exe po skopiowaniu samego pliku).
            Log.Error("Agent nie wystartował", error);
            ShowStartError(error);
        }
        GC.KeepAlive(mutex);
    }

    private static void ShowStartError(Exception? error)
    {
        var missing = error is System.IO.FileNotFoundException || error is System.IO.FileLoadException
            || error?.InnerException is System.IO.FileNotFoundException;
        var text = "Agent sklepu nie mógł się uruchomić.\n\n" + (error == null ? "Nieznany błąd" : error.GetType().Name
            + ": " + error.Message) + (missing
            ? "\n\nObok IntermanageAgent.exe brakuje bibliotek. Rozpakuj cały folder agenta z archiwum (exe razem z plikami .dll i .config)."
            : "") + "\n\nDziennik: " + Log.Folder;
        try
        {
            MessageBox.Show(text, "Intermanage – agent sklepu", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        catch
        {
            // Bez okna zostaje dziennik.
        }
    }
}
