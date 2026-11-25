using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace Intermanage.Collector
{
    /// <summary>Powierzchnia rysowana z bufora (bez migania). Nie bierze fokusu: klawisze obsługuje okno.</summary>
    internal abstract class Surface : Control
    {
        private Bitmap buffer;

        protected abstract void Draw(Graphics g, int width, int height);

        protected override void OnPaintBackground(PaintEventArgs e)
        {
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            if (ClientSize.Width <= 0 || ClientSize.Height <= 0)
            {
                return;
            }
            if (buffer == null || buffer.Width != ClientSize.Width || buffer.Height != ClientSize.Height)
            {
                if (buffer != null)
                {
                    buffer.Dispose();
                }
                buffer = new Bitmap(ClientSize.Width, ClientSize.Height);
            }
            using (Graphics g = Graphics.FromImage(buffer))
            {
                g.Clear(Theme.Paper);
                Draw(g, buffer.Width, buffer.Height);
            }
            e.Graphics.DrawImage(buffer, 0, 0);
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            Invalidate();
        }

        protected static void Fill(Graphics g, Color color, int x, int y, int width, int height)
        {
            using (SolidBrush brush = new SolidBrush(color))
            {
                g.FillRectangle(brush, x, y, width, height);
            }
        }

        protected static void Rule(Graphics g, int y, int width)
        {
            using (Pen pen = new Pen(Theme.Line))
            {
                g.DrawLine(pen, 0, y, width, y);
            }
        }

        protected static void Write(Graphics g, string text, Font font, Color color, int x, int y)
        {
            using (SolidBrush brush = new SolidBrush(color))
            {
                g.DrawString(text, font, brush, x, y);
            }
        }

        protected static void WriteRight(Graphics g, string text, Font font, Color color, int right, int y)
        {
            Write(g, text, font, color, right - TextWidth(g, text, font), y);
        }

        protected static int TextWidth(Graphics g, string text, Font font)
        {
            return (int)Math.Ceiling(g.MeasureString(text, font).Width);
        }

        protected static int LineHeight(Graphics g, Font font)
        {
            return (int)Math.Ceiling(g.MeasureString("Ąg", font).Height);
        }

        /// <summary>Tekst przycięty do szerokości, z „…” na końcu.</summary>
        protected static string Cut(Graphics g, string text, Font font, int width)
        {
            if (TextWidth(g, text, font) <= width)
            {
                return text;
            }
            int length = text.Length;
            while (length > 1 && TextWidth(g, text.Substring(0, length) + "…", font) > width)
            {
                length--;
            }
            return text.Substring(0, length) + "…";
        }

        /// <summary>Tekst łamany po słowach do szerokości.</summary>
        protected static List<string> Wrap(Graphics g, string text, Font font, int width)
        {
            List<string> lines = new List<string>();
            foreach (string paragraph in text.Split('\n'))
            {
                string line = "";
                foreach (string word in paragraph.Split(' '))
                {
                    string longer = line.Length == 0 ? word : line + " " + word;
                    if (line.Length > 0 && TextWidth(g, longer, font) > width)
                    {
                        lines.Add(line);
                        line = word;
                    }
                    else
                    {
                        line = longer;
                    }
                }
                lines.Add(line);
            }
            return lines;
        }
    }

    /// <summary>
    /// Widok z treścią wyższą niż ekran: okno pokazuje wtedy suwak obok (jedno dotknięcie i przeciągnięcie zamiast wielu
    /// kliknięć) i zwęża widok. Przesunięcie jest w pikselach.
    /// </summary>
    internal abstract class ScrollView : Surface
    {
        private static readonly Bitmap Probe = new Bitmap(1, 1);
        private int offset;

        /// <summary>Widok sam się przesunął (klawisze); okno ustawia suwak.</summary>
        public event EventHandler Moved;

        public int Offset
        {
            get { return offset; }
        }

        public int Content { get; private set; }

        /// <summary>Wysokość treści przy tej szerokości.</summary>
        protected abstract int Measure(Graphics g, int width);

        public int MeasureFor(int width)
        {
            using (Graphics g = Graphics.FromImage(Probe))
            {
                Content = Measure(g, width);
            }
            return Content;
        }

        public void ScrollTo(int value)
        {
            int limit = Math.Max(0, Content - ClientSize.Height);
            int next = Math.Max(0, Math.Min(limit, value));
            if (next != offset)
            {
                offset = next;
                Invalidate();
            }
        }

        /// <summary>Strzałki i PageUp / PageDown.</summary>
        public virtual void Step(int direction, bool page)
        {
            MoveTo(offset + direction * (page ? Math.Max(40, ClientSize.Height - 30) : 40));
        }

        protected void MoveTo(int value)
        {
            ScrollTo(value);
            if (Moved != null)
            {
                Moved(this, EventArgs.Empty);
            }
        }
    }

    /// <summary>Lista równych wierszy z zaznaczeniem.</summary>
    internal abstract class RowView : ScrollView
    {
        /// <summary>Wybrany wiersz (dotknięcie).</summary>
        public event EventHandler Chosen;

        protected abstract int RowHeight { get; }
        protected abstract int Rows { get; }
        protected abstract void DrawRow(Graphics g, int index, int y, int width, bool selected);

        public int Selected { get; private set; }

        protected void Reset()
        {
            Selected = 0;
            ScrollTo(0);
            Invalidate();
        }

        protected override int Measure(Graphics g, int width)
        {
            return Rows * RowHeight;
        }

        public override void Step(int direction, bool page)
        {
            if (Rows == 0)
            {
                return;
            }
            int by = direction * (page ? Math.Max(1, ClientSize.Height / RowHeight) : 1);
            Selected = Math.Max(0, Math.Min(Rows - 1, Selected + by));
            int top = Selected * RowHeight;
            if (top < Offset)
            {
                MoveTo(top);
            }
            else if (top + RowHeight > Offset + ClientSize.Height)
            {
                MoveTo(top + RowHeight - ClientSize.Height);
            }
            Invalidate();
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            for (int i = Offset / RowHeight; i < Rows && i * RowHeight < Offset + height; i++)
            {
                int y = i * RowHeight - Offset;
                bool selected = i == Selected;
                if (selected)
                {
                    Fill(g, Theme.Selected, 0, y, width, RowHeight);
                }
                DrawRow(g, i, y, width, selected);
                Rule(g, y + RowHeight - 1, width);
            }
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            int index = (Offset + e.Y) / RowHeight;
            if (index < Rows)
            {
                Selected = index;
                Invalidate();
                if (Chosen != null)
                {
                    Chosen(this, EventArgs.Empty);
                }
            }
        }
    }

    /// <summary>Ekran główny: ponumerowane opcje.</summary>
    internal class HomeMenu : RowView
    {
        private string[] titles = new string[0];
        private string[] notes = new string[0];

        public void Show(string[] newTitles, string[] newNotes)
        {
            titles = newTitles;
            notes = newNotes;
            Invalidate();
        }

        public void Select(int index)
        {
            Reset();
            for (int i = 0; i < index; i++)
            {
                Step(1, false);
            }
        }

        protected override int RowHeight { get { return 58; } }
        protected override int Rows { get { return titles.Length; } }

        protected override void DrawRow(Graphics g, int index, int y, int width, bool selected)
        {
            Fill(g, Theme.Ink, 8, y + 10, 36, 36);
            string number = (index + 1).ToString();
            Write(g, number, Theme.Number, Color.White, 8 + (36 - TextWidth(g, number, Theme.Number)) / 2, y + 14);
            Write(g, titles[index], Theme.Title, Theme.Ink, 54, y + 9);
            Write(g, Cut(g, notes[index], Theme.Text, width - 60), Theme.Text, Theme.Muted, 54, y + 31);
        }
    }

    /// <summary>Lista znalezionych artykułów; wiersze czytane z pliku dopiero do narysowania.</summary>
    internal class ResultList : RowView
    {
        private Catalog catalog;
        private List<Match> items = new List<Match>();

        public Match SelectedMatch
        {
            get { return items.Count == 0 ? null : items[Selected]; }
        }

        public int Count { get { return items.Count; } }

        public void Show(Catalog source, List<Match> found)
        {
            catalog = source;
            items = found;
            Reset();
        }

        protected override int RowHeight { get { return 40; } }
        protected override int Rows { get { return items.Count; } }

        protected override void DrawRow(Graphics g, int index, int y, int width, bool selected)
        {
            Article article = catalog.Read(items[index].Index);
            Color ink = article.Active ? Theme.Ink : Theme.Muted;
            string price = Theme.Money(article.PriceOn(DateTime.Today));
            bool promo = article.PromotionOn(DateTime.Today);
            Write(g, Cut(g, article.Name, Theme.Bold, width - 8), Theme.Bold, ink, 4, y + 3);
            string details = article.Brand + (article.Active ? "" : "  nieaktywny");
            int priceWidth = TextWidth(g, price, Theme.Bold);
            WriteRight(g, price, Theme.Bold, promo ? Theme.Bad : ink, width - 4, y + 20);
            string stock = "stan " + Theme.Quantity(article.Stock);
            int stockWidth = TextWidth(g, stock, Theme.Text);
            WriteRight(g, stock, Theme.Text, Theme.IsNegativeOrZero(article.Stock) ? Theme.Bad : Theme.Good,
                width - 12 - priceWidth, y + 20);
            Write(g, Cut(g, details, Theme.Text, width - 28 - priceWidth - stockWidth), Theme.Text, Theme.Muted, 4, y + 20);
        }
    }

    /// <summary>Pozycje protokołu, od ostatnio dodanej.</summary>
    internal class ProtocolView : RowView
    {
        private List<ProtocolLine> lines = new List<ProtocolLine>();

        public ProtocolLine SelectedLine
        {
            get { return lines.Count == 0 ? null : lines[Selected]; }
        }

        public void Show(List<ProtocolLine> newLines)
        {
            lines = newLines;
            Reset();
        }

        protected override int RowHeight { get { return 42; } }
        protected override int Rows { get { return lines.Count; } }

        protected override void DrawRow(Graphics g, int index, int y, int width, bool selected)
        {
            ProtocolLine line = lines[index];
            string quantity = Theme.Quantity(ProtocolLine.Number(line.Quantity));
            int quantityWidth = TextWidth(g, quantity, Theme.Number);
            WriteRight(g, quantity, Theme.Number, Theme.Ink, width - 5, y + 8);
            int room = width - quantityWidth - 16;
            Write(g, Cut(g, line.Name, Theme.Bold, room), Theme.Bold, Theme.Ink, 4, y + 3);
            string note = line.Converted
                ? Theme.Quantity(ProtocolLine.Number(line.Entered)) + (line.Numerator == 1 ? " szt." : "") + " × "
                    + Theme.Ratio(line.Numerator, line.Denominator) + "  " + line.Code
                : line.Code;
            Write(g, Cut(g, note, Theme.Text, room), Theme.Text, line.Converted ? Theme.Warning : Theme.Muted, 4, y + 22);
        }
    }

    /// <summary>Artykuł, do którego wpisuje się ilość; samo pole ilości jest kontrolką nad tym widokiem.</summary>
    internal class QuantityView : Surface
    {
        private Article article;
        private int numerator = 1;
        private int denominator = 1;
        private string result = "";
        private string note = "";
        private bool editing;

        public const int InputTop = 104;
        public const int InputLeft = 74;

        public void Show(Article value, string name, string brand, int newNumerator, int newDenominator, bool isEdit)
        {
            article = value;
            Title = name;
            Brand = brand;
            numerator = newNumerator;
            denominator = newDenominator;
            editing = isEdit;
            Invalidate();
        }

        private string Title = "";
        private string Brand = "";

        /// <summary>Wiersz pod polem: przeliczona ilość albo opis błędu.</summary>
        public void ShowResult(string text, string smallNote)
        {
            if (text != result || smallNote != note)
            {
                result = text;
                note = smallNote;
                Invalidate();
            }
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            int y = 4;
            List<string> lines = Wrap(g, Title, Theme.Title, width - 8);
            for (int i = 0; i < lines.Count && i < 2; i++)
            {
                Write(g, i == 1 && lines.Count > 2 ? Cut(g, lines[i] + "…", Theme.Title, width - 8) : lines[i], Theme.Title,
                    Theme.Ink, 4, y);
                y += LineHeight(g, Theme.Title);
            }
            string details = Brand;
            if (article != null)
            {
                details += (details.Length > 0 ? ", " : "") + Theme.Money(article.PriceOn(DateTime.Today)) + ", stan "
                    + Theme.Quantity(article.Stock);
            }
            Write(g, Cut(g, details, Theme.Text, width - 8), Theme.Text, Theme.Muted, 4, y + 1);
            if (numerator != denominator)
            {
                string pack = numerator == 1 ? "Liczysz sztuki, " + denominator + " szt. = 1 wielopak"
                    : "Opakowanie " + Theme.Ratio(numerator, denominator) + ": wpisz sztuki tego kodu";
                Fill(g, Theme.WarningBack, 0, InputTop - 26, width, 21);
                Write(g, Cut(g, pack, Theme.Bold, width - 8), Theme.Bold, Theme.Warning, 4, InputTop - 24);
            }
            Write(g, "Ilość", Theme.Title, Theme.Ink, 6, InputTop + 8);
            Write(g, Cut(g, result, Theme.Title, width - 8), Theme.Title, Theme.Good, 4, InputTop + 44);
            Write(g, Cut(g, note, Theme.Text, width - 8), Theme.Text, Theme.Muted, 4, InputTop + 66);
            Write(g, Cut(g, editing ? "Enter zapisuje, 0 usuwa, Esc anuluje" : "Enter dodaje, Esc anuluje", Theme.Text, width - 8),
                Theme.Text, Theme.Muted, 4, height - 20);
        }
    }

    /// <summary>Karta artykułu.</summary>
    internal class ArticleCard : ScrollView
    {
        private Article article;
        private Catalog catalog;
        private string label = "";

        /// <summary>scaleNote = co mówi zeskanowana etykieta wagi, pusty przy zwykłym kodzie.</summary>
        public void Show(Catalog source, Article value, string scaleNote)
        {
            catalog = source;
            article = value;
            label = scaleNote;
            ScrollTo(0);
            Invalidate();
        }

        protected override int Measure(Graphics g, int width)
        {
            return article == null ? 0 : Compose(g, width, 0);
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            if (article != null)
            {
                Compose(g, width, -Offset);
            }
        }

        /// <summary>Rysuje kartę od wysokości y i zwraca wysokość treści.</summary>
        private int Compose(Graphics g, int width, int from)
        {
            DateTime today = DateTime.Today;
            int y = from + 4;
            int titleHeight = LineHeight(g, Theme.Title);
            foreach (string line in Wrap(g, article.Name, Theme.Title, width - 8))
            {
                Write(g, line, Theme.Title, Theme.Ink, 4, y);
                y += titleHeight;
            }
            int textHeight = LineHeight(g, Theme.Text) + 3;
            if (article.Brand.Length > 0)
            {
                Write(g, article.Brand, Theme.Text, Theme.Muted, 4, y);
                y += textHeight;
            }
            if (!article.Active)
            {
                y = Banner(g, "Artykuł nieaktywny", Theme.Warning, Theme.WarningBack, y, width);
            }
            y += 3;

            bool promo = article.PromotionOn(today);
            int priceHeight = LineHeight(g, Theme.Price);
            Fill(g, promo ? Theme.BadBack : Theme.Panel, 0, y, width, priceHeight + (promo ? textHeight : 0) + 6);
            Write(g, Theme.Money(article.PriceOn(today)), Theme.Price, promo ? Theme.Bad : Theme.Ink, 4, y + 2);
            if (promo)
            {
                WriteRight(g, "PROMOCJA", Theme.Bold, Theme.Bad, width - 4, y + 8);
                string regular = article.PromoRegular.Length > 0
                    ? "zwykła " + Theme.Money(article.PromoRegular) + ", " : "";
                Write(g, Cut(g, regular + "do " + Theme.Day(article.PromoTo), Theme.Text, width - 8), Theme.Text,
                    Theme.Bad, 4, y + priceHeight + 2);
                y += textHeight;
            }
            y += priceHeight + 6;
            if (label.Length > 0)
            {
                y = Banner(g, label, Theme.Ink, Theme.Selected, y, width);
            }
            if (!promo && article.HasPromotion && article.PromoFrom > today)
            {
                y = Banner(g, "Promocja " + Theme.Money(article.PromoPrice) + " od " + Theme.Day(article.PromoFrom)
                    + " do " + Theme.Day(article.PromoTo), Theme.Warning, Theme.WarningBack, y, width);
            }

            int half = width / 2;
            int tile = LineHeight(g, Theme.Small) + LineHeight(g, Theme.Number) + 6;
            bool empty = Theme.IsNegativeOrZero(article.Stock);
            Fill(g, empty ? Theme.BadBack : Theme.GoodBack, 0, y + 2, half - 1, tile);
            Write(g, "Stan", Theme.Small, empty ? Theme.Bad : Theme.Good, 4, y + 4);
            Write(g, Theme.Quantity(article.Stock), Theme.Number, empty ? Theme.Bad : Theme.Good, 4,
                y + 4 + LineHeight(g, Theme.Small));
            Fill(g, Theme.Panel, half + 1, y + 2, width - half - 1, tile);
            Write(g, "Rotacja", Theme.Small, Theme.Muted, half + 5, y + 4);
            Write(g, Theme.Quantity(article.Rotation), Theme.Number, Theme.Ink, half + 5, y + 4 + LineHeight(g, Theme.Small));
            y += tile + 6;

            y = Row(g, "Karton", Theme.Quantity(article.CaseSize), y, width, textHeight);
            if (article.NetContent.Length > 0)
            {
                y = Row(g, "Zawartość", Theme.Quantity(article.NetContent) + " " + article.Unit, y, width, textHeight);
            }
            y = Row(g, "Cadencier", article.Cadencier ? "tak" : "nie", y, width, textHeight);
            if (article.CadencierPrice.Length > 0 && Theme.Money(article.CadencierPrice) != Theme.Money("0"))
            {
                y = Row(g, "Cena cadencier", Theme.Money(article.CadencierPrice), y, width, textHeight);
            }
            y = Row(g, "Ostatnia dostawa", Theme.Day(article.LastDelivery), y, width, textHeight);
            if (article.Supplier.Length > 0)
            {
                y = Block(g, "Dostawca", article.Supplier, y, width, textHeight);
            }
            y = Row(g, "ITM8", article.Itm8.TrimStart('0'), y, width, textHeight);
            y = Row(g, "EAN", article.Ean, y, width, textHeight);
            if (article.OtherEans.Length > 0)
            {
                y = Block(g, "Pozostałe EAN-y", article.OtherEans, y, width, textHeight);
            }
            if (article.Subfamily.Length == 6)
            {
                y = Block(g, "Nomenklatura", Level(2) + " › " + Level(4) + " › " + Level(6), y, width, textHeight);
            }
            return y - from + 4;
        }

        private string Level(int digits)
        {
            string code = article.Subfamily.Substring(0, digits);
            return code.Substring(digits - 2) + " " + catalog.NomenclatureName(code);
        }

        private static int Banner(Graphics g, string text, Color ink, Color back, int y, int width)
        {
            List<string> lines = Wrap(g, text, Theme.Bold, width - 8);
            int line = LineHeight(g, Theme.Bold);
            Fill(g, back, 0, y + 2, width, lines.Count * line + 4);
            foreach (string part in lines)
            {
                Write(g, part, Theme.Bold, ink, 4, y + 4);
                y += line;
            }
            return y + 8;
        }

        private static int Row(Graphics g, string label, string value, int y, int width, int height)
        {
            Write(g, label, Theme.Text, Theme.Muted, 4, y + 2);
            WriteRight(g, value, Theme.Bold, Theme.Ink, width - 4, y + 2);
            Rule(g, y + height + 2, width);
            return y + height + 4;
        }

        private static int Block(Graphics g, string label, string value, int y, int width, int height)
        {
            Write(g, label, Theme.Text, Theme.Muted, 4, y + 2);
            y += height;
            foreach (string line in Wrap(g, value, Theme.Bold, width - 8))
            {
                Write(g, line, Theme.Bold, Theme.Ink, 4, y + 2);
                y += height;
            }
            Rule(g, y + 2, width);
            return y + 4;
        }
    }

    /// <summary>Duży komunikat (brak danych, nic nie znaleziono, stan sieci).</summary>
    internal class MessageView : ScrollView
    {
        private string title = "";
        private string text = "";
        private bool error;

        public void Show(string newTitle, string newText, bool isError)
        {
            title = newTitle;
            text = newText;
            error = isError;
            ScrollTo(0);
            Invalidate();
        }

        protected override int Measure(Graphics g, int width)
        {
            return Compose(g, width, 0);
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            if (error)
            {
                Fill(g, Theme.BadBack, 0, 0, width, height);
            }
            Compose(g, width, -Offset);
        }

        private int Compose(Graphics g, int width, int from)
        {
            int y = from + 12;
            foreach (string line in Wrap(g, title, Theme.Title, width - 16))
            {
                Write(g, line, Theme.Title, error ? Theme.Bad : Theme.Ink, 8, y);
                y += LineHeight(g, Theme.Title);
            }
            y += 6;
            foreach (string line in Wrap(g, text, Theme.Text, width - 16))
            {
                Write(g, line, Theme.Text, error ? Theme.Bad : Theme.Muted, 8, y);
                y += LineHeight(g, Theme.Text) + 2;
            }
            return y - from + 4;
        }
    }

    /// <summary>Pasek: tekst po lewej i po prawej, opcjonalnie postęp i mała ikona z lewej.</summary>
    internal class Bar : Surface
    {
        public enum Icon { None, Trash, Undo, Back }

        public const int IconWidth = 34;
        private const int ToggleWidth = 58;
        private string toggle = "";
        private bool toggled;
        private string left = "";
        private string right = "";
        private Color back = Theme.Ink;
        private Color ink = Color.White;
        private int percent = -1;
        private Icon icon = Icon.None;

        public void Show(Icon newIcon, string leftText, string rightText, Color background, Color foreground, int progress)
        {
            if (left == leftText && right == rightText && back == background && percent == progress && icon == newIcon)
            {
                return;
            }
            icon = newIcon;
            left = leftText;
            right = rightText;
            back = background;
            ink = foreground;
            percent = progress;
            Invalidate();
        }

        /// <summary>Przełącznik w lewym rogu paska (pusty tekst = bez niego); włączony jest niebieski.</summary>
        public void ShowToggle(string text, bool on)
        {
            if (toggle != text || toggled != on)
            {
                toggle = text;
                toggled = on;
                Invalidate();
            }
        }

        private int IconLeft
        {
            get { return toggle.Length > 0 ? ToggleWidth : 0; }
        }

        public bool OnToggle(int x)
        {
            return x < IconLeft;
        }

        public bool OnIcon(int x)
        {
            return icon != Icon.None && x >= IconLeft && x < IconLeft + IconWidth;
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            Fill(g, back, 0, 0, width, height);
            if (percent >= 0)
            {
                Fill(g, Theme.GoodBack, 0, 0, width * percent / 100, height);
            }
            int y = (height - LineHeight(g, Theme.Bold)) / 2;
            int start = IconLeft + 5;
            if (toggle.Length > 0)
            {
                Fill(g, toggled ? Theme.Selected : Theme.Ink, 0, 0, ToggleWidth - 1, height);
                Fill(g, Theme.Paper, ToggleWidth - 1, 0, 1, height);
                int textWidth = TextWidth(g, toggle, Theme.Bold);
                Write(g, toggle, Theme.Bold, toggled ? Theme.Ink : Color.White, (ToggleWidth - textWidth) / 2, y);
            }
            if (icon != Icon.None)
            {
                DrawIcon(g, height);
                start = IconLeft + IconWidth + 3;
            }
            int rightWidth = TextWidth(g, right, Theme.Bold);
            Write(g, Cut(g, left, Theme.Bold, width - rightWidth - start - 11), Theme.Bold, ink, start, y);
            Write(g, right, Theme.Bold, ink, width - rightWidth - 5, y);
        }

        private void DrawIcon(Graphics g, int height)
        {
            Color color = Color.White;
            if (icon != Icon.Back)
            {
                Fill(g, icon == Icon.Trash ? Theme.Bad : Theme.Ink, IconLeft, 0, IconWidth, height);
            }
            int x = IconLeft + IconWidth / 2;
            int y = height / 2;
            using (Pen pen = new Pen(color))
            {
                if (icon == Icon.Trash)
                {
                    g.DrawLine(pen, x - 7, y - 5, x + 7, y - 5);
                    g.DrawLine(pen, x - 3, y - 7, x + 3, y - 7);
                    g.DrawLine(pen, x - 5, y - 4, x - 4, y + 7);
                    g.DrawLine(pen, x + 5, y - 4, x + 4, y + 7);
                    g.DrawLine(pen, x - 4, y + 7, x + 4, y + 7);
                    g.DrawLine(pen, x - 1, y - 2, x - 1, y + 4);
                    g.DrawLine(pen, x + 2, y - 2, x + 2, y + 4);
                }
                else if (icon == Icon.Undo)
                {
                    g.DrawLine(pen, x - 7, y - 2, x - 3, y - 6);
                    g.DrawLine(pen, x - 7, y - 2, x - 3, y + 2);
                    g.DrawLine(pen, x - 7, y - 2, x + 4, y - 2);
                    g.DrawLine(pen, x + 4, y - 2, x + 7, y + 1);
                    g.DrawLine(pen, x + 7, y + 1, x + 7, y + 3);
                    g.DrawLine(pen, x + 7, y + 3, x + 4, y + 6);
                    g.DrawLine(pen, x + 4, y + 6, x - 4, y + 6);
                }
                else
                {
                    for (int i = 0; i < 2; i++)
                    {
                        g.DrawLine(pen, x + 3 + i, y - 7, x - 4 + i, y);
                        g.DrawLine(pen, x - 4 + i, y, x + 3 + i, y + 7);
                    }
                }
            }
        }
    }
}
