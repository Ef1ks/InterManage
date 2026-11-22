using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace Intermanage.Agent;

internal enum RowState
{
    Working,
    Done,
    Failed,
}

/// <summary>
/// Okno „AKTUALIZACJA DANYCH – POCZEKAJ DO POTWIERDZENIA” na wierzchu, na czas aktualizacji z Mercalys: lista plików,
/// które zapisze makro, ze stanem wgrania na serwer. Bez przycisku zamknięcia, dopóki trwa (można je tylko ukryć);
/// po wgraniu wszystkiego pokazuje „Dane zaktualizowane” i znika samo, przy błędzie czeka na OK. Metody można wołać
/// z dowolnego wątku.
/// </summary>
internal sealed class UpdateForm : Form
{
    private static readonly Color Working = Color.FromArgb(180, 83, 9);
    private static readonly Color Done = Color.FromArgb(4, 120, 87);
    private static readonly Color Failed = Color.FromArgb(185, 28, 28);

    private readonly Label title = new()
    {
        Text = "AKTUALIZACJA DANYCH",
        Dock = DockStyle.Top,
        Height = 56,
        TextAlign = ContentAlignment.MiddleCenter,
        Font = new Font("Segoe UI", 24f, FontStyle.Bold),
        ForeColor = Color.White,
        BackColor = Working,
    };
    private readonly Label subtitle = new()
    {
        Text = "POCZEKAJ DO POTWIERDZENIA",
        Dock = DockStyle.Top,
        Height = 36,
        TextAlign = ContentAlignment.MiddleCenter,
        Font = new Font("Segoe UI", 14f, FontStyle.Bold),
        ForeColor = Working,
    };
    private readonly Label status = new()
    {
        Dock = DockStyle.Top,
        Height = 64,
        TextAlign = ContentAlignment.MiddleCenter,
        Font = new Font("Segoe UI", 10.5f),
        Padding = new Padding(16, 0, 16, 0),
    };
    private readonly ProgressBar progress = new() { Dock = DockStyle.Top, Height = 10, Style = ProgressBarStyle.Marquee };
    private readonly ListView list = new()
    {
        Dock = DockStyle.Fill,
        View = View.Details,
        FullRowSelect = true,
        HeaderStyle = ColumnHeaderStyle.Nonclickable,
        Font = new Font("Segoe UI", 10f),
    };
    private readonly Button hide = new() { Text = "Ukryj okno (aktualizacja trwa dalej)", AutoSize = true };
    private readonly Button ok = new() { Text = "OK", AutoSize = true, Visible = false };
    private readonly Dictionary<string, ListViewItem> rows = new(StringComparer.OrdinalIgnoreCase);
    private readonly Timer closeTimer = new() { Interval = 10_000 };
    private bool finished;

    public UpdateForm(List<MacroFile> files, List<SkippedReport> skipped)
    {
        Text = "Intermanage – aktualizacja danych";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        ControlBox = false;
        TopMost = true;
        ShowInTaskbar = true;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(720, 520);
        Font = new Font("Segoe UI", 9.5f);

        list.Columns.Add("Raport", 200);
        list.Columns.Add("Plik", 250);
        list.Columns.Add("Stan", 240);
        foreach (var file in files)
        {
            var item = new ListViewItem(new[] { file.Title, file.FileName, "czeka na Mercalys" }) { ForeColor = Working };
            rows[file.FileName] = item;
            list.Items.Add(item);
        }
        foreach (var report in skipped)
        {
            list.Items.Add(new ListViewItem(new[] { report.Title, "–", "pominięty: " + report.Reason })
            {
                ForeColor = SystemColors.GrayText,
            });
        }

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            FlowDirection = FlowDirection.RightToLeft,
            AutoSize = true,
            Padding = new Padding(8),
        };
        buttons.Controls.Add(ok);
        buttons.Controls.Add(hide);
        Controls.Add(list);
        Controls.Add(progress);
        Controls.Add(status);
        Controls.Add(subtitle);
        Controls.Add(title);
        Controls.Add(buttons);

        hide.Click += (_, _) => Hide();
        ok.Click += (_, _) => Close();
        closeTimer.Tick += (_, _) => Close();
        FormClosed += (_, _) => closeTimer.Dispose();
    }

    public void Status(string text) => OnUi(() => status.Text = text);

    public void Row(string fileName, string text, RowState state) => OnUi(() =>
    {
        if (!rows.TryGetValue(fileName, out var item)) return;
        item.SubItems[2].Text = text;
        item.ForeColor = state == RowState.Done ? Done : state == RowState.Failed ? Failed : Working;
    });

    public void Progress(int done, int total) => OnUi(() =>
    {
        if (total <= 0 || finished) return;
        progress.Style = ProgressBarStyle.Continuous;
        progress.Maximum = total;
        progress.Value = Math.Min(done, total);
    });

    /// <summary>Koniec: sukces znika sam po 10 s, błąd czeka na OK. Ukryte okno wraca na wierzch.</summary>
    public void Finish(bool success, string text) => OnUi(() =>
    {
        finished = true;
        var color = success ? Done : Failed;
        title.Text = success ? "DANE ZAKTUALIZOWANE" : "AKTUALIZACJA NIEPEŁNA";
        title.BackColor = color;
        subtitle.Text = success ? "Możesz pracować" : "Sprawdź listę poniżej";
        subtitle.ForeColor = color;
        status.Text = text;
        progress.Style = ProgressBarStyle.Continuous;
        progress.Value = success ? progress.Maximum : progress.Value;
        hide.Visible = false;
        ok.Visible = true;
        AcceptButton = ok;
        if (!Visible) Show();
        Activate();
        if (success) closeTimer.Start();
    });

    private void OnUi(Action action)
    {
        if (IsDisposed) return;
        if (InvokeRequired)
        {
            try { BeginInvoke(action); } catch (ObjectDisposedException) { } catch (InvalidOperationException) { }
        }
        else
        {
            action();
        }
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        Activate();
        // Kolumny na całą szerokość listy.
        var width = list.ClientSize.Width;
        list.Columns[0].Width = (int)(width * 0.28);
        list.Columns[1].Width = (int)(width * 0.36);
        list.Columns[2].Width = width - list.Columns[0].Width - list.Columns[1].Width - 4;
    }
}
