using System;
using System.IO;
using System.Text;

namespace Intermanage.Collector
{
    /// <summary>
    /// Zapis początku rozmowy z serwerem (zanim zacznie się szyfrowanie), żeby po nieudanym połączeniu było wiadomo, co
    /// odpowiedziała sieć. Po udanym połączeniu niczego nie zapisuje.
    /// </summary>
    internal class Trace : Stream
    {
        private const int Limit = 8192;
        private const int Shown = 8;
        private readonly Stream inner;
        private readonly MemoryStream received = new MemoryStream();
        private readonly MemoryStream sent = new MemoryStream();
        private bool on = true;

        public Trace(Stream inner)
        {
            this.inner = inner;
        }

        public void Stop()
        {
            on = false;
        }

        /// <summary>Krótko na ekran: ile bajtów przyszło i pierwsze z nich.</summary>
        public string Short
        {
            get { return "wysłano " + sent.Length + " B, odebrano " + received.Length + " B: " + Hex(received, Shown); }
        }

        /// <summary>Odpowiedź, która nie jest TLS (np. strona zapory), zaczyna się zwykłym tekstem.</summary>
        public bool PlainAnswer
        {
            get
            {
                byte[] bytes = received.ToArray();
                return bytes.Length > 0 && (bytes[0] < 20 || bytes[0] > 24);
            }
        }

        public void WriteTo(string title)
        {
            Log.Write(title + "\r\n wysłano: " + Hex(sent, Limit) + "\r\n odebrano: " + Hex(received, Limit));
        }

        public override bool CanRead { get { return true; } }
        public override bool CanSeek { get { return false; } }
        public override bool CanWrite { get { return true; } }
        public override long Length { get { throw new NotSupportedException(); } }

        public override long Position
        {
            get { throw new NotSupportedException(); }
            set { throw new NotSupportedException(); }
        }

        public override void Flush()
        {
            inner.Flush();
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            int read = inner.Read(buffer, offset, count);
            Keep(received, buffer, offset, read);
            return read;
        }

        public override void Write(byte[] buffer, int offset, int count)
        {
            inner.Write(buffer, offset, count);
            Keep(sent, buffer, offset, count);
        }

        public override long Seek(long offset, SeekOrigin origin)
        {
            throw new NotSupportedException();
        }

        public override void SetLength(long value)
        {
            throw new NotSupportedException();
        }

        public override void Close()
        {
            inner.Close();
        }

        private void Keep(MemoryStream target, byte[] buffer, int offset, int count)
        {
            int room = Limit - (int)target.Length;
            if (on && count > 0 && room > 0)
            {
                target.Write(buffer, offset, Math.Min(count, room));
            }
        }

        private static string Hex(MemoryStream source, int limit)
        {
            byte[] bytes = source.ToArray();
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < bytes.Length && i < limit; i++)
            {
                result.Append(bytes[i].ToString("x2"));
            }
            return result.ToString();
        }
    }
}
