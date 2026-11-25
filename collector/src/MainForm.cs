using System;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Collector
{
    /// <summary>
    /// Magazyn: pole skanowania i szukania, pod nim lista albo karta artykułu. Fokus jest zawsze w polu, więc kolejny
    /// skan działa z każdego miejsca: po wyszukaniu tekst jest zaznaczony i następny kod go zastępuje.
    /// </summary>
    internal class MainForm : Form
    {
        private const int HeaderHeight = 26;
        private const int FooterHeight = 22;
        private const int ScrollStep = 60;
        /// <summary>Skaner wpisuje cały kod naraz: przerwa po nim kończy kod, gdy skaner nie wysyła Enter.</summary>
        private const int ScanPause = 350;
        private const int ScanMillisPerKey = 45;
        private const int MinScanLength = 8;
        /// <summary>Nowa wersja uruchamia się sama na podstawce po takiej przerwie w pracy.</summary>
        private const int IdleBeforeUpdate = 60000;

        private readonly Bar header = new Bar();
        private readonly Bar footer = new Bar();
        private readonly TextBox input = new TextBox();
        private readonly ResultList list = new ResultList();
        private readonly ArticleCard card = new ArticleCard();
        private readonly MessageView message = new MessageView();
        private readonly Panel pairing = new Panel();
        private readonly TextBox pairCode = new TextBox();
        private readonly Label pairInfo = new Label();
        private readonly Button pairButton = new Button();
        private readonly ContextMenu menu = new ContextMenu();
        private readonly System.Windows.Forms.Timer clock = new System.Windows.Forms.Timer();
        private readonly System.Windows.Forms.Timer scan = new System.Windows.Forms.Timer();
        private readonly Sync sync = new Sync();

        private Catalog catalog;
        private bool openedFromList;
        private string searched = "";
        private int typingFrom;
        private bool closing;
        private int lastInput = Environment.TickCount;
        private readonly MenuItem install = new MenuItem();

        public MainForm()
        {
            Text = Native.ProgramTitle;
            BackColor = Theme.Paper;
            FormBorderStyle = FormBorderStyle.None;
            ControlBox = false;
            KeyPreview = true;
            Menu = null;

            input.Font = Theme.Input;
            input.BorderStyle = BorderStyle.FixedSingle;
            input.TextChanged += new EventHandler(OnTyped);
            input.KeyDown += new KeyEventHandler(OnKey);
            input.KeyPress += new KeyPressEventHandler(OnKeyPress);
            list.Chosen += new EventHandler(OnChosen);
            header.MouseDown += new MouseEventHandler(OnHeader);
            footer.MouseDown += new MouseEventHandler(OnFooter);

            pairInfo.Font = Theme.Text;
            pairInfo.ForeColor = Theme.Ink;
            pairCode.Font = Theme.Price;
            pairCode.MaxLength = 9;
            pairCode.KeyPress += new KeyPressEventHandler(OnPairKey);
            pairButton.Font = Theme.Bold;
            pairButton.Text = "Połącz ze sklepem";
            pairButton.Click += new EventHandler(OnPair);
            pairing.BackColor = Theme.Paper;
            pairing.Controls.Add(pairInfo);
            pairing.Controls.Add(pairCode);
            pairing.Controls.Add(pairButton);

            MenuItem download = new MenuItem();
            download.Text = "Pobierz dane teraz";
            download.Click += new EventHandler(OnDownload);
            MenuItem about = new MenuItem();
            about.Text = "O programie";
            about.Click += new EventHandler(OnAbout);
            MenuItem close = new MenuItem();
            close.Text = "Zamknij program";
            close.Click += new EventHandler(OnCloseProgram);
            install.Text = "Zainstaluj nową wersję";
            install.Enabled = false;
            install.Click += new EventHandler(OnInstallVersion);
            menu.MenuItems.Add(download);
            menu.MenuItems.Add(install);
            menu.MenuItems.Add(about);
            menu.MenuItems.Add(close);

            Controls.Add(header);
            Controls.Add(input);
            Controls.Add(list);
            Controls.Add(card);
            Controls.Add(message);
            Controls.Add(pairing);
            Controls.Add(footer);

            clock.Interval = 5000;
            clock.Tick += new EventHandler(OnClock);
            scan.Interval = ScanPause;
            scan.Tick += new EventHandler(OnScanPause);
            sync.Changed += new EventHandler(OnSyncChanged);
            sync.Downloaded += new EventHandler(OnSyncDownloaded);
            sync.Unpaired += new EventHandler(OnSyncUnpaired);
            sync.RolledBack += new EventHandler(OnSyncRolledBack);
        }

        protected override void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            if (Native.OnDevice)
            {
                WindowState = FormWindowState.Maximized;
                Native.ShowTaskbar(false);
                Bounds = Screen.PrimaryScreen.Bounds;
            }
            else
            {
                ClientSize = new Size(320, 320);
            }
            OpenCatalog();
            Arrange();
            ShowStart();
            clock.Enabled = true;
            sync.Start(catalog == null ? "" : catalog.Version);
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            Arrange();
        }

        protected override void OnActivated(EventArgs e)
        {
            base.OnActivated(e);
            Native.ShowTaskbar(false);
            FocusInput();
        }

        protected override void OnClosed(EventArgs e)
        {
            closing = true;
            sync.Stop();
            clock.Enabled = false;
            if (catalog != null)
            {
                catalog.Dispose();
            }
            base.OnClosed(e);
        }

        private void Arrange()
        {
            int width = ClientSize.Width;
            int height = ClientSize.Height;
            header.Bounds = new Rectangle(0, 0, width, HeaderHeight);
            footer.Bounds = new Rectangle(0, height - FooterHeight, width, FooterHeight);
            input.Bounds = new Rectangle(3, HeaderHeight + 3, width - 6, input.Height);
            int top = input.Bottom + 3;
            Rectangle body = new Rectangle(0, top, width, Math.Max(0, height - FooterHeight - top));
            list.Bounds = body;
            card.Bounds = body;
            message.Bounds = body;
            pairing.Bounds = new Rectangle(0, HeaderHeight, width, Math.Max(0, height - FooterHeight - HeaderHeight));
            pairInfo.Bounds = new Rectangle(8, 8, width - 16, 108);
            pairCode.Bounds = new Rectangle(8, 118, width - 16, pairCode.Height);
            pairButton.Bounds = new Rectangle(8, 168, width - 16, 40);
        }

        // ---- widoki ----

        private void ShowOnly(Control view)
        {
            bool paired = Settings.Paired;
            pairing.Visible = !paired;
            input.Visible = paired;
            list.Visible = paired && view == list;
            card.Visible = paired && view == card;
            message.Visible = paired && view == message;
        }

        private void ShowStart()
        {
            openedFromList = false;
            searched = "";
            if (!Settings.Paired)
            {
                pairInfo.Text = "Postaw kolektor na podstawce i wpisz kod z Intermanage:\r\n"
                    + "Ustawienia sklepu › Kolektory › Dodaj kolektor.\r\n\r\nKod jest ważny 15 minut.";
                ShowOnly(null);
            }
            else if (catalog == null)
            {
                message.Show("Brak danych na kolektorze", "Zostaw kolektor na podstawce. Dane sklepu "
                    + Settings.StoreNumber + " pobiorą się same.", false);
                ShowOnly(message);
            }
            else
            {
                message.Show("Zeskanuj artykuł", "albo wpisz EAN, ITM8 lub fragment nazwy i naciśnij Enter.\n"
                    + catalog.Count + " artykułów, sklep " + catalog.StoreNumber + ".", false);
                ShowOnly(message);
            }
            FocusInput();
            UpdateBars();
        }

        private void ShowArticle(int index)
        {
            card.Show(catalog, catalog.Read(index));
            ShowOnly(card);
            UpdateBars();
        }

        private void Search()
        {
            scan.Enabled = false;
            string text = input.Text.Trim();
            if (text.Length == 0 || catalog == null)
            {
                return;
            }
            if (text == searched && list.Visible)
            {
                OnChosen(list, EventArgs.Empty);
                return;
            }
            List<int> found;
            Cursor.Current = Cursors.WaitCursor;
            try
            {
                found = catalog.Find(text);
            }
            finally
            {
                Cursor.Current = Cursors.Default;
            }
            searched = text;
            openedFromList = found.Count > 1;
            if (found.Count == 0)
            {
                Native.ErrorBeep();
                message.Show("Nie ma takiego artykułu", text + "\nnie występuje w kartotece z "
                    + Theme.Day(catalog.ReportAt) + ".", true);
                ShowOnly(message);
            }
            else if (found.Count == 1)
            {
                ShowArticle(found[0]);
            }
            else
            {
                list.Show(catalog, found);
                ShowOnly(list);
            }
            input.SelectAll();
            UpdateBars();
        }

        private void Back()
        {
            if (card.Visible && openedFromList)
            {
                ShowOnly(list);
                UpdateBars();
                return;
            }
            input.Text = "";
            ShowStart();
        }

        private void FocusInput()
        {
            if (Settings.Paired)
            {
                input.Focus();
            }
            else
            {
                pairCode.Focus();
            }
        }

        private void UpdateBars()
        {
            int battery = Native.Battery;
            string store = Settings.StoreNumber.Length > 0 ? "Intermanage " + Settings.StoreNumber : "Intermanage";
            if (list.Visible)
            {
                store = list.Count >= Catalog.ResultLimit ? "Pierwsze " + Catalog.ResultLimit + " wyników"
                    : "Wyniki: " + list.Count;
            }
            header.Show(store, (battery < 0 ? "" : battery + "%  ") + "Menu", Theme.Ink, Color.White, -1);

            if (sync.Percent >= 0)
            {
                footer.Show(sync.Status, sync.Percent + "%", Theme.Panel, Theme.Ink, sync.Percent);
            }
            else if (sync.Status.Length > 0 && Native.OnCradle)
            {
                footer.Show(sync.Status, DataTime(), Theme.BadBack, Theme.Bad, -1);
            }
            else if (catalog == null)
            {
                footer.Show(Settings.Paired ? "Brak danych" : "Niepołączony", "", Theme.BadBack, Theme.Bad, -1);
            }
            else if (sync.ReadyVersion.Length > 0)
            {
                footer.Show("Nowa wersja " + sync.ReadyVersion + " w Menu", DataTime(), Theme.Selected, Theme.Ink, -1);
            }
            else if (catalog.ReportAt.Date < DateTime.Today)
            {
                footer.Show(Native.OnCradle ? "Sklep ma stary raport" : "Nieaktualne, na podstawkę", DataTime(), Theme.WarningBack, Theme.Warning, -1);
            }
            else
            {
                footer.Show("Dane aktualne", DataTime(), Theme.GoodBack, Theme.Good, -1);
            }
        }

        private string DataTime()
        {
            return catalog == null ? "" : catalog.ReportAt.ToString("dd.MM HH:mm", CultureInfo.InvariantCulture);
        }

        private void OpenCatalog()
        {
            if (!File.Exists(Sync.CatalogFile))
            {
                return;
            }
            try
            {
                Catalog opened = new Catalog(Sync.CatalogFile);
                // Kolektor połączony z innym sklepem nie pokazuje danych poprzedniego.
                if (opened.StoreNumber != Settings.StoreNumber)
                {
                    opened.Dispose();
                    return;
                }
                catalog = opened;
            }
            catch (Exception error)
            {
                Log.Write("Dane: " + error.Message);
            }
        }

        // ---- klawisze i skaner ----

        private void OnKey(object sender, KeyEventArgs e)
        {
            lastInput = Environment.TickCount;
            switch (e.KeyCode)
            {
                case Keys.Up:
                    MoveView(-1);
                    break;
                case Keys.Down:
                    MoveView(1);
                    break;
                case Keys.PageUp:
                    MoveView(-Page());
                    break;
                case Keys.PageDown:
                    MoveView(Page());
                    break;
                default:
                    return;
            }
            e.Handled = true;
        }

        private int Page()
        {
            return list.Visible ? list.Page : 3;
        }

        private void MoveView(int by)
        {
            if (list.Visible)
            {
                list.Move(by);
            }
            else if (card.Visible)
            {
                card.Scroll(by * ScrollStep);
            }
        }

        private void OnKeyPress(object sender, KeyPressEventArgs e)
        {
            if (e.KeyChar == '\r' || e.KeyChar == '\n' || e.KeyChar == '\t')
            {
                e.Handled = true;
                Search();
            }
            else if (e.KeyChar == (char)27)
            {
                e.Handled = true;
                Back();
            }
        }

        private void OnTyped(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            if (input.Text.Length <= 1)
            {
                typingFrom = Environment.TickCount;
            }
            scan.Enabled = false;
            if (input.Text != searched && Catalog.IsDigits(input.Text) && input.Text.Length >= MinScanLength)
            {
                scan.Enabled = true;
            }
        }

        private void OnScanPause(object sender, EventArgs e)
        {
            scan.Enabled = false;
            int typed = Environment.TickCount - typingFrom - ScanPause;
            if (typed <= input.Text.Length * ScanMillisPerKey)
            {
                Search();
            }
        }

        private void OnChosen(object sender, EventArgs e)
        {
            int index = list.SelectedArticle;
            if (index >= 0)
            {
                ShowArticle(index);
            }
            FocusInput();
        }

        // ---- menu ----

        private void OnHeader(object sender, MouseEventArgs e)
        {
            lastInput = Environment.TickCount;
            install.Enabled = sync.ReadyVersion.Length > 0;
            menu.Show(header, new Point(e.X, HeaderHeight));
            FocusInput();
        }

        private void OnFooter(object sender, MouseEventArgs e)
        {
            OnDownload(sender, e);
        }

        private void OnDownload(object sender, EventArgs e)
        {
            sync.Request();
            FocusInput();
        }

        private void OnAbout(object sender, EventArgs e)
        {
            MessageBox.Show("Intermanage, magazyn na kolektorze\r\nWersja " + BuildInfo.Version + "\r\nKolektor: "
                + (Settings.CollectorName.Length > 0 ? Settings.CollectorName : "niepołączony") + "\r\nDane z raportu: "
                + (catalog == null ? "brak" : catalog.ReportAt.ToString("dd.MM.yyyy HH:mm")) + "\r\nSerwer: "
                + Settings.Server + "\r\n.NET " + Environment.Version + ", " + Environment.OSVersion, "O programie");
            FocusInput();
        }

        private void OnInstallVersion(object sender, EventArgs e)
        {
            string version = sync.ReadyVersion;
            if (version.Length == 0 || closing)
            {
                return;
            }
            try
            {
                Updater.Apply(version);
                Close();
            }
            catch (Exception error)
            {
                Log.Write("Nowa wersja: " + error);
                MessageBox.Show("Nie udało się uruchomić nowej wersji: " + error.Message, "Intermanage");
            }
        }

        private void OnCloseProgram(object sender, EventArgs e)
        {
            Close();
        }

        // ---- łączenie ze sklepem ----

        private void OnPairKey(object sender, KeyPressEventArgs e)
        {
            if (e.KeyChar == '\r')
            {
                e.Handled = true;
                OnPair(sender, EventArgs.Empty);
            }
        }

        private void OnPair(object sender, EventArgs e)
        {
            if (!pairButton.Enabled)
            {
                return;
            }
            string code = pairCode.Text.Replace(" ", "");
            if (code.Length != 8 || !Catalog.IsDigits(code))
            {
                pairInfo.Text = "Kod ma 8 cyfr.";
                return;
            }
            pairButton.Enabled = false;
            pairInfo.Text = "Łączę z serwerem, to może potrwać kilkanaście sekund…";
            Thread thread = new Thread(new ThreadStart(new Pairing(this, code).Run));
            thread.IsBackground = true;
            thread.Start();
        }

        /// <summary>Łączenie w tle (pierwsze szyfrowane połączenie trwa na kolektorze kilka sekund).</summary>
        private class Pairing
        {
            private readonly MainForm form;
            private readonly string code;

            public Pairing(MainForm form, string code)
            {
                this.form = form;
                this.code = code;
            }

            public void Run()
            {
                string problem = "";
                try
                {
                    Sync.Pair(code);
                }
                catch (Exception error)
                {
                    Log.Write("Łączenie ze sklepem: " + error);
                    problem = error.Message;
                }
                form.pairProblem = problem;
                form.OnUi(new EventHandler(form.OnPaired));
            }
        }

        private string pairProblem = "";

        private void OnPaired(object sender, EventArgs e)
        {
            pairButton.Enabled = true;
            if (pairProblem.Length > 0)
            {
                pairInfo.Text = pairProblem;
                pairCode.SelectAll();
                return;
            }
            pairCode.Text = "";
            if (catalog != null && catalog.StoreNumber != Settings.StoreNumber)
            {
                catalog.Dispose();
                catalog = null;
            }
            ShowStart();
            sync.Request();
        }

        // ---- zdarzenia z wątku pobierania ----

        private void OnSyncChanged(object sender, EventArgs e)
        {
            OnUi(new EventHandler(OnClock));
        }

        private void OnSyncDownloaded(object sender, EventArgs e)
        {
            OnUi(new EventHandler(OnInstall));
        }

        private void OnSyncUnpaired(object sender, EventArgs e)
        {
            OnUi(new EventHandler(OnUnpaired));
        }

        private void OnSyncRolledBack(object sender, EventArgs e)
        {
            OnUi(new EventHandler(OnRolledBack));
        }

        private void OnRolledBack(object sender, EventArgs e)
        {
            Updater.Restart();
            Close();
        }

        private void OnUi(EventHandler handler)
        {
            if (closing)
            {
                return;
            }
            try
            {
                Invoke(handler, new object[] { this, EventArgs.Empty });
            }
            catch (ObjectDisposedException)
            {
                // Program właśnie się zamyka.
            }
        }

        private void OnInstall(object sender, EventArgs e)
        {
            string version;
            using (Catalog fresh = new Catalog(Sync.NewCatalogFile))
            {
                version = fresh.Version;
            }
            if (catalog != null)
            {
                catalog.Dispose();
                catalog = null;
            }
            try
            {
                sync.Install(version);
            }
            catch (IOException error)
            {
                Log.Write("Podmiana danych: " + error.Message);
            }
            OpenCatalog();
            // Numery artykułów zmieniają się z plikiem, więc lista i karta zaczynają od nowa z tym samym szukaniem.
            string text = searched;
            if (text.Length > 0 && catalog != null)
            {
                searched = "";
                input.Text = text;
                Search();
            }
            else
            {
                ShowStart();
            }
        }

        private void OnUnpaired(object sender, EventArgs e)
        {
            ShowStart();
            pairInfo.Text = "Kolektor został odłączony w Ustawieniach sklepu. Wpisz nowy kod.";
        }

        private void OnClock(object sender, EventArgs e)
        {
            UpdateBars();
            if (sender == clock && sync.ReadyVersion.Length > 0 && sync.Percent < 0 && Native.OnCradle
                && Environment.TickCount - lastInput > IdleBeforeUpdate)
            {
                OnInstallVersion(sender, e);
            }
        }
    }
}
