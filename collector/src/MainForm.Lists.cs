using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows.Forms;

namespace Intermanage.Collector
{
    /// <summary>
    /// Ustaw promocje i pytania przy wysyłce list. Na podstawce program pyta, czy wysłać listę (nic nie wychodzi bez
    /// pytania); po wysyłce pyta, czy ją usunąć, a wysłana i niezmieniona lista znika sama po 2 godzinach.
    /// </summary>
    internal partial class MainForm
    {
        private enum CalendarFor { Start, DefaultEnd, LineEnd }
        private enum AskFor { None, SendProtocol, SendPromo, DeleteProtocol, DeletePromo }

        private readonly PromoStore promos = new PromoStore();
        private readonly PromoView promoList = new PromoView();
        private readonly PriceView price = new PriceView();
        private readonly TextBox priceInput = new TextBox();
        private readonly CalendarView calendar = new CalendarView();
        private readonly AskView ask = new AskView();
        /// <summary>
        /// Niewidoczne pole z fokusem na kalendarzu i pytaniu: strzałki trafiają wtedy do okna jak w polu tekstowym,
        /// a nie przesuwają fokusu między kontrolkami.
        /// </summary>
        private readonly TextBox keys = new TextBox();

        // Wpisywanie ceny promocji: nowy albo poprawiany artykuł (wtedy promoEditing).
        private PromoLine pendingPromo;
        private bool promoEditing;
        private bool settingPrice;
        private CalendarFor calendarFor;
        /// <summary>Kalendarz otworzył Enter przy cenie bez końca: po wyborze dnia artykuł od razu idzie na listę.</summary>
        private bool commitAfterCalendar;

        private AskFor asking = AskFor.None;
        private Control beforeAsk;
        private bool declinedProtocol;
        private bool declinedPromo;
        private bool wasOnCradle;

        private void SetUpLists()
        {
            priceInput.Font = Theme.Price;
            priceInput.BorderStyle = BorderStyle.FixedSingle;
            priceInput.MaxLength = 8;
            priceInput.TextChanged += new EventHandler(OnPriceTyped);
            price.Controls.Add(priceInput);
            price.MouseDown += new MouseEventHandler(OnPriceTap);
            promoList.Chosen += new EventHandler(OnPromoChosen);
            calendar.Picked += new EventHandler(OnDayPicked);
            ask.Chosen += new EventHandler(OnAskChosen);
            keys.Bounds = new System.Drawing.Rectangle(-40, -40, 10, 10);
            Controls.Add(keys);
        }

        private bool Entering
        {
            get { return view == quantity || view == unit || view == price || view == calendar; }
        }

        // ---- promocje ----

        private void ShowPromo()
        {
            List<PromoLine> lines = promos.Lines;
            promoList.Show(lines, promos.Start == DateTime.MinValue ? "dziś" : Theme.DayName(promos.Start),
                promos.DefaultEnd == DateTime.MinValue ? "wybierz przy pierwszym artykule"
                    : Theme.DayName(promos.DefaultEnd));
            ShowView(promoList);
        }

        private void OnPromoChosen(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            int setting = promoList.SelectedSetting;
            if (setting == 0)
            {
                OpenCalendar(CalendarFor.Start, promos.Start, false);
            }
            else if (setting == 1)
            {
                OpenCalendar(CalendarFor.DefaultEnd, promos.DefaultEnd, false);
            }
            else if (promoList.SelectedLine != null)
            {
                PromoLine line = promoList.SelectedLine;
                EnterPrice(line.Copy(), null, true);
            }
        }

        /// <summary>Zeskanowany artykuł w promocjach: nowy z końcem dla nowych albo poprawka tego, co już jest.</summary>
        private void OpenPromo(Article article)
        {
            if (article.Ean.Length == 0)
            {
                Native.ErrorBeep();
                message.Show("Artykuł bez EAN-u", article.Name + "\nnie trafi do promocji.", true);
                ShowView(message);
                return;
            }
            PromoLine known = promos.Find(article.Itm8);
            if (known != null)
            {
                EnterPrice(known.Copy(), article, true);
                return;
            }
            PromoLine line = new PromoLine();
            line.Itm8 = article.Itm8;
            line.Ean = article.Ean;
            line.Name = article.Name;
            line.Brand = article.Brand;
            line.Regular = article.RegularOn(DateTime.Today);
            line.DateTo = promos.DefaultEnd;
            EnterPrice(line, article, false);
        }

