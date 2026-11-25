using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Collector
{
    /// <summary>
    /// Instalator kolektora (IntermanageSetup.exe, jeden plik z programem w środku). Instaluje w \Application, bo ta
    /// pamięć zostaje po zimnym restarcie. Ponowne uruchomienie naprawia program, a ustawienia i dane zostają.
    /// </summary>
    internal class Setup : Form
    {
        private const string FlashRoot = "\\Application";
        private const int WaitForClose = 15000;

        private readonly Label title = new Label();
        private readonly Label status = new Label();
        private readonly CheckBox autostart = new CheckBox();
        private readonly Button button = new Button();
        private bool done;

        [MTAThread]
        private static void Main()
        {
            Application.Run(new Setup());
        }

        private Setup()
        {
            Text = "Intermanage - instalator";
            BackColor = Color.White;
            title.Text = "Intermanage " + BuildInfo.Version;
            title.Font = new Font("Tahoma", 14F, FontStyle.Bold);
            title.ForeColor = Color.FromArgb(17, 17, 17);
            status.Text = "Magazyn artykułów na kolektor.\r\nFolder: " + Target;
            status.Font = new Font("Tahoma", 9F, FontStyle.Regular);
            status.ForeColor = Color.FromArgb(17, 17, 17);
            autostart.Text = "Uruchamiaj po włączeniu";
            autostart.Font = new Font("Tahoma", 9F, FontStyle.Regular);
            autostart.Checked = true;
            button.Text = "Zainstaluj";
            button.Font = new Font("Tahoma", 12F, FontStyle.Bold);
            button.Click += new EventHandler(OnButton);
            Controls.Add(title);
            Controls.Add(status);
            Controls.Add(autostart);
            Controls.Add(button);
            MinimizeBox = false;
        }

        protected override void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            if (Native.OnDevice)
            {
                WindowState = FormWindowState.Maximized;
            }
            else
            {
                ClientSize = new Size(320, 260);
            }
            Arrange();
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            Arrange();
        }

        private void Arrange()
        {
            int width = ClientSize.Width;
            title.Bounds = new Rectangle(8, 8, width - 16, 28);
            status.Bounds = new Rectangle(8, 42, width - 16, 72);
            autostart.Bounds = new Rectangle(8, 118, width - 16, 24);
            button.Bounds = new Rectangle(8, 150, width - 16, 44);
        }

        private static string Target
        {
            get
            {
                if (!Native.OnDevice)
                {
                    return Path.Combine(Versions.ExeFolder, "Intermanage");
                }
                return (Directory.Exists(FlashRoot) ? FlashRoot : "\\Program Files") + "\\Intermanage";
            }
        }

        private void OnButton(object sender, EventArgs e)
        {
            if (done)
            {
                Process.Start(Path.Combine(Target, Versions.LauncherFile), "");
                Close();
                return;
            }
            button.Enabled = false;
            Cursor.Current = Cursors.WaitCursor;
            try
            {
                Install();
                done = true;
                status.Text = "Zainstalowano w " + Target + ".";
                button.Text = "Uruchom";
                autostart.Visible = false;
            }
            catch (Exception error)
            {
                status.Text = "Nie udało się zainstalować: " + error.Message;
                button.Text = "Spróbuj ponownie";
            }
            finally
            {
                Cursor.Current = Cursors.Default;
                button.Enabled = true;
            }
        }

        private void Install()
        {
            status.Text = "Zamykam działający program…";
            Refresh();
            Native.CloseWindowOf(Native.ProgramTitle);
            int from = Environment.TickCount;
            while (Native.IsRunning)
            {
                if (Environment.TickCount - from > WaitForClose)
                {
                    throw new IOException("zamknij program Intermanage i spróbuj ponownie");
                }
                Thread.Sleep(250);
            }
            status.Text = "Kopiuję pliki…";
            Refresh();
            string program = Path.Combine(Target, BuildInfo.Version);
            if (!Directory.Exists(program))
            {
                Directory.CreateDirectory(program);
            }
            Copy(Versions.ProgramFile, program);
            Copy("BouncyCastle.dll", program);
            Copy(Versions.LauncherFile, Target);
            // Pierwsza wersja próbna leżała bez folderu wersji.
            string old = Path.Combine(Target, "BouncyCastle.dll");
            if (File.Exists(old))
            {
                File.Delete(old);
            }
            Versions.Read(Target).Install(BuildInfo.Version);
            if (Native.OnDevice)
            {
                string launcher = Path.Combine(Target, Versions.LauncherFile);
                Shortcuts.ToDesktop(launcher);
                if (autostart.Checked && Directory.Exists(FlashRoot))
                {
                    Shortcuts.ToStartup(launcher);
                }
                else
                {
                    Shortcuts.RemoveStartup();
                }
            }
        }

        private static void Copy(string name, string folder)
        {
            string target = Path.Combine(folder, name);
            string temporary = target + ".new";
            using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            using (FileStream output = new FileStream(temporary, FileMode.Create, FileAccess.Write))
            {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = source.Read(buffer, 0, buffer.Length)) > 0)
                {
                    output.Write(buffer, 0, read);
                }
            }
            if (File.Exists(target))
            {
                File.Delete(target);
            }
            File.Move(temporary, target);
        }
    }
}
