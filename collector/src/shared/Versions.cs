using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;

namespace Intermanage.Collector
{
    /// <summary>Teksty „klucz=wartość” w liniach: ustawienia, wersje i odpowiedzi serwera.</summary>
    internal static class Pairs
    {
        public static Dictionary<string, string> Parse(string text)
        {
            Dictionary<string, string> result = new Dictionary<string, string>();
            foreach (string raw in text.Split('\n'))
            {
                string line = raw.Trim('\r', ' ', '﻿');
                int at = line.IndexOf('=');
                if (at > 0)
                {
                    result[line.Substring(0, at)] = line.Substring(at + 1);
                }
            }
            return result;
        }

        public static string Get(Dictionary<string, string> values, string key)
        {
            string value;
            return values.TryGetValue(key, out value) ? value : "";
        }

        public static Dictionary<string, string> Load(string file)
        {
            if (!File.Exists(file))
            {
                return new Dictionary<string, string>();
            }
            using (StreamReader reader = new StreamReader(file, Encoding.UTF8))
            {
                return Parse(reader.ReadToEnd());
            }
        }

        /// <summary>Zapis przez plik obok, żeby brak prądu w trakcie nie zostawił połowy pliku.</summary>
        public static void Save(string file, Dictionary<string, string> values)
        {
            StringBuilder text = new StringBuilder();
            foreach (KeyValuePair<string, string> entry in values)
            {
                text.Append(entry.Key).Append('=').Append(entry.Value).Append("\r\n");
            }
            string temporary = file + ".tmp";
            using (StreamWriter writer = new StreamWriter(temporary, false, Encoding.UTF8))
            {
                writer.Write(text.ToString());
            }
            if (File.Exists(file))
            {
                File.Delete(file);
            }
            File.Move(temporary, file);
        }
    }

    /// <summary>
    /// Wersje programu na kolektorze. Każda ma swój folder (Intermanage\1.1.0\Kolektor.exe), bo działającego exe nie da
    /// się nadpisać, a wersja.txt mówi, którą uruchomić. Nowa wersja jest na próbie, dopóki nie połączy się z serwerem:
    /// gdy nie da się jej uruchomić, program startowy wraca do poprzedniej.
    /// </summary>
    internal class Versions
    {
        public const string ProgramFile = "Kolektor.exe";
        public const string LauncherFile = "Intermanage.exe";
        private const int TrialStarts = 3;

        private readonly string folder;
        private readonly Dictionary<string, string> values;

        private Versions(string folder)
        {
            this.folder = folder;
            values = Pairs.Load(Path.Combine(folder, "wersja.txt"));
        }

        public static Versions Read(string folder)
        {
            return new Versions(folder);
        }

        /// <summary>Folder pliku exe, który teraz działa.</summary>
        public static string ExeFolder
        {
            get
            {
                string path = Assembly.GetExecutingAssembly().GetName().CodeBase;
                if (path.StartsWith("file:///"))
                {
                    path = path.Substring(Path.DirectorySeparatorChar == '/' ? 7 : 8);
                }
                return Path.GetDirectoryName(path.Replace('/', Path.DirectorySeparatorChar));
            }
        }

        public string Current { get { return Pairs.Get(values, "current"); } }
        public string Previous { get { return Pairs.Get(values, "previous"); } }

        /// <summary>Wersja, która się nie sprawdziła; nie jest pobierana drugi raz.</summary>
        public string Bad { get { return Pairs.Get(values, "bad"); } }

        public bool OnTrial { get { return Pairs.Get(values, "trial").Length > 0; } }

        public string ProgramPath
        {
            get { return Path.Combine(Path.Combine(folder, Current), ProgramFile); }
        }

        public string FolderOf(string version)
        {
            return Path.Combine(folder, version);
        }

        /// <summary>Świeża instalacja: wersja bez próby, bo nie ma do czego wracać.</summary>
        public void Install(string version)
        {
            values["previous"] = Current == version ? "" : Current;
            values["current"] = version;
            values.Remove("trial");
            values.Remove("bad");
            Save();
        }

        /// <summary>Aktualizacja: wersja na próbie.</summary>
        public void Switch(string version)
        {
            values["previous"] = Current;
            values["current"] = version;
            values["trial"] = "0";
            Save();
        }

