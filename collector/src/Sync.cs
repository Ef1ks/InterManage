using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Threading;
using Org.BouncyCastle.Crypto.Digests;
using Org.BouncyCastle.Utilities.Zlib;

namespace Intermanage.Collector
{
    /// <summary>
    /// Pobieranie danych w tle: kolektor ma sieć tylko na podstawce, więc program sprawdza serwer po postawieniu na niej
    /// i co kilka minut, a plik pobiera tylko w nowej wersji. Nowy plik zastępuje poprzedni dopiero po sprawdzeniu
    /// skrótu, więc zdjęcie z podstawki w trakcie niczego nie psuje.
    /// </summary>
    internal class Sync
    {
        private const int CheckEvery = 5 * 60 * 1000;
        private const int RetryEvery = 30 * 1000;
        /// <summary>Zaraz po postawieniu na podstawce sieć dopiero wstaje, więc ponawia częściej.</summary>
        private const int FirstRetryEvery = 8 * 1000;
        private const int FirstRetries = 2 * 60 * 1000;
        /// <summary>Próba bez żadnego postępu: zawieszona (np. po uśpieniu kolektora).</summary>
        private const int StuckAfter = 3 * 60 * 1000;
        private const int Step = 2000;
        /// <summary>Tyle na podstawce bez połączenia i wersja na próbie wraca do poprzedniej.</summary>
        private const int TrialLimit = 15 * 60 * 1000;

        /// <summary>Zmienił się stan (Status, Percent); wołane z wątku w tle.</summary>
        public event EventHandler Changed;

        /// <summary>Gotowy nowy plik (katalog.new): właściciel zamyka stary katalog i woła Install.</summary>
        public event EventHandler Downloaded;

        /// <summary>Wersja na próbie nie łączy się z serwerem i została cofnięta: program trzeba uruchomić ponownie.</summary>
        public event EventHandler RolledBack;

        /// <summary>Klucz przestał działać: kolektor trzeba połączyć nowym kodem.</summary>
        public event EventHandler Unpaired;

        /// <summary>Lista protokołu została zamknięta albo wysłana.</summary>
        public event EventHandler ListsChanged;

        private readonly ProtocolStore protocols;
        private readonly AutoResetEvent wake = new AutoResetEvent(false);
        private volatile bool requested;
        private volatile bool stopping;
        private volatile string status = "";
        private volatile int percent = -1;
        private volatile string version = "";
        private volatile string readyVersion = "";
        private bool onTrial;
        private int cradleSince;
        private volatile int worker;
        private volatile int busySince;
        private volatile bool busy;

        /// <summary>Opis trwającej pracy albo ostatniego błędu; pusty, gdy wszystko jest w porządku.</summary>
        public string Status { get { return status; } }

        /// <summary>Pobrana nowa wersja programu, gotowa do uruchomienia, albo pusty tekst.</summary>
        public string ReadyVersion { get { return readyVersion; } }

        /// <summary>Postęp pobierania 0-100 albo -1.</summary>
        public int Percent { get { return percent; } }

        public static string CatalogFile
        {
            get { return Path.Combine(Settings.Folder, "katalog.dat"); }
        }

        public static string NewCatalogFile
        {
            get { return Path.Combine(Settings.Folder, "katalog.new"); }
        }

        public Sync(ProtocolStore protocols)
        {
            this.protocols = protocols;
        }

        public void Start(string currentVersion)
        {
            version = currentVersion;
            onTrial = Updater.Managed && Versions.Read(Settings.Folder).OnTrial;
            StartWorker();
        }

        private void StartWorker()
        {
            worker++;
            busy = false;
            Thread thread = new Thread(new ThreadStart(Run));
            thread.IsBackground = true;
            thread.Priority = ThreadPriority.BelowNormal;
            thread.Start();
        }

        /// <summary>Wołane z zegara okna: zawieszoną próbę zostawia i zaczyna od nowa w nowym wątku.</summary>
        public void RestartWhenStuck()
        {
            if (busy && !stopping && Environment.TickCount - busySince > StuckAfter)
            {
                Log.Write("Pobieranie nie odpowiada, zaczynam od nowa");
                Set("", -1);
                requested = true;
                StartWorker();
            }
        }

