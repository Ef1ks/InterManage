using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;

namespace Intermanage.Collector
{
    /// <summary>
    /// Pozycja listy: zeskanowany kod z wpisaną ilością. Ilość do protokołu jest w jednostce głównego kodu artykułu:
    /// 8 puszek z czteropaka (przelicznik 1/4) to 2.
    /// </summary>
    internal class ProtocolLine
    {
        public string Itm8 = "";
        public string Code = "";
        public string Ean = "";
        public string Name = "";
        public string Brand = "";
        public decimal Entered;
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

        public string ToText()
        {
            return Itm8 + "\t" + Code + "\t" + Ean + "\t" + Name.Replace('\t', ' ') + "\t" + Brand.Replace('\t', ' ') + "\t"
                + Number(Entered) + "\t" + Number(Quantity) + "\t" + Numerator + "/" + Denominator;
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
            return line;
        }

        public static string Number(decimal value)
        {
            string text = value.ToString("0.###", CultureInfo.InvariantCulture);
            return text;
        }
    }

    /// <summary>
    /// Lista protokołu na kolektorze (protokol.txt, zapisywana po każdej zmianie). Po włożeniu na podstawkę lista jest
    /// zamykana (do-wyslania) i wysyłana do Intermanage, a kolejny skan zaczyna nową. Wysłane i wyczyszczone listy zostają
    /// na kolektorze, więc przypadkowe wyczyszczenie da się cofnąć.
    /// </summary>
    internal class ProtocolStore
    {
        private const int QuietBeforeSend = 15000;
        private const int KeptSent = 20;
        private readonly object gate = new object();
        private readonly List<ProtocolLine> lines = new List<ProtocolLine>();
        private string started = "";
        private int changedAt = Environment.TickCount;
        private bool editing;
        private string lastSent = "";

        private static string FileName { get { return Path.Combine(Settings.Folder, "protokol.txt"); } }
        private static string BackupName { get { return Path.Combine(Settings.Folder, "protokol-wyczyszczony.txt"); } }
        private static string Outbox { get { return Path.Combine(Settings.Folder, "do-wyslania"); } }
        private static string Sent { get { return Path.Combine(Settings.Folder, "wyslane"); } }

        /// <summary>Pozycje od ostatnio dodanej (kopia).</summary>
        public List<ProtocolLine> Lines
        {
            get
            {
                lock (gate)
                {
                    return new List<ProtocolLine>(lines);
                }
            }
        }

        public int Count
        {
            get
            {
                lock (gate)
                {
                    return lines.Count;
                }
            }
        }

        /// <summary>Opis ostatnio wysłanej listy albo pusty tekst.</summary>
        public string LastSent
        {
            get { return lastSent; }
        }

        /// <summary>Trwa wpisywanie ilości: lista nie może się teraz zamknąć.</summary>
        public bool Editing
        {
            set
            {
                lock (gate)
                {
                    editing = value;
                    changedAt = Environment.TickCount;
                }
            }
        }

        public bool CanRestore
        {
            get { return Count == 0 && File.Exists(BackupName); }
        }

        public void Load()
        {
            lock (gate)
            {
                ReadFrom(FileName);
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
                if (lines.Count == 0)
                {
                    started = DateTime.Now.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture);
                }
                lines.Insert(0, line);
                Save();
            }
        }

        /// <summary>Poprawiona ilość pozycji; 0 zdejmuje ją z listy.</summary>
        public void Change(ProtocolLine target, decimal entered)
        {
            lock (gate)
            {
                ProtocolLine line = Find(target);
                if (line == null)
                {
                    return;
                }
                if (entered == 0)
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
                Save();
            }
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

        /// <summary>Jest co wysłać i nikt nie uzupełnia teraz listy.</summary>
        public bool Ready
        {
            get
            {
                lock (gate)
                {
                    bool quiet = !editing && Environment.TickCount - changedAt >= QuietBeforeSend;
                    return Waiting.Length > 0 || (lines.Count > 0 && quiet);
                }
            }
        }

        /// <summary>Zamyka listę do wysłania; fałsz, gdy ktoś ją właśnie uzupełnia.</summary>
        public bool Close()
        {
            lock (gate)
            {
                if (lines.Count == 0)
                {
                    return true;
                }
                if (editing || Environment.TickCount - changedAt < QuietBeforeSend)
                {
                    return false;
                }
                Folder(Outbox);
                Write(Path.Combine(Outbox, Guid.NewGuid().ToString() + ".txt"));
                lines.Clear();
                Save();
                return true;
            }
        }

        /// <summary>Listy zamknięte, jeszcze niewysłane.</summary>
        public static string[] Waiting
        {
            get { return Directory.Exists(Outbox) ? Directory.GetFiles(Outbox, "*.txt") : new string[0]; }
        }

        /// <summary>Wysyła jedną zamkniętą listę; potwierdzona trafia do wysłanych.</summary>
        public void Send(string file)
        {
            string startedAt;
            List<ProtocolLine> content = Parse(file, out startedAt);
            string id = Path.GetFileNameWithoutExtension(file);
            if (content.Count > 0)
            {
                StringBuilder text = new StringBuilder();
                // Od pierwszej zeskanowanej, a na kolektorze lista jest od ostatniej.
                for (int i = content.Count - 1; i >= 0; i--)
                {
                    text.Append(content[i].ToText()).Append('\n');
                }
                Http.Text("POST", "/api/collector/lists", "id=" + id + "&started=" + startedAt + "&lines="
                    + Http.Encode(text.ToString()));
                lastSent = content.Count + " poz., " + DateTime.Now.ToString("HH:mm");
            }
            Folder(Sent);
            string target = Path.Combine(Sent, Path.GetFileName(file));
            if (File.Exists(target))
            {
                File.Delete(target);
            }
            File.Move(file, target);
            Prune();
        }

        private static void Prune()
        {
            List<FileInfo> files = new List<FileInfo>(new DirectoryInfo(Sent).GetFiles("*.txt"));
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

        private ProtocolLine Find(ProtocolLine wanted)
        {
            foreach (ProtocolLine line in lines)
            {
                if (line.SameAs(wanted))
                {
                    return line;
                }
            }
            return null;
        }

        private void Save()
        {
            changedAt = Environment.TickCount;
            if (lines.Count == 0)
            {
                if (File.Exists(FileName))
                {
                    File.Delete(FileName);
                }
                return;
            }
            Write(FileName);
        }

        private void Write(string file)
        {
            string temporary = file + ".tmp";
            using (StreamWriter writer = new StreamWriter(temporary, false, Encoding.UTF8))
            {
                writer.WriteLine("started=" + started);
                foreach (ProtocolLine line in lines)
                {
                    writer.WriteLine(line.ToText());
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
            if (File.Exists(file))
            {
                lines.AddRange(Parse(file, out started));
            }
        }

        private static List<ProtocolLine> Parse(string file, out string startedAt)
        {
            List<ProtocolLine> result = new List<ProtocolLine>();
            startedAt = "";
            using (StreamReader reader = new StreamReader(file, Encoding.UTF8))
            {
                string text;
                while ((text = reader.ReadLine()) != null)
                {
                    if (text.StartsWith("started="))
                    {
                        startedAt = text.Substring(8);
                        continue;
                    }
                    ProtocolLine line = ProtocolLine.Parse(text);
                    if (line != null)
                    {
                        result.Add(line);
                    }
                }
            }
            return result;
        }

        private static void Folder(string path)
        {
            if (!Directory.Exists(path))
            {
                Directory.CreateDirectory(path);
            }
        }
    }
}