        private void EnterPrice(PromoLine line, Article article, bool editing)
        {
            pendingPromo = line;
            promoEditing = editing;
            string info = line.Regular.Length > 0 ? "Zwykła " + Theme.Money(line.Regular) : "Brak ceny zwykłej";
            if (article != null && article.PromotionOn(DateTime.Today))
            {
                info += ", teraz " + Theme.Money(article.PromoPrice) + " do " + Theme.Short(article.PromoTo);
            }
            price.Show(line.Name, info, RegularPrice > 0, editing);
            settingPrice = true;
            priceInput.Text = editing ? PromoLine.Money(line.Price).Replace('.', ',') : "";
            settingPrice = false;
            ShowView(price);
            priceInput.SelectAll();
            ShowPriceResult();
        }

        private decimal RegularPrice
        {
            get
            {
                return pendingPromo == null || pendingPromo.Regular.Length == 0 ? 0
                    : decimal.Parse(pendingPromo.Regular, CultureInfo.InvariantCulture);
            }
        }

        /// <summary>Wpisana cena albo -1, gdy jej nie ma lub jest zła.</summary>
        private decimal TypedPrice()
        {
            string text = priceInput.Text.Trim().Replace(',', '.');
            if (text.Length == 0)
            {
                return -1;
            }
            try
            {
                decimal value = decimal.Parse(text, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture);
                return value > 99999 ? -1 : decimal.Round(value, 2);
            }
            catch (FormatException)
            {
                return -1;
            }
        }

        private void OnPriceTyped(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            if (!settingPrice && pendingPromo != null)
            {
                decimal typed = TypedPrice();
                // Cena wpisana ręcznie to już nie przecena, chyba że wyszła ta sama.
                if (pendingPromo.Discount > 0 && (RegularPrice <= 0
                    || typed != PromoLine.Discounted(RegularPrice, pendingPromo.Discount)))
                {
                    pendingPromo.Discount = 0;
                }
            }
            ShowPriceResult();
        }

        private void ShowPriceResult()
        {
            if (pendingPromo == null)
            {
                return;
            }
            decimal typed = TypedPrice();
            decimal regular = RegularPrice;
            string result = "";
            bool warning = false;
            if (typed > 0 && regular > 0)
            {
                decimal off = decimal.Round((1 - typed / regular) * 100, 0);
                if (typed >= regular)
                {
                    result = "Nie niżej niż zwykła " + Theme.Money(pendingPromo.Regular);
                    warning = true;
                }
                else
                {
                    result = "−" + off + "% od ceny zwykłej";
                }
            }
            else if (typed == 0 && promoEditing)
            {
                result = "Enter usunie artykuł z promocji";
                warning = true;
            }
            price.Update(pendingPromo.Discount, pendingPromo.DateTo, result, warning);
        }

        private void ChooseDiscount(int index)
        {
            decimal regular = RegularPrice;
            if (pendingPromo == null || regular <= 0)
            {
                Native.ErrorBeep();
                return;
            }
            pendingPromo.Discount = PriceView.Discounts[index];
            settingPrice = true;
            priceInput.Text = PromoLine.Money(PromoLine.Discounted(regular, pendingPromo.Discount)).Replace('.', ',');
            settingPrice = false;
            priceInput.SelectAll();
            ShowPriceResult();
        }

        /// <summary>Strzałki w polu ceny: kolejna przecena (−30%, −50%, −70%).</summary>
        private void StepDiscount(int direction)
        {
            int at = Array.IndexOf(PriceView.Discounts, pendingPromo.Discount);
            int next = at < 0 ? (direction > 0 ? 0 : PriceView.Discounts.Length - 1)
                : Math.Max(0, Math.Min(PriceView.Discounts.Length - 1, at + direction));
            ChooseDiscount(next);
        }

        private void OnPriceTap(object sender, MouseEventArgs e)
        {
            lastInput = Environment.TickCount;
            int hit = price.Hit(e.X, e.Y);
            if (hit >= 0 && hit < PriceView.Discounts.Length)
            {
                ChooseDiscount(hit);
            }
            else if (hit == 3)
            {
                OpenCalendar(CalendarFor.LineEnd, pendingPromo.DateTo, false);
            }
            else
            {
                FocusInput();
            }
        }

