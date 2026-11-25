using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace Intermanage.Collector
{
    /// <summary>Artykuł z kartoteki; pola w kolejności jak w CollectorCatalogFile na serwerze.</summary>
    internal class Article
    {
        private const int FieldCount = 21;
        private readonly string[] fields;

        public Article(string row)
        {
            string[] read = row.Split('\t');
            if (read.Length < FieldCount)
            {
                throw new InvalidDataFileException();
            }
            fields = read;
        }

        public string Itm8 { get { return fields[0]; } }
        public string Ean { get { return fields[1]; } }
        public string Name { get { return fields[2]; } }
        public string Brand { get { return fields[3]; } }
        public string Subfamily { get { return fields[4]; } }
        public string SalePrice { get { return fields[5]; } }
        public string PromoPrice { get { return fields[6]; } }
        public DateTime PromoFrom { get { return Day(fields[7]); } }
        public DateTime PromoTo { get { return Day(fields[8]); } }
        public string PromoRegular { get { return fields[9]; } }
        public string Stock { get { return fields[10]; } }
        public string Rotation { get { return fields[11]; } }
        public string CaseSize { get { return fields[12]; } }
        public string Unit { get { return fields[13]; } }
        public string NetContent { get { return fields[14]; } }
        public string CadencierPrice { get { return fields[15]; } }
        public bool Active { get { return fields[16] != "0"; } }
        public bool Cadencier { get { return fields[17] == "1"; } }
        public string Supplier { get { return fields[18]; } }
        public DateTime LastDelivery { get { return Day(fields[19]); } }
        public string OtherEans { get { return fields[20]; } }

        public bool HasPromotion
        {
            get { return PromoPrice.Length > 0 && PromoTo != DateTime.MinValue; }
        }

        public bool PromotionOn(DateTime day)
        {
            return HasPromotion && PromoFrom <= day && day <= PromoTo;
        }

        /// <summary>Cena na kasie tego dnia: w promocji promocyjna, inaczej zwykła.</summary>
        public string PriceOn(DateTime day)
        {
            return PromotionOn(day) ? PromoPrice : SalePrice;
        }

        private static DateTime Day(string value)
        {
            return value.Length != 8 ? DateTime.MinValue : new DateTime(int.Parse(value.Substring(0, 4)),
                int.Parse(value.Substring(4, 2)), int.Parse(value.Substring(6, 2)));
        }
    }

    internal class InvalidDataFileException : Exception
    {
        public InvalidDataFileException()
            : base("Plik z danymi jest uszkodzony")
        {
        }
    }

    /// <summary>
    /// Plik kartoteki na kolektorze (katalog.dat; układ opisuje CollectorCatalogFile na serwerze). W pamięci są tylko
    /// nagłówek i nazwy nomenklatury, reszta jest czytana z pliku w miejscu wskazanym przez indeks, bo sklep ma nawet
    /// 170 tys. artykułów.
    /// </summary>
    internal class Catalog : IDisposable
    {
        public const int ResultLimit = 200;
        private const string Magic = "IMKATAL1";
        private const int ChunkSize = 65536;

        private readonly FileStream file;
        private readonly Dictionary<string, string> header;
        private readonly Dictionary<string, string> nomenclature = new Dictionary<string, string>();
        private readonly int count;
        private readonly int searchSize;
        private readonly int eanCount;
        private readonly long rowStarts;
        private readonly long rows;
        private readonly long search;
        private readonly long eanKeys;
        private readonly long eanRows;
        private readonly long itm8Keys;
        private readonly long itm8Rows;
        private readonly byte[] number = new byte[8];

        public Catalog(string path)
        {
            file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            try
            {
                if (Text(Bytes(8)) != Magic)
                {
                    throw new InvalidDataFileException();
                }
                header = Pairs.Parse(Text(Bytes(Int())));
                count = Int();
                int rowsSize = Int();
                searchSize = Int();
                eanCount = Int();
                int namesSize = Int();
                rowStarts = file.Position;
                rows = rowStarts + 4L * (count + 1);
                search = rows + rowsSize;
                eanKeys = search + searchSize;
                eanRows = eanKeys + 8L * eanCount;
                itm8Keys = eanRows + 4L * eanCount;
                itm8Rows = itm8Keys + 8L * count;
                long names = itm8Rows + 4L * count;
                if (file.Length != names + namesSize)
                {
                    throw new InvalidDataFileException();
                }
                file.Seek(names, SeekOrigin.Begin);
                foreach (string line in Text(Bytes(namesSize)).Split('\n'))
                {
                    int at = line.IndexOf('\t');
                    if (at > 0)
                    {
                        nomenclature[line.Substring(0, at)] = line.Substring(at + 1);
                    }
                }
            }
            catch (Exception)
            {
                file.Close();
                throw;
            }
        }

        public string Version { get { return Pairs.Get(header, "version"); } }
        public string StoreNumber { get { return Pairs.Get(header, "store"); } }
        public int Count { get { return count; } }

        /// <summary>Data raportu kartoteki (stany i ceny są z tej chwili).</summary>
        public DateTime ReportAt { get { return Minute(Pairs.Get(header, "report")); } }

        /// <summary>Kiedy serwer złożył plik.</summary>
        public DateTime GeneratedAt { get { return Minute(Pairs.Get(header, "generated")); } }

        public string NomenclatureName(string code)
        {
            return Pairs.Get(nomenclature, code);
        }

        public Article Read(int index)
        {
            file.Seek(rowStarts + 4L * index, SeekOrigin.Begin);
            int start = Int();
            int end = Int();
            file.Seek(rows + start, SeekOrigin.Begin);
            return new Article(Text(Bytes(end - start)));
        }

        /// <summary>
        /// Artykuły pasujące do wpisanego albo zeskanowanego tekstu: kod z cyfr (EAN, ITM8, kod z etykiety wagi),
        /// a bez trafienia i dla innego tekstu nazwa z marką (każde słowo jako fragment).
        /// </summary>
        public List<int> Find(string text)
        {
            string query = text.Trim();
            List<int> found = new List<int>();
            if (IsDigits(query) && query.Length <= 18)
            {
                long code = long.Parse(query);
                Append(found, eanKeys, eanRows, eanCount, code, code);
                if (found.Count == 0 && query.Length <= 13)
                {
                    Append(found, itm8Keys, itm8Rows, count, code, code);
                }
                if (found.Count == 0 && IsScaleCode(query))
                {
                    // Kod z etykiety wagi: pierwsze 7 cyfr to artykuł, reszta to cena albo waga.
                    long article = long.Parse(query.Substring(0, 7)) * 1000000;
                    Append(found, eanKeys, eanRows, eanCount, article, article + 999999);
                }
                if (found.Count > 0)
                {
                    return found;
                }
            }
            return FindText(query);
        }

        public void Dispose()
        {
            file.Close();
        }

        public static bool IsDigits(string text)
        {
            foreach (char letter in text)
            {
                if (letter < '0' || letter > '9')
                {
                    return false;
                }
            }
            return text.Length > 0;
        }

        private static bool IsScaleCode(string code)
        {
            return (code.Length == 12 || code.Length == 13) && code[0] == '2' && code[1] >= '1';
        }

        /// <summary>Numery artykułów z indeksu, których klucz jest w przedziale [from, to].</summary>
        private void Append(List<int> found, long keys, long indexes, int size, long from, long to)
        {
            int low = 0;
            int high = size;
            while (low < high)
            {
                int middle = low + (high - low) / 2;
                if (Key(keys, middle) < from)
                {
                    low = middle + 1;
                }
                else
                {
                    high = middle;
                }
            }
            for (int i = low; i < size && found.Count < ResultLimit && Key(keys, i) <= to; i++)
            {
                file.Seek(indexes + 4L * i, SeekOrigin.Begin);
                int index = Int();
                if (!found.Contains(index))
                {
                    found.Add(index);
                }
            }
        }

        private long Key(long keys, int position)
        {
            file.Seek(keys + 8L * position, SeekOrigin.Begin);
            Fill(number, 8);
            return BitConverter.ToInt64(number, 0);
        }

        /// <summary>Szuka w tekstach nazw po kolei; linia = artykuł.</summary>
        private List<int> FindText(string query)
        {
            List<int> found = new List<int>();
            List<byte[]> words = new List<byte[]>();
            foreach (string word in Plain(query).Split(' '))
            {
                if (word.Length > 0)
                {
                    words.Add(Encoding.ASCII.GetBytes(word));
                }
            }
            if (words.Count == 0)
            {
                return found;
            }
            byte[][] patterns = words.ToArray();
            byte[] buffer = new byte[ChunkSize];
            int kept = 0;
            int line = 0;
            int left = searchSize;
            file.Seek(search, SeekOrigin.Begin);
            while (left > 0 || kept > 0)
            {
                int wanted = Math.Min(buffer.Length - kept, left);
                int read = wanted == 0 ? 0 : file.Read(buffer, kept, wanted);
                if (read <= 0)
                {
                    break;
                }
                left -= read;
                int filled = kept + read;
                int start = 0;
                for (int i = kept; i < filled; i++)
                {
                    if (buffer[i] != 10)
                    {
                        continue;
                    }
                    if (Matches(buffer, start, i, patterns))
                    {
                        found.Add(line);
                        if (found.Count >= ResultLimit)
                        {
                            return found;
                        }
                    }
                    line++;
                    start = i + 1;
                }
                kept = filled - start;
                Buffer.BlockCopy(buffer, start, buffer, 0, kept);
            }
            return found;
        }

        private static bool Matches(byte[] buffer, int start, int end, byte[][] patterns)
        {
            for (int p = 0; p < patterns.Length; p++)
            {
                byte[] pattern = patterns[p];
                int last = end - pattern.Length;
                byte head = pattern[0];
                bool matched = false;
                for (int i = start; i <= last && !matched; i++)
                {
                    if (buffer[i] != head)
                    {
                        continue;
                    }
                    int k = 1;
                    while (k < pattern.Length && buffer[i + k] == pattern[k])
                    {
                        k++;
                    }
                    matched = k == pattern.Length;
                }
                if (!matched)
                {
                    return false;
                }
            }
            return true;
        }

        /// <summary>Jak CollectorCatalogFile.searchText: wielkie litery bez polskich znaków.</summary>
        private static string Plain(string text)
        {
            const string polish = "ĄĆĘŁŃÓŚŹŻ";
            const string ascii = "ACELNOSZZ";
            StringBuilder result = new StringBuilder();
            foreach (char letter in text.ToUpper())
            {
                int at = polish.IndexOf(letter);
                result.Append(at >= 0 ? ascii[at] : letter < ' ' || letter > '~' ? '?' : letter);
            }
            return result.ToString();
        }

        private static DateTime Minute(string value)
        {
            return value.Length != 12 ? DateTime.MinValue
                : DateTime.ParseExact(value, "yyyyMMddHHmm", CultureInfo.InvariantCulture);
        }

        private int Int()
        {
            Fill(number, 4);
            return BitConverter.ToInt32(number, 0);
        }

        private byte[] Bytes(int length)
        {
            if (length < 0 || length > file.Length)
            {
                throw new InvalidDataFileException();
            }
            byte[] result = new byte[length];
            Fill(result, length);
            return result;
        }

        private void Fill(byte[] target, int length)
        {
            int at = 0;
            while (at < length)
            {
                int read = file.Read(target, at, length - at);
                if (read <= 0)
                {
                    throw new InvalidDataFileException();
                }
                at += read;
            }
        }

        private static string Text(byte[] bytes)
        {
            return Encoding.UTF8.GetString(bytes, 0, bytes.Length);
        }
    }
}
