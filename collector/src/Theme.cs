using System;
using System.Drawing;
using System.Globalization;

namespace Intermanage.Collector
{
    /// <summary>Kolory i czcionki. Ekran kolektora to 320 × 320 w hali sklepu, więc mocne kontrasty i duże cyfry.</summary>
    internal static class Theme
    {
        public static readonly Color Ink = Color.FromArgb(17, 17, 17);
        public static readonly Color Muted = Color.FromArgb(90, 90, 90);
        public static readonly Color Line = Color.FromArgb(200, 200, 200);
        public static readonly Color Paper = Color.White;
        public static readonly Color Panel = Color.FromArgb(235, 235, 235);
        public static readonly Color Selected = Color.FromArgb(191, 219, 254);
        public static readonly Color Brand = Color.FromArgb(226, 0, 26);
        public static readonly Color Good = Color.FromArgb(22, 101, 52);
        public static readonly Color GoodBack = Color.FromArgb(134, 239, 172);
        public static readonly Color Warning = Color.FromArgb(120, 53, 15);
        public static readonly Color WarningBack = Color.FromArgb(252, 211, 77);
        public static readonly Color Bad = Color.FromArgb(153, 27, 27);
        public static readonly Color BadBack = Color.FromArgb(252, 165, 165);

        public static readonly Font Small = new Font("Tahoma", 8F, FontStyle.Regular);
        public static readonly Font Text = new Font("Tahoma", 9F, FontStyle.Regular);
        public static readonly Font Bold = new Font("Tahoma", 9F, FontStyle.Bold);
        public static readonly Font Title = new Font("Tahoma", 11F, FontStyle.Bold);
        public static readonly Font Input = new Font("Tahoma", 12F, FontStyle.Bold);
        public static readonly Font Price = new Font("Tahoma", 20F, FontStyle.Bold);
        public static readonly Font Number = new Font("Tahoma", 16F, FontStyle.Bold);

        /// <summary>„7.5” → „7,50 zł”; pusta cena → kreska.</summary>
        public static string Money(string value)
        {
            return value.Length == 0 ? "–"
                : decimal.Parse(value, CultureInfo.InvariantCulture).ToString("0.00", CultureInfo.InvariantCulture)
                    .Replace('.', ',') + " zł";
        }

        public static string Quantity(string value)
        {
            return value.Length == 0 ? "–" : value.Replace('.', ',');
        }

        /// <summary>Ilość do pola: „1.23” → „1,23”, brak = puste pole.</summary>
        public static string QuantityText(string value)
        {
            return value.Replace('.', ',');
        }

        /// <summary>Przelicznik opakowania: 1/4 → „0,25”, 1/6 → „1/6”, 6/1 → „6”.</summary>
        public static string Ratio(int numerator, int denominator)
        {
            decimal value = (decimal)numerator / denominator;
            return decimal.Round(value, 3) == value ? Quantity(ProtocolLine.Number(value)) : numerator + "/" + denominator;
        }

        public static string Day(DateTime day)
        {
            return day == DateTime.MinValue ? "–" : day.ToString("dd.MM.yyyy", CultureInfo.InvariantCulture);
        }

        private static readonly string[] DayNames = new string[] { "Nd", "Pn", "Wt", "Śr", "Cz", "Pt", "So" };

        /// <summary>„Nd 07.12.2026”; brak daty → kreska.</summary>
        public static string DayName(DateTime day)
        {
            return day == DateTime.MinValue ? "–" : DayNames[(int)day.DayOfWeek] + " " + Day(day);
        }

        /// <summary>„07.12”; brak daty → kreska.</summary>
        public static string Short(DateTime day)
        {
            return day == DateTime.MinValue ? "–" : day.ToString("dd.MM", CultureInfo.InvariantCulture);
        }

        public static bool IsNegativeOrZero(string value)
        {
            return value.Length == 0 || decimal.Parse(value, CultureInfo.InvariantCulture) <= 0;
        }
    }
}
