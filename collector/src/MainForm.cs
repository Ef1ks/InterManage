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
    /// Okno kolektora: ekran główny (1 Skanowanie, 2 Protokoły) i dwa narzędzia. W obu jest pole skanowania i szukania,
    /// a fokus zawsze wraca do pola, więc kolejny skan działa z każdego miejsca. Treść wyższa niż ekran dostaje suwak.
    /// </summary>
    internal class MainForm : Form
    {
        private enum Mode { Pair, Home, Scan, Protocol }

        private const int HeaderHeight = 28;
        private const int FooterHeight = 24;
        private const int ScrollWidth = 26;
        /// <summary>Skaner wpisujący jak klawiatura daje cały kod naraz: przerwa po nim kończy kod bez Enter.</summary>
        private const int ScanPause = 350;
        private const int ScanMillisPerKey = 45;
        private const int MinScanLength = 8;
        /// <summary>Nowa wersja uruchamia się sama na podstawce po takiej przerwie w pracy.</summary>
        private const int IdleBeforeUpdate = 60000;
        private static readonly decimal MaxQuantity = 99999;
        /// <summary>Jedno naciśnięcie Enter potrafi przyjść dwa razy (okno i pole): drugie jest pomijane.</summary>
        private const int EnterGap = 300;

        private readonly Bar header = new Bar();
        private readonly Bar footer = new Bar();
        private readonly TextBox input = new TextBox();
        private readonly VScrollBar scroll = new VScrollBar();
        private readonly HomeMenu home = new HomeMenu();
        private readonly HomeMenu unit = new HomeMenu();
        private readonly ResultList list = new ResultList();
        private readonly ArticleCard card = new ArticleCard();
        private readonly ProtocolView protocol = new ProtocolView();
        private readonly MessageView message = new MessageView();
        private readonly QuantityView quantity = new QuantityView();
        private readonly TextBox quantityInput = new TextBox();
        private readonly Panel pairing = new Panel();
        private readonly TextBox pairCode = new TextBox();
        private readonly Label pairInfo = new Label();
        private readonly Button pairButton = new Button();
        private readonly ContextMenu menu = new ContextMenu();
        private readonly MenuItem install = new MenuItem();
        private readonly System.Windows.Forms.Timer clock = new System.Windows.Forms.Timer();
        private readonly System.Windows.Forms.Timer scan = new System.Windows.Forms.Timer();
        private readonly ProtocolStore protocols = new ProtocolStore();
        private readonly Sync sync;
        private readonly Scanner scanner = new Scanner();
        private readonly Control[] views;

        private Mode mode = Mode.Home;
        private Control view;
        private Catalog catalog;
        private bool openedFromList;
        private bool settingScroll;
        private string searched = "";
        private int typingFrom;
        private int lastInput = Environment.TickCount;
        private bool closing;
        private int lastEnter = Environment.TickCount - EnterGap;
        private string pairProblem = "";
        private string networkReport = "";

        // Wpisywanie ilości: nowa pozycja albo poprawiana (wtedy editedLine).
        private ProtocolLine pending;
        private Article pendingArticle;
        private string scaleCode = "";
        private ProtocolLine editedLine;

        /// <summary>
        /// „24: kg” w lewym dolnym rogu, jak „Kody 24 = waga” we wprowadzaniu danych: koniec kodu 24 to waga, a nie cena.
        /// Po ponownym uruchomieniu programu znów cena.
        /// </summary>
        private bool weightIn24;
        private Match shownMatch;

        public MainForm()
        {
            sync = new Sync(protocols);
            Text = Native.ProgramTitle;
            BackColor = Theme.Paper;
            FormBorderStyle = FormBorderStyle.None;
            ControlBox = false;
            KeyPreview = true;
            Menu = null;

            input.Font = Theme.Input;
            input.BorderStyle = BorderStyle.FixedSingle;
            input.TextChanged += new EventHandler(OnTyped);
            quantityInput.Font = Theme.Price;
            quantityInput.BorderStyle = BorderStyle.FixedSingle;
            quantityInput.MaxLength = 9;
            quantityInput.TextChanged += new EventHandler(OnQuantityTyped);
            quantity.Controls.Add(quantityInput);

            home.Chosen += new EventHandler(OnHomeChosen);
            unit.Chosen += new EventHandler(OnUnitChosen);
            list.Chosen += new EventHandler(OnChosen);
            protocol.Chosen += new EventHandler(OnLineChosen);
            header.MouseDown += new MouseEventHandler(OnHeader);
            footer.MouseDown += new MouseEventHandler(OnFooter);
            scroll.ValueChanged += new EventHandler(OnScroll);
            views = new Control[] { home, unit, list, card, protocol, message, quantity };
            foreach (Control item in views)
            {
                ScrollView scrolled = item as ScrollView;
                if (scrolled != null)
                {
                    scrolled.Moved += new EventHandler(OnViewMoved);
                }
            }

            pairInfo.Font = Theme.Text;
            pairInfo.ForeColor = Theme.Ink;
            pairCode.Font = Theme.Price;
            pairCode.MaxLength = 9;
            pairButton.Font = Theme.Bold;
            pairButton.Text = "Połącz ze sklepem";
            pairButton.Click += new EventHandler(OnPair);
            pairing.BackColor = Theme.Paper;
            pairing.Controls.Add(pairInfo);
            pairing.Controls.Add(pairCode);
            pairing.Controls.Add(pairButton);

            AddMenu("Pobierz dane teraz", new EventHandler(OnDownload));
            install.Text = "Zainstaluj nową wersję";
            install.Enabled = false;
            install.Click += new EventHandler(OnInstallVersion);
            menu.MenuItems.Add(install);
            AddMenu("Stan sieci", new EventHandler(OnNetwork));
            AddMenu("O programie", new EventHandler(OnAbout));
            AddMenu("Zamknij program", new EventHandler(OnCloseProgram));

            Controls.Add(header);
            Controls.Add(input);
            Controls.Add(scroll);
            foreach (Control item in views)
            {
                item.Visible = false;
                Controls.Add(item);
            }
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
            sync.ListsChanged += new EventHandler(OnSyncLists);
            scanner.Scanned += new EventHandler(OnScannerRead);
        }

        private void AddMenu(string text, EventHandler handler)
        {
            MenuItem item = new MenuItem();
            item.Text = text;
            item.Click += handler;
            menu.MenuItems.Add(item);
        }

        protected override void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            if (Native.OnDevice)
            {
                FillScreen();
            }
            else
            {
                ClientSize = new Size(320, 320);
            }
            OpenCatalog();
            protocols.Load();
            Arrange();
            GoHome();
            clock.Enabled = true;
            sync.Start(catalog == null ? "" : catalog.Version);
            scanner.Start();
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            Arrange();
        }

        protected override void OnActivated(EventArgs e)
        {
            base.OnActivated(e);
            scanner.Active = true;
            FillScreen();
            FocusInput();
        }

        protected override void OnDeactivate(EventArgs e)
        {
            scanner.Active = false;
            base.OnDeactivate(e);
        }

        protected override void OnClosed(EventArgs e)
        {
            closing = true;
            scanner.Stop();
            sync.Stop();
            clock.Enabled = false;
            if (catalog != null)
            {
                catalog.Dispose();
            }
            base.OnClosed(e);
        }

        // ---- układ i suwak ----

        private void FillScreen()
        {
            Native.ShowTaskbar(false);
            Rectangle screen = Screen.PrimaryScreen.Bounds;
            Native.FillScreen(Handle, screen.Width, screen.Height);
        }

        private Rectangle Body
        {
            get
            {
                int top = HeaderHeight + (InputShown ? input.Height + 6 : 0);
                return new Rectangle(0, top, ClientSize.Width, Math.Max(0, ClientSize.Height - FooterHeight - top));
            }
        }

        private bool InputShown
        {
            get { return (mode == Mode.Scan || mode == Mode.Protocol) && view != quantity && view != unit; }
        }

        private void Arrange()
        {
            int width = ClientSize.Width;
            int height = ClientSize.Height;
            header.Bounds = new Rectangle(0, 0, width, HeaderHeight);
            footer.Bounds = new Rectangle(0, height - FooterHeight, width, FooterHeight);
            input.Bounds = new Rectangle(3, HeaderHeight + 3, width - 6, input.Height);
            pairing.Bounds = new Rectangle(0, HeaderHeight, width, Math.Max(0, height - FooterHeight - HeaderHeight));
            pairInfo.Bounds = new Rectangle(8, 8, width - 16, 108);
            pairCode.Bounds = new Rectangle(8, 118, width - 16, pairCode.Height);
            pairButton.Bounds = new Rectangle(8, 168, width - 16, 40);
            quantityInput.Bounds = new Rectangle(QuantityView.InputLeft, QuantityView.InputTop, 130, quantityInput.Height);
            PlaceView();
        }

        /// <summary>Widok na całą treść okna; gdy się nie mieści, obok staje suwak, a widok się zwęża.</summary>
        private void PlaceView()
        {
            if (view == null)
            {
                scroll.Visible = false;
                return;
            }
            Rectangle body = Body;
            ScrollView scrolled = view as ScrollView;
            bool overflow = scrolled != null && scrolled.MeasureFor(body.Width) > body.Height && body.Height > 0;
            int width = body.Width - (overflow ? ScrollWidth : 0);
            view.Bounds = new Rectangle(body.X, body.Y, width, body.Height);
            if (overflow)
            {
                int content = scrolled.MeasureFor(width);
                settingScroll = true;
                scroll.Bounds = new Rectangle(width, body.Y, ScrollWidth, body.Height);
                scroll.Minimum = 0;
                scroll.Maximum = Math.Max(0, content - 1);
                scroll.LargeChange = Math.Max(1, body.Height);
                scroll.SmallChange = 40;
                scrolled.ScrollTo(scrolled.Offset);
                scroll.Value = Math.Min(scrolled.Offset, Math.Max(0, content - body.Height));
                settingScroll = false;
            }
            else if (scrolled != null)
            {
                scrolled.ScrollTo(0);
            }
            scroll.Visible = overflow;
        }

        private void OnScroll(object sender, EventArgs e)
        {
            ScrollView scrolled = view as ScrollView;
            if (!settingScroll && scrolled != null)
            {
                lastInput = Environment.TickCount;
                scrolled.ScrollTo(scroll.Value);
                FocusInput();
            }
        }

        private void OnViewMoved(object sender, EventArgs e)
        {
            ScrollView scrolled = view as ScrollView;
            if (scrolled != null && scroll.Visible)
            {
                settingScroll = true;
                scroll.Value = Math.Min(scrolled.Offset, Math.Max(0, scroll.Maximum - scroll.LargeChange + 1));
                settingScroll = false;
            }
        }

        private void ShowView(Control next)
        {
            view = next;
            pairing.Visible = mode == Mode.Pair;
            input.Visible = InputShown;
            PlaceView();
            foreach (Control item in views)
            {
                item.Visible = item == view;
            }
            if (view != null)
            {
                view.Invalidate();
            }
            FocusInput();
            UpdateBars();
        }

        private void FocusInput()
        {
            if (mode == Mode.Pair)
            {
                pairCode.Focus();
            }
            else if (view == quantity)
            {
                quantityInput.Focus();
            }
            else if (InputShown)
            {
                input.Focus();
            }
            else if (view != null)
            {
                view.Focus();
            }
        }

        // ---- ekran główny ----

        private void GoHome()
        {
            searched = "";
            input.Text = "";
            if (!Settings.Paired)
            {
                mode = Mode.Pair;
                pairInfo.Text = "Postaw kolektor na podstawce i wpisz kod z Intermanage:\r\n"
                    + "Kolektory › Urządzenia › Dodaj kolektor.\r\n\r\nKod jest ważny 15 minut.";
                ShowView(null);
                return;
            }
            mode = Mode.Home;
            RefreshHome();
            ShowView(home);
        }

        private void RefreshHome()
        {
            int count = protocols.Count;
            int waiting = ProtocolStore.Waiting.Length;
            string note = count > 0 ? "Na liście: " + count + " poz."
                : waiting > 0 ? "Do wysłania: " + waiting + ", włóż na podstawkę"
                : protocols.LastSent.Length > 0 ? "Wysłano " + protocols.LastSent : "Skanuj, wpisz ilość, Enter";
            home.Show(new string[] { "Skanowanie", "Protokoły" }, new string[] {
                catalog == null ? "Brak danych, zostaw na podstawce" : catalog.Count + " artykułów", note });
        }

        private void OnHomeChosen(object sender, EventArgs e)
        {
            Open(home.Selected);
        }

        private void Open(int option)
        {
            input.Text = "";
            searched = "";
            openedFromList = false;
            mode = option == 0 ? Mode.Scan : Mode.Protocol;
            if (mode == Mode.Scan)
            {
                ShowScanStart();
            }
            else
            {
                ShowProtocol();
            }
        }

        private void ShowScanStart()
        {
            openedFromList = false;
            if (catalog == null)
            {
                message.Show("Brak danych na kolektorze", "Zostaw kolektor na podstawce. Dane sklepu "
                    + Settings.StoreNumber + " pobiorą się same.", false);
            }
            else
            {
                message.Show("Zeskanuj artykuł", "albo wpisz EAN, ITM8 lub fragment nazwy i naciśnij Enter.", false);
            }
            ShowView(message);
        }

        private void ShowProtocol()
        {
            List<ProtocolLine> lines = protocols.Lines;
            protocol.Show(lines);
            if (lines.Count > 0)
            {
                ShowView(protocol);
                return;
            }
            string sent = protocols.LastSent.Length > 0 ? "Ostatnia lista wysłana: " + protocols.LastSent + ".\n" : "";
            int waiting = ProtocolStore.Waiting.Length;
            if (waiting > 0)
            {
                sent = "Do wysłania czeka list: " + waiting + ". Włóż kolektor na podstawkę.\n";
            }
            message.Show("Lista jest pusta", sent + "Zeskanuj artykuł, wpisz ilość i naciśnij Enter.", false);
            ShowView(message);
        }

        // ---- szukanie ----

        private void Search()
        {
            scan.Enabled = false;
            string text = input.Text.Trim();
            if (catalog == null)
            {
                return;
            }
            if (text.Length == 0)
            {
                if (view == protocol)
                {
                    OnLineChosen(protocol, EventArgs.Empty);
                }
                return;
            }
            if (text == searched && view == list)
            {
                OnChosen(list, EventArgs.Empty);
                return;
            }
            List<Match> found;
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
                ShowView(message);
            }
            else if (found.Count == 1)
            {
                OpenMatch(found[0]);
            }
            else
            {
                list.Show(catalog, found);
                ShowView(list);
            }
            input.SelectAll();
        }

        private void OpenMatch(Match match)
        {
            Article article = catalog.Read(match.Index);
            if (mode == Mode.Scan)
            {
                shownMatch = match;
                card.Show(catalog, article, match.ScaleCode.Length == 0 ? ""
                    : Catalog.ScaleNote(match.ScaleCode, article, weightIn24));
                ShowView(card);
                return;
            }
            // Etykieta wagi ma w kodzie cenę albo wagę, więc na listę idzie EAN artykułu z kartoteki.
            bool label = match.ScaleCode.Length > 0;
            ProtocolLine line = new ProtocolLine();
            line.Itm8 = article.Itm8;
            line.Code = match.Code.Length > 0 && !label ? match.Code : article.Ean;
            line.Ean = article.Ean;
            line.Name = article.Name;
            line.Brand = article.Brand;
            line.Numerator = match.Numerator;
            line.Denominator = match.Denominator;
            if (line.Code.Length == 0 || line.Ean.Length == 0)
            {
                Native.ErrorBeep();
                message.Show("Artykuł bez EAN-u", article.Name + "\nnie trafi do protokołu.", true);
                ShowView(message);
                return;
            }
            pending = line;
            pendingArticle = article;
            scaleCode = match.ScaleCode;
            editedLine = null;
            protocols.Editing = true;
            if (article.Multipack > 1 && !label)
            {
                AskUnit(line.Numerator == 1 && line.Denominator > 1);
                return;
            }
            EnterQuantity();
        }

        /// <summary>
        /// Artykuł z kodem sztuki i kodem wielopaku: pracownik wybiera, co liczy. Zaznaczona jest jednostka zeskanowanego
        /// kodu, więc sam Enter wystarcza.
        /// </summary>
        private void AskUnit(bool pieces)
        {
            int size = pendingArticle.Multipack;
            unit.Show(new string[] { "Sztuki", size + "-paki" }, new string[] {
                size + " szt. = 1 na protokole", "1 wielopak = 1 na protokole" });
            unit.Select(pieces ? 0 : 1);
            ShowView(unit);
        }

        private void OnUnitChosen(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            ChooseUnit(unit.Selected);
        }

        private void ChooseUnit(int option)
        {
            if (pending == null)
            {
                return;
            }
            pending.Numerator = 1;
            pending.Denominator = option == 0 ? pendingArticle.Multipack : 1;
            EnterQuantity();
        }

        private void EnterQuantity()
        {
            quantity.Show(pendingArticle, pending.Name, pending.Brand, pending.Numerator, pending.Denominator, false);
            quantityInput.Text = LabelQuantity();
            ShowView(quantity);
            quantityInput.SelectAll();
            ShowQuantityResult();
        }

        /// <summary>Ilość z etykiety wagi (kg) do pola ilości; przy zwykłym kodzie pusta.</summary>
        private string LabelQuantity()
        {
            return scaleCode.Length == 0 ? ""
                : Theme.QuantityText(Catalog.ScaleQuantity(scaleCode, pendingArticle, weightIn24));
        }

        private void OnToggle24()
        {
            weightIn24 = !weightIn24;
            if (view == quantity && pending != null && scaleCode.Length > 0)
            {
                quantityInput.Text = LabelQuantity();
                quantityInput.SelectAll();
                ShowQuantityResult();
            }
            else if (view == card && shownMatch != null)
            {
                OpenMatch(shownMatch);
            }
            UpdateBars();
            FocusInput();
        }

        private void OnChosen(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            Match match = list.SelectedMatch;
            if (match != null)
            {
                OpenMatch(match);
            }
        }

        // ---- ilość w protokole ----

        private void OnLineChosen(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            ProtocolLine line = protocol.SelectedLine;
            if (line == null)
            {
                return;
            }
            pending = null;
            scaleCode = "";
            editedLine = line;
            protocols.Editing = true;
            quantity.Show(null, line.Name, line.Brand, line.Numerator, line.Denominator, true);
            quantityInput.Text = ProtocolLine.Number(line.Entered).Replace('.', ',');
            ShowView(quantity);
            quantityInput.SelectAll();
            ShowQuantityResult();
        }

        private void OnQuantityTyped(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            ShowQuantityResult();
        }

        /// <summary>Wpisana ilość albo -1, gdy jej nie ma lub jest zła.</summary>
        private decimal Typed()
        {
            string text = quantityInput.Text.Trim().Replace(',', '.');
            if (text.Length == 0)
            {
                return -1;
            }
            try
            {
                decimal value = decimal.Parse(text, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture);
                return value > MaxQuantity ? -1 : decimal.Round(value, 3);
            }
            catch (FormatException)
            {
                return -1;
            }
        }

        private void ShowQuantityResult()
        {
            ProtocolLine line = editedLine != null ? editedLine : pending;
            if (line == null)
            {
                return;
            }
            decimal typed = Typed();
            decimal already = editedLine != null ? 0 : protocols.EnteredOf(line);
            string result = "";
            if (typed >= 0 && line.Numerator != line.Denominator)
            {
                result = "= " + Show(ProtocolLine.Convert(typed, line.Numerator, line.Denominator)) + " na protokole";
            }
            string note = already == 0
                ? scaleCode.Length > 0 && editedLine == null
                    ? Catalog.ScaleNote(scaleCode, pendingArticle, weightIn24) : line.Code
                : "Na liście jest " + Show(already) + (typed > 0 ? ", razem " + Show(already + typed) : "");
            quantity.ShowResult(result, note);
        }

        private static string Show(decimal value)
        {
            return ProtocolLine.Number(value).Replace('.', ',');
        }

        /// <summary>Enter przy ilości; fałsz, gdy ilość jest zła.</summary>
        private bool CommitQuantity()
        {
            decimal typed = Typed();
            if (typed < 0 || (typed == 0 && editedLine == null))
            {
                Native.ErrorBeep();
                quantity.ShowResult("", "Wpisz ilość większą od 0");
                quantityInput.SelectAll();
                return false;
            }
            try
            {
                if (editedLine != null)
                {
                    protocols.Change(editedLine, typed);
                }
                else
                {
                    pending.Entered = typed;
                    protocols.Add(pending);
                }
            }
            catch (Exception error)
            {
                Log.Write("Protokół: " + error);
                MessageBox.Show("Nie udało się zapisać pozycji: " + error.Message, "Intermanage");
                return false;
            }
            EndQuantity();
            return true;
        }

        private void EndQuantity()
        {
            pending = null;
            pendingArticle = null;
            scaleCode = "";
            editedLine = null;
            protocols.Editing = false;
            searched = "";
            input.Text = "";
            ShowProtocol();
        }

        private void OnResetList()
        {
            if (protocols.Count > 0)
            {
                if (Ask("Wyczyścić listę (" + protocols.Count + " poz.)?"))
                {
                    protocols.Reset();
                }
            }
            else if (protocols.CanRestore && Ask("Przywrócić wyczyszczoną listę?"))
            {
                protocols.Restore();
            }
            ShowProtocol();
        }

        private static bool Ask(string question)
        {
            return MessageBox.Show(question, "Protokół", MessageBoxButtons.YesNo, MessageBoxIcon.Question,
                MessageBoxDefaultButton.Button2) == DialogResult.Yes;
        }

        // ---- klawisze i skaner ----

        protected override void OnKeyDown(KeyEventArgs e)
        {
            lastInput = Environment.TickCount;
            ScrollView scrolled = view as ScrollView;
            if (mode != Mode.Pair && scrolled != null)
            {
                switch (e.KeyCode)
                {
                    case Keys.Up:
                        scrolled.Step(-1, false);
                        e.Handled = true;
                        break;
                    case Keys.Down:
                        scrolled.Step(1, false);
                        e.Handled = true;
                        break;
                    case Keys.PageUp:
                        scrolled.Step(-1, true);
                        e.Handled = true;
                        break;
                    case Keys.PageDown:
                        scrolled.Step(1, true);
                        e.Handled = true;
                        break;
                }
            }
            base.OnKeyDown(e);
        }

        protected override void OnKeyPress(KeyPressEventArgs e)
        {
            lastInput = Environment.TickCount;
            bool enter = e.KeyChar == '\r' || e.KeyChar == '\n' || e.KeyChar == '\t';
            if (enter)
            {
                bool repeated = Environment.TickCount - lastEnter < EnterGap;
                lastEnter = Environment.TickCount;
                if (repeated)
                {
                    e.Handled = true;
                    return;
                }
            }
            if (mode == Mode.Pair)
            {
                if (enter)
                {
                    e.Handled = true;
                    OnPair(this, EventArgs.Empty);
                }
            }
            else if (e.KeyChar == (char)27)
            {
                e.Handled = true;
                Back();
            }
            else if (mode == Mode.Home)
            {
                e.Handled = true;
                if (enter)
                {
                    Open(home.Selected);
                }
                else if (e.KeyChar == '1' || e.KeyChar == '2')
                {
                    Open(e.KeyChar - '1');
                }
            }
            else if (view == unit)
            {
                e.Handled = true;
                if (enter)
                {
                    ChooseUnit(unit.Selected);
                }
                else if (e.KeyChar == '1' || e.KeyChar == '2')
                {
                    ChooseUnit(e.KeyChar - '1');
                }
            }
            else if (enter)
            {
                e.Handled = true;
                if (view == quantity)
                {
                    CommitQuantity();
                }
                else
                {
                    Search();
                }
            }
            else if (view == quantity && e.KeyChar != '\b' && e.KeyChar != ',' && e.KeyChar != '.'
                && (e.KeyChar < '0' || e.KeyChar > '9'))
            {
                e.Handled = true;
            }
            if (enter)
            {
                // Także po długim szukaniu: powtórzony Enter czekał w kolejce.
                lastEnter = Environment.TickCount;
            }
            base.OnKeyPress(e);
        }

        private void Back()
        {
            if (mode == Mode.Home || mode == Mode.Pair)
            {
                return;
            }
            if (view == quantity || view == unit)
            {
                EndQuantity();
                return;
            }
            if (view == card && openedFromList)
            {
                ShowView(list);
                return;
            }
            bool start = input.Text.Length == 0;
            input.Text = "";
            searched = "";
            if (start)
            {
                GoHome();
            }
            else if (mode == Mode.Scan)
            {
                ShowScanStart();
            }
            else
            {
                ShowProtocol();
            }
        }

        private void OnTyped(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            if (input.Text.Length <= 1)
            {
                typingFrom = Environment.TickCount;
            }
            scan.Enabled = input.Text != searched && Catalog.IsDigits(input.Text) && input.Text.Length >= MinScanLength;
        }

        private void OnScanPause(object sender, EventArgs e)
        {
            scan.Enabled = false;
            int typed = Environment.TickCount - typingFrom - ScanPause;
            if (InputShown && typed <= input.Text.Length * ScanMillisPerKey)
            {
                Search();
            }
        }

        private void OnScannerRead(object sender, EventArgs e)
        {
            OnUi(new EventHandler(OnScanned));
        }

        private void OnScanned(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            string code = scanner.Code;
            if (mode == Mode.Pair)
            {
                if (code.Length == 8 && Catalog.IsDigits(code))
                {
                    pairCode.Text = code;
                }
                return;
            }
            if (mode == Mode.Home)
            {
                Open(0);
            }
            if (view == unit)
            {
                Native.ErrorBeep();
                EndQuantity();
            }
            if (view == quantity && !FinishBeforeNextScan())
            {
                return;
            }
            // Ten sam kod drugi raz też ma pokazać artykuł, a nie otworzyć pozycję listy.
            searched = "";
            input.Text = code;
            Search();
            FocusInput();
        }

        /// <summary>
        /// Skan w trakcie wpisywania ilości: wpisana ilość jest dodawana, a artykuł bez ilości zostaje pominięty z
        /// ostrzegawczym dźwiękiem.
        /// </summary>
        private bool FinishBeforeNextScan()
        {
            if (Typed() > 0)
            {
                return CommitQuantity();
            }
            Native.ErrorBeep();
            EndQuantity();
            return true;
        }

        // ---- paski ----

        private void UpdateBars()
        {
            int battery = Native.Battery;
            string right = (battery < 0 ? "" : battery + "%  ") + "Menu";
            bool inside = mode == Mode.Scan || mode == Mode.Protocol;
            string title = Settings.StoreNumber.Length > 0 ? "Intermanage " + Settings.StoreNumber : "Intermanage";
            if (view == list)
            {
                title = list.Count >= Catalog.ResultLimit ? "Pierwsze " + Catalog.ResultLimit : "Wyniki: " + list.Count;
            }
            else if (mode == Mode.Scan)
            {
                title = "Skanowanie";
            }
            else if (mode == Mode.Protocol)
            {
                title = "Protokół: " + protocols.Count + " poz.";
            }
            header.Show(inside ? Bar.Icon.Back : Bar.Icon.None, title, right, Theme.Ink, Color.White, -1);

            Bar.Icon icon = mode != Mode.Protocol || view == quantity || view == unit ? Bar.Icon.None
                : protocols.Count > 0 ? Bar.Icon.Trash : protocols.CanRestore ? Bar.Icon.Undo : Bar.Icon.None;
            footer.ShowToggle(inside ? weightIn24 ? "24: kg" : "24: zł" : "", weightIn24);
            if (sync.Percent >= 0)
            {
                footer.Show(icon, sync.Status, sync.Percent + "%", Theme.Panel, Theme.Ink, sync.Percent);
            }
            else if (sync.Status.Length > 0 && Native.OnCradle)
            {
                footer.Show(icon, sync.Status, DataTime(), Theme.BadBack, Theme.Bad, -1);
            }
            else if (catalog == null)
            {
                footer.Show(icon, Settings.Paired ? "Brak danych" : "Niepołączony", "", Theme.BadBack, Theme.Bad, -1);
            }
            else if (sync.ReadyVersion.Length > 0)
            {
                footer.Show(icon, "Nowa wersja " + sync.ReadyVersion + " w Menu", DataTime(), Theme.Selected, Theme.Ink, -1);
            }
            else if (catalog.ReportAt.Date < DateTime.Today)
            {
                footer.Show(icon, inside ? "Nieaktualne" : Native.OnCradle ? "Sklep ma stary raport" : "Nieaktualne, na podstawkę", DataTime(),
                    Theme.WarningBack, Theme.Warning, -1);
            }
            else
            {
                footer.Show(icon, "Dane aktualne", DataTime(), Theme.GoodBack, Theme.Good, -1);
            }
        }

        private string DataTime()
        {
            return catalog == null ? "" : catalog.ReportAt.ToString("dd.MM HH:mm", CultureInfo.InvariantCulture);
        }

        private void OnHeader(object sender, MouseEventArgs e)
        {
            lastInput = Environment.TickCount;
            if (header.OnIcon(e.X))
            {
                Back();
                return;
            }
            if (e.X < ClientSize.Width / 2)
            {
                return;
            }
            install.Enabled = sync.ReadyVersion.Length > 0;
            menu.Show(header, new Point(e.X, HeaderHeight));
            FocusInput();
        }

        private void OnFooter(object sender, MouseEventArgs e)
        {
            lastInput = Environment.TickCount;
            if (footer.OnToggle(e.X))
            {
                OnToggle24();
                return;
            }
            if (footer.OnIcon(e.X))
            {
                OnResetList();
                return;
            }
            OnDownload(sender, e);
        }

        // ---- menu ----

        private void OnDownload(object sender, EventArgs e)
        {
            sync.Request();
            FocusInput();
        }

        private void OnNetwork(object sender, EventArgs e)
        {
            message.Show("Stan sieci", "Sprawdzam…", false);
            if (mode == Mode.Home)
            {
                mode = Mode.Scan;
            }
            if (mode != Mode.Pair && view != quantity && view != unit)
            {
                ShowView(message);
            }
            Thread thread = new Thread(new ThreadStart(CheckNetwork));
            thread.IsBackground = true;
            thread.Start();
        }

        private void CheckNetwork()
        {
            networkReport = Network.Report(new Uri(Settings.Server));
            Log.Write("Stan sieci:\r\n" + networkReport);
            OnUi(new EventHandler(OnNetworkReport));
        }

        private void OnNetworkReport(object sender, EventArgs e)
        {
            message.Show("Stan sieci", networkReport, false);
            if (mode == Mode.Pair)
            {
                pairInfo.Text = networkReport.Replace("\n", "\r\n");
            }
            else if (view == message)
            {
                ShowView(message);
            }
        }

        private void OnAbout(object sender, EventArgs e)
        {
            MessageBox.Show("Wersja " + BuildInfo.Version + "\r\nKolektor: "
                + (Settings.CollectorName.Length > 0 ? Settings.CollectorName : "niepołączony") + "\r\nDane z raportu: "
                + (catalog == null ? "brak" : catalog.ReportAt.ToString("dd.MM.yyyy HH:mm")) + "\r\nSerwer: "
                + Settings.Server + "\r\nSkaner: " + scanner.State + "\r\n.NET " + Environment.Version + ", "
                + Environment.OSVersion, "O programie");
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
            // Enter z kodu potrafi dojść jeszcze raz, już na ekranie głównym.
            lastEnter = Environment.TickCount;
            GoHome();
            sync.Request();
        }

        // ---- zdarzenia z wątków w tle ----

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

        private void OnSyncLists(object sender, EventArgs e)
        {
            OnUi(new EventHandler(OnListsChanged));
        }

        private void OnListsChanged(object sender, EventArgs e)
        {
            if (mode == Mode.Home)
            {
                RefreshHome();
            }
            else if (mode == Mode.Protocol && (view == protocol || view == message))
            {
                ShowProtocol();
            }
            UpdateBars();
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
            if (mode == Mode.Home)
            {
                RefreshHome();
            }
            else if (view != quantity && view != unit && mode != Mode.Pair)
            {
                // Numery artykułów zmieniają się z plikiem, więc wyniki zaczynają od nowa z tym samym szukaniem.
                string text = searched;
                searched = "";
                if (text.Length > 0 && catalog != null && (view == list || view == card))
                {
                    input.Text = text;
                    Search();
                }
                else if (mode == Mode.Scan)
                {
                    ShowScanStart();
                }
            }
            UpdateBars();
        }

        private void OnUnpaired(object sender, EventArgs e)
        {
            GoHome();
            pairInfo.Text = "Kolektor został odłączony w Intermanage. Wpisz nowy kod.";
        }

        private void OnClock(object sender, EventArgs e)
        {
            UpdateBars();
            if (sender != clock)
            {
                return;
            }
            sync.RestartWhenStuck();
            if (sync.ReadyVersion.Length > 0 && sync.Percent < 0 && Native.OnCradle && view != quantity && view != unit
                && Environment.TickCount - lastInput > IdleBeforeUpdate)
            {
                OnInstallVersion(sender, e);
            }
        }
    }
}
