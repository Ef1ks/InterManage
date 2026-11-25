using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using Org.BouncyCastle.Crypto.Digests;

namespace Intermanage.Collector
{
    /// <summary>
    /// Nowa wersja programu z serwera, tym samym sprawdzonym połączeniem co dane. Pliki idą do folderu obok i są
    /// sprawdzane skrótem SHA-256, a dopiero gotowy folder wersji może być uruchomiony. Plik taki sam jak w bieżącej
    /// wersji (biblioteka szyfrowania) jest kopiowany, a nie pobierany.
    /// </summary>
    internal static class Updater
    {
        private const int MaxFile = 20 * 1024 * 1024;

        /// <summary>Program uruchomiony z folderu wersji przez program startowy (z instalatora).</summary>
        public static bool Managed
        {
            get { return Settings.Folder != Versions.ExeFolder; }
        }

        public static bool Wanted(string latest)
        {
            return Managed && Versions.IsVersion(latest) && Versions.IsNewer(latest, BuildInfo.Version)
                && latest != Versions.Read(Settings.Folder).Bad;
        }

        /// <summary>Pobiera wersję z serwera do jej folderu; zwraca numer wersji.</summary>
        public static string Download(Progress progress)
        {
            Dictionary<string, string> manifest = Http.Text("GET", "/api/collector/program", null);
            string version = Pairs.Get(manifest, "version");
            if (!Wanted(version))
            {
                return "";
            }
            string temporary = Path.Combine(Settings.Folder, "nowa");
            Clear(temporary);
            Directory.CreateDirectory(temporary);
            foreach (string name in Pairs.Get(manifest, "files").Split(','))
            {
                if (!IsFileName(name))
                {
                    throw new HttpException(0, "Zła nazwa pliku nowej wersji");
                }
                string expected = Pairs.Get(manifest, name + ".sha256").ToLower();
                string target = Path.Combine(temporary, name);
                string present = Path.Combine(Versions.ExeFolder, name);
                if (File.Exists(present) && Hash(present) == expected)
                {
                    File.Copy(present, target, true);
                }
                else
                {
                    Fetch(name, version, target, long.Parse(Pairs.Get(manifest, name + ".size")), progress);
                }
                if (Hash(target) != expected)
                {
                    throw new HttpException(0, "Nowa wersja pobrała się z błędem, spróbuję jeszcze raz");
                }
            }
            if (!File.Exists(Path.Combine(temporary, Versions.ProgramFile)))
            {
                throw new HttpException(0, "Nowa wersja jest niepełna");
            }
            string folder = Versions.Read(Settings.Folder).FolderOf(version);
            Clear(folder);
            Directory.Move(temporary, folder);
            return version;
        }

        public delegate void Progress(int percent);

        /// <summary>Przełącza na pobraną wersję i uruchamia program startowy; program trzeba zaraz zamknąć.</summary>
        public static void Apply(string version)
        {
            Versions.Read(Settings.Folder).Switch(version);
            Restart();
        }

        /// <summary>Wersja na próbie nie łączy się z serwerem: powrót do poprzedniej.</summary>
        public static bool GiveUp()
        {
            return Versions.Read(Settings.Folder).Rollback();
        }

        public static void Restart()
        {
            Process.Start(Path.Combine(Settings.Folder, Versions.LauncherFile), "czekaj");
        }

        private static void Fetch(string name, string version, string target, long size, Progress progress)
        {
            if (size <= 0 || size > MaxFile)
            {
                throw new HttpException(0, "Zły rozmiar pliku nowej wersji");
            }
            Http connection;
            HttpResponse response = Http.Send("GET", "/api/collector/program/" + name + "?version=" + version, null,
                out connection);
            using (connection)
            {
                try
                {
                    byte[] buffer = new byte[16384];
                    long written = 0;
                    using (FileStream output = new FileStream(target, FileMode.Create, FileAccess.Write))
                    {
                        int read;
                        while (written < size && (read = response.Body.Read(buffer, 0, buffer.Length)) > 0)
                        {
                            output.Write(buffer, 0, read);
                            written += read;
                            Native.StayAwake();
                            progress((int)(Math.Min(written, size) * 100 / size));
                        }
                    }
                }
                catch (Exception error)
                {
                    throw connection.Describe(error);
                }
            }
        }

        private static bool IsFileName(string name)
        {
            foreach (char letter in name)
            {
                bool plain = (letter >= 'a' && letter <= 'z') || (letter >= 'A' && letter <= 'Z')
                    || (letter >= '0' && letter <= '9') || letter == '.' || letter == '-' || letter == '_';
                if (!plain)
                {
                    return false;
                }
            }
            return name.Length > 0 && name[0] != '.';
        }

        private static void Clear(string folder)
        {
            if (Directory.Exists(folder))
            {
                Directory.Delete(folder, true);
            }
        }

        private static string Hash(string file)
        {
            Sha256Digest digest = new Sha256Digest();
            byte[] buffer = new byte[16384];
            using (FileStream input = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                int read;
                while ((read = input.Read(buffer, 0, buffer.Length)) > 0)
                {
                    digest.BlockUpdate(buffer, 0, read);
                }
            }
            byte[] hash = new byte[digest.GetDigestSize()];
            digest.DoFinal(hash, 0);
            return Hex(hash);
        }

        public static string Hex(byte[] bytes)
        {
            StringBuilder result = new StringBuilder();
            foreach (byte value in bytes)
            {
                result.Append(value.ToString("x2"));
            }
            return result.ToString();
        }
    }
}
