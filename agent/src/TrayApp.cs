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
    private readonly NotifyIcon icon;
    private readonly ToolStripMenuItem status = new() { Enabled = false };
    private readonly ToolStripMenuItem activity = new() { Enabled = false, Visible = false };
    private readonly ToolStripMenuItem autostart = new("Uruchamiaj z Windows") { CheckOnClick = true };
    private readonly ToolStripMenuItem updateStatus = new() { Enabled = false, Visible = false };
    private readonly ToolStripMenuItem showUpdate = new("Otwórz „Do pobrania”") { Visible = false };
    private readonly SelfUpdate selfUpdate = new();
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
        showUpdate.Click += (_, _) => Open(settings.ServerUrl + "/ustawienia/do-pobrania");
        autostart.Checked = AgentSettings.Autostart;
        autostart.Click += (_, _) =>
        {
            try
            {
                AgentSettings.AutostartPath = autostart.Checked ? Application.ExecutablePath : null;
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
        updateStatus.Text = selfUpdate.Status ?? "";
        updateStatus.Visible = updateStatus.Text.Length > 0;
        showUpdate.Visible = selfUpdate.NewerVersion != null;
        // Podpowiedź ikony ma limit 63 znaków.
        var tip = Title + "\n" + host.StatusText;
        icon.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;
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
                autostart.Checked = AgentSettings.Autostart;
            }
        }
        openSettings = null;
        UpdateIcon();
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
        host.Dispose();
        icon.Visible = false;
        icon.Dispose();
        ui.Dispose();
        base.ExitThreadCore();
    }
}
