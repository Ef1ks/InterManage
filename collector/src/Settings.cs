using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace Intermanage.Collector
{
    /// <summary>
    /// Ustawienia obok programu (ustawienia.txt). Klucz kolektora daje sam odczyt kartoteki jednego sklepu i da się go
    /// wyłączyć w Intermanage, więc leży zwykłym tekstem.
    /// </summary>
    internal static class Settings
    {
        private const string DefaultServer = "https://intermanage.pl";
        private static Dictionary<string, string> values = new Dictionary<string, string>();

        /// <summary>
        /// Folder ustawień i danych: nad folderem wersji (program z instalatora) albo folder samego programu.
        /// </summary>
        public static string Folder
        {
            get
            {
                string folder = Versions.ExeFolder;
                return Versions.IsVersion(Path.GetFileName(folder)) ? Path.GetDirectoryName(folder) : folder;
            }
        }

        private static string FileName
        {
            get { return Path.Combine(Folder, "ustawienia.txt"); }
        }

        public static string Server
        {
            get { return Value("server", DefaultServer).TrimEnd('/'); }
        }

        public static string Key
        {
            get { return Value("key", ""); }
        }

        /// <summary>Adresy IP, pod którymi ostatnio odpowiadał serwer (na czas, gdy DNS na podstawce nie działa).</summary>
        public static string Addresses
        {
            get { return Value("addresses", ""); }
            set
            {
                if (Value("addresses", "") != value)
                {
                    values["addresses"] = value;
                    Save();
                }
            }
        }

        public static bool Paired
        {
            get { return Key.Length > 0; }
        }

        public static string StoreNumber
        {
            get { return Value("store", ""); }
        }

        public static string CollectorName
        {
            get { return Value("name", ""); }
        }

        public static void Load()
        {
            values = Pairs.Load(FileName);
        }

        public static void Pair(string key, string store, string name)
        {
            values["key"] = key;
            values["store"] = store;
            values["name"] = name;
            Save();
        }

        /// <summary>Klucz przestał działać (kolektor usunięty w Intermanage): potrzebny nowy kod.</summary>
        public static void Unpair()
        {
            values.Remove("key");
            Save();
        }

        private static string Value(string key, string fallback)
        {
            string value = Pairs.Get(values, key);
            return value.Length == 0 ? fallback : value;
        }

        private static void Save()
        {
            Pairs.Save(FileName, values);
        }
    }

    /// <summary>Dziennik obok programu (dziennik.txt), żeby dało się sprawdzić przyczynę błędu; do 64 KB.</summary>
    internal static class Log
    {
        private const int MaxBytes = 64 * 1024;
        private static readonly object Gate = new object();

        public static void Write(string text)
        {
            lock (Gate)
            {
                try
                {
                    string file = Path.Combine(Settings.Folder, "dziennik.txt");
                    bool append = File.Exists(file) && new FileInfo(file).Length < MaxBytes;
                    using (StreamWriter writer = new StreamWriter(file, append, Encoding.UTF8))
                    {
                        writer.WriteLine(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + text);
                    }
                }
                catch (IOException)
                {
                    // Bez dziennika program działa dalej.
                }
            }
        }
    }
}
