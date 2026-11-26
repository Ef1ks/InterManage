using System;
using System.Globalization;
using System.Text;

namespace Intermanage.Collector
{
    /// <summary>Dane cenówki w chwili wydruku.</summary>
    internal sealed class PriceLabel
    {
        public string Name = "";
        public string Brand = "";
        public decimal Price;
        /// <summary>Jednostka sprzedaży w prawym rogu: „Szt” albo „kg” (towar na wagę).</summary>
        public string SaleUnit = "Szt";
        /// <summary>„CENA ZA kg 151.96zł”; pusty = bez ceny jednostkowej.</summary>
        public string UnitPrice = "";
        public string Ean = "";
        public DateTime PrintedOn = DateTime.Today;

        /// <summary>Cenówka artykułu z kartoteki na dziś (w promocji cena promocyjna); null, gdy nie ma ceny.</summary>
        public static PriceLabel Of(Article article)
        {
            string price = article.PriceOn(DateTime.Today);
            if (Theme.IsNegativeOrZero(price))
            {
                return null;
            }
            PriceLabel label = new PriceLabel();
            label.Name = article.Name;
            label.Brand = article.Brand;
            label.Price = Parse(price);
            label.Ean = article.Ean;
            Measure(label, article.Unit, article.NetContent);
            return label;
        }

        /// <summary>Cenówka próbna: ta sama co na wzorze ze sklepu, żeby dało się je porównać.</summary>
        public static PriceLabel Sample()
        {
            PriceLabel label = new PriceLabel();
            label.Name = "KAWA BRAZIL SANTOS 250G M.";
            label.Brand = "VASPIATTA";
            label.Price = 37.99m;
            label.UnitPrice = "CENA ZA kg 151.96zł";
            label.Ean = "5906660548089";
            return label;
        }

        /// <summary>
        /// Cena jednostkowa jak na cenówkach sklepu: za kg albo l z zawartości, towar na wagę (A.Wa) za kg, reszta za szt.
        /// </summary>
        private static void Measure(PriceLabel label, string unit, string netContent)
        {
            string kind = unit.Trim().ToLower();
            if (kind == "a.wa")
            {
                label.SaleUnit = "kg";
                label.UnitPrice = "CENA ZA kg " + Money(label.Price) + "zł";
                return;
            }
            decimal content = netContent.Length == 0 ? 0 : Parse(netContent);
            string per = kind == "kg" || kind == "g" ? "kg" : kind == "l" || kind == "ml" ? "l" : "";
            if (kind == "g" || kind == "ml")
            {
                content = content / 1000m;
            }
            if (per.Length == 0 || content <= 0)
            {
                label.UnitPrice = "CENA ZA szt. " + Money(label.Price) + "zł";
                return;
            }
            label.UnitPrice = "CENA ZA " + per + " " + Money(label.Price / content) + "zł";
        }

        private static decimal Parse(string value)
        {
            return decimal.Parse(value.Replace(',', '.'), CultureInfo.InvariantCulture);
        }

        /// <summary>Kwota z kropką, jak na cenówkach sklepu: 37.99.</summary>
        public static string Money(decimal value)
        {
            return decimal.Round(value, 2).ToString("0.00", CultureInfo.InvariantCulture);
        }
    }

    /// <summary>
    /// Cenówka w ZPL na etykietę 52 × 38 mm (realnie ok. 51,5 × 37,5 mm, z marginesem 2 mm), w układzie cenówek sklepu:
    /// nazwa, marka, wielka cena (złote, mniejsze grosze z kropką i „zł”), pod nią EAN, kod kreskowy i data, po prawej
    /// jednostka i cena za kg / l / szt. Układ w punktach 203 dpi (8 na mm) przeliczany do rozdzielczości drukarki.
    /// Polskie litery przez ^CI28 (UTF-8) i czcionkę 0. Obrót o 180° (^POI): bez niego cenówka wychodziła do góry
    /// nogami.
    /// </summary>
    internal static class LabelZpl
    {
        public const decimal WidthMm = 51.5m;
        public const decimal HeightMm = 37.5m;
        public const decimal MarginMm = 2m;
        private const int BaseDpi = 203;
        /// <summary>Pole na treść przy 203 dpi: (51,5 − 2 × 2) × 8 i (37,5 − 2 × 2) × 8.</summary>
        private const int Width = 380;

        /// <summary>Linia dołu ceny (złote i grosze stoją na niej razem).</summary>
        private const int PriceBase = 190;
        private const int WholeHeight = 160;
        private const int WholeWidth = 96;
        private const int CentsHeight = 96;
        private const int CentsWidth = 60;

        /// <summary>
        /// Szerokość cyfry czcionki 0 jako część szerokości z ^A0 (szacunek: na niej stoją grosze za złotymi; do
        /// poprawienia po wydruku, gdy grosze nachodzą na złote albo stoją za daleko).
        /// </summary>
        private const decimal DigitAdvance = 0.6m;

