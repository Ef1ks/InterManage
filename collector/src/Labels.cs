using System;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Text;

namespace Intermanage.Collector
{
    /// <summary>
    /// Czcionka ceny na cenówce (od 1.8.3): DejaVu Sans Bold okrojona do cyfr, kropki, przecinka, „z” i „ł”
    /// (collector/res/IMCENA.TTF, licencja DejaVu-LICENSE.txt obok; nowy znak = nowe okrojenie fontTools z
    /// backend/src/main/resources/fonts/DejaVuSans-Bold.ttf). Prawdziwie gruba czcionka zamiast podwójnego druku czcionki 0,
    /// a szerokości znaków są znane z pliku, więc grosze stoją dokładnie za złotymi. Plik idzie do pamięci drukarki (E:)
    /// tylko, gdy jej tam nie ma (^HW, raz na uruchomienie programu, Printer.FontSent), a bez niej w drukarce cena idzie
    /// czcionką 0 (Printer.FontReady), żeby cenówka nigdy nie wyszła bez ceny.
    /// </summary>
    internal static class LabelFont
    {
        /// <summary>
        /// Plik w drukarce. W ~DY nazwa idzie bez rozszerzenia (rozszerzenie daje pole T = TrueType); w 1.8.3 nazwa
        /// z „.TTF” w ~DY dawała plik pod inną nazwą i cenówki bez ceny.
        /// </summary>
        public const string File = "E:IMCENA.TTF";
        private const string DownloadName = "E:IMCENA";
        /// <summary>Lista plików czcionki w drukarce; odpowiedź kończy się wierszem „… bytes free”.</summary>
        public const string Directory = "^XA^HWE:IMCENA.TTF^XZ";

        /// <summary>Czy odpowiedź ^HW ma plik czcionki.</summary>
        public static bool Listed(string directory)
        {
            return directory.ToUpper().IndexOf("IMCENA.TTF") >= 0;
        }

        /// <summary>Szerokości znaków jako część wysokości czcionki (hmtx / unitsPerEm 2048).</summary>
        public const decimal Digit = 0.6958m;
        public const decimal Dot = 0.3799m;
        /// <summary>Wysokość cyfr jako część wysokości czcionki (1493 / 2048).</summary>
        public const decimal DigitHeight = 0.729m;

        private static string download;

