using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Intermanage.Agent;

/// <summary>
/// Ikona przy zegarze, zawsze widoczna: „Intermanage – agent sklepu”. Kolor = stan (zielona działa, bursztynowa dysk
/// albo serwer niedostępny, czerwona zły klucz, szara bez klucza). Menu: Stan, Otwórz Intermanage, Ustawienia,
/// Sprawdź teraz, Pokaż logi, Uruchamiaj z Windows, Zakończ.
/// </summary>
internal sealed class TrayApp : ApplicationContext
{
    private const string Title = "Intermanage – agent sklepu";

    private readonly AgentHost host = new();
    private readonly PlayerHost player = new();
    private readonly ToolStripMenuItem macros = new("Makra: uruchamiam odtwarzacz…") { Visible = false };
    private readonly NotifyIcon icon;
    private readonly ToolStripMenuItem status = new() { Enabled = false };
    private readonly ToolStripMenuItem activity = new() { Enabled = false, Visible = false };
    private readonly ToolStripMenuItem autostart = new("Uruchamiaj z Windows") { CheckOnClick = true };
    private readonly ToolStripMenuItem updateStatus = new() { Enabled = false, Visible = false };
    private readonly ToolStripMenuItem showUpdate = new("Pokaż pobraną aktualizację") { Visible = false };
    private readonly ToolStripMenuItem runNewer = new() { Visible = false, Font = new Font(SystemFonts.MenuFont, FontStyle.Bold) };
    private readonly SelfUpdate selfUpdate = new();
    private readonly VersionWatch versions = new();
    private readonly IDisposable exitRequest;
    /// <summary>Tyle najwyżej czeka zamykany agent na trwający zapis albo wysyłkę (makro kończy zawsze).</summary>
    private static readonly TimeSpan DrainLimit = TimeSpan.FromMinutes(10);
    private readonly Control ui = new();
    private readonly Icon green = Dot(Color.FromArgb(16, 185, 129));
    private readonly Icon amber = Dot(Color.FromArgb(245, 158, 11));
    private readonly Icon red = Dot(Color.FromArgb(220, 38, 38));
    private readonly Icon gray = Dot(Color.FromArgb(148, 163, 184));
    private AgentSettings settings;
    private readonly DataUpdate update;
    private readonly RemoteMacros remoteMacros;
    private readonly CancellationTokenSource closing = new();
    /// <summary>Aktualizacja przy starcie idzie raz na uruchomienie agenta (nie po zmianie ustawień); 1 = trwa albo była.</summary>
    private int startUpdateDone;
    /// <summary>Serwer albo dysk z raportami nie odpowiada: aktualizacja przy starcie próbuje znowu po tym czasie.</summary>
    private static readonly TimeSpan StartUpdateRetry = TimeSpan.FromMinutes(1);

