using System;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace Intermanage.Collector
{
    /// <summary>
    /// Połączenie z drukarką Bluetooth po adresie MAC: gniazdo RFCOMM ze stosu Bluetooth Microsoftu (Windows CE), profil
    /// portu szeregowego (SPP). Bez parowania w ustawieniach systemu; PIN, gdy drukarka go wymaga, podaje się stosowi
    /// tuż przed łączeniem. Kolektor ze stosem StoneStreet One nie ma takich gniazd (błąd 10047).
    /// </summary>
    internal sealed class BluetoothLink : IDisposable
    {
        private const AddressFamily Bluetooth = (AddressFamily)32;
        private const ProtocolType Rfcomm = (ProtocolType)3;
        /// <summary>Usługa portu szeregowego (SPP); stos sam odczytuje z drukarki jej kanał RFCOMM.</summary>
        private static readonly Guid SerialPort = new Guid("00001101-0000-1000-8000-00805F9B34FB");
        private const int BthPowerOff = 0;
        private const int BthConnectable = 1;
        private const int ReplyWait = 3000;

        private readonly Socket socket;

        private BluetoothLink(Socket socket)
        {
            this.socket = socket;
        }

        /// <summary>
        /// Łączy z drukarką (włącza Bluetooth kolektora, gdy jest wyłączony). Najpierw przez usługę SPP, a gdy stos jej
        /// nie znajdzie, wprost kanałem 1, na którym drukarki Zebra mają port szeregowy.
        /// </summary>
        public static BluetoothLink Connect(string mac, string pin)
        {
            long address = ParseMac(mac);
            RadioOn();
            if (pin != null && pin.Length > 0)
            {
                SetPin(address, pin);
            }
            SocketException first = null;
            foreach (int channel in new int[] { 0, 1 })
            {
                Socket socket = null;
                try
                {
                    socket = new Socket(Bluetooth, SocketType.Stream, Rfcomm);
                    socket.Connect(new BluetoothEndPoint(address, channel == 0 ? SerialPort : Guid.Empty, channel));
                    return new BluetoothLink(socket);
                }
                catch (SocketException error)
                {
                    if (socket != null)
                    {
                        socket.Close();
                    }
                    if (error.ErrorCode == 10047)
                    {
                        throw new BluetoothException("Ten kolektor ma inny stos Bluetooth (bez gniazd Microsoftu). "
                            + "Trzeba będzie łączyć przez port COM.", error.ErrorCode);
                    }
                    if (first == null)
                    {
                        first = error;
                    }
                    Log.Write("Bluetooth " + Bar(mac) + (channel == 0 ? " (SPP)" : " (kanał 1)") + ": " + error.ErrorCode);
                }
            }
            throw new BluetoothException(Describe(first.ErrorCode), first.ErrorCode);
        }

        public void Send(string text)
        {
            byte[] bytes = Encoding.ASCII.GetBytes(text);
            int sent = 0;
            while (sent < bytes.Length)
            {
                sent += socket.Send(bytes, sent, bytes.Length - sent, SocketFlags.None);
            }
        }

        /// <summary>
        /// Ustawienie drukarki Zebra (SGD, działa w każdym języku drukarki); odpowiedź to wartość w cudzysłowie.
        /// Pusty tekst, gdy drukarka nie odpowiedziała.
        /// </summary>
        public string GetVar(string name)
        {
            Send("! U1 getvar \"" + name + "\"\r\n");
            StringBuilder reply = new StringBuilder();
            byte[] buffer = new byte[256];
            int quotes = 0;
            int until = Environment.TickCount + ReplyWait;
            while (quotes < 2 && Environment.TickCount - until < 0)
            {
                if (!socket.Poll(200 * 1000, SelectMode.SelectRead))
                {
                    continue;
                }
                int read = socket.Receive(buffer, 0, buffer.Length, SocketFlags.None);
                if (read <= 0)
                {
                    break;
                }
                for (int i = 0; i < read && quotes < 2; i++)
                {
                    char c = (char)buffer[i];
                    if (c == '"')
                    {
                        quotes++;
                    }
                    else if (quotes == 1)
                    {
                        reply.Append(c);
                    }
                }
            }
            string value = reply.ToString().Trim();
            return quotes == 2 && value != "?" ? value : "";
        }

        public void Dispose()
        {
            try
            {
                socket.Shutdown(SocketShutdown.Both);
            }
            catch (SocketException)
            {
                // Drukarka mogła już zamknąć połączenie.
            }
            socket.Close();
        }

        /// <summary>AC3FA4123456 → AC:3F:A4:12:34:56.</summary>
        public static string Bar(string mac)
        {
            if (mac == null || mac.Length != 12)
            {
                return mac;
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 12; i += 2)
            {
                if (i > 0)
                {
                    text.Append(':');
                }
                text.Append(mac, i, 2);
            }
            return text.ToString();
        }

        private static long ParseMac(string mac)
        {
            if (mac == null || mac.Length != 12)
            {
                throw new BluetoothException("Zły adres drukarki", 0);
            }
            return long.Parse(mac, System.Globalization.NumberStyles.HexNumber);
        }

        private static void RadioOn()
        {
            try
            {
                int mode;
                if (BthGetMode(out mode) == 0 && mode == BthPowerOff)
                {
                    Log.Write("Bluetooth był wyłączony, włączam");
                    BthSetMode(BthConnectable);
                    Thread.Sleep(2500);
                }
            }
            catch (MissingMethodException)
            {
                // Bez btdrt.dll: inny stos, rozstrzygnie próba połączenia.
            }
            catch (DllNotFoundException)
            {
            }
        }

        private static void SetPin(long address, string pin)
        {
            byte[] bytes = Encoding.ASCII.GetBytes(pin);
            try
            {
                int result = BthSetPIN(ref address, bytes.Length, bytes);
                if (result != 0)
                {
                    Log.Write("Bluetooth: PIN nie przyjęty (" + result + ")");
                }
            }
            catch (MissingMethodException)
            {
            }
            catch (DllNotFoundException)
            {
            }
        }

        private static string Describe(int code)
        {
            switch (code)
            {
                case 10050:
                    return "Bluetooth kolektora nie działa. Włącz go w ustawieniach albo uruchom kolektor ponownie.";
                case 10013:
                    return "Drukarka odrzuciła połączenie: sprawdź PIN w Intermanage albo ustawienia bezpieczeństwa drukarki.";
                case 10060:
                case 10064:
                case 10065:
                    return "Drukarka nie odpowiada. Sprawdź, czy jest włączona, ma Bluetooth i stoi blisko.";
                case 10061:
                    return "Drukarka odmówiła połączenia. Może jest połączona z innym kolektorem albo telefonem.";
                case 10048:
                    return "Kolektor ma już połączenie z tą drukarką. Spróbuj za chwilę.";
                default:
                    return "Nie udało się połączyć z drukarką (błąd " + code + ").";
            }
        }

        [DllImport("btdrt.dll")]
        private static extern int BthGetMode(out int mode);

        [DllImport("btdrt.dll")]
        private static extern int BthSetMode(int mode);

        [DllImport("btdrt.dll")]
        private static extern int BthSetPIN(ref long address, int length, byte[] pin);

        /// <summary>SOCKADDR_BTH (ws2bth.h, spakowany do bajtu): rodzina, adres, usługa, kanał; 30 bajtów.</summary>
        private sealed class BluetoothEndPoint : EndPoint
        {
            private const int Size = 30;
            private readonly long address;
            private readonly Guid service;
            private readonly int channel;

            public BluetoothEndPoint(long address, Guid service, int channel)
            {
                this.address = address;
                this.service = service;
                this.channel = channel;
            }

            public override AddressFamily AddressFamily
            {
                get { return Bluetooth; }
            }

            public override SocketAddress Serialize()
            {
                SocketAddress result = new SocketAddress(Bluetooth, Size);
                byte[] bytes = BitConverter.GetBytes(address);
                for (int i = 0; i < 8; i++)
                {
                    result[2 + i] = bytes[i];
                }
                bytes = service.ToByteArray();
                for (int i = 0; i < 16; i++)
                {
                    result[10 + i] = bytes[i];
                }
                bytes = BitConverter.GetBytes(channel);
                for (int i = 0; i < 4; i++)
                {
                    result[26 + i] = bytes[i];
                }
                return result;
            }

            public override EndPoint Create(SocketAddress socketAddress)
            {
                long remote = 0;
                for (int i = 7; i >= 0; i--)
                {
                    remote = (remote << 8) | socketAddress[2 + i];
                }
                return new BluetoothEndPoint(remote, Guid.Empty, socketAddress[26]);
            }
        }
    }

    internal sealed class BluetoothException : Exception
    {
        public readonly int Code;

        public BluetoothException(string message, int code)
            : base(message)
        {
            Code = code;
        }
    }
}