        /// <summary>~DY: plik czcionki szesnastkowo (bez znaków, które drukarka wzięłaby za polecenia).</summary>
        public static string Download()
        {
            if (download == null)
            {
                byte[] bytes;
                using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("IMCENA.TTF"))
                {
                    bytes = new byte[stream.Length];
                    int read = 0;
                    while (read < bytes.Length)
                    {
                        read += stream.Read(bytes, read, bytes.Length - read);
                    }
                }
                StringBuilder s = new StringBuilder(bytes.Length * 2 + 40);
                s.Append("~DY").Append(DownloadName).Append(",A,T,").Append(bytes.Length).Append(",,");
                foreach (byte b in bytes)
                {
                    s.Append(b.ToString("X2"));
                }
                download = s.ToString();
            }
            return download;
        }
    }

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
        /// <summary>Kaucja za opakowanie (doliczana na kasie, nie wchodzi w cenę); 0 = bez.</summary>
        public decimal Deposit;
        /// <summary>Kaucja jest za sztukę wielopaku (czteropak: 0,50 zł za każdą puszkę), a nie za całą cenówkę.</summary>
        public bool DepositPerPiece;
        public DateTime PrintedOn = DateTime.Today;

        /// <summary>
        /// Cenówka artykułu z kartoteki na dziś (w promocji cena promocyjna); null, gdy nie ma ceny. Zeskanowany kod
        /// z multikodów (puszka z czteropaka) daje nazwę, cenę i kaucję tego kodu i jego EAN na kodzie kreskowym;
        /// główny EAN, ITM8 albo nazwa: dane z kartoteki.
        /// </summary>
        public static PriceLabel Of(Article article, Match match)
        {
            string price = article.PriceOn(DateTime.Today);
            if (Theme.IsNegativeOrZero(price))
            {
                return null;
            }
            PriceLabel label = new PriceLabel();
            label.Brand = article.Brand;
            LabelCode code = match == null || match.ScaleCode.Length > 0 ? null : article.CodeFor(match.Code);
            decimal articlePrice = Parse(price);
            string deposit = article.Deposit;
            decimal contentShare = 1;
            if (code == null)
            {
                label.Name = article.Name;
                label.Price = articlePrice;
                label.Ean = article.Ean;
                // Wielopak bez własnej kaucji (czteropak ma w danych kod 0): kaucja sztuki, na cenówce za sztukę.
                // Własna kaucja opakowania (skrzynka: transporter 15 zł) jest za całość, bez „/szt.”.
                if (article.Multipack > 1 && DepositValue(deposit) <= 0)
                {
                    deposit = article.PieceDeposit();
                    label.DepositPerPiece = true;
                }
            }
            else
            {
                label.Name = code.Name.Length > 0 ? code.Name : article.Name;
                label.Price = decimal.Round(articlePrice * code.Multiplier, 2);
                label.Ean = match.Code;
                deposit = code.Deposit;
                // Zawartość sztuki z przelicznika opakowań (puszka z czteropaka: 1/4 zawartości czteropaku).
                contentShare = (decimal)match.Numerator / match.Denominator;
            }
            label.Deposit = DepositValue(deposit);
            Measure(label, article.Unit, article.NetContent, contentShare);
            return label;
        }

        /// <summary>Wartość z „wartość;nazwa”; brak = 0.</summary>
        private static decimal DepositValue(string deposit)
        {
            if (deposit == null || deposit.Length == 0)
            {
                return 0;
            }
            int at = deposit.IndexOf(';');
            try
            {
                return Parse(at < 0 ? deposit : deposit.Substring(0, at));
            }
            catch (FormatException)
            {
                return 0;
            }
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
        private static void Measure(PriceLabel label, string unit, string netContent, decimal contentShare)
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
            content = content * contentShare;
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
        /// <summary>
        /// Wysokość i szerokość czcionki ceny (^A@, LabelFont): cyfry złotych ok. 90 punktów wysokości (11 mm), zwężone
        /// jak dawna czcionka 0; grosze i „zł” w tej samej proporcji.
        /// </summary>
        private const int WholeHeight = 124;
        private const int WholeWidth = 84;
        private const int CentsHeight = 74;
        private const int CentsWidth = 50;
        private const int UnitHeight = 40;
        private const int UnitWidth = 34;
        /// <summary>Odstęp między złotymi a kropką groszy i między groszami a „zł”.</summary>
        private const int PriceGap = 4;

        /// <summary>
        /// Cena czcionką 0, gdy drukarka nie ma pliku LabelFont (nie przyjęła go): jak w 1.8.2, ale bez podwójnego druku,
        /// a szerokość cyfry to szacunek z zapasem (złote i tak wyrównane do prawej przed kropką).
        /// </summary>
        private const int Font0WholeHeight = 160;
        private const int Font0WholeWidth = 96;
        private const int Font0CentsHeight = 96;
        private const int Font0CentsWidth = 60;
        private const decimal Font0Digit = 0.66m;
        private const decimal Font0Dot = 0.3m;

        /// <summary>
        /// Cenówka w ZPL. Przesunięcia wydruku (^LS, ^LT) nie ma w cenówce: trzyma je drukarka (Menu, Drukarka), bo
        /// zależą od drukarki i rolki, a nie od kolektora.
        /// </summary>
        public static string Build(PriceLabel label, int dpi)
        {
            return Build(label, dpi, true);
        }

        /// <summary>ownFont: drukarka ma plik LabelFont; bez niego cena czcionką 0.</summary>
        public static string Build(PriceLabel label, int dpi, bool ownFont)
        {
            Zpl z = new Zpl(dpi <= 0 ? BaseDpi : dpi, MarginMm, MarginMm);
            StringBuilder s = z.Text;
            s.Append("^XA^CI28^POI^MMT^LH0,0");
            s.Append("^PW").Append(z.Mm(WidthMm)).Append("^LL").Append(z.Mm(HeightMm));

            z.Block(0, 0, Width, 1, 22, 20, 'L', label.Name);
            z.Block(0, 36, Width, 1, 22, 18, 'L', label.Brand);

            // Cena czcionką LabelFont, wszystkie części na wspólnej linii dołu (^FT). Szerokości znaków są znane z pliku
            // czcionki, a złote i tak stoją wyrównane do prawej w polu kończącym się przed kropką, więc nawet inne
            // skalowanie szerokości w drukarce nie przesunie ich na grosze.
            string money = PriceLabel.Money(label.Price);
            int dot = money.IndexOf('.');
            string whole = money.Substring(0, dot);
            string cents = money.Substring(dot);
            int wholeHeight = ownFont ? WholeHeight : Font0WholeHeight;
            int centsHeight = ownFont ? CentsHeight : Font0CentsHeight;
            int centsWidth = ownFont ? CentsWidth : Font0CentsWidth;
            decimal digit = ownFont ? LabelFont.Digit : Font0Digit;
            decimal dotWidth = ownFont ? LabelFont.Dot : Font0Dot;
            // Od czterech cyfr (1000 zł) węższe złote, żeby grosze i „zł” zmieściły się na etykiecie.
            int wholeWidth = ownFont ? WholeWidth : Font0WholeWidth;
            if (whole.Length >= 4)
            {
                wholeWidth = wholeWidth * 3 / 4;
            }
            // Pole złotych o 8 punktów szersze niż napis: gdyby drukarka skalowała szerokość inaczej, napis i tak mieści się
            // w jednym wierszu pola (za szeroki ^FB zawija albo nadpisuje), a koniec zostaje przed kropką.
            int x = Advance(whole.Length * digit, wholeWidth) + 8;
            z.Price(ownFont, 0, PriceBase, x, 'R', wholeHeight, wholeWidth, whole);
            x += PriceGap;
            z.Price(ownFont, x, PriceBase, 0, 'L', centsHeight, centsWidth, cents);
            x += Advance(dotWidth + (cents.Length - 1) * digit, centsWidth) + PriceGap;
            // „zł” małe, górą równo z górą groszy.
            int unitBase = PriceBase - (int)(LabelFont.DigitHeight * (centsHeight - UnitHeight));
            z.Price(ownFont, x, unitBase, 0, 'L', UnitHeight, UnitWidth, "zł");

            z.Block(8, 198, 200, 1, 20, 16, 'L', label.Ean);
            z.Block(200, 196, Width - 200, 1, 16, 14, 'R', label.SaleUnit);
            z.Block(140, 214, Width - 140, 1, 18, 16, 'R', label.UnitPrice);

            // Kaucja osobno od ceny (doliczana na kasie), po prawej obok kodu kreskowego.
            if (label.Deposit > 0)
            {
                string deposit = "+ KAUCJA " + PriceLabel.Money(label.Deposit) + "zł"
                    + (label.DepositPerPiece ? "/szt." : "");
                // Obok kodu kreskowego jest ok. 22 mm: dłuższy napis (15.00zł/szt.) węższą czcionką.
                z.Block(202, 236, Width - 202, 1, 20, deposit.Length > 18 ? 13 : 16, 'R', deposit);
            }

            // Kreski 24 punkty, a paski skrajne i środkowy EAN-13 schodzą jeszcze ok. 10 punktów niżej: data pod nimi.
            z.Barcode(8, 220, 24, label.Ean);
            z.Block(8, 258, 200, 1, 16, 14, 'L', label.PrintedOn.ToString("dd.MM.yyyy", CultureInfo.InvariantCulture));
            s.Append("^PQ1^XZ");
            return s.ToString();
        }

        /// <summary>Szerokość napisu w punktach: suma szerokości znaków (część em) razy szerokość czcionki.</summary>
        private static int Advance(decimal em, int fontWidth)
        {
            return (int)decimal.Round(em * fontWidth, 0);
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

            /// <summary>
            /// Część ceny czcionką LabelFont na linii dołu y. Z width &gt; 0 napis jest wyrównany w polu od x o tej
            /// szerokości (^FT z ^FB: linia dołu ostatniego wiersza pola), np. złote do prawej, żeby kończyły się przed
            /// kropką bez względu na skalowanie szerokości w drukarce.
            /// </summary>
            public void Price(bool ownFont, int x, int y, int width, char align, int height, int fontWidth, string text)
            {
                Text.Append("^FT").Append(left + D(x)).Append(',').Append(top + D(y));
                if (ownFont)
                {
                    Text.Append("^A@N,").Append(D(height)).Append(',').Append(D(fontWidth)).Append(',').Append(LabelFont.File);
                }
                else
                {
                    Text.Append("^A0N,").Append(D(height)).Append(',').Append(D(fontWidth));
                }
                if (width > 0)
                {
                    Text.Append("^FB").Append(D(width)).Append(",1,0,").Append(align).Append(",0");
                }
                Text.Append("^FD").Append(Clean(text)).Append("^FS");
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
