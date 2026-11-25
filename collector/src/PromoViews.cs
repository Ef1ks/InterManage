using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace Intermanage.Collector
{
    /// <summary>
    /// Promocje na kolektorze: dwa wiersze ustawień (początek operacji, koniec dla nowych artykułów), pod nimi artykuły
    /// od ostatnio dodanego.
    /// </summary>
    internal class PromoView : RowView
    {
        private List<PromoLine> lines = new List<PromoLine>();
        private string start = "";
        private string end = "";

        public const int SettingRows = 2;

        public PromoLine SelectedLine
        {
            get { return Selected < SettingRows || lines.Count == 0 ? null : lines[Selected - SettingRows]; }
        }

        /// <summary>0 = początek, 1 = koniec dla nowych, -1 = artykuł.</summary>
        public int SelectedSetting
        {
            get { return Selected < SettingRows ? Selected : -1; }
        }

        public void Show(List<PromoLine> newLines, string startText, string endText)
        {
            lines = newLines;
            start = startText;
            end = endText;
            Reset();
            // Z artykułami zaznaczony jest ostatnio dodany, więc Enter go poprawia.
            if (lines.Count > 0)
            {
                Step(SettingRows, false);
            }
        }

        protected override int RowHeight { get { return 42; } }
        protected override int Rows { get { return SettingRows + lines.Count; } }

        protected override void DrawRow(Graphics g, int index, int y, int width, bool selected)
        {
            if (index < SettingRows)
            {
                if (!selected)
                {
                    Fill(g, Theme.Panel, 0, y, width, RowHeight);
                }
                Write(g, index == 0 ? "Początek promocji" : "Koniec dla nowych", Theme.Text, Theme.Muted, 6, y + 3);
                string value = index == 0 ? start : end;
                Write(g, Cut(g, value, Theme.Title, width - 30), Theme.Title, Theme.Ink, 6, y + 18);
                WriteRight(g, "›", Theme.Title, Theme.Muted, width - 8, y + 10);
                return;
            }
            PromoLine line = lines[index - SettingRows];
            string price = Theme.Money(PromoLine.Money(line.Price));
            int priceWidth = TextWidth(g, price, Theme.Title);
            WriteRight(g, price, Theme.Title, Theme.Bad, width - 5, y + 2);
            Write(g, Cut(g, line.Name, Theme.Bold, width - priceWidth - 16), Theme.Bold, Theme.Ink, 4, y + 3);
            string until = "do " + Theme.Short(line.DateTo);
            int untilWidth = TextWidth(g, until, Theme.Bold);
            WriteRight(g, until, Theme.Bold, Theme.Ink, width - 5, y + 22);
            string note = line.Regular.Length > 0 ? "zwykła " + Theme.Money(line.Regular) : line.Ean;
            if (line.Discount > 0)
            {
                note += "  −" + line.Discount + "%";
            }
            if (line.Unsent && line.SentKey.Length > 0)
            {
                note += "  zmieniona";
            }
            Write(g, Cut(g, note, Theme.Text, width - untilWidth - 16), Theme.Text, Theme.Muted, 4, y + 22);
        }
    }

    /// <summary>
    /// Cena promocji artykułu: pole ceny (kontrolka nad widokiem), trzy duże przyciski przecen od ceny zwykłej i koniec
    /// promocji (dotknięcie otwiera kalendarz).
    /// </summary>
    internal class PriceView : Surface
    {
        public static readonly int[] Discounts = new int[] { 30, 50, 70 };
        public const int InputTop = 66;
        public const int InputLeft = 74;
        private const int ResultTop = 104;
        private const int DiscountTop = 128;
        private const int ButtonHeight = 38;
        private const int EndTop = 174;

        private string title = "";
        private string details = "";
        private bool hasRegular;
        private int discount;
        private DateTime end = DateTime.MinValue;
        private string result = "";
        private bool warning;
        private bool editing;

        public void Show(string name, string info, bool regularKnown, bool isEdit)
        {
            title = name;
            details = info;
            hasRegular = regularKnown;
            editing = isEdit;
            Invalidate();
        }

        public void Update(int newDiscount, DateTime newEnd, string newResult, bool isWarning)
        {
            discount = newDiscount;
            end = newEnd;
            result = newResult;
            warning = isWarning;
            Invalidate();
        }

        /// <summary>Indeks przecenki (0-2), 3 = koniec promocji, -1 = nic.</summary>
        public int Hit(int x, int y)
        {
            if (y >= DiscountTop && y < DiscountTop + ButtonHeight && hasRegular)
            {
                return Math.Min(Discounts.Length - 1, x * Discounts.Length / Math.Max(1, ClientSize.Width));
            }
            return y >= EndTop && y < EndTop + ButtonHeight ? 3 : -1;
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            int y = 4;
            List<string> lines = Wrap(g, title, Theme.Title, width - 8);
            for (int i = 0; i < lines.Count && i < 2; i++)
            {
                Write(g, i == 1 && lines.Count > 2 ? Cut(g, lines[i] + "…", Theme.Title, width - 8) : lines[i],
                    Theme.Title, Theme.Ink, 4, y);
                y += LineHeight(g, Theme.Title);
            }
            Write(g, Cut(g, details, Theme.Bold, width - 8), Theme.Bold, Theme.Muted, 4, InputTop - 20);
            Write(g, "Cena", Theme.Title, Theme.Ink, 6, InputTop + 8);
            Write(g, Cut(g, result, Theme.Bold, width - 8), Theme.Bold, warning ? Theme.Warning : Theme.Good, 4, ResultTop);

            int buttonWidth = width / Discounts.Length;
            for (int i = 0; i < Discounts.Length; i++)
            {
                bool on = discount == Discounts[i];
                int x = i * buttonWidth;
                Fill(g, !hasRegular ? Theme.Panel : on ? Theme.Ink : Theme.WarningBack, x + 2, DiscountTop,
                    buttonWidth - 4, ButtonHeight);
                string label = "−" + Discounts[i] + "%";
                Color ink = !hasRegular ? Theme.Muted : on ? Color.White : Theme.Warning;
                Write(g, label, Theme.Number, ink, x + (buttonWidth - TextWidth(g, label, Theme.Number)) / 2,
                    DiscountTop + (ButtonHeight - LineHeight(g, Theme.Number)) / 2);
            }

            Fill(g, end == DateTime.MinValue ? Theme.WarningBack : Theme.Selected, 2, EndTop, width - 4, ButtonHeight);
            Write(g, "Koniec", Theme.Text, Theme.Muted, 8, EndTop + 2);
            string until = end == DateTime.MinValue ? "wybierz" : Theme.DayName(end);
            Write(g, until, Theme.Title, Theme.Ink, 8, EndTop + 16);
            WriteRight(g, "›", Theme.Number, Theme.Ink, width - 10, EndTop + 6);

            string hint = hasRegular ? "Enter " + (editing ? "zapisuje, 0 usuwa" : "dodaje") + ", ↑↓ przecena, Esc"
                : "Enter " + (editing ? "zapisuje, 0 usuwa" : "dodaje") + ", Esc anuluje";
            Write(g, Cut(g, hint, Theme.Text, width - 8), Theme.Text, Theme.Muted, 4, height - 20);
        }
    }

    /// <summary>
    /// Kalendarz na cały ekran: miesiąc z dużymi polami dni (jedno dotknięcie wybiera dzień), strzałki miesięcy u góry
    /// i skróty na dole. Dni przed {@code min} są wyszarzone. Klawisze obsługuje okno (strzałki, Enter, Esc).
    /// </summary>
    internal class CalendarView : Surface
    {
        private static readonly string[] Months = new string[] { "styczeń", "luty", "marzec", "kwiecień", "maj",
            "czerwiec", "lipiec", "sierpień", "wrzesień", "październik", "listopad", "grudzień" };
        private static readonly string[] WeekDays = new string[] { "Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd" };
        private const int NavHeight = 32;
        private const int WeekHeight = 18;
        private const int ChipHeight = 36;
        private const int ArrowWidth = 48;

        private DateTime month;
        private DateTime selected;
        private DateTime min;
        private string[] chipLabels = new string[0];
        private DateTime[] chipDates = new DateTime[0];

        /// <summary>Wybrany dzień (dotknięcie dnia albo skrótu, Enter).</summary>
        public event EventHandler Picked;

        public DateTime Selected { get { return selected; } }

        public void Show(DateTime value, DateTime earliest, string[] labels, DateTime[] dates)
        {
            min = earliest.Date;
            selected = value == DateTime.MinValue || value < min ? min : value.Date;
            month = new DateTime(selected.Year, selected.Month, 1);
            chipLabels = labels;
            chipDates = dates;
            Invalidate();
        }

        public void Move(int days)
        {
            Select(selected.AddDays(days));
        }

        public void MoveMonth(int months)
        {
            DateTime next = month.AddMonths(months);
            if (next.AddMonths(1).AddDays(-1) < min)
            {
                return;
            }
            month = next;
            DateTime day = new DateTime(next.Year, next.Month, Math.Min(selected.Day,
                DateTime.DaysInMonth(next.Year, next.Month)));
            selected = day < min ? min : day;
            Invalidate();
        }

        public void Pick()
        {
            if (Picked != null)
            {
                Picked(this, EventArgs.Empty);
            }
        }

        private void Select(DateTime day)
        {
            selected = day < min ? min : day;
            month = new DateTime(selected.Year, selected.Month, 1);
            Invalidate();
        }

        private DateTime GridStart
        {
            get { return month.AddDays(-(((int)month.DayOfWeek + 6) % 7)); }
        }

        private int RowHeight(int height)
        {
            return Math.Max(20, (height - NavHeight - WeekHeight - ChipHeight) / 6);
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            int width = ClientSize.Width;
            int height = ClientSize.Height;
            if (e.Y < NavHeight)
            {
                if (e.X < ArrowWidth)
                {
                    MoveMonth(-1);
                }
                else if (e.X > width - ArrowWidth)
                {
                    MoveMonth(1);
                }
                return;
            }
            if (e.Y >= height - ChipHeight)
            {
                if (chipDates.Length > 0)
                {
                    int chip = Math.Min(chipDates.Length - 1, e.X * chipDates.Length / Math.Max(1, width));
                    if (chipDates[chip] >= min)
                    {
                        Select(chipDates[chip]);
                        Pick();
                    }
                }
                return;
            }
            int top = NavHeight + WeekHeight;
            if (e.Y < top)
            {
                return;
            }
            int row = (e.Y - top) / RowHeight(height);
            int column = Math.Min(6, e.X * 7 / Math.Max(1, width));
            if (row > 5)
            {
                return;
            }
            DateTime day = GridStart.AddDays(row * 7 + column);
            if (day >= min)
            {
                Select(day);
                Pick();
            }
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            Fill(g, Theme.Panel, 0, 0, width, NavHeight);
            Arrow(g, ArrowWidth / 2, NavHeight / 2, -1, month.AddDays(-1) >= min);
            Arrow(g, width - ArrowWidth / 2, NavHeight / 2, 1, true);
            string label = Months[month.Month - 1] + " " + month.Year;
            Write(g, label, Theme.Title, Theme.Ink, (width - TextWidth(g, label, Theme.Title)) / 2,
                (NavHeight - LineHeight(g, Theme.Title)) / 2);

            int cell = width / 7;
            for (int i = 0; i < 7; i++)
            {
                Write(g, WeekDays[i], Theme.Small, i == 6 ? Theme.Bad : Theme.Muted,
                    i * cell + (cell - TextWidth(g, WeekDays[i], Theme.Small)) / 2, NavHeight + 3);
            }
            int rowHeight = RowHeight(height);
            int top = NavHeight + WeekHeight;
            DateTime day = GridStart;
            DateTime today = DateTime.Today;
            for (int row = 0; row < 6; row++)
            {
                for (int column = 0; column < 7; column++, day = day.AddDays(1))
                {
                    int x = column * cell;
                    int y = top + row * rowHeight;
                    bool inMonth = day.Month == month.Month;
                    bool allowed = day >= min;
                    bool chosen = day == selected;
                    if (chosen)
                    {
                        Fill(g, Theme.Ink, x + 2, y + 2, cell - 4, rowHeight - 4);
                    }
                    else if (day == today)
                    {
                        using (Pen pen = new Pen(Theme.Ink))
                        {
                            g.DrawRectangle(pen, x + 2, y + 2, cell - 5, rowHeight - 5);
                        }
                    }
                    string number = day.Day.ToString();
                    Color ink = chosen ? Color.White : !allowed ? Theme.Line : !inMonth ? Theme.Muted
                        : column == 6 ? Theme.Bad : Theme.Ink;
                    Font font = inMonth ? Theme.Title : Theme.Text;
                    Write(g, number, font, ink, x + (cell - TextWidth(g, number, font)) / 2,
                        y + (rowHeight - LineHeight(g, font)) / 2);
                }
            }

            int chipTop = height - ChipHeight;
            if (chipDates.Length > 0)
            {
                int chipWidth = width / chipDates.Length;
                for (int i = 0; i < chipDates.Length; i++)
                {
                    bool allowed = chipDates[i] >= min;
                    Fill(g, allowed ? Theme.Selected : Theme.Panel, i * chipWidth + 2, chipTop + 2, chipWidth - 4,
                        ChipHeight - 4);
                    Write(g, chipLabels[i], Theme.Bold, allowed ? Theme.Ink : Theme.Muted,
                        i * chipWidth + (chipWidth - TextWidth(g, chipLabels[i], Theme.Bold)) / 2,
                        chipTop + (ChipHeight - LineHeight(g, Theme.Bold)) / 2);
                }
            }
        }

        private static void Arrow(Graphics g, int x, int y, int direction, bool enabled)
        {
            Point[] points = new Point[] { new Point(x - 6 * direction, y - 9), new Point(x + 6 * direction, y),
                new Point(x - 6 * direction, y + 9) };
            using (SolidBrush brush = new SolidBrush(enabled ? Theme.Ink : Theme.Line))
            {
                g.FillPolygon(brush, points);
            }
        }
    }

    /// <summary>Pytanie z dużymi, ponumerowanymi odpowiedziami (np. przy wysyłce listy na podstawce).</summary>
    internal class AskView : Surface
    {
        private const int OptionHeight = 50;
        private string title = "";
        private string text = "";
        private string[] options = new string[0];
        private string[] notes = new string[0];
        private int[] tops = new int[0];

        public event EventHandler Chosen;

        public int Selected { get; private set; }
        public int Count { get { return options.Length; } }

        public void Show(string newTitle, string newText, string[] newOptions, string[] newNotes)
        {
            title = newTitle;
            text = newText;
            options = newOptions;
            notes = newNotes;
            tops = new int[options.Length];
            Selected = 0;
            Invalidate();
        }

        public void Step(int direction)
        {
            Selected = Math.Max(0, Math.Min(options.Length - 1, Selected + direction));
            Invalidate();
        }

        public void Choose(int index)
        {
            if (index < 0 || index >= options.Length)
            {
                return;
            }
            Selected = index;
            if (Chosen != null)
            {
                Chosen(this, EventArgs.Empty);
            }
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            for (int i = 0; i < tops.Length; i++)
            {
                if (e.Y >= tops[i] && e.Y < tops[i] + OptionHeight)
                {
                    Choose(i);
                    return;
                }
            }
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            int y = 8;
            foreach (string line in Wrap(g, title, Theme.Title, width - 16))
            {
                Write(g, line, Theme.Title, Theme.Ink, 8, y);
                y += LineHeight(g, Theme.Title);
            }
            y += 4;
            foreach (string line in Wrap(g, text, Theme.Text, width - 16))
            {
                Write(g, line, Theme.Text, Theme.Muted, 8, y);
                y += LineHeight(g, Theme.Text) + 2;
            }
            // Odpowiedzi przy dolnej krawędzi, gdy jest miejsce: kciuk sięga tam najłatwiej.
            y = Math.Max(y + 8, height - options.Length * OptionHeight);
            for (int i = 0; i < options.Length; i++)
            {
                tops[i] = y;
                if (i == Selected)
                {
                    Fill(g, Theme.Selected, 0, y, width, OptionHeight);
                }
                Fill(g, Theme.Ink, 8, y + 7, 36, 36);
                string number = (i + 1).ToString();
                Write(g, number, Theme.Number, Color.White, 8 + (36 - TextWidth(g, number, Theme.Number)) / 2, y + 11);
                Write(g, Cut(g, options[i], Theme.Title, width - 60), Theme.Title, Theme.Ink, 54, y + 6);
                Write(g, Cut(g, notes[i], Theme.Text, width - 60), Theme.Text, Theme.Muted, 54, y + 28);
                Rule(g, y + OptionHeight - 1, width);
                y += OptionHeight;
            }
        }
    }
}
