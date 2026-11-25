using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace Intermanage.Collector
{
    /// <summary>Pozycja listy na kolektorze (protokół albo promocja), z tym, co z niej już wysłano.</summary>
    internal abstract class ListLine
    {
        public string Itm8 = "";
        public string Ean = "";
        public string Name = "";
        public string Brand = "";

        /// <summary>Linia pliku listy na kolektorze (z tym, co już wysłano).</summary>
        public abstract string Save();

        /// <summary>Zmieniona od ostatniej wysyłki.</summary>
        public abstract bool Unsent { get; }

        /// <summary>Wysłana, potem usunięta: czeka tylko na wysłanie zmiany, na liście jej nie widać.</summary>
        public virtual bool Hidden { get { return false; } }

        /// <summary>Linia do wysłania: sama zmiana od ostatniej wysyłki.</summary>
        public abstract string Change();

        public abstract void MarkSent();

        protected static string Field(string text)
        {
            return text.Replace('\t', ' ').Replace('\n', ' ').Replace('\r', ' ');
        }
    }

    /// <summary>
    /// Pozycja protokołu: zeskanowany kod z wpisaną ilością. Ilość do protokołu jest w jednostce głównego kodu artykułu:
    /// 8 puszek z czteropaka (przelicznik 1/4) to 2. {@code Sent}: wpisana ilość, która już poszła do Intermanage.
    /// </summary>
    internal class ProtocolLine : ListLine
    {
        public string Code = "";
        public decimal Entered;
        public decimal Sent;
        public int Numerator = 1;
        public int Denominator = 1;

        public decimal Quantity
        {
            get { return Convert(Entered, Numerator, Denominator); }
        }

        public bool Converted
        {
            get { return Numerator != Denominator; }
        }

        /// <summary>Ta sama pozycja: ten sam artykuł liczony w tej samej jednostce (sztuki i wielopaki osobno).</summary>
        public bool SameAs(ProtocolLine other)
        {
            return Itm8 == other.Itm8 && Numerator * other.Denominator == other.Numerator * Denominator;
        }

        public static decimal Convert(decimal entered, int numerator, int denominator)
        {
            return decimal.Round(entered * numerator / denominator, 3);
        }

        public override bool Unsent { get { return Entered != Sent; } }

        public override bool Hidden { get { return Entered == 0; } }

        private string Text(decimal entered)
        {
            return Itm8 + "\t" + Code + "\t" + Ean + "\t" + Field(Name) + "\t" + Field(Brand) + "\t" + Number(entered)
                + "\t" + Number(Convert(entered, Numerator, Denominator)) + "\t" + Numerator + "/" + Denominator;
        }

        public override string Save()
        {
            return Text(Entered) + "\t" + Number(Sent);
        }

        public override string Change()
        {
            return Text(Entered - Sent);
        }

        public override void MarkSent()
        {
            Sent = Entered;
        }

        public static ProtocolLine Parse(string text)
        {
            string[] fields = text.Split('\t');
            if (fields.Length < 8)
            {
                return null;
            }
            string[] ratio = fields[7].Split('/');
            ProtocolLine line = new ProtocolLine();
            line.Itm8 = fields[0];
            line.Code = fields[1];
            line.Ean = fields[2];
            line.Name = fields[3];
            line.Brand = fields[4];
            line.Entered = decimal.Parse(fields[5], CultureInfo.InvariantCulture);
            line.Numerator = int.Parse(ratio[0]);
            line.Denominator = int.Parse(ratio[1]);
            // Lista sprzed 1.4.0 nie zapisywała, co wysłano: liczy się jako niewysłana.
            line.Sent = fields.Length > 8 && fields[8].Length > 0
                ? decimal.Parse(fields[8], CultureInfo.InvariantCulture) : 0;
            return line;
        }

        public static string Number(decimal value)
        {
            return value.ToString("0.###", CultureInfo.InvariantCulture);
        }
    }

    /// <summary>Artykuł promocji: cena promocji, przecena (0 = cena wpisana) i koniec promocji tego artykułu.</summary>
    internal class PromoLine : ListLine
    {
        public string Regular = "";
        public decimal Price;
        public int Discount;
        public DateTime DateTo = DateTime.MinValue;
        public string SentKey = "";

        private string Key
        {
            get { return Money(Price) + "|" + Day(DateTo); }
        }

        public override bool Unsent { get { return SentKey != Key; } }

        public override string Save()
        {
            return Itm8 + "\t" + Ean + "\t" + Field(Name) + "\t" + Field(Brand) + "\t" + Regular + "\t" + Money(Price) + "\t"
                + Discount + "\t" + Day(DateTo) + "\t" + SentKey;
        }

        public override string Change()
        {
            return Itm8 + "\t" + Ean + "\t" + Field(Name) + "\t" + Field(Brand) + "\t" + Money(Price) + "\t" + Day(DateTo);
        }

        public override void MarkSent()
        {
            SentKey = Key;
        }

        public PromoLine Copy()
        {
            return (PromoLine)MemberwiseClone();
        }

        public static PromoLine Parse(string text)
        {
            string[] fields = text.Split('\t');
            if (fields.Length < 9)
            {
                return null;
            }
            PromoLine line = new PromoLine();
            line.Itm8 = fields[0];
            line.Ean = fields[1];
            line.Name = fields[2];
            line.Brand = fields[3];
            line.Regular = fields[4];
            line.Price = decimal.Parse(fields[5], CultureInfo.InvariantCulture);
            line.Discount = int.Parse(fields[6]);
            line.DateTo = ParseDay(fields[7]);
            line.SentKey = fields[8];
            return line;
        }

        public static string Money(decimal value)
        {
            return value.ToString("0.00", CultureInfo.InvariantCulture);
        }

        public static string Day(DateTime day)
        {
            return day == DateTime.MinValue ? "" : day.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
        }

        public static DateTime ParseDay(string text)
        {
            return text.Length != 8 ? DateTime.MinValue
                : DateTime.ParseExact(text, "yyyyMMdd", CultureInfo.InvariantCulture);
        }

        /// <summary>Cena po przecenie o {@code percent} % od zwykłej, do grosza (jak na stronie, od .5 w górę).</summary>
        public static decimal Discounted(decimal regular, int percent)
        {
            return decimal.Truncate(regular * (100 - percent) + 0.5m) / 100;
        }
    }

    /// <summary>
    /// Lista na kolektorze (zapisywana po każdej zmianie). Zostaje na kolektorze, aż pracownik ją usunie: na podstawce
    /// program pyta, czy ją wysłać, a wysyła same zmiany od poprzedniej wysyłki (jako kolejną listę w Intermanage).
    /// Wysłana i niezmieniona lista znika sama po {@link #KeepSent}; wyczyszczoną da się przywrócić.
    /// </summary>
    internal abstract class ListStore
    {
        public static readonly TimeSpan KeepSent = TimeSpan.FromHours(2);
        private const int KeptSent = 20;
        protected readonly object gate = new object();
        protected readonly List<ListLine> lines = new List<ListLine>();
        private string started = "";
        private DateTime sentAt = DateTime.MinValue;
        private bool justSent;
        private string lastNote = "";

        protected abstract string BaseName { get; }
        protected abstract string OutboxFolder { get; }
        protected abstract string SentFolder { get; }
        protected abstract ListLine ParseLine(string text);
        protected abstract void Post(string id, string startedAt, Dictionary<string, string> header, string body,
            out string note);

        protected virtual void ReadHeader(string key, string value)
        {
        }

        protected virtual void WriteHeader(StreamWriter writer)
        {
        }

        private string FileName { get { return Path.Combine(Settings.Folder, BaseName + ".txt"); } }
        private string BackupName { get { return Path.Combine(Settings.Folder, BaseName + "-wyczyszczony.txt"); } }

        /// <summary>Widoczne pozycje od ostatnio dodanej.</summary>
        public List<ListLine> Visible
        {
            get
            {
                lock (gate)
                {
                    List<ListLine> result = new List<ListLine>();
                    foreach (ListLine line in lines)
                    {
                        if (!line.Hidden)
                        {
                            result.Add(line);
                        }
                    }
                    return result;
                }
            }
        }

        public int Count
        {
            get { return Visible.Count; }
        }

        /// <summary>Są zmiany, których Intermanage jeszcze nie ma.</summary>
        public bool Unsent
        {
            get
            {
                lock (gate)
                {
                    foreach (ListLine line in lines)
                    {
                        if (line.Unsent)
                        {
                            return true;
                        }
                    }
                    return false;
                }
            }
        }

        /// <summary>Kiedy lista poszła ostatnio do Intermanage albo {@code DateTime.MinValue}.</summary>
        public DateTime SentAt { get { return sentAt; } }

        /// <summary>Właśnie wysłana: program pyta, czy ją usunąć.</summary>
        public bool JustSent { get { return justSent; } }

        /// <summary>Co odpowiedział serwer na ostatnią wysyłkę (np. czy ruszyło makro).</summary>
        public string LastNote { get { return lastNote; } }

        public bool CanRestore
        {
            get { return Count == 0 && File.Exists(BackupName); }
        }

        public string[] Waiting
        {
            get { return Directory.Exists(OutboxFolder) ? Directory.GetFiles(OutboxFolder, "*.txt") : new string[0]; }
        }

        public void Load()
        {
            lock (gate)
            {
                ReadFrom(FileName);
            }
        }

        public void Answered()
        {
            justSent = false;
        }

        /// <summary>Zamyka do wysłania same zmiany od poprzedniej wysyłki; potem wysyła je Sync.</summary>
        public void PrepareSend(Dictionary<string, string> extra)
        {
            lock (gate)
            {
                StringBuilder text = new StringBuilder();
                text.Append("started=").Append(started).Append("\r\n");
                foreach (KeyValuePair<string, string> pair in extra)
                {
                    text.Append(pair.Key).Append('=').Append(pair.Value).Append("\r\n");
                }
                int count = 0;
                // Od pierwszej zeskanowanej, a na kolektorze lista jest od ostatniej.
                for (int i = lines.Count - 1; i >= 0; i--)
                {
                    if (lines[i].Unsent)
                    {
                        text.Append(lines[i].Change()).Append("\r\n");
                        lines[i].MarkSent();
                        count++;
                    }
                }
                for (int i = lines.Count - 1; i >= 0; i--)
                {
                    if (lines[i].Hidden)
                    {
                        lines.RemoveAt(i);
                    }
                }
                if (count == 0)
                {
                    return;
                }
                Folder(OutboxFolder);
                string target = Path.Combine(OutboxFolder, Guid.NewGuid().ToString() + ".txt");
                string temporary = target + ".tmp";
                using (StreamWriter writer = new StreamWriter(temporary, false, Encoding.UTF8))
                {
                    writer.Write(text.ToString());
                }
                File.Move(temporary, target);
                Save();
            }
        }

        /// <summary>Wysyła jedną zamkniętą wysyłkę; potwierdzona trafia do wysłanych.</summary>
        public void Send(string file)
        {
            string id = Path.GetFileNameWithoutExtension(file);
            string startedAt = "";
            Dictionary<string, string> header = new Dictionary<string, string>();
            StringBuilder body = new StringBuilder();
            using (StreamReader reader = new StreamReader(file, Encoding.UTF8))
            {
                string text;
                while ((text = reader.ReadLine()) != null)
                {
                    int at = text.IndexOf('=');
                    if (text.IndexOf('\t') < 0 && at > 0)
                    {
                        if (text.StartsWith("started="))
                        {
                            startedAt = text.Substring(8);
                        }
                        else
                        {
                            header[text.Substring(0, at)] = text.Substring(at + 1);
                        }
                    }
                    else if (text.Length > 0)
                    {
                        body.Append(text).Append('\n');
                    }
                }
            }
            string note = "";
            if (body.Length > 0)
            {
                Post(id, startedAt, header, body.ToString(), out note);
            }
            Folder(SentFolder);
            string target = Path.Combine(SentFolder, Path.GetFileName(file));
            if (File.Exists(target))
            {
                File.Delete(target);
            }
            File.Move(file, target);
            Prune();
            lock (gate)
            {
                sentAt = DateTime.Now;
                lastNote = note;
                justSent = Waiting.Length == 0 && lines.Count > 0;
                Save();
            }
        }

        /// <summary>Wysłana i niezmieniona lista po {@link #KeepSent} znika (z możliwością przywrócenia).</summary>
        public bool ClearIfOld()
        {
            lock (gate)
            {
                if (lines.Count == 0 || sentAt == DateTime.MinValue || Waiting.Length > 0 || Unsent
                    || DateTime.Now - sentAt < KeepSent)
                {
                    return false;
                }
                Reset();
                return true;
            }
        }

        /// <summary>Wyczyszczenie listy; poprzednia zostaje w pliku do przywrócenia.</summary>
        public void Reset()
        {
            lock (gate)
            {
                if (lines.Count == 0)
                {
                    return;
                }
                Write(BackupName);
                lines.Clear();
                sentAt = DateTime.MinValue;
                justSent = false;
                Cleared();
                Save();
            }
        }

        /// <summary>Po wyczyszczeniu (np. promocje zapominają początek i koniec).</summary>
        protected virtual void Cleared()
        {
        }

        public void Restore()
        {
            lock (gate)
            {
                if (lines.Count == 0 && File.Exists(BackupName))
                {
                    ReadFrom(BackupName);
                    Save();
                    File.Delete(BackupName);
                }
            }
        }

        /// <summary>Nowa lista zaczyna się z pierwszą pozycją.</summary>
        protected void Started()
        {
            if (lines.Count == 0)
            {
                started = DateTime.Now.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture);
                sentAt = DateTime.MinValue;
            }
        }

        protected void Save()
        {
            if (lines.Count == 0 && !KeepEmpty)
            {
                if (File.Exists(FileName))
                {
                    File.Delete(FileName);
                }
                return;
            }
            Write(FileName);
        }

        /// <summary>Plik zostaje także bez pozycji (np. ustawione daty promocji).</summary>
        protected virtual bool KeepEmpty { get { return false; } }

        private void Write(string file)
        {
            string temporary = file + ".tmp";
            using (StreamWriter writer = new StreamWriter(temporary, false, Encoding.UTF8))
            {
                writer.WriteLine("started=" + started);
                writer.WriteLine("sent=" + (sentAt == DateTime.MinValue ? ""
                    : sentAt.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture)));
                WriteHeader(writer);
                foreach (ListLine line in lines)
                {
                    writer.WriteLine(line.Save());
                }
            }
            if (File.Exists(file))
            {
                File.Delete(file);
            }
            File.Move(temporary, file);
        }

        private void ReadFrom(string file)
        {
            lines.Clear();
            // Brak prądu między usunięciem starego a nazwaniem nowego pliku zostawia sam .tmp.
            if (!File.Exists(file) && File.Exists(file + ".tmp"))
            {
                File.Move(file + ".tmp", file);
            }
            if (!File.Exists(file))
            {
                return;
            }
            using (StreamReader reader = new StreamReader(file, Encoding.UTF8))
            {
                string text;
                while ((text = reader.ReadLine()) != null)
                {
                    int at = text.IndexOf('=');
                    if (text.IndexOf('\t') < 0 && at > 0)
                    {
                        string key = text.Substring(0, at);
                        string value = text.Substring(at + 1);
                        if (key == "started")
                        {
                            started = value;
                        }
                        else if (key == "sent")
                        {
                            sentAt = value.Length != 14 ? DateTime.MinValue : DateTime.ParseExact(value,
                                "yyyyMMddHHmmss", CultureInfo.InvariantCulture);
                        }
                        else
                        {
                            ReadHeader(key, value);
                        }
                        continue;
                    }
                    ListLine line = ParseLine(text);
                    if (line != null)
                    {
                        lines.Add(line);
                    }
                }
            }
        }

        private void Prune()
        {
            List<FileInfo> files = new List<FileInfo>(new DirectoryInfo(SentFolder).GetFiles("*.txt"));
            files.Sort(new Comparison<FileInfo>(NewerFirst));
            for (int i = KeptSent; i < files.Count; i++)
            {
                files[i].Delete();
            }
        }

        private static int NewerFirst(FileInfo a, FileInfo b)
        {
            return b.CreationTime.CompareTo(a.CreationTime);
        }

        protected static void Folder(string path)
        {
            if (!Directory.Exists(path))
            {
                Directory.CreateDirectory(path);
            }
        }
    }

    /// <summary>Lista protokołu (protokol.txt); wysyłka idzie do Kolektory → Dane z kolektorów.</summary>
    internal class ProtocolStore : ListStore
    {
        protected override string BaseName { get { return "protokol"; } }
        protected override string OutboxFolder { get { return Path.Combine(Settings.Folder, "do-wyslania"); } }
        protected override string SentFolder { get { return Path.Combine(Settings.Folder, "wyslane"); } }

        protected override ListLine ParseLine(string text)
        {
            return ProtocolLine.Parse(text);
        }

        public List<ProtocolLine> Lines
        {
            get
            {
                List<ProtocolLine> result = new List<ProtocolLine>();
                foreach (ListLine line in Visible)
                {
                    result.Add((ProtocolLine)line);
                }
                return result;
            }
        }

        /// <summary>Ile tego artykułu w tej jednostce jest już na liście (wpisane).</summary>
        public decimal EnteredOf(ProtocolLine wanted)
        {
            lock (gate)
            {
                ProtocolLine line = Find(wanted);
                return line == null ? 0 : line.Entered;
            }
        }

        /// <summary>Dodaje ilość do pozycji (albo nową) i przenosi ją na początek.</summary>
        public void Add(ProtocolLine added)
        {
            lock (gate)
            {
                Started();
                ProtocolLine line = Find(added);
                if (line == null)
                {
                    line = added;
                }
                else
                {
                    line.Entered += added.Entered;
                    lines.Remove(line);
                }
                lines.Insert(0, line);
                Save();
            }
        }

        /// <summary>Poprawiona ilość pozycji; 0 zdejmuje ją z listy (wysłana czeka na wysłanie zmiany).</summary>
        public void Change(ProtocolLine target, decimal entered)
        {
            lock (gate)
            {
                ProtocolLine line = Find(target);
                if (line == null)
                {
                    return;
                }
                if (entered == 0 && line.Sent == 0)
                {
                    lines.Remove(line);
                }
                else
                {
                    line.Entered = entered;
                }
                Save();
            }
        }

        protected override void Post(string id, string startedAt, Dictionary<string, string> header, string body,
            out string note)
        {
            Http.Text("POST", "/api/collector/lists", "id=" + id + "&started=" + startedAt + "&lines=" + Http.Encode(body));
            note = "";
        }

        private ProtocolLine Find(ProtocolLine wanted)
        {
            foreach (ListLine line in lines)
            {
                ProtocolLine protocol = (ProtocolLine)line;
                if (protocol.SameAs(wanted))
                {
                    return protocol;
                }
            }
            return null;
        }
    }

    /// <summary>
    /// Promocje (promocje.txt): artykuł raz, z ceną i końcem; do tego początek operacji i koniec dla nowych artykułów.
    /// Wysyłka idzie do Kolektory → Promocje z kolektorów, a z „macro=1” od razu do makra na komputerze sklepu.
    /// </summary>
    internal class PromoStore : ListStore
    {
        private DateTime start = DateTime.MinValue;
        private DateTime end = DateTime.MinValue;

        protected override string BaseName { get { return "promocje"; } }
        protected override string OutboxFolder { get { return Path.Combine(Settings.Folder, "promocje-do-wyslania"); } }
        protected override string SentFolder { get { return Path.Combine(Settings.Folder, "promocje-wyslane"); } }
        protected override bool KeepEmpty { get { return start != DateTime.MinValue || end != DateTime.MinValue; } }

        protected override ListLine ParseLine(string text)
        {
            return PromoLine.Parse(text);
        }

        /// <summary>Początek operacji; {@code DateTime.MinValue} albo dzień przed dziś = od dziś.</summary>
        public DateTime Start
        {
            get { return start != DateTime.MinValue && start > DateTime.Today ? start : DateTime.MinValue; }
            set
            {
                lock (gate)
                {
                    start = value;
                    Save();
                }
            }
        }

        /// <summary>Koniec dla nowych artykułów; {@code DateTime.MinValue} = jeszcze nie wybrany.</summary>
        public DateTime DefaultEnd
        {
            get { return end; }
            set
            {
                lock (gate)
                {
                    end = value;
                    Save();
                }
            }
        }

        public List<PromoLine> Lines
        {
            get
            {
                List<PromoLine> result = new List<PromoLine>();
                foreach (ListLine line in Visible)
                {
                    result.Add((PromoLine)line);
                }
                return result;
            }
        }

        public PromoLine Find(string itm8)
        {
            lock (gate)
            {
                foreach (ListLine line in lines)
                {
                    if (line.Itm8 == itm8)
                    {
                        return (PromoLine)line;
                    }
                }
                return null;
            }
        }

        /// <summary>Artykuł z ceną i końcem: nowy albo zastępuje ten sam artykuł (to, co już wysłano, zostaje).</summary>
        public void Put(PromoLine changed)
        {
            lock (gate)
            {
                Started();
                PromoLine known = Find(changed.Itm8);
                if (known != null)
                {
                    changed.SentKey = known.SentKey;
                    lines.Remove(known);
                }
                lines.Insert(0, changed);
                Save();
            }
        }

        public void Remove(string itm8)
        {
            lock (gate)
            {
                PromoLine known = Find(itm8);
                if (known != null)
                {
                    lines.Remove(known);
                    Save();
                }
            }
        }

        protected override void Cleared()
        {
            start = DateTime.MinValue;
            end = DateTime.MinValue;
        }

        protected override void ReadHeader(string key, string value)
        {
            if (key == "start")
            {
                start = PromoLine.ParseDay(value);
            }
            else if (key == "end")
            {
                end = PromoLine.ParseDay(value);
            }
        }

        protected override void WriteHeader(StreamWriter writer)
        {
            writer.WriteLine("start=" + PromoLine.Day(start));
            writer.WriteLine("end=" + PromoLine.Day(end));
        }

        /// <summary>Wysyłka zmian; {@code macro}: od razu ustawić je w Mercalys na komputerze sklepu.</summary>
        public void PrepareSend(bool macro)
        {
            Dictionary<string, string> extra = new Dictionary<string, string>();
            extra["start"] = PromoLine.Day(Start);
            extra["macro"] = macro ? "1" : "0";
            PrepareSend(extra);
        }

        protected override void Post(string id, string startedAt, Dictionary<string, string> header, string body,
            out string note)
        {
            string macro = Pairs.Get(header, "macro");
            Dictionary<string, string> answer = Http.Text("POST", "/api/collector/promotions", "id=" + id + "&started="
                + startedAt + "&start=" + Pairs.Get(header, "start") + "&macro=" + macro + "&lines=" + Http.Encode(body));
            string outcome = Pairs.Get(answer, "macro");
            note = macro != "1" ? "Wstaw je w Intermanage: Kolektory, Promocje z kolektorów."
                : outcome == "ok" ? "Komputer sklepu ustawi je w Mercalys, potem zrobi cenówki."
                : "Makro nie ruszyło: " + outcome + " Promocje są w Intermanage: Kolektory, Promocje z kolektorów.";
        }
    }
}
