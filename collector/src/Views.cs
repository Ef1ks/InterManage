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

    /// <summary>Lista znalezionych artykułów; wiersze czytane z pliku dopiero do narysowania.</summary>
    internal class ResultList : Surface
    {
        private const int RowHeight = 40;

        /// <summary>Wybrany artykuł (dotknięcie).</summary>
        public event EventHandler Chosen;

        private Catalog catalog;
        private List<int> items = new List<int>();
        private int selected;
        private int top;

        public int Count
        {
            get { return items.Count; }
        }

        public int SelectedArticle
        {
            get { return items.Count == 0 ? -1 : items[selected]; }
        }

        public void Show(Catalog source, List<int> found)
        {
            catalog = source;
            items = found;
            selected = 0;
            top = 0;
            Invalidate();
        }

        public void Move(int by)
        {
            if (items.Count == 0)
            {
                return;
            }
            selected = Math.Max(0, Math.Min(items.Count - 1, selected + by));
            int page = Page;
            if (selected < top)
            {
                top = selected;
            }
            else if (selected >= top + page)
            {
                top = selected - page + 1;
            }
            Invalidate();
        }

        public int Page
        {
            get { return Math.Max(1, ClientSize.Height / RowHeight); }
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            for (int i = top; i < items.Count && (i - top) * RowHeight < height; i++)
            {
                int y = (i - top) * RowHeight;
                Article article = catalog.Read(items[i]);
                if (i == selected)
                {
                    Fill(g, Theme.Selected, 0, y, width, RowHeight);
                }
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
                Write(g, Cut(g, details, Theme.Text, width - 28 - priceWidth - stockWidth), Theme.Text, Theme.Muted, 4,
                    y + 20);
                Rule(g, y + RowHeight - 1, width);
            }
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            int index = top + e.Y / RowHeight;
            if (index < items.Count)
            {
                selected = index;
                Invalidate();
                if (Chosen != null)
                {
                    Chosen(this, EventArgs.Empty);
                }
            }
        }
    }

    /// <summary>Karta artykułu, przewijana strzałkami albo palcem.</summary>
    internal class ArticleCard : Surface
    {
        private Article article;
        private Catalog catalog;
        private int offset;
        private int contentHeight;
        private int dragFrom = -1;
        private int dragOffset;

        public void Show(Catalog source, Article value)
        {
            catalog = source;
            article = value;
            offset = 0;
            Invalidate();
        }

        public void Scroll(int by)
        {
            int limit = Math.Max(0, contentHeight - ClientSize.Height);
            offset = Math.Max(0, Math.Min(limit, offset + by));
            Invalidate();
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            if (article == null)
            {
                return;
            }
            DateTime today = DateTime.Today;
            int y = 4 - offset;
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
            contentHeight = y + offset + 6;
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            dragFrom = e.Y;
            dragOffset = offset;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            if (dragFrom >= 0)
            {
                Scroll(dragOffset + dragFrom - e.Y - offset);
            }
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            dragFrom = -1;
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

    /// <summary>Duży komunikat na środku (brak danych, nic nie znaleziono).</summary>
    internal class MessageView : Surface
    {
        private string title = "";
        private string text = "";
        private bool error;

        public void Show(string newTitle, string newText, bool isError)
        {
            title = newTitle;
            text = newText;
            error = isError;
            Invalidate();
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            if (error)
            {
                Fill(g, Theme.BadBack, 0, 0, width, height);
            }
            int y = 14;
            foreach (string line in Wrap(g, title, Theme.Title, width - 16))
            {
                Write(g, line, Theme.Title, error ? Theme.Bad : Theme.Ink, 8, y);
                y += LineHeight(g, Theme.Title);
            }
            y += 8;
            foreach (string line in Wrap(g, text, Theme.Text, width - 16))
            {
                Write(g, line, Theme.Text, error ? Theme.Bad : Theme.Muted, 8, y);
                y += LineHeight(g, Theme.Text) + 2;
            }
        }
    }

    /// <summary>Pasek: tekst po lewej i po prawej, opcjonalnie postęp.</summary>
    internal class Bar : Surface
    {
        private string left = "";
        private string right = "";
        private Color back = Theme.Ink;
        private Color ink = Color.White;
        private int percent = -1;

        public void Show(string leftText, string rightText, Color background, Color foreground, int progress)
        {
            if (left == leftText && right == rightText && back == background && percent == progress)
            {
                return;
            }
            left = leftText;
            right = rightText;
            back = background;
            ink = foreground;
            percent = progress;
            Invalidate();
        }

        protected override void Draw(Graphics g, int width, int height)
        {
            Fill(g, back, 0, 0, width, height);
            if (percent >= 0)
            {
                Fill(g, Theme.GoodBack, 0, 0, width * percent / 100, height);
            }
            int y = (height - LineHeight(g, Theme.Bold)) / 2;
            int rightWidth = TextWidth(g, right, Theme.Bold);
            Write(g, Cut(g, left, Theme.Bold, width - rightWidth - 16), Theme.Bold, ink, 5, y);
            Write(g, right, Theme.Bold, ink, width - rightWidth - 5, y);
        }
    }
}
