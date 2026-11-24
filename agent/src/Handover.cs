using System;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Agent;

/// <summary>
/// Jedna instancja agenta na użytkownika i przejęcie pracy po poprzedniej wersji (od 1.7.5): uruchomiona nowa wersja
/// prosi działającą o zamknięcie (zdarzenie Windows), czeka, aż ta skończy makro i wysyłkę, i dopiero startuje. Wersje do
/// 1.7.4 prośby nie znają: okno mówi wtedy, żeby je zamknąć ręcznie, i startuje zaraz po tym.
/// </summary>
internal static class Handover
{
    private const string InstanceName = @"Local\Intermanage.Agent";
    private const string WaitingName = @"Local\Intermanage.Agent.Waiting";
    private const string ExitName = @"Local\Intermanage.Agent.Exit";
    private const string Title = "Intermanage – agent sklepu";

    /// <summary>Wersja i plik działającego agenta, żeby nowa wersja wiedziała, kogo zastępuje.</summary>
    private static string RunningFile => Path.Combine(AgentSettings.Folder, "dziala.txt");

    /// <summary>Zajmuje miejsce jedynej instancji; null = inna zostaje (albo człowiek zrezygnował).</summary>
    public static Mutex? Acquire()
    {
        var mutex = new Mutex(true, InstanceName, out var created);
        if (created || TakeOver(mutex))
        {
            WriteRunning();
            return mutex;
        }
        mutex.Dispose();
        return null;
    }

    /// <summary>Prośba o zamknięcie od nowej wersji: woła <paramref name="exit"/> raz, w wątku w tle.</summary>
    public static IDisposable Listen(Action exit)
    {
        var request = new EventWaitHandle(false, EventResetMode.AutoReset, ExitName);
        ThreadPool.RegisterWaitForSingleObject(request, (_, _) => exit(), null, Timeout.Infinite, true);
        return request;
    }

    private static bool TakeOver(Mutex mutex)
    {
        // Dwuklik kilka razy: czeka tylko pierwsza nowa wersja.
        using var waiting = new Mutex(true, WaitingName, out var first);
        if (!first) return false;
        EventWaitHandle.TryOpenExisting(ExitName, out var request);
        using (request)
        {
            if (request != null && !Confirmed()) return false;
            request?.Set();
            using var form = new WaitForm(mutex, request != null);
            Application.Run(form);
            // Niepobrana prośba (poprzednia wersja padła) nie może zamknąć nas samych.
            if (form.Acquired) request?.Reset();
            return form.Acquired;
        }
    }

    private static bool Confirmed()
    {
        var (version, path) = ReadRunning();
        var same = version == Program.Version;
        if (same && VersionWatch.Same(path, Application.ExecutablePath))
        {
            MessageBox.Show("Agent sklepu już działa: ikona jest przy zegarze.", Title, MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return false;
        }
        if (version.Length == 0 || !SelfUpdate.IsNewer(version, Program.Version)) return true;
        return MessageBox.Show("Działa nowsza wersja agenta (" + version + ").\n\nCzy wrócić do wersji " + Program.Version
            + "? Usuń potem folder nowszej wersji, inaczej agent znów przestawi na nią autostart.", Title,
            MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2) == DialogResult.Yes;
    }

    private static void WriteRunning()
    {
        try
        {
            Directory.CreateDirectory(AgentSettings.Folder);
            File.WriteAllText(RunningFile, Program.Version + "\n" + Application.ExecutablePath, Encoding.UTF8);
        }
        catch (Exception error)
        {
            Log.Warn("Zapis wersji działającego agenta: " + error.Message);
        }
    }

    private static (string Version, string Path) ReadRunning()
    {
        try
        {
            var lines = File.ReadAllText(RunningFile, Encoding.UTF8).Split('\n');
            return (lines[0].Trim(), lines.Length > 1 ? lines[1].Trim() : "");
        }
        catch (Exception)
        {
            return ("", "");
        }
    }

    private sealed class WaitForm : Form
    {
        private readonly Mutex mutex;
        private readonly System.Windows.Forms.Timer timer = new() { Interval = 300 };

        public bool Acquired { get; private set; }

        public WaitForm(Mutex mutex, bool asked)
        {
            this.mutex = mutex;
            Text = Title;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Dpi;
            Font = new Font("Segoe UI", 10f);
            ClientSize = new Size(460, 170);
            // Po prośbie poprzednia wersja i tak się zamknie, więc tego okna nie da się porzucić.
            ControlBox = !asked;
            MinimizeBox = false;
            Controls.Add(new Label
            {
                Dock = DockStyle.Fill,
                Padding = new Padding(18),
                TextAlign = ContentAlignment.MiddleCenter,
                Text = asked
                    ? "Poprzednia wersja agenta kończy pracę (makro albo wysyłkę pliku).\n\nWersja " + Program.Version
                        + " wystartuje sama, nic nie klikaj."
                    : "Działa starsza wersja agenta, która nie umie zamknąć się sama.\n\nKliknij jej ikonę przy zegarze "
                        + "prawym przyciskiem i wybierz „Zakończ”. Wersja " + Program.Version + " wystartuje sama.",
            });
            timer.Tick += (_, _) => TryAcquire();
            Shown += (_, _) => timer.Start();
            FormClosed += (_, _) => timer.Dispose();
        }

        private void TryAcquire()
        {
            try
            {
                if (!mutex.WaitOne(0)) return;
            }
            catch (AbandonedMutexException)
            {
                // Poprzednia wersja zakończyła proces bez zwolnienia: miejsce jest nasze.
            }
            Acquired = true;
            Close();
        }
    }
}