        /// <summary>Enter przy cenie; fałsz, gdy cena jest zła.</summary>
        private bool CommitPrice()
        {
            decimal typed = TypedPrice();
            if (typed == 0 && promoEditing)
            {
                promos.Remove(pendingPromo.Itm8);
                EndPromoEntry();
                return true;
            }
            if (typed <= 0)
            {
                Native.ErrorBeep();
                price.Update(pendingPromo.Discount, pendingPromo.DateTo, "Wpisz cenę promocji", true);
                priceInput.SelectAll();
                return false;
            }
            pendingPromo.Price = typed;
            if (pendingPromo.DateTo == DateTime.MinValue || pendingPromo.DateTo < EarliestEnd)
            {
                // Pierwszy artykuł: koniec wybiera się raz, kolejne go dostają.
                OpenCalendar(CalendarFor.LineEnd, pendingPromo.DateTo, true);
                return false;
            }
            try
            {
                promos.Put(pendingPromo);
                if (promos.DefaultEnd == DateTime.MinValue)
                {
                    promos.DefaultEnd = pendingPromo.DateTo;
                }
            }
            catch (Exception error)
            {
                Log.Write("Promocje: " + error);
                MessageBox.Show("Nie udało się zapisać promocji: " + error.Message, "Intermanage");
                return false;
            }
            EndPromoEntry();
            return true;
        }

        private void EndPromoEntry()
        {
            pendingPromo = null;
            searched = "";
            input.Text = "";
            ShowPromo();
        }

        private void OnResetPromo()
        {
            if (promos.Count > 0)
            {
                string unsent = promos.Unsent ? " Lista nie poszła jeszcze do Intermanage." : "";
                if (Ask("Wyczyścić promocje (" + promos.Count + " art.)?" + unsent))
                {
                    promos.Reset();
                }
            }
            else if (promos.CanRestore && Ask("Przywrócić wyczyszczone promocje?"))
            {
                promos.Restore();
            }
            ShowPromo();
        }

        // ---- kalendarz ----

        /// <summary>Koniec promocji nie wcześniej niż jej początek (albo dziś).</summary>
        private DateTime EarliestEnd
        {
            get { return promos.Start == DateTime.MinValue ? DateTime.Today : promos.Start; }
        }

        private void OpenCalendar(CalendarFor target, DateTime value, bool commit)
        {
            calendarFor = target;
            commitAfterCalendar = commit;
            DateTime today = DateTime.Today;
            if (target == CalendarFor.Start)
            {
                calendar.Show(value == DateTime.MinValue ? today : value, today,
                    new string[] { "Dziś", "Jutro", "Za tydzień" },
                    new DateTime[] { today, today.AddDays(1), today.AddDays(7) });
            }
            else
            {
                DateTime from = EarliestEnd;
                DateTime monthEnd = new DateTime(from.Year, from.Month, 1).AddMonths(1).AddDays(-1);
                if (monthEnd < from.AddDays(3))
                {
                    monthEnd = monthEnd.AddMonths(1);
                }
                calendar.Show(value == DateTime.MinValue ? from.AddDays(6) : value, from,
                    new string[] { "+1 tydz.", "+2 tyg.", "Koniec mies." },
                    new DateTime[] { from.AddDays(6), from.AddDays(13), monthEnd });
            }
            ShowView(calendar);
        }

        private void OnDayPicked(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            DateTime day = calendar.Selected;
            switch (calendarFor)
            {
                case CalendarFor.Start:
                    promos.Start = day <= DateTime.Today ? DateTime.MinValue : day;
                    if (promos.DefaultEnd != DateTime.MinValue && promos.DefaultEnd < EarliestEnd)
                    {
                        promos.DefaultEnd = DateTime.MinValue;
                    }
                    ShowPromo();
                    break;
                case CalendarFor.DefaultEnd:
                    promos.DefaultEnd = day;
                    ShowPromo();
                    break;
                default:
                    pendingPromo.DateTo = day;
                    if (commitAfterCalendar)
                    {
                        commitAfterCalendar = false;
                        if (CommitPrice())
                        {
                            return;
                        }
                    }
                    ShowView(price);
                    priceInput.SelectAll();
                    ShowPriceResult();
                    break;
            }
        }

