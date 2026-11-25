using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace Intermanage.Collector
{
    /// <summary>
    /// Adresy serwera i stan sieci. Po powrocie na podstawkę kolektor bywa w sieci bez działającego DNS, więc program
    /// pamięta ostatnie dobre adresy. Tożsamość serwera i tak sprawdza certyfikat, nie adres.
    /// </summary>
    internal static class Network
    {
        private const int DnsLimit = 12000;
        private const string DefaultHost = "intermanage.pl";
        /// <summary>Adresy Cloudflare domeny, gdy DNS nie działa, a program nie połączył się jeszcze nigdy.</summary>
        private static readonly string[] DefaultAddresses = new string[] { "104.21.30.139", "172.67.172.248" };

        private static string dnsError = "";

        /// <summary>Adresy do wypróbowania po kolei: z DNS, potem zapamiętane, na końcu domyślne.</summary>
        public static List<IPAddress> Addresses(string host)
        {
            List<IPAddress> result = new List<IPAddress>();
            if (host.Length > 0 && host[0] >= '0' && host[0] <= '9')
            {
                AppendText(result, host);
                return result;
            }
            foreach (IPAddress address in Lookup(host))
            {
                Append(result, address);
            }
            if (result.Count == 0)
            {
                Log.Write("DNS " + host + ": " + dnsError);
                foreach (string text in Settings.Addresses.Split(','))
                {
                    AppendText(result, text);
                }
                if (host == DefaultHost)
                {
                    foreach (string text in DefaultAddresses)
                    {
                        AppendText(result, text);
                    }
                }
            }
            if (result.Count == 0)
            {
                throw new HttpException(0, "Brak sieci (" + dnsError + ")");
            }
            return result;
        }

        /// <summary>Adres, który odpowiedział dobrym certyfikatem, na początek zapamiętanych.</summary>
        public static void Remember(IPAddress good, List<IPAddress> all)
        {
            StringBuilder text = new StringBuilder(good.ToString());
            foreach (IPAddress address in all)
            {
                if (!address.Equals(good))
                {
                    text.Append(',').Append(address.ToString());
                }
            }
            Settings.Addresses = text.ToString();
        }

        /// <summary>Własny adres kolektora albo pusty tekst, gdy nie ma sieci.</summary>
        public static string OwnAddress()
        {
            StringBuilder result = new StringBuilder();
            foreach (IPAddress address in Lookup(""))
            {
                string text = address.ToString();
                if (!text.StartsWith("127.") && text != "0.0.0.0")
                {
                    result.Append(result.Length > 0 ? ", " : "").Append(text);
                }
            }
            return result.ToString();
        }

        public static string Text(Exception error)
        {
            SocketException socket = error as SocketException;
            return socket != null ? "błąd " + socket.ErrorCode : error.GetType().Name;
        }

        /// <summary>Opis do „Stan sieci”: wystarczy zrzut, żeby wiedzieć, co nie działa.</summary>
        public static string Report(Uri server)
        {
            StringBuilder text = new StringBuilder();
            text.Append("Podstawka: ").Append(Native.OnCradle ? "tak" : "nie (zasilanie z baterii)");
            string own = OwnAddress();
            text.Append("\nAdres kolektora: ").Append(own.Length > 0 ? own : "brak");
            int from = Environment.TickCount;
            List<IPAddress> found = Lookup(server.Host);
            text.Append("\nDNS: ").Append(found.Count > 0 ? found[0].ToString() : "nie działa, " + dnsError);
            text.Append(" (").Append(Environment.TickCount - from).Append(" ms)");
            text.Append("\nZapamiętane: ").Append(Settings.Addresses.Length > 0 ? Settings.Addresses : "brak");
            from = Environment.TickCount;
            try
            {
                Http.Text("GET", "/api/auth/csrf", null);
                text.Append("\nSerwer: odpowiada");
            }
            catch (HttpException error)
            {
                text.Append("\nSerwer: ").Append(error.Status > 0 ? "odpowiada " + error.Status : error.Message);
            }
            text.Append(" (").Append(Environment.TickCount - from).Append(" ms)");
            text.Append("\nZegar: ").Append(DateTime.Now.ToString("dd.MM.yyyy HH:mm"));
            return text.ToString();
        }

        /// <summary>Adresy nazwy (pusta = ten kolektor) z limitem czasu, bo DNS na Windows CE potrafi zawisnąć.</summary>
        private static List<IPAddress> Lookup(string host)
        {
            Query query = new Query(host);
            Thread thread = new Thread(new ThreadStart(query.Run));
            thread.IsBackground = true;
            thread.Start();
            if (!query.Done.WaitOne(DnsLimit, false))
            {
                dnsError = "DNS nie odpowiada";
                return new List<IPAddress>();
            }
            if (query.Error.Length > 0)
            {
                dnsError = query.Error;
            }
            return query.Found;
        }

        private static void AppendText(List<IPAddress> result, string text)
        {
            if (text.Length > 0)
            {
                try
                {
                    Append(result, IPAddress.Parse(text));
                }
                catch (FormatException)
                {
                    // Uszkodzony wpis w ustawieniach.
                }
            }
        }

        private static void Append(List<IPAddress> result, IPAddress address)
        {
            if (!result.Contains(address))
            {
                result.Add(address);
            }
        }

        private class Query
        {
            public readonly ManualResetEvent Done = new ManualResetEvent(false);
            public readonly List<IPAddress> Found = new List<IPAddress>();
            public string Error = "";
            private readonly string host;

            public Query(string host)
            {
                this.host = host;
            }

            public void Run()
            {
                try
                {
                    string name = host.Length == 0 ? Dns.GetHostName() : host;
                    foreach (IPAddress address in Dns.GetHostEntry(name).AddressList)
                    {
                        if (address.AddressFamily == AddressFamily.InterNetwork)
                        {
                            Found.Add(address);
                        }
                    }
                    if (Found.Count == 0)
                    {
                        Error = "DNS bez adresu";
                    }
                }
                catch (Exception error)
                {
                    Error = "DNS " + Text(error);
                }
                Done.Set();
            }
        }
    }
}
