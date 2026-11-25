using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using Org.BouncyCastle.Crypto.Tls;
using Org.BouncyCastle.Security;
using Org.BouncyCastle.X509;

namespace Intermanage.Collector
{
    /// <summary>Odmowa serwera albo błąd połączenia, z opisem dla pracownika.</summary>
    internal class HttpException : Exception
    {
        public readonly int Status;

        public HttpException(int status, string message)
            : base(message)
        {
            Status = status;
        }
    }

    internal class HttpResponse
    {
        public int Status;
        public Dictionary<string, string> Headers = new Dictionary<string, string>();
        public Stream Body;

        public string Header(string name)
        {
            return Pairs.Get(Headers, name.ToLower());
        }

        public long Length
        {
            get
            {
                string value = Header("content-length");
                return value.Length == 0 ? -1 : long.Parse(value);
            }
        }

        public string ReadText()
        {
            MemoryStream text = new MemoryStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = Body.Read(buffer, 0, buffer.Length)) > 0)
            {
                text.Write(buffer, 0, read);
            }
            byte[] bytes = text.ToArray();
            return Encoding.UTF8.GetString(bytes, 0, bytes.Length);
        }
    }

    /// <summary>
    /// Jedno żądanie HTTPS na połączenie. Szyfrowanie jest w programie (TLS 1.2 z BouncyCastle), bo Windows CE 6.0 zna
    /// najwyżej TLS 1.0 i nie podaje nazwy serwera (SNI), więc Cloudflare go odrzuca.
    /// </summary>
    internal class Http : IDisposable
    {
        private const int IdleLimit = 60000;
        private const int ConnectLimit = 15000;
        private Socket socket;
        private readonly Timer watchdog;
        private volatile int lastActivity = Environment.TickCount;
        private volatile int limit = ConnectLimit;
        private volatile bool timedOut;
        private Stream stream;

        private Http(Uri server)
        {
            bool local = server.Host == "127.0.0.1" || server.Host == "localhost";
            if (server.Scheme != "https" && !local)
            {
                throw new HttpException(0, "Adres serwera musi zaczynać się od https://");
            }
            List<IPAddress> addresses = Network.Addresses(server.Host);
            // Na Windows CE gniazdo nie ma limitu czasu, więc bezczynne połączenie zamyka zegar.
            watchdog = new Timer(new TimerCallback(CloseWhenIdle), null, 2000, 2000);
            try
            {
                IPAddress address = Connect(addresses, server.Port);
                limit = IdleLimit;
                stream = new NetworkStream(socket, false);
                if (server.Scheme == "https")
                {
                    stream = Secure(stream, server.Host, address);
                    Network.Remember(address, addresses);
                }
            }
            catch (Exception error)
            {
                Dispose();
                throw Describe(error);
            }
        }

        private IPAddress Connect(List<IPAddress> addresses, int port)
        {
            Exception last = null;
            foreach (IPAddress address in addresses)
            {
                timedOut = false;
                lastActivity = Environment.TickCount;
                socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
                try
                {
                    socket.Connect(new IPEndPoint(address, port));
                    lastActivity = Environment.TickCount;
                    return address;
                }
                catch (Exception error)
                {
                    last = error;
                    Log.Write("Łączenie z " + address + ": " + Network.Text(error) + (timedOut ? " (limit czasu)" : ""));
                    socket.Close();
                }
            }
            throw new HttpException(0, Network.OwnAddress().Length == 0 ? "Podstawka nie daje sieci"
                : "Brak połączenia z serwerem" + (timedOut ? "" : " (" + Network.Text(last) + ")"));
        }

        public static HttpResponse Send(string method, string path, string form, out Http connection)
        {
            Uri server = new Uri(Settings.Server);
            Http http = new Http(server);
            connection = http;
            try
            {
                byte[] body = form == null ? new byte[0] : Encoding.UTF8.GetBytes(form);
                StringBuilder head = new StringBuilder();
                head.Append(method).Append(' ').Append(path).Append(" HTTP/1.1\r\n");
                head.Append("Host: ").Append(server.Host).Append(server.IsDefaultPort ? "" : ":" + server.Port).Append("\r\n");
                head.Append("User-Agent: IntermanageKolektor/").Append(BuildInfo.Version).Append("\r\n");
                head.Append("X-Collector-Version: ").Append(BuildInfo.Version).Append("\r\n");
                head.Append("Accept-Encoding: identity\r\nConnection: close\r\n");
                if (Settings.Paired)
                {
                    head.Append("Authorization: Bearer ").Append(Settings.Key).Append("\r\n");
                }
                if (form != null)
                {
                    head.Append("Content-Type: application/x-www-form-urlencoded\r\n");
                    head.Append("Content-Length: ").Append(body.Length).Append("\r\n");
                }
                head.Append("\r\n");
                byte[] bytes = Encoding.ASCII.GetBytes(head.ToString());
                http.stream.Write(bytes, 0, bytes.Length);
                if (body.Length > 0)
                {
                    http.stream.Write(body, 0, body.Length);
                }
                http.stream.Flush();
                return http.ReadResponse();
            }
            catch (Exception error)
            {
                HttpException described = http.Describe(error);
                http.Dispose();
                throw described;
            }
        }

        /// <summary>Żądanie z odpowiedzią tekstową „klucz=wartość”.</summary>
        public static Dictionary<string, string> Text(string method, string path, string form)
        {
            Http connection;
            HttpResponse response = Send(method, path, form, out connection);
            using (connection)
            {
                try
                {
                    return Pairs.Parse(response.ReadText());
                }
                catch (Exception error)
                {
                    throw connection.Describe(error);
                }
            }
        }

        /// <summary>Tekst do formularza; Uri.EscapeDataString ma limit długości, a lista bywa długa.</summary>
        public static string Encode(string text)
        {
            StringBuilder result = new StringBuilder();
            foreach (byte value in Encoding.UTF8.GetBytes(text))
            {
                bool plain = (value >= '0' && value <= '9') || (value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z') || value == '-' || value == '.' || value == '_';
                if (plain)
                {
                    result.Append((char)value);
                }
                else
                {
                    result.Append('%').Append(value.ToString("X2"));
                }
            }
            return result.ToString();
        }

        public HttpException Describe(Exception error)
        {
            HttpException known = error as HttpException;
            if (known != null)
            {
                return known;
            }
            Log.Write("Połączenie: " + error.GetType().Name + " " + error.Message);
            if (timedOut)
            {
                return new HttpException(0, "Serwer nie odpowiada");
            }
            if (error is SocketException)
            {
                return new HttpException(0, "Brak połączenia z serwerem");
            }
            return new HttpException(0, "Połączenie przerwane: " + error.Message);
        }

        public void Dispose()
        {
            if (watchdog != null)
            {
                watchdog.Dispose();
            }
            if (socket != null)
            {
                socket.Close();
            }
        }

        private void CloseWhenIdle(object state)
        {
            Socket open = socket;
            if (open != null && !timedOut && Environment.TickCount - lastActivity > limit)
            {
                timedOut = true;
                open.Close();
            }
        }

        private Stream Secure(Stream plain, string host, IPAddress address)
        {
            Client client = new Client(host);
            Trace trace = new Trace(plain);
            try
            {
                TlsClientProtocol protocol = new TlsClientProtocol(trace, new SecureRandom());
                protocol.Connect(client);
                trace.Stop();
                return protocol.Stream;
            }
            catch (Exception error)
            {
                if (client.Rejected != null)
                {
                    throw new HttpException(0, "Niezaufane połączenie: " + client.Rejected);
                }
                trace.WriteTo("Szyfrowanie z " + host + " (" + address + "), etap " + client.Stage + ": " + error);
                string reason = trace.PlainAnswer
                    ? "Sieć nie przepuszcza połączenia (odpowiedź bez szyfrowania)"
                    : "Szyfrowanie: " + error.GetType().Name + " " + error.Message;
                throw new HttpException(0, reason + "\r\n" + address + ", etap " + client.Stage + ", " + trace.Short
                    + "\r\n" + Place(error));
            }
        }

        /// <summary>Skąd błąd: nazwy trzech ostatnich metod ze śladu wywołań.</summary>
        private static string Place(Exception error)
        {
            StringBuilder result = new StringBuilder();
            string trace = error.StackTrace;
            string[] lines = trace == null ? new string[0] : trace.Split('\n');
            for (int i = 0; i < lines.Length && i < 3; i++)
            {
                int end = lines[i].IndexOf('(');
                int dot = end < 0 ? -1 : lines[i].LastIndexOf('.', end);
                if (dot >= 0)
                {
                    result.Append(result.Length > 0 ? " < " : "").Append(lines[i].Substring(dot + 1, end - dot - 1).Trim());
                }
            }
            return result.ToString();
        }

        private HttpResponse ReadResponse()
        {
            HttpResponse response = new HttpResponse();
            string[] first = ReadLine().Split(' ');
            if (first.Length < 2)
            {
                throw new HttpException(0, "Niezrozumiała odpowiedź serwera");
            }
            response.Status = int.Parse(first[1]);
            string line;
            while ((line = ReadLine()).Length > 0)
            {
                int at = line.IndexOf(':');
                if (at > 0)
                {
                    response.Headers[line.Substring(0, at).Trim().ToLower()] = line.Substring(at + 1).Trim();
                }
            }
            response.Body = response.Header("transfer-encoding").ToLower() == "chunked"
                ? (Stream)new ChunkedBody(this)
                : new PlainBody(this, response.Length);
            if (response.Status >= 400)
            {
                throw new HttpException(response.Status, ErrorText(response));
            }
            return response;
        }

        /// <summary>Opis błędu z ProblemDetail serwera (pole „detail”), a bez niego sam kod.</summary>
        private static string ErrorText(HttpResponse response)
        {
            if (response.Header("content-type").IndexOf("json") >= 0)
            {
                string text = response.ReadText();
                int at = text.IndexOf("\"detail\":\"");
                if (at >= 0)
                {
                    StringBuilder result = new StringBuilder();
                    for (int i = at + 10; i < text.Length && text[i] != '"'; i++)
                    {
                        if (text[i] == '\\' && i + 1 < text.Length)
                        {
                            i++;
                        }
                        result.Append(text[i]);
                    }
                    return result.ToString();
                }
            }
            return "Błąd serwera " + response.Status;
        }

        private string ReadLine()
        {
            StringBuilder line = new StringBuilder();
            while (true)
            {
                int value = ReadByte();
                if (value < 0)
                {
                    throw new EndOfStreamException("koniec odpowiedzi");
                }
                if (value == '\n')
                {
                    return line.ToString();
                }
                if (value != '\r')
                {
                    line.Append((char)value);
                }
                if (line.Length > 16384)
                {
                    throw new HttpException(0, "Niezrozumiała odpowiedź serwera");
                }
            }
        }

        private readonly byte[] input = new byte[8192];
        private int inputStart;
        private int inputEnd;

        private int ReadByte()
        {
            return Fill() ? input[inputStart++] : -1;
        }

        private int Read(byte[] buffer, int offset, int count)
        {
            if (!Fill())
            {
                return 0;
            }
            int size = Math.Min(count, inputEnd - inputStart);
            Buffer.BlockCopy(input, inputStart, buffer, offset, size);
            inputStart += size;
            return size;
        }

        private bool Fill()
        {
            if (inputStart < inputEnd)
            {
                return true;
            }
            inputStart = 0;
            inputEnd = stream.Read(input, 0, input.Length);
            lastActivity = Environment.TickCount;
            return inputEnd > 0;
        }

        /// <summary>Treść odpowiedzi tylko do odczytu po kolei.</summary>
        private abstract class ResponseBody : Stream
        {
            protected readonly Http Connection;

            protected ResponseBody(Http connection)
            {
                Connection = connection;
            }

            public override bool CanRead
            {
                get { return true; }
            }

            public override bool CanSeek
            {
                get { return false; }
            }

            public override bool CanWrite
            {
                get { return false; }
            }

            public override long Length
            {
                get { throw new NotSupportedException(); }
            }

            public override long Position
            {
                get { throw new NotSupportedException(); }
                set { throw new NotSupportedException(); }
            }

            public override void Flush()
            {
            }

            public override long Seek(long offset, SeekOrigin origin)
            {
                throw new NotSupportedException();
            }

            public override void SetLength(long value)
            {
                throw new NotSupportedException();
            }

            public override void Write(byte[] buffer, int offset, int count)
            {
                throw new NotSupportedException();
            }
        }

        private class PlainBody : ResponseBody
        {
            private long left;

            public PlainBody(Http connection, long length)
                : base(connection)
            {
                left = length;
            }

            public override int Read(byte[] buffer, int offset, int count)
            {
                if (left == 0 || count == 0)
                {
                    return 0;
                }
                int read = Connection.Read(buffer, offset, left > 0 ? (int)Math.Min(count, left) : count);
                if (left > 0)
                {
                    if (read == 0)
                    {
                        throw new EndOfStreamException("odpowiedź urwana");
                    }
                    left -= read;
                }
                return read;
            }
        }

        private class ChunkedBody : ResponseBody
        {
            private long left;
            private bool first = true;
            private bool done;

            public ChunkedBody(Http connection)
                : base(connection)
            {
            }

            public override int Read(byte[] buffer, int offset, int count)
            {
                if (done || count == 0)
                {
                    return 0;
                }
                if (left == 0)
                {
                    if (!first)
                    {
                        Connection.ReadLine();
                    }
                    first = false;
                    string size = Connection.ReadLine();
                    int end = size.IndexOf(';');
                    left = Convert.ToInt64(end >= 0 ? size.Substring(0, end) : size.Trim(), 16);
                    if (left == 0)
                    {
                        done = true;
                        return 0;
                    }
                }
                int read = Connection.Read(buffer, offset, (int)Math.Min(count, left));
                if (read == 0)
                {
                    throw new EndOfStreamException("odpowiedź urwana");
                }
                left -= read;
                return read;
            }
        }

        private class Client : DefaultTlsClient, TlsAuthentication
        {
            public string Rejected;

            /// <summary>Dokąd doszło uzgadnianie: 0 początek, 1 odpowiedź serwera, 2 certyfikat, 3 sprawdzony.</summary>
            public int Stage;
            private readonly string host;

            public Client(string host)
            {
                this.host = host;
            }

            public override ProtocolVersion MinimumVersion
            {
                get { return ProtocolVersion.TLSv12; }
            }

            public override int[] GetCipherSuites()
            {
                // ChaCha20 pierwszy: procesor kolektora nie ma sprzętowego AES.
                return new int[] {
                    CipherSuite.DRAFT_TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.DRAFT_TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
                };
            }

            public override IDictionary GetClientExtensions()
            {
                IDictionary extensions = TlsExtensionsUtilities.EnsureExtensionsInitialised(base.GetClientExtensions());
                ArrayList names = new ArrayList();
                names.Add(new ServerName(NameType.host_name, host));
                TlsExtensionsUtilities.AddServerNameExtension(extensions, new ServerNameList(names));
                return extensions;
            }

            public override void NotifySelectedCipherSuite(int selectedCipherSuite)
            {
                Stage = 1;
                base.NotifySelectedCipherSuite(selectedCipherSuite);
            }

            public override TlsAuthentication GetAuthentication()
            {
                return this;
            }

            public void NotifyServerCertificate(Certificate serverCertificate)
            {
                Stage = 2;
                ArrayList chain = new ArrayList();
                foreach (Org.BouncyCastle.Asn1.X509.X509CertificateStructure item
                    in serverCertificate.GetCertificateList())
                {
                    chain.Add(new X509Certificate(item));
                }
                string problem;
                try
                {
                    problem = Certificates.Check(chain, host);
                }
                catch (Exception error)
                {
                    problem = "nie udało się sprawdzić certyfikatu: " + error.Message;
                }
                if (problem.Length > 0)
                {
                    Rejected = problem;
                    throw new TlsFatalAlert(AlertDescription.bad_certificate);
                }
                Stage = 3;
            }

            public TlsCredentials GetClientCredentials(CertificateRequest certificateRequest)
            {
                return null;
            }
        }
    }
}