        private void LeaveCalendar()
        {
            if (calendarFor == CalendarFor.LineEnd && pendingPromo != null)
            {
                commitAfterCalendar = false;
                ShowView(price);
                ShowPriceResult();
            }
            else
            {
                ShowPromo();
            }
        }

        // ---- pytania na podstawce ----

        /// <summary>Co kilka sekund: listy po 2 godzinach, a na podstawce pytanie o wysyłkę albo usunięcie.</summary>
        private void CheckLists()
        {
            if (closing || mode == Mode.Pair || !Settings.Paired)
            {
                return;
            }
            bool cleared = protocols.ClearIfOld();
            cleared = promos.ClearIfOld() || cleared;
            if (cleared && !Entering && view != ask)
            {
                RefreshMode();
            }
            bool onCradle = Native.OnCradle;
            if (!onCradle && wasOnCradle)
            {
                // Po zdjęciu z podstawki „Nie teraz” przestaje obowiązywać.
                declinedProtocol = false;
                declinedPromo = false;
            }
            wasOnCradle = onCradle;
            if (view == ask || Entering || Environment.TickCount - lastInput < 3000)
            {
                return;
            }
            if (protocols.JustSent)
            {
                AskDelete(AskFor.DeleteProtocol, "Lista wysłana do Intermanage", protocols.Count + " poz. w Kolektory, "
                    + "Dane z kolektorów.");
            }
            else if (promos.JustSent)
            {
                AskDelete(AskFor.DeletePromo, "Promocje wysłane do Intermanage", promos.LastNote);
            }
            else if (onCradle && !declinedProtocol && protocols.Unsent && protocols.Waiting.Length == 0)
            {
                ShowAsk(AskFor.SendProtocol, "Wysłać listę protokołu?", protocols.Count + " poz."
                    + (protocols.SentAt != DateTime.MinValue ? " Pójdą same zmiany od ostatniej wysyłki." : "")
                    + " Lista zostaje na kolektorze.",
                    new string[] { "Wyślij do Intermanage", "Nie teraz" },
                    new string[] { "protokół wybierzesz w Intermanage", "zapytam po ponownym włożeniu" });
            }
            else if (onCradle && !declinedPromo && promos.Unsent && promos.Waiting.Length == 0)
            {
                ShowAsk(AskFor.SendPromo, "Wysłać promocje?", promos.Count + " art., od "
                    + (promos.Start == DateTime.MinValue ? "dziś" : Theme.Day(promos.Start))
                    + (promos.SentAt != DateTime.MinValue ? ". Pójdą same zmiany od ostatniej wysyłki." : "."),
                    new string[] { "Wyślij i ustaw w Mercalys", "Tylko wyślij", "Nie teraz" },
                    new string[] { "komputer sklepu, potem cenówki", "wstawisz je w Ustaw promocje",
                        "zapytam po ponownym włożeniu" });
            }
        }

        private void AskDelete(AskFor kind, string title, string note)
        {
            ShowAsk(kind, title, (note.Length > 0 ? note + " " : "") + "Bez usuwania lista zniknie sama za 2 godziny.",
                new string[] { "Usuń z kolektora", "Zostaw" },
                new string[] { "da się ją przywrócić koszem", "zniknie sama za 2 godziny" });
        }

        private void ShowAsk(AskFor kind, string title, string text, string[] options, string[] notes)
        {
            beforeAsk = view;
            asking = kind;
            ask.Show(title, text, options, notes);
            ShowView(ask);
            Native.Beep();
        }

        private void OnAskChosen(object sender, EventArgs e)
        {
            lastInput = Environment.TickCount;
            Answer(ask.Selected);
        }

        /// <summary>Odpowiedź; ostatnia opcja to zawsze „nie teraz” / „zostaw”.</summary>
        private void Answer(int option)
        {
            AskFor kind = asking;
            asking = AskFor.None;
            bool last = option >= ask.Count - 1;
            try
            {
                switch (kind)
                {
                    case AskFor.SendProtocol:
                        if (last)
                        {
                            declinedProtocol = true;
                        }
                        else
                        {
                            protocols.PrepareSend(new Dictionary<string, string>());
                            sync.Request();
                        }
                        break;
                    case AskFor.SendPromo:
                        if (last)
                        {
                            declinedPromo = true;
                        }
                        else
                        {
                            promos.PrepareSend(option == 0);
                            sync.Request();
                        }
                        break;
                    case AskFor.DeleteProtocol:
                        protocols.Answered();
                        if (!last)
                        {
                            protocols.Reset();
                        }
                        break;
                    case AskFor.DeletePromo:
                        promos.Answered();
                        if (!last)
                        {
                            promos.Reset();
                        }
                        break;
                }
            }
            catch (Exception error)
            {
                Log.Write("Wysyłka listy: " + error);
                MessageBox.Show("Nie udało się przygotować wysyłki: " + error.Message, "Intermanage");
            }
            RefreshMode();
        }

