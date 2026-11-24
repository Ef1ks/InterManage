using System;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Agent;

internal static class Program
{
    public static readonly string Version =
        Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0";

    [STAThread]
    private static void Main()
    {
        // Jedna instancja na użytkownika (nazwany mutex w przestrzeni sesji).
        using var mutex = new Mutex(true, @"Local\Intermanage.Agent", out var created);
        if (!created)
        {
            MessageBox.Show("Agent sklepu już działa: ikona jest przy zegarze.", "Intermanage – agent sklepu",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
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
