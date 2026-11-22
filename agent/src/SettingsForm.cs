using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Agent;

/// <summary>
/// Okno ustawień: adres serwera i klucz agenta (z Ustawień sklepu w Intermanage), test przez /hello, oraz hasło
/// Mercalys i aktualizacja danych przy starcie komputera.
/// </summary>
internal sealed class SettingsForm : Form
{
    private readonly TextBox server = new() { Dock = DockStyle.Fill };
    private readonly TextBox key = new() { Dock = DockStyle.Fill, UseSystemPasswordChar = true };
    private readonly TextBox mercalysPassword = new() { Dock = DockStyle.Fill, UseSystemPasswordChar = true };
    private readonly CheckBox updateOnStart = new()
    {
        Text = "Przy starcie komputera aktualizuj dane z Mercalys (pliki raportów nie z dzisiaj)",
        AutoSize = true,
    };
    private readonly Label result = new() { Dock = DockStyle.Fill, AutoSize = false, Height = 48 };
    private readonly Button save = new() { Text = "Sprawdź i zapisz", AutoSize = true };
    private readonly Button cancel = new() { Text = "Anuluj", AutoSize = true, DialogResult = DialogResult.Cancel };
    private readonly AgentSettings settings;

    public SettingsForm(AgentSettings settings)
    {
        this.settings = settings;
        Text = "Intermanage – agent sklepu: ustawienia";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(560, 340);
        Font = new Font("Segoe UI", 9f);

        server.Text = settings.ServerUrl;
        key.Text = "";
        updateOnStart.Checked = settings.UpdateOnStart;

        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(12), ColumnCount = 2, RowCount = 8 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.Controls.Add(new Label { Text = "Adres serwera", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        layout.Controls.Add(server, 1, 0);
        layout.Controls.Add(new Label { Text = "Klucz agenta", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 1);
        layout.Controls.Add(key, 1, 1);
        var hint = new Label
        {
            Text = settings.HasKey
                ? "Klucz jest zapisany. Wklej nowy tylko, gdy go zmieniasz (Ustawienia sklepu → Agent sklepu)."
                : "Klucz skopiujesz w Intermanage: Ustawienia sklepu → Agent sklepu → Dodaj agenta.",
            AutoSize = false,
            Dock = DockStyle.Fill,
            Height = 36,
            ForeColor = SystemColors.GrayText,
        };
        layout.Controls.Add(hint, 1, 2);
        layout.Controls.Add(new Label { Text = "Hasło Mercalys", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 3);
        layout.Controls.Add(mercalysPassword, 1, 3);
        layout.Controls.Add(new Label
        {
            Text = settings.HasMercalysPassword
                ? "Hasło jest zapisane (zaszyfrowane na tym komputerze). Wpisz nowe tylko, gdy je zmieniasz."
                : "Makro aktualizacji samo uruchomi Mercalys i zaloguje się tym hasłem. Bez hasła czeka na logowanie.",
            AutoSize = false,
            Dock = DockStyle.Fill,
            Height = 36,
            ForeColor = SystemColors.GrayText,
        }, 1, 4);
        layout.Controls.Add(updateOnStart, 1, 5);
        layout.Controls.Add(result, 1, 6);
        var buttons = new FlowLayoutPanel { FlowDirection = FlowDirection.RightToLeft, Dock = DockStyle.Fill, AutoSize = true };
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(save);
        layout.Controls.Add(buttons, 1, 7);
        Controls.Add(layout);
        AcceptButton = save;
        CancelButton = cancel;
        save.Click += async (_, _) => await SaveAsync();
    }

    private async System.Threading.Tasks.Task SaveAsync()
    {
        var url = AgentSettings.NormalizeServer(server.Text);
        if (url == null)
        {
            ShowResult("Podaj adres zaczynający się od https://", true);
            return;
        }
        var newPassword = mercalysPassword.Text;
        if (newPassword.Contains("|") || newPassword.Length > 20)
        {
            ShowResult("Hasło Mercalys: najwyżej 20 znaków i bez znaku |.", true);
            return;
        }
        var newKey = key.Text.Trim();
        var testKey = newKey.Length > 0 ? newKey : settings.Key;
        if (string.IsNullOrEmpty(testKey))
        {
            ShowResult("Wklej klucz agenta.", true);
            return;
        }
        save.Enabled = false;
        ShowResult("Sprawdzam…", false);
        try
        {
            using var api = new ApiClient(url, testKey!);
            using var limit = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            var hello = await api.HelloAsync(Program.Version, limit.Token);
            settings.ServerUrl = url;
            if (newKey.Length > 0) settings.Key = newKey;
            if (newPassword.Length > 0) settings.MercalysPassword = newPassword;
            settings.UpdateOnStart = updateOnStart.Checked;
            settings.Save();
            Log.Info("Zapisano ustawienia (sklep " + hello.StoreNumber + ")");
            MessageBox.Show(this, "Połączono ze sklepem " + hello.StoreNumber
                + (string.IsNullOrEmpty(hello.StoreName) ? "" : " (" + hello.StoreName + ")")
                + ".\nAgent: " + hello.AgentName, Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (InvalidKeyException)
        {
            ShowResult("Serwer odrzucił klucz. Sprawdź, czy agent nie został usunięty, i wklej klucz jeszcze raz.", true);
        }
        catch (Exception error)
        {
            ShowResult("Nie udało się połączyć: " + error.Message, true);
        }
        finally
        {
            save.Enabled = true;
        }
    }

    private void ShowResult(string text, bool error)
    {
        result.Text = text;
        result.ForeColor = error ? Color.Firebrick : SystemColors.ControlText;
    }
}