    public TrayApp()
    {
        // Uchwyt okna od razu: przez niego zmiany stanu z wątków w tle trafiają do ikony.
        _ = ui.Handle;
        settings = AgentSettings.Load();
        update = new DataUpdate(host, ui);
        remoteMacros = new RemoteMacros(host, ui, () => settings);
        selfUpdate.StatusChanged += () =>
        {
            if (ui.IsHandleCreated) ui.BeginInvoke(new Action(UpdateIcon));
        };
        host.UpdateOffered += selfUpdate.Offer;

        var menu = new ContextMenuStrip();
        menu.Items.Add(status);
        menu.Items.Add(activity);
        menu.Items.Add(updateStatus);
        menu.Items.Add(showUpdate);
        menu.Items.Add(runNewer);
        menu.Items.Add(macros);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Otwórz Intermanage", null, (_, _) => Open(settings.ServerUrl));
        menu.Items.Add("Ustawienia…", null, (_, _) => ShowSettings());
        menu.Items.Add("Sprawdź teraz", null, (_, _) => host.ScanNow());
        menu.Items.Add("Aktualizuj dane z Mercalys", null,
            (_, _) => Task.Run(() => update.RunAsync(settings, true, closing.Token)));
        menu.Items.Add("Pokaż logi", null, (_, _) => OpenFolder(Log.Folder));
        menu.Items.Add(autostart);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Zakończ", null, (_, _) => ExitThread());
        showUpdate.Click += (_, _) => selfUpdate.ShowReadyFile();
        runNewer.Click += (_, _) => versions.Launch();
        versions.Changed += () =>
        {
            if (ui.IsHandleCreated) ui.BeginInvoke(new Action(UpdateIcon));
        };
        versions.Start();
        exitRequest = Handover.Listen(() => Task.Run(ExitForNewVersionAsync));
        settings.EnsureAutostart();
        autostart.Checked = AgentSettings.Autostart;
        autostart.Click += (_, _) =>
        {
            try
            {
                AgentSettings.AutostartPath = autostart.Checked ? versions.NewerPath ?? Application.ExecutablePath : null;
                settings.AutostartOff = !autostart.Checked;
                settings.Save();
            }
            catch (Exception error) { Log.Error("Autostart", error); }
        };

        icon = new NotifyIcon { Icon = gray, Text = Title, Visible = true, ContextMenuStrip = menu };
        icon.DoubleClick += (_, _) => ShowSettings();
        host.StateChanged += () =>
        {
            if (ui.IsHandleCreated) ui.BeginInvoke(new Action(UpdateIcon));
        };
        // Przy starcie komputera (agent startuje z Windows): raporty Importu danych nie z dzisiaj aktualizuje makro.
        // Tylko główny agent sklepu, więc przy agencie na kilku komputerach makro nie rusza na każdym (kasa); komputer,
        // który przejmie rolę później, sprawdza raporty wtedy (serwer pilnuje, żeby nie aktualizowały dwa naraz).
        // Gdy serwer albo dysk sieciowy nie odpowiada, próbuje co minutę, aż wróci.
        void StartUpdate()
        {
            if (!settings.UpdateOnStart || host.Config == null || !host.IsLeader) return;
            if (Interlocked.Exchange(ref startUpdateDone, 1) == 1) return;
            Task.Run(async () =>
            {
                if (await update.RunAsync(settings, false, closing.Token)) return;
                await Task.Delay(StartUpdateRetry, closing.Token);
                Interlocked.Exchange(ref startUpdateDone, 0);
                StartUpdate();
            });
        }
        host.Connected += StartUpdate;
        host.LeadershipChanged += StartUpdate;

        // Odtwarzacz makr jest w agencie: własnej ikony nie ma, jego wersja i pomoc są w menu agenta.
        macros.Visible = PlayerHost.Embedded;
        macros.Click += (_, _) => ShowMacroHelp();
        player.Changed += () =>
        {
            if (ui.IsHandleCreated)
            {
                ui.BeginInvoke(new Action(() => macros.Text = player.Blocked != null
                    ? "Makra: Windows zablokował odtwarzacz"
                    : "Makra (Alt+E), odtwarzacz " + player.Version));
            }
        };
        player.Start();
        remoteMacros.Start(closing.Token);

        UpdateIcon();
        if (!settings.HasKey)
        {
            // Pierwsze uruchomienie: od razu okno ustawień.
            ui.BeginInvoke(new Action(ShowSettings));
        }
        else
        {
            host.Start(settings);
        }
    }

    private void UpdateIcon()
    {
        icon.Icon = host.State switch
        {
            AgentState.Working => green,
            AgentState.Degraded => amber,
            AgentState.KeyRejected => red,
            _ => gray,
        };
        status.Text = "Stan: " + host.StatusText;
        activity.Visible = host.LastActivity != null;
        activity.Text = "Ostatnio: " + host.LastActivity;
        // Rozpakowana nowa wersja zastępuje podpowiedź o rozpakowaniu, chyba że pobrano jeszcze nowszą.
        var newer = versions.NewerVersion;
        var toUnpack = newer == null
            || (selfUpdate.ReadyVersion != null && SelfUpdate.IsNewer(selfUpdate.ReadyVersion, newer));
        updateStatus.Text = newer != null && !toUnpack
            ? "Wersja " + newer + " wystartuje przy następnym logowaniu do Windows"
            : selfUpdate.Status ?? "";
        updateStatus.Visible = updateStatus.Text.Length > 0;
        showUpdate.Visible = toUnpack && selfUpdate.ReadyFile != null;
        runNewer.Visible = newer != null;
        runNewer.Text = "Uruchom nową wersję " + newer;
        // Podpowiedź ikony ma limit 63 znaków.
        var tip = Title + "\n" + host.StatusText;
        icon.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;
    }

