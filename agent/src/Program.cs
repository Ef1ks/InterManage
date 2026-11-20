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
            Log.Error("Nieobsłużony błąd", e.ExceptionObject as Exception);
        Log.Info("Start agenta " + Version + " na " + Environment.MachineName);
        Application.Run(new TrayApp());
        GC.KeepAlive(mutex);
    }
}