        /// <summary>Start wersji na próbie; po kilku nieudanych wraca poprzednia.</summary>
        public void Starting()
        {
            if (Current.Length > 0 && !File.Exists(ProgramPath))
            {
                Rollback();
                return;
            }
            if (!OnTrial)
            {
                return;
            }
            int starts = int.Parse(Pairs.Get(values, "trial")) + 1;
            if (starts > TrialStarts)
            {
                Rollback();
                return;
            }
            values["trial"] = starts.ToString();
            Save();
        }

        /// <summary>Wersja działa (połączyła się z serwerem): koniec próby, starsze foldery można usunąć.</summary>
        public void Confirm()
        {
            if (!OnTrial)
            {
                return;
            }
            values.Remove("trial");
            Save();
            foreach (string path in Directory.GetDirectories(folder))
            {
                string name = Path.GetFileName(path);
                if (IsVersion(name) && name != Current && name != Previous)
                {
                    try
                    {
                        Directory.Delete(path, true);
                    }
                    catch (IOException)
                    {
                        // Zostanie do następnego razu.
                    }
                }
            }
        }

        /// <summary>Powrót do poprzedniej wersji; bez niej zostaje bieżąca.</summary>
        public bool Rollback()
        {
            if (Previous.Length == 0 || !File.Exists(Path.Combine(FolderOf(Previous), ProgramFile)))
            {
                return false;
            }
            values["bad"] = Current;
            values["current"] = Previous;
            values["previous"] = "";
            values.Remove("trial");
            Save();
            return true;
        }

        /// <summary>Wersja jak 1.2.0: same liczby i kropki (nazwa folderu przychodzi z serwera).</summary>
        public static bool IsVersion(string text)
        {
            if (text.Length == 0 || text.Length > 20 || text[0] == '.')
            {
                return false;
            }
            foreach (string part in text.Split('.'))
            {
                if (part.Length == 0 || part.Length > 5)
                {
                    return false;
                }
                foreach (char letter in part)
                {
                    if (letter < '0' || letter > '9')
                    {
                        return false;
                    }
                }
            }
            return true;
        }

        public static bool IsNewer(string candidate, string than)
        {
            string[] a = candidate.Split('.');
            string[] b = than.Split('.');
            for (int i = 0; i < Math.Max(a.Length, b.Length); i++)
            {
                int left = i < a.Length ? int.Parse(a[i]) : 0;
                int right = i < b.Length ? int.Parse(b[i]) : 0;
                if (left != right)
                {
                    return left > right;
                }
            }
            return false;
        }

        private void Save()
        {
            Pairs.Save(Path.Combine(folder, "wersja.txt"), values);
        }
    }

    /// <summary>Skróty Windows CE (plik .lnk to tekst: długość, # i ścieżka w cudzysłowie).</summary>
    internal static class Shortcuts
    {
        public const string Startup = "\\Application\\Startup";
        private const string Desktop = "\\Windows\\Desktop";

        public static void ToDesktop(string target)
        {
            // Pulpit jest w pamięci ulotnej: po zimnym restarcie skrót trzeba założyć jeszcze raz.
            Write(Desktop, target, true);
        }

        public static void ToStartup(string target)
        {
            if (!Directory.Exists(Startup))
            {
                Directory.CreateDirectory(Startup);
            }
            Write(Startup, target, true);
        }

        public static void RemoveStartup()
        {
            string file = Path.Combine(Startup, "Intermanage.lnk");
            if (File.Exists(file))
            {
                File.Delete(file);
            }
        }

        private static void Write(string folder, string target, bool overwrite)
        {
            if (!Directory.Exists(folder))
            {
                return;
            }
            string file = Path.Combine(folder, "Intermanage.lnk");
            if (File.Exists(file) && !overwrite)
            {
                return;
            }
            string quoted = "\"" + target + "\"";
            using (FileStream stream = new FileStream(file, FileMode.Create))
            {
                byte[] bytes = Encoding.ASCII.GetBytes(quoted.Length + "#" + quoted);
                stream.Write(bytes, 0, bytes.Length);
            }
        }
    }
}