    /// <summary>Nowa wersja prosi o miejsce: kończymy makro oraz trwający zapis i zamykamy się.</summary>
    private async Task ExitForNewVersionAsync()
    {
        try
        {
            Log.Info("Uruchomiono inną wersję agenta: kończę pracę");
            // Zajęta bramka = żadne nowe makro już nie ruszy.
            await MacroRunner.Gate.WaitAsync(closing.Token).ConfigureAwait(false);
            await host.DrainAsync(DrainLimit).ConfigureAwait(false);
            ui.BeginInvoke(new Action(ExitThread));
        }
        catch (Exception error) when (error is OperationCanceledException || error is ObjectDisposedException
            || error is InvalidOperationException)
        {
            // Agent i tak się właśnie zamyka.
        }
    }

    private SettingsForm? openSettings;

    private void ShowSettings()
    {
        if (openSettings != null)
        {
            openSettings.Activate();
            return;
        }
        using (openSettings = new SettingsForm(settings))
        {
            if (openSettings.ShowDialog() == DialogResult.OK)
            {
                settings = AgentSettings.Load();
                host.Start(settings);
                ApplyAutostart();
            }
        }
        openSettings = null;
        UpdateIcon();
    }

    /// <summary>„Uruchamiaj z Windows” z okna ustawień: wpis w rejestrze i zaznaczenie w menu.</summary>
    private void ApplyAutostart()
    {
        try
        {
            if (settings.AutostartOff) AgentSettings.AutostartPath = null;
            else settings.EnsureAutostart();
            autostart.Checked = AgentSettings.Autostart;
        }
        catch (Exception error) { Log.Error("Autostart", error); }
    }

    private void ShowMacroHelp()
    {
        if (player.Blocked != null)
        {
            MessageBox.Show("Windows nie pozwolił uruchomić odtwarzacza makr (" + player.Blocked + ").\n\n"
                + "Komputer pozwala uruchamiać programy tylko z wybranych folderów. Odtwarzacz leży w folderze agenta:\n"
                + Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "player", "player.exe")
                + "\n\nPoproś informatyka o zgodę na ten plik albo przenieś cały folder agenta do dozwolonego folderu.",
                "Intermanage – makra", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        MessageBox.Show("Odtwarzacz makr " + (player.Version ?? "") + " działa w agencie, nie trzeba go uruchamiać.\n\n"
            + "Alt+E: wykonuje makro skopiowane ze strony Intermanage. Makro działa raz, potem Schowek jest pusty.\n"
            + "Esc w trakcie makra: zatrzymuje makro.\n"
            + "Alt+R nad oknem Mercalys: tryb skanowania, klik w okno daje listę przycisków strony w Schowku, Esc anuluje.",
            "Intermanage – makra", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private static void Open(string url)
    {
        // Adres strony otwiera domyślna przeglądarka (bez uruchamiania powłoki ani skryptów).
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (Exception error) { Log.Error("Otwieranie " + url, error); }
    }

    private static void OpenFolder(string folder)
    {
        try
        {
            Directory.CreateDirectory(folder);
            Process.Start(new ProcessStartInfo(folder) { UseShellExecute = true });
        }
        catch (Exception error)
        {
            Log.Error("Otwieranie folderu logów", error);
        }
    }

    /// <summary>Okrągła ikona w kolorze stanu, rysowana w pamięci (bez plików ikon).</summary>
    private static Icon Dot(Color color)
    {
        using var bitmap = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var fill = new SolidBrush(color);
            g.FillEllipse(fill, 3, 3, 26, 26);
            using var pen = new Pen(Color.White, 3);
            g.DrawEllipse(pen, 3, 3, 26, 26);
            using var letter = new Font("Segoe UI", 13f, FontStyle.Bold, GraphicsUnit.Pixel);
            using var white = new SolidBrush(Color.White);
            var size = g.MeasureString("i", letter);
            g.DrawString("i", letter, white, (32 - size.Width) / 2, (32 - size.Height) / 2);
        }
        var handle = bitmap.GetHicon();
        // Kopia ikony, żeby nie trzymać uchwytu GDI z bitmapy.
        using var temp = Icon.FromHandle(handle);
        return (Icon)temp.Clone();
    }

    protected override void ExitThreadCore()
    {
        Log.Info("Zakończono agenta");
        closing.Cancel();
        remoteMacros.Dispose();
        exitRequest.Dispose();
        versions.Dispose();
        selfUpdate.Dispose();
        host.Dispose();
        player.Dispose();
        icon.Visible = false;
        icon.Dispose();
        ui.Dispose();
        base.ExitThreadCore();
    }
}