        public void Stop()
        {
            stopping = true;
            wake.Set();
        }

        /// <summary>„Pobierz dane teraz”.</summary>
        public void Request()
        {
            requested = true;
            wake.Set();
        }

        /// <summary>Podmienia plik danych; katalog musi być zamknięty.</summary>
        public void Install(string newVersion)
        {
            if (File.Exists(CatalogFile))
            {
                File.Delete(CatalogFile);
            }
            File.Move(NewCatalogFile, CatalogFile);
            version = newVersion;
        }

        /// <summary>Połączenie ze sklepem kodem z Ustawień sklepu.</summary>
        public static void Pair(string code)
        {
            Dictionary<string, string> answer = Http.Text("POST", "/api/collector/pair", "code=" + code
                + "&device=" + Uri.EscapeDataString(DeviceName()) + "&version=" + BuildInfo.Version);
            SetClock(answer);
            string key = Pairs.Get(answer, "key");
            if (key.Length == 0)
            {
                throw new HttpException(0, "Niezrozumiała odpowiedź serwera");
            }
            Settings.Pair(key, Pairs.Get(answer, "store"), Pairs.Get(answer, "name"));
        }

        private static string DeviceName()
        {
            try
            {
                return System.Net.Dns.GetHostName();
            }
            catch (Exception)
            {
                return "kolektor";
            }
        }

        private void Run()
        {
            int me = worker;
            int nextAt = Environment.TickCount;
            int checkedAt = Environment.TickCount - RetryEvery;
            bool wasOnCradle = false;
            while (!stopping && me == worker)
            {
                bool onCradle = Native.OnCradle;
                if (!onCradle || !wasOnCradle)
                {
                    cradleSince = Environment.TickCount;
                }
                bool due = onCradle && (!wasOnCradle || Environment.TickCount - nextAt >= 0);
                due = due || (onCradle && Environment.TickCount - checkedAt > RetryEvery && protocols.Ready);
                wasOnCradle = onCradle;
                if (Settings.Paired && (requested || due))
                {
                    bool manual = requested;
                    requested = false;
                    busySince = Environment.TickCount;
                    checkedAt = Environment.TickCount;
                    busy = true;
                    bool done = Check(manual);
                    if (me != worker)
                    {
                        return;
                    }
                    busy = false;
                    bool early = Environment.TickCount - cradleSince < FirstRetries;
                    // Lista uzupełniana na podstawce pójdzie chwilę po ostatnim skanie.
                    bool lists = protocols.Count > 0 || ProtocolStore.Waiting.Length > 0;
                    nextAt = Environment.TickCount
                        + (done && !lists ? CheckEvery : early || done ? FirstRetryEvery : RetryEvery);
                }
                if (onTrial && onCradle && Environment.TickCount - cradleSince > TrialLimit && Updater.GiveUp())
                {
                    Log.Write("Wersja " + BuildInfo.Version + " nie łączy się z serwerem, wracam do poprzedniej");
                    onTrial = false;
                    Raise(RolledBack);
                }
                wake.WaitOne(Step, false);
            }
        }

        private bool Check(bool manual)
        {
            try
            {
                if (manual)
                {
                    Set("Łączę z serwerem…", -1);
                }
                Dictionary<string, string> answer = Http.Text("GET", "/api/collector/status", null);
                SetClock(answer);
                Contacted();
                SendLists();
                // Najpierw program: dane w nowym układzie przeczyta dopiero nowa wersja.
                string program = Pairs.Get(answer, "program");
                if (readyVersion != program && Updater.Wanted(program))
                {
                    Set("Pobieram nową wersję…", 0);
                    readyVersion = Updater.Download(new Updater.Progress(ShowProgramProgress));
                }
                string latest = Pairs.Get(answer, "catalog");
                if (latest.Length > 0 && latest != version)
                {
                    Download();
                }
                Set("", -1);
                return true;
            }
            catch (HttpException error)
            {
                // Odmowa zapory (bez opisu serwera) nie kasuje klucza.
                if (error.Status == 403 && error.Message.IndexOf("sparowany") >= 0)
                {
                    Contacted();
                    Settings.Unpair();
                    Set("", -1);
                    Raise(Unpaired);
                    return false;
                }
                Set(error.Message, -1);
                return false;
            }
            catch (Exception error)
            {
                Log.Write("Pobieranie: " + error);
                Set(error is IOException ? "Nie udało się zapisać danych" : "Błąd pobierania: " + error.Message, -1);
                return false;
            }
        }

