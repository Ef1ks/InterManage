using System;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Agent;

internal static class Program
{
    public static readonly string Version =
        Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "0.0.0";

    /// <summary>Ten start to nowa wersja po automatycznej aktualizacji (w razie błędu startu wracamy do poprzedniej).</summary>
    private static bool afterUpdate;

    [STAThread]
    private static void Main(string[] args)
    {
        afterUpdate = Array.IndexOf(args, SelfUpdate.UpdatedArg) >= 0;
        // Jedna instancja na użytkownika (nazwany mutex w przestrzeni sesji). Po aktualizacji (--wait) nowa wersja czeka,
        // aż poprzednia się zamknie.
        using var mutex = new Mutex(true, @"Local\Intermanage.Agent", out var created);
        if (!created && Array.IndexOf(args, SelfUpdate.WaitArg) >= 0)
        {
            try { created = mutex.WaitOne(TimeSpan.FromSeconds(90)); }
            catch (AbandonedMutexException) { created = true; }
        }
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
            Application.Run(new TrayApp(afterUpdate));
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
        // Nowa wersja z automatycznej aktualizacji nie wystartowała: wraca poprzednia, bez okna z błędem.
        if (SelfUpdate.RollBackAfterFailedStart()) return;
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
