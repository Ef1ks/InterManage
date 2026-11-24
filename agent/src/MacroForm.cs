using System;
using System.Drawing;
using System.Windows.Forms;

namespace Intermanage.Agent;

/// <summary>
/// Okno makra zleconego z przeglądarki: odliczanie z „Anuluj”, potem mały pasek w rogu na czas makra i wynik. Nie zabiera
/// fokusu (klawisze makra idą do Mercalys). Udane znika samo, błąd zostaje do zamknięcia. Metody można wołać
/// z dowolnego wątku.
/// </summary>
internal sealed class MacroForm : Form
{
    private static readonly Color Waiting = Color.FromArgb(180, 83, 9);
    private static readonly Color Done = Color.FromArgb(4, 120, 87);
    private static readonly Color Failed = Color.FromArgb(185, 28, 28);

    private readonly Label head = new()
    {
        Dock = DockStyle.Top,
        Height = 48,
        TextAlign = ContentAlignment.MiddleCenter,
        Font = new Font("Segoe UI", 16f, FontStyle.Bold),
        ForeColor = Color.White,
        BackColor = Waiting,
    };
    private readonly Label count = new()
    {
        Dock = DockStyle.Top,
        Height = 120,
        TextAlign = ContentAlignment.MiddleCenter,
        Font = new Font("Segoe UI", 60f, FontStyle.Bold),
        ForeColor = Waiting,
    };
    private readonly Label text = new()
    {
        Dock = DockStyle.Fill,
        TextAlign = ContentAlignment.MiddleCenter,
        Font = new Font("Segoe UI", 11f),
        Padding = new Padding(16, 4, 16, 4),
    };
    private readonly Button button = new()
    {
        Dock = DockStyle.Bottom,
        Height = 56,
        Font = new Font("Segoe UI", 13f, FontStyle.Bold),
        Text = "Anuluj, pracuję na tym komputerze",
    };
    private readonly Timer closeTimer = new() { Interval = 10_000 };
    private bool counting = true;

    /// <summary>Pracownik kliknął „Anuluj” w trakcie odliczania.</summary>
    public volatile bool Cancelled;

    protected override bool ShowWithoutActivation => true;

    public MacroForm(MacroOffer offer)
    {
        Text = "Intermanage – makro";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        ControlBox = false;
        TopMost = true;
        ShowInTaskbar = true;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(560, 340);
        head.Text = "MAKRO ROZPOCZNIE SIĘ ZA";
        text.Text = offer.Title + "\nZlecone przez: " + offer.RequestedBy
            + "\nMakro przejmie mysz i klawiaturę. Po anulowaniu przejdzie na inny komputer.";
        Controls.Add(text);
        Controls.Add(count);
        Controls.Add(head);
        Controls.Add(button);
        button.Click += (_, _) =>
        {
            if (counting) Cancelled = true;
            else Close();
        };
        closeTimer.Tick += (_, _) => Close();
        FormClosed += (_, _) => closeTimer.Dispose();
    }

    public void Countdown(int seconds) => OnUi(() => count.Text = seconds.ToString());

    public void Running(string title) => OnUi(() =>
    {
        counting = false;
        count.Visible = false;
        button.Visible = false;
        head.Text = "MAKRO W TOKU";
        text.Text = title + "\nNie używaj komputera. Esc zatrzymuje makro.";
        ClientSize = new Size(420, 130);
        var area = Screen.PrimaryScreen.WorkingArea;
        Location = new Point(area.Right - Width - 12, area.Bottom - Height - 12);
    });

    public void Finish(bool ok, string message) => OnUi(() =>
    {
        counting = false;
        count.Visible = false;
        head.Text = ok ? "MAKRO ZAKOŃCZONE" : "MAKRO ZATRZYMANE";
        head.BackColor = ok ? Done : Failed;
        text.Text = ok ? "Możesz pracować." : message.Length > 500 ? message.Substring(0, 500) + "…" : message;
        button.Text = "OK";
        button.Visible = true;
        ClientSize = new Size(420, ok ? 160 : 320);
        var area = Screen.PrimaryScreen.WorkingArea;
        Location = new Point(area.Right - Width - 12, area.Bottom - Height - 12);
        if (ok) closeTimer.Start();
    });

    public void CloseNow() => OnUi(Close);

    private void OnUi(Action action)
    {
        if (IsDisposed) return;
        if (!InvokeRequired)
        {
            action();
            return;
        }
        try { BeginInvoke(action); }
        catch (ObjectDisposedException) { }
        catch (InvalidOperationException) { }
    }
}