        /// <summary>
        /// Cenówka w ZPL. Przesunięcia wydruku (^LS, ^LT) nie ma w cenówce: trzyma je drukarka (Menu, Drukarka), bo
        /// zależą od drukarki i rolki, a nie od kolektora.
        /// </summary>
        public static string Build(PriceLabel label, int dpi)
        {
            Zpl z = new Zpl(dpi <= 0 ? BaseDpi : dpi, MarginMm, MarginMm);
            StringBuilder s = z.Text;
            s.Append("^XA^CI28^POI^MMT^LH0,0");
            s.Append("^PW").Append(z.Mm(WidthMm)).Append("^LL").Append(z.Mm(HeightMm));

            z.Block(0, 0, Width, 1, 22, 20, 'L', label.Name);
            z.Block(0, 36, Width, 1, 22, 18, 'L', label.Brand);

            // Cena: wszystkie części na wspólnej linii dołu (^FT), grosze zaraz za złotymi.
            string money = PriceLabel.Money(label.Price);
            int dot = money.IndexOf('.');
            string whole = money.Substring(0, dot);
            string cents = money.Substring(dot);
            int x = 0;
            z.Baseline(x, PriceBase, WholeHeight, WholeWidth, whole);
            x += (int)(whole.Length * WholeWidth * DigitAdvance) + 2;
            z.Baseline(x, PriceBase, CentsHeight, CentsWidth, cents);
            x += (int)((cents.Length - 0.5m) * CentsWidth * DigitAdvance) + 2;
            // „zł” małe, równo z górą groszy.
            z.Baseline(x, PriceBase - CentsHeight * 7 / 10 + 26, 36, 30, "zł");

            z.Block(8, 198, 200, 1, 20, 16, 'L', label.Ean);
            z.Block(200, 196, Width - 200, 1, 16, 14, 'R', label.SaleUnit);
            z.Block(140, 214, Width - 140, 1, 18, 16, 'R', label.UnitPrice);

            z.Barcode(8, 222, 30, label.Ean);
            z.Block(8, 256, 200, 1, 18, 16, 'L', label.PrintedOn.ToString("dd.MM.yyyy", CultureInfo.InvariantCulture));
            s.Append("^PQ1^XZ");
            return s.ToString();
        }

        private sealed class Zpl
        {
            public readonly StringBuilder Text = new StringBuilder();
            private readonly int dpi;
            private readonly int left;
            private readonly int top;

            public Zpl(int dpi, decimal leftMm, decimal topMm)
            {
                this.dpi = dpi;
                left = Mm(leftMm);
                top = Mm(topMm);
            }

            public int Mm(decimal mm)
            {
                return (int)decimal.Round(mm * dpi / 25.4m, 0);
            }

            private int D(int dots)
            {
                return (dots * dpi + BaseDpi / 2) / BaseDpi;
            }

            /// <summary>^FO z punktu układu (203 dpi) przesuniętego o początek treści.</summary>
            private StringBuilder At(int x, int y)
            {
                return Text.Append("^FO").Append(left + D(x)).Append(',').Append(top + D(y));
            }

            public void Block(int x, int y, int width, int lines, int height, int fontWidth, char align, string text)
            {
                if (text == null || text.Trim().Length == 0)
                {
                    return;
                }
                At(x, y)
                    .Append("^A0N,").Append(D(height)).Append(',').Append(D(fontWidth))
                    .Append("^FB").Append(D(width)).Append(',').Append(lines).Append(",0,").Append(align).Append(",0")
                    .Append("^FD").Append(Clean(text)).Append("^FS");
            }

            /// <summary>Napis stojący na linii dołu (^FT): y to dół liter, nie góra pola.</summary>
            public void Baseline(int x, int y, int height, int fontWidth, string text)
            {
                Text.Append("^FT").Append(left + D(x)).Append(',').Append(top + D(y))
                    .Append("^A0N,").Append(D(height)).Append(',').Append(D(fontWidth))
                    .Append("^FD").Append(Clean(text)).Append("^FS");
            }

            /// <summary>EAN-13 albo EAN-8 bez cyfr pod kodem (cyfry stoją nad nim); inny kod Code 128.</summary>
            public void Barcode(int x, int y, int height, string code)
            {
                if (code == null || code.Length == 0)
                {
                    return;
                }
                At(x, y).Append("^BY").Append(Math.Max(1, D(2)));
                if (code.Length == 13 && Catalog.IsDigits(code))
                {
                    Text.Append("^BEN,").Append(D(height)).Append(",N,N^FD").Append(code.Substring(0, 12));
                }
                else if (code.Length == 8 && Catalog.IsDigits(code))
                {
                    Text.Append("^B8N,").Append(D(height)).Append(",N,N^FD").Append(code.Substring(0, 7));
                }
                else
                {
                    Text.Append("^BCN,").Append(D(height)).Append(",N,N,N^FD").Append(Clean(code));
                }
                Text.Append("^FS");
            }

            /// <summary>^ i ~ są w ZPL poleceniami, więc nie mogą trafić do tekstu.</summary>
            private static string Clean(string text)
            {
                return text.Replace('^', ' ').Replace('~', ' ').Replace('\r', ' ').Replace('\n', ' ');
            }
        }
    }
}