        private void Download()
        {
            Set("Pobieram dane…", 0);
            Http connection;
            HttpResponse response = Http.Send("GET", "/api/collector/catalog", null, out connection);
            using (connection)
            {
                string expected = response.Header("x-catalog-sha256").ToLower();
                long size = long.Parse(response.Header("x-catalog-size"));
                Sha256Digest digest = new Sha256Digest();
                long written = 0;
                try
                {
                    ZInputStream plain = new ZInputStream(response.Body);
                    byte[] buffer = new byte[16384];
                    using (FileStream output = new FileStream(NewCatalogFile, FileMode.Create, FileAccess.Write))
                    {
                        int read;
                        while (written < size && (read = plain.Read(buffer, 0, buffer.Length)) > 0)
                        {
                            output.Write(buffer, 0, read);
                            digest.BlockUpdate(buffer, 0, read);
                            written += read;
                            Native.StayAwake();
                            int now = (int)(written * 100 / size);
                            if (now != percent)
                            {
                                Set("Pobieram dane…", now);
                            }
                        }
                    }
                }
                catch (Exception error)
                {
                    throw connection.Describe(error);
                }
                byte[] hash = new byte[digest.GetDigestSize()];
                digest.DoFinal(hash, 0);
                if (written != size || Updater.Hex(hash) != expected)
                {
                    throw new HttpException(0, "Dane pobrały się z błędem, spróbuję jeszcze raz");
                }
            }
            string downloaded;
            using (Catalog check = new Catalog(NewCatalogFile))
            {
                downloaded = check.Version;
            }
            Raise(Downloaded);
            if (version != downloaded)
            {
                throw new HttpException(0, "Nie udało się podmienić danych");
            }
            Http.Text("POST", "/api/collector/synced", "version=" + downloaded);
        }

        /// <summary>Na podstawce: zamyka bieżącą listę i wysyła wszystkie zamknięte.</summary>
        private void SendLists()
        {
            bool changed = false;
            if (Native.OnCradle && protocols.Count > 0)
            {
                changed = protocols.Close();
            }
            foreach (string file in ProtocolStore.Waiting)
            {
                Set("Wysyłam listę…", -1);
                protocols.Send(file);
                changed = true;
            }
            if (changed)
            {
                Raise(ListsChanged);
            }
        }

        private void ShowProgramProgress(int done)
        {
            Set("Pobieram nową wersję…", done);
        }

        /// <summary>Wersja łączy się z serwerem, więc da się ją zaktualizować: koniec próby.</summary>
        private void Contacted()
        {
            if (onTrial)
            {
                onTrial = false;
                Versions.Read(Settings.Folder).Confirm();
            }
        }

        private static void SetClock(Dictionary<string, string> answer)
        {
            string time = Pairs.Get(answer, "time");
            if (time.Length != 14)
            {
                return;
            }
            DateTime server = DateTime.ParseExact(time, "yyyyMMddHHmmss", CultureInfo.InvariantCulture);
            if (Math.Abs((server - DateTime.Now).TotalSeconds) > 60)
            {
                Native.SetClock(server);
            }
        }

        private void Set(string text, int progress)
        {
            if (status == text && percent == progress)
            {
                return;
            }
            status = text;
            percent = progress;
            busySince = Environment.TickCount;
            Raise(Changed);
        }

        private void Raise(EventHandler handler)
        {
            if (handler != null)
            {
                handler(this, EventArgs.Empty);
            }
        }
    }
}
