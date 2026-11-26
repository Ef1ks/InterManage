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
        /// <summary>Zawartość do napisu, np. „500 g”; pusta = bez.</summary>
        public string Content = "";
        public decimal Price;
        /// <summary>„1 kg = 25,98 zł”; pusty = bez ceny jednostkowej.</summary>
        public string UnitPrice = "";
        public bool Promotion;
        public DateTime PromoTo = DateTime.MinValue;
        /// <summary>Cena zwykła przy promocji; 0 = bez.</summary>
        public decimal Regular;
        public string Ean = "";
        public string Itm8 = "";
        public DateTime PrintedOn = DateTime.Today;

        /// <summary>Cenówka artykułu z kartoteki na dziś; null, gdy artykuł nie ma ceny.</summary>
        public static PriceLabel Of(Article article)
        {
            DateTime today = DateTime.Today;
            string price = article.PriceOn(today);
            if (Theme.IsNegativeOrZero(price))
            {
                return null;
            }
            PriceLabel label = new PriceLabel();
            label.Name = article.Name;
            label.Brand = article.Brand;
            label.Price = Parse(price);
            label.Promotion = article.PromotionOn(today);
            if (label.Promotion)
            {
                label.PromoTo = article.PromoTo;
                string regular = article.RegularOn(today);
                label.Regular = Theme.IsNegativeOrZero(regular) ? 0 : Parse(regular);
                if (label.Regular <= label.Price)
                {
                    label.Regular = 0;
                }
            }
            label.Ean = article.Ean;
            label.Itm8 = article.Itm8.TrimStart('0');
            Measure(label, article.Unit, article.NetContent);
            return label;
        }

        /// <summary>Cenówka próbna do ustawienia drukarki: długa nazwa z polskimi literami i promocja.</summary>
        public static PriceLabel Sample()
        {
            PriceLabel label = new PriceLabel();
            label.Name = "CZEKOLADA MLECZNA Z ORZECHAMI LASKOWYMI ŻÓŁĆ";
            label.Brand = "ZAŻÓŁĆ GĘŚLĄ JAŹŃ";
            label.Content = "100 g";
            label.Price = 12.99m;
            label.UnitPrice = "1 kg = 129,90 zł";
            label.Promotion = true;
            label.PromoTo = DateTime.Today.AddDays(7);
            label.Regular = 15.99m;
            label.Ean = "5901234123457";
            label.Itm8 = "1234567";
            return label;
        }

        /// <summary>
        /// Zawartość i cena za kilogram albo litr. Towar na wagę (A.Wa) ma cenę za kilogram. Sztuki bez ceny jednostkowej.
        /// </summary>
        private static void Measure(PriceLabel label, string unit, string netContent)
        {
            string kind = unit.Trim().ToLower();
            if (kind == "a.wa")
            {
                label.UnitPrice = "cena za 1 kg";
                return;
            }
            decimal content = netContent.Length == 0 ? 0 : Parse(netContent);
            if (content <= 0)
            {
                return;
            }
            string per;
            if (kind == "kg" || kind == "g")
            {
                per = "kg";
            }
            else if (kind == "l" || kind == "ml")
            {
                per = "l";
            }
            else
            {
                return;
            }
            if (kind == "g" || kind == "ml")
            {
                content = content / 1000m;
            }
            label.Content = content < 1 ? Number(content * 1000m) + (per == "kg" ? " g" : " ml")
                : Number(content) + " " + per;
            label.UnitPrice = "1 " + per + " = " + Money(label.Price / content) + " zł";
        }

        private static decimal Parse(string value)
        {
            return decimal.Parse(value.Replace(',', '.'), CultureInfo.InvariantCulture);
        }

        private static string Number(decimal value)
        {
            return decimal.Round(value, 3).ToString("0.###", CultureInfo.InvariantCulture).Replace('.', ',');
        }

        public static string Money(decimal value)
        {
            return decimal.Round(value, 2).ToString("0.00", CultureInfo.InvariantCulture).Replace('.', ',');
        }
    }

    /// <summary>
    /// Cenówka w ZPL na etykietę 52 × 38 mm (realnie ok. 51,5 × 37,5 mm, więc z marginesem 2 mm z każdej strony).
    /// Układ rysowany w punktach drukarki 203 dpi (8 punktów na mm) i przeliczany do rozdzielczości drukarki.
    /// Polskie litery przez ^CI28 (UTF-8) i czcionkę 0. Obrót o 180° (^POI): bez niego cenówka wychodziła do góry nogami.
    /// </summary>
    internal static class LabelZpl
    {
        public const decimal WidthMm = 51.5m;
        public const decimal HeightMm = 37.5m;
        public const decimal MarginMm = 2m;
        private const int BaseDpi = 203;
        /// <summary>Pole na treść przy 203 dpi: (52 − 2 × 2) × 8 i (38 − 2 × 2) × 8.</summary>
        private const int Width = 384;
        private const int Height = 272;

        public static string Build(PriceLabel label, int dpi)
        {
            Zpl z = new Zpl(dpi <= 0 ? BaseDpi : dpi);
            StringBuilder s = z.Text;
            s.Append("^XA^CI28^POI^MMT^LS0^LT0");
            s.Append("^PW").Append(z.Mm(WidthMm)).Append("^LL").Append(z.Mm(HeightMm));
            s.Append("^LH").Append(z.Mm(MarginMm)).Append(',').Append(z.Mm(MarginMm));

            // Nazwa: dwie linie, za długa jest ucinana przez drukarkę.
            z.Block(0, 0, Width, 2, 28, 24, 'L', label.Name);
            string second = (label.Brand + "  " + label.Content).Trim();
            z.Block(0, 62, Width, 1, 20, 18, 'L', second);

            if (label.Promotion)
            {
                string until = label.PromoTo == DateTime.MinValue ? ""
                    : " do " + label.PromoTo.ToString("dd.MM", CultureInfo.InvariantCulture);
                z.Box(0, 88, 196, 26);
                z.Inverse(6, 92, 186, 20, 18, "PROMOCJA" + until);
                if (label.Regular > 0)
                {
                    z.Block(204, 92, Width - 204, 1, 20, 18, 'R', "zwykła " + PriceLabel.Money(label.Regular) + " zł");
                }
            }

            // Cena: złote duże, grosze mniejsze u góry, „zł” pod groszami.
            string money = PriceLabel.Money(label.Price);
            int comma = money.IndexOf(',');
            string whole = money.Substring(0, comma);
            string cents = money.Substring(comma + 1);
            z.Block(0, 118, 300, 1, 96, 80, 'R', whole + ",");
            z.Block(302, 124, 82, 1, 46, 40, 'L', cents);
            z.Block(302, 172, 82, 1, 30, 28, 'L', "zł");

            z.Block(0, 214, 230, 1, 20, 18, 'L', label.UnitPrice);
            z.Block(230, 214, Width - 230, 1, 18, 16, 'R', label.PrintedOn.ToString("dd.MM.yyyy",
                CultureInfo.InvariantCulture));

            z.Barcode(12, 238, label.Ean);
            z.Block(230, 246, Width - 230, 1, 20, 18, 'R', label.Itm8.Length > 0 ? "ITM " + label.Itm8 : "");
            s.Append("^PQ1^XZ");
            return s.ToString();
        }

        private sealed class Zpl
        {
            public readonly StringBuilder Text = new StringBuilder();
            private readonly int dpi;

            public Zpl(int dpi)
            {
                this.dpi = dpi;
            }

            public int Mm(decimal mm)
            {
                return (int)decimal.Round(mm * dpi / 25.4m, 0);
            }

            private int D(int dots)
            {
                return (dots * dpi + BaseDpi / 2) / BaseDpi;
            }

            public void Block(int x, int y, int width, int lines, int height, int fontWidth, char align, string text)
            {
                if (text == null || text.Trim().Length == 0)
                {
                    return;
                }
                Text.Append("^FO").Append(D(x)).Append(',').Append(D(y))
                    .Append("^A0N,").Append(D(height)).Append(',').Append(D(fontWidth))
                    .Append("^FB").Append(D(width)).Append(',').Append(lines).Append(",0,").Append(align).Append(",0")
                    .Append("^FD").Append(Clean(text)).Append("^FS");
            }

            public void Box(int x, int y, int width, int height)
            {
                Text.Append("^FO").Append(D(x)).Append(',').Append(D(y))
                    .Append("^GB").Append(D(width)).Append(',').Append(D(height)).Append(',').Append(D(height))
                    .Append("^FS");
            }

            /// <summary>Biały napis na czarnym polu (^FR odwraca to, co pod nim).</summary>
            public void Inverse(int x, int y, int width, int height, int fontWidth, string text)
            {
                Text.Append("^FO").Append(D(x)).Append(',').Append(D(y))
                    .Append("^A0N,").Append(D(height)).Append(',').Append(D(fontWidth))
                    .Append("^FB").Append(D(width)).Append(",1,0,C,0^FR^FD").Append(Clean(text)).Append("^FS");
            }

            /// <summary>EAN-13 albo EAN-8 z cyframi pod kodem; inny kod Code 128.</summary>
            public void Barcode(int x, int y, string code)
            {
                if (code == null || code.Length == 0)
                {
                    return;
                }
                Text.Append("^FO").Append(D(x)).Append(',').Append(D(y)).Append("^BY").Append(Math.Max(1, D(2)));
                if (code.Length == 13 && Catalog.IsDigits(code))
                {
                    Text.Append("^BEN,").Append(D(20)).Append(",Y,N^FD").Append(code.Substring(0, 12));
                }
                else if (code.Length == 8 && Catalog.IsDigits(code))
                {
                    Text.Append("^B8N,").Append(D(20)).Append(",Y,N^FD").Append(code.Substring(0, 7));
                }
                else
                {
                    Text.Append("^BCN,").Append(D(20)).Append(",Y,N,N^FD").Append(Clean(code));
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
