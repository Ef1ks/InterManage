using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace Intermanage.Collector
{
    internal sealed class Printer
    {
        public string Name = "";
        public string Mac = "";
        public string Model = "";
        public string Pin = "";

        /// <summary>Nie ma jej na liście sklepu (wybrana skanem z adresem spoza Intermanage).</summary>
        public bool Unlisted;
    }

    /// <summary>
    /// Drukarki Bluetooth sklepu z Intermanage (drukarki.txt obok programu, w tym samym układzie co odpowiedź serwera),
    /// więc wybór drukarki działa też z dala od podstawki. Wybrana drukarka jest w ustawieniach.
    /// </summary>
    internal static class Printers
    {
        private static readonly object Gate = new object();
        private static List<Printer> list = new List<Printer>();
        private static string version = "";

        private static string FileName
        {
            get { return Path.Combine(Settings.Folder, "drukarki.txt"); }
        }

        public static string Version
        {
            get { lock (Gate) { return version; } }
        }

        public static List<Printer> List
        {
            get { lock (Gate) { return new List<Printer>(list); } }
        }

        public static void Load()
        {
            try
            {
                Apply(Pairs.Load(FileName));
            }
            catch (IOException error)
            {
                Log.Write("Drukarki: " + error.Message);
            }
        }

        /// <summary>Pobiera listę z serwera (na podstawce, gdy zmieniła się jej wersja) i zapisuje ją u siebie.</summary>
        public static void Download()
        {
            Dictionary<string, string> answer = Http.Text("GET", "/api/collector/printers", null);
            Dictionary<string, string> kept = new Dictionary<string, string>();
            foreach (KeyValuePair<string, string> entry in answer)
            {
                if (entry.Key == "printers" || entry.Key == "count" || entry.Key.StartsWith("printer."))
                {
                    kept[entry.Key] = entry.Value;
                }
            }
            if (Pairs.Get(kept, "printers").Length == 0)
            {
                throw new HttpException(0, "Niezrozumiała lista drukarek");
            }
            Pairs.Save(FileName, kept);
            Apply(kept);
        }

        /// <summary>Wybrana drukarka: z listy sklepu, a spoza niej tylko z adresem z ustawień; brak = null.</summary>
        public static Printer Chosen
        {
            get
            {
                string mac = Settings.PrinterMac;
                if (mac.Length == 0)
                {
                    return null;
                }
                Printer known = Find(mac);
                if (known != null)
                {
                    return known;
                }
                Printer other = new Printer();
                other.Mac = mac;
                other.Name = Settings.PrinterName.Length > 0 ? Settings.PrinterName : "Drukarka spoza listy";
                other.Unlisted = true;
                return other;
            }
        }

        public static Printer Find(string mac)
        {
            foreach (Printer printer in List)
            {
                if (printer.Mac == mac)
                {
                    return printer;
                }
            }
            return null;
        }

        /// <summary>
        /// Adres Bluetooth w zeskanowanym kodzie (sam adres, z dwukropkami albo kreskami, albo w dłuższym tekście z kodu
        /// QR na wydruku drukarki). Najpierw adres drukarki z listy sklepu; pusty tekst, gdy kodu nie da się odczytać.
        /// </summary>
        public static string MacIn(string code)
        {
            string plain = code.Replace(":", "").Replace("-", "").ToUpper();
            List<string> found = new List<string>();
            int run = 0;
            for (int i = 0; i <= plain.Length; i++)
            {
                bool hex = i < plain.Length && ((plain[i] >= '0' && plain[i] <= '9') || (plain[i] >= 'A' && plain[i] <= 'F'));
                if (hex)
                {
                    run++;
                    continue;
                }
                if (run == 12)
                {
                    string candidate = plain.Substring(i - 12, 12);
                    // Sam numer z cyfr (EAN-12, numer seryjny) nie jest adresem, chyba że to drukarka z listy.
                    if (candidate != "000000000000" && (!Catalog.IsDigits(candidate) || Find(candidate) != null))
                    {
                        found.Add(candidate);
                    }
                }
                run = 0;
            }
            foreach (string candidate in found)
            {
                if (Find(candidate) != null)
                {
                    return candidate;
                }
            }
            return found.Count > 0 ? found[0] : "";
        }

        /// <summary>
        /// Łączy z drukarką i czyta jej stan (nazwa, języki, bateria, papier, głowica). Wywoływane w tle; wyjątek
        /// BluetoothException opisuje, dlaczego się nie udało.
        /// </summary>
        public static string Check(Printer printer)
        {
            int started = Environment.TickCount;
            using (BluetoothLink link = BluetoothLink.Connect(printer.Mac, printer.Pin))
            {
                int connected = Environment.TickCount - started;
                string name = link.GetVar("device.friendly_name");
                string languages = link.GetVar("device.languages");
                string battery = link.GetVar("power.percent_full");
                string media = link.GetVar("media.status");
                string head = link.GetVar("head.latch");
                Log.Write("Drukarka " + BluetoothLink.Bar(printer.Mac) + ": połączono w " + connected + " ms, " + name
                    + ", " + languages + ", bateria " + battery + ", papier " + media + ", głowica " + head);
                StringBuilder text = new StringBuilder();
                text.Append("Połączono w ").Append((connected + 500) / 1000).Append(" s.\n");
                if (name.Length == 0 && languages.Length == 0)
                {
                    text.Append("Drukarka nie odpowiada na pytania o stan (może to nie jest drukarka Zebra).");
                    return text.ToString();
                }
                if (name.Length > 0)
                {
                    text.Append("Nazwa: ").Append(name).Append('\n');
                }
                if (languages.Length > 0)
                {
                    text.Append("Języki: ").Append(languages).Append('\n');
                }
                if (battery.Length > 0)
                {
                    text.Append("Bateria: ").Append(battery).Append("%\n");
                }
                if (media.Length > 0)
                {
                    text.Append("Papier: ").Append(media == "ok" ? "jest" : media == "out" ? "BRAK PAPIERU" : media)
                        .Append('\n');
                }
                if (head.Length > 0)
                {
                    text.Append("Pokrywa: ").Append(head == "ok" ? "zamknięta" : head == "open" ? "OTWARTA" : head)
                        .Append('\n');
                }
                text.Append(MediaReport(link));
                return text.ToString().TrimEnd('\n');
            }
        }

        /// <summary>
        /// Drukuje cenówkę (w tle). Przed wydrukiem sprawdza papier i pokrywę, po nim czeka na odpowiedź drukarki, żeby
        /// rozłączenie nie ucięło danych. Wyjątek BluetoothException: brak połączenia albo drukarka nie jest gotowa.
        /// </summary>
        public static string Print(Printer printer, PriceLabel label)
        {
            using (BluetoothLink link = BluetoothLink.Connect(printer.Mac, printer.Pin))
            {
                string head = link.GetVar("head.latch");
                if (head == "open")
                {
                    throw new BluetoothException("Pokrywa drukarki jest otwarta.", 0);
                }
                string media = link.GetVar("media.status");
                if (media == "out")
                {
                    throw new BluetoothException("W drukarce nie ma etykiet.", 0);
                }
                int dpi;
                try
                {
                    dpi = int.Parse(link.GetVar("head.resolution.in_dpi"));
                }
                catch (FormatException)
                {
                    dpi = 203;
                }
                string zpl = LabelZpl.Build(label, dpi);
                link.Send(Encoding.UTF8.GetBytes(zpl));
                // Odpowiedź przychodzi dopiero po przyjęciu etykiety.
                link.GetVar("device.friendly_name");
                Log.Write("Cenówka " + label.Itm8 + " na " + BluetoothLink.Bar(printer.Mac) + ", " + dpi + " dpi");
                return "Wydrukowano cenówkę na: " + printer.Name + " (" + dpi + " dpi).";
            }
        }

        /// <summary>
        /// Ustawienia papieru, od których zależy, gdzie drukarka zaczyna i kończy etykietę: rodzaj papieru, czujnik,
        /// długość etykiety i przesunięcie początku (w punktach, 8 na mm przy 203 dpi).
        /// </summary>
        private static string MediaReport(BluetoothLink link)
        {
            string type = link.GetVar("ezpl.media_type");
            string sense = link.GetVar("media.sense_mode");
            string length = link.GetVar("zpl.label_length");
            string top = link.GetVar("zpl.label_top");
            string mode = link.GetVar("media.printmode");
            Log.Write("Papier: typ " + type + ", czujnik " + sense + ", długość " + length + ", początek " + top
                + ", tryb " + mode);
            StringBuilder text = new StringBuilder();
            if (type.Length > 0)
            {
                text.Append("Papier: ").Append(type == "web" ? "etykiety z przerwą" : type == "continuous" ? "CIĄGŁY"
                    : type == "mark" ? "ZE ZNACZNIKIEM" : type).Append('\n');
            }
            if (sense.Length > 0)
            {
                text.Append("Czujnik: ").Append(sense).Append('\n');
            }
            if (length.Length > 0)
            {
                text.Append("Długość etykiety: ").Append(length).Append(" pkt\n");
            }
            if (top.Length > 0 && top != "0" && top != "000")
            {
                text.Append("Przesunięcie początku: ").Append(top).Append(" pkt\n");
            }
            return text.ToString();
        }

        /// <summary>
        /// Ustawia rodzaj etykiet i mierzy je (~JC): przerwa między etykietami (czujnik przerwy, ^MNY) albo czarny
        /// znacznik na spodzie rolki (czujnik znacznika, ^MNM). Początek wydruku bez przesunięcia, odrywanie, a etykiety
        /// drukarka szuka najwyżej na 80 mm (^ML), żeby przy złym czujniku nie przewinęła pół rolki.
        /// </summary>
        public static string Calibrate(Printer printer, bool blackMark)
        {
            using (BluetoothLink link = BluetoothLink.Connect(printer.Mac, printer.Pin))
            {
                if (link.GetVar("head.latch") == "open")
                {
                    throw new BluetoothException("Pokrywa drukarki jest otwarta.", 0);
                }
                int dpi;
                try
                {
                    dpi = int.Parse(link.GetVar("head.resolution.in_dpi"));
                }
                catch (FormatException)
                {
                    dpi = 203;
                }
                int maxLength = (int)(80m * dpi / 25.4m);
                link.Send("! U1 setvar \"media.type\" \"label\"\r\n");
                link.Send("! U1 setvar \"media.sense_mode\" \"" + (blackMark ? "bar" : "gap") + "\"\r\n");
                link.Send("! U1 setvar \"zpl.label_top\" \"0\"\r\n");
                link.Send("! U1 setvar \"media.printmode\" \"tear off\"\r\n");
                link.Send("^XA" + (blackMark ? "^MNM" : "^MNY") + "^MMT^LT0^ML" + maxLength + "^JUS^XZ");
                link.GetVar("device.friendly_name");
                link.Send("~JC");
                // Pomiar trwa kilka sekund; odpowiedź przychodzi po nim.
                System.Threading.Thread.Sleep(8000);
                string length = link.GetVar("zpl.label_length");
                Log.Write("Kalibracja " + BluetoothLink.Bar(printer.Mac) + (blackMark ? " (znacznik)" : " (przerwa)")
                    + ": długość " + length);
                return "Czujnik: " + (blackMark ? "czarny znacznik" : "przerwa między etykietami") + ".\n"
                    + "Zmierzona długość" + (length.Length > 0 ? ": " + length + " pkt" : ": brak") + " (etykieta 37,5 mm "
                    + "to ok. " + (int)(37.5m * dpi / 25.4m) + " pkt).\n" + MediaReport(link).TrimEnd('\n');
            }
        }

        private static void Apply(Dictionary<string, string> values)
        {
            List<Printer> loaded = new List<Printer>();
            int count;
            try
            {
                count = int.Parse(Pairs.Get(values, "count"));
            }
            catch (FormatException)
            {
                count = 0;
            }
            for (int i = 1; i <= count; i++)
            {
                string prefix = "printer." + i + ".";
                Printer printer = new Printer();
                printer.Mac = Pairs.Get(values, prefix + "mac");
                printer.Name = Pairs.Get(values, prefix + "name");
                printer.Model = Pairs.Get(values, prefix + "model");
                printer.Pin = Pairs.Get(values, prefix + "pin");
                if (printer.Mac.Length == 12)
                {
                    loaded.Add(printer);
                }
            }
            lock (Gate)
            {
                list = loaded;
                version = Pairs.Get(values, "printers");
            }
        }
    }
}