        /// <summary>Po pytaniu i po zmianie list: bieżący ekran od nowa.</summary>
        private void RefreshMode()
        {
            switch (mode)
            {
                case Mode.Home:
                    GoHome();
                    break;
                case Mode.Protocol:
                    ShowProtocol();
                    break;
                case Mode.Promo:
                    ShowPromo();
                    break;
                default:
                    ShowView(beforeAsk != null && beforeAsk != ask ? beforeAsk : message);
                    break;
            }
            UpdateBars();
        }

        // ---- klawisze ----

        /// <summary>Klawisze ekranów promocji i pytań; prawda, gdy obsłużone.</summary>
        private bool ListKeyDown(KeyEventArgs e)
        {
            if (view == calendar)
            {
                switch (e.KeyCode)
                {
                    case Keys.Left: calendar.Move(-1); return true;
                    case Keys.Right: calendar.Move(1); return true;
                    case Keys.Up: calendar.Move(-7); return true;
                    case Keys.Down: calendar.Move(7); return true;
                    case Keys.PageUp: calendar.MoveMonth(-1); return true;
                    case Keys.PageDown: calendar.MoveMonth(1); return true;
                }
            }
            else if (view == ask && (e.KeyCode == Keys.Up || e.KeyCode == Keys.Down))
            {
                ask.Step(e.KeyCode == Keys.Up ? -1 : 1);
                return true;
            }
            else if (view == price && (e.KeyCode == Keys.Up || e.KeyCode == Keys.Down))
            {
                StepDiscount(e.KeyCode == Keys.Down ? 1 : -1);
                return true;
            }
            return false;
        }

        /// <summary>Znaki na ekranach promocji i pytań; prawda, gdy obsłużone.</summary>
        private bool ListKeyPress(KeyPressEventArgs e, bool enter)
        {
            if (view == ask)
            {
                e.Handled = true;
                if (enter)
                {
                    Answer(ask.Selected);
                }
                else if (e.KeyChar >= '1' && e.KeyChar < '1' + ask.Count)
                {
                    Answer(e.KeyChar - '1');
                }
                return true;
            }
            if (view == calendar)
            {
                e.Handled = true;
                if (enter)
                {
                    calendar.Pick();
                }
                return true;
            }
            if (view == price)
            {
                if (enter)
                {
                    e.Handled = true;
                    CommitPrice();
                }
                else if (e.KeyChar != '\b' && e.KeyChar != ',' && e.KeyChar != '.'
                    && (e.KeyChar < '0' || e.KeyChar > '9'))
                {
                    e.Handled = true;
                }
                return true;
            }
            return false;
        }

        /// <summary>Esc i strzałka w nagłówku na ekranach promocji i pytań; prawda, gdy obsłużone.</summary>
        private bool BackInLists()
        {
            if (view == ask)
            {
                Answer(ask.Count - 1);
                return true;
            }
            if (view == calendar)
            {
                LeaveCalendar();
                return true;
            }
            if (view == price)
            {
                EndPromoEntry();
                return true;
            }
            return false;
        }

        /// <summary>
        /// Skan w trakcie pytania albo ceny promocji: pytanie znika jak „Nie teraz”, a cena z poprawnym końcem jest
        /// zapisywana (bez ceny artykuł zostaje pominięty z ostrzegawczym dźwiękiem).
        /// </summary>
        private bool BeforeScan()
        {
            if (view == ask)
            {
                Answer(ask.Count - 1);
            }
            if (view == calendar)
            {
                LeaveCalendar();
            }
            if (view == price)
            {
                bool ready = TypedPrice() > 0 && pendingPromo.DateTo != DateTime.MinValue
                    && pendingPromo.DateTo >= EarliestEnd;
                if (ready)
                {
                    return CommitPrice();
                }
                Native.ErrorBeep();
                EndPromoEntry();
            }
            return true;
        }
    }
}
