using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace Intermanage.Collector
{
    /// <summary>
    /// Skaner kolektora przez sterownik Motoroli (SCNAPI32.dll): dopóki czeka odczyt, przycisk skanera włącza laser.
    /// Czekanie jest w osobnym wątku, krótkimi odczytami, żeby po uśpieniu kolektora otworzyć skaner od nowa. Gdy
    /// sterownika nie da się użyć, uruchamia ScanWedge z kolektora (wpisuje kody jak klawiatura).
    /// </summary>
    internal class Scanner
    {
        /// <summary>Zeskanowany kod; wołane z wątku skanera.</summary>
        public event EventHandler Scanned;

        private const int BufferSize = 4096;
        /// <summary>Długi odczyt: nowy zaczęty przy wciśniętym przycisku nie włączyłby lasera.</summary>
        private const int ReadLimit = 30000;
        private const int FastFailure = 1000;
        private const int Reopen = 2000;
        private const int DeviceAttempts = 5;

        // Początek SCAN_BUFFER: StructInfo (8 B), dwDataBuffSize, dwOffsetDataBuff, dwDataLength, dwTimeout, dwStatus.
        private const int SizeField = 8;
        private const int OffsetField = 12;
        private const int LengthField = 16;

        private volatile bool stopping;
        private volatile bool active = true;
        private volatile string code = "";
        private volatile string state = "nie uruchomiony";

        public string Code { get { return code; } }

        /// <summary>Opis do „O programie”.</summary>
        public string State { get { return state; } }

        public void Start()
        {
            if (!Native.OnDevice)
            {
                state = "brak (komputer)";
                return;
            }
            Thread thread = new Thread(new ThreadStart(Run));
            thread.IsBackground = true;
            thread.Start();
        }

        public void Stop()
        {
            stopping = true;
        }

        /// <summary>Skaner czyta tylko, gdy okno programu jest na wierzchu.</summary>
        public bool Active
        {
            set { active = value; }
        }

        private void Run()
        {
            int failures = 0;
            while (!stopping)
            {
                try
                {
                    Driver driver = new Driver();
                    try
                    {
                        Read(driver, ref failures);
                    }
                    finally
                    {
                        driver.Close();
                    }
                }
                catch (Exception error)
                {
                    failures++;
                    state = "błąd: " + error.Message;
                    Log.Write("Skaner: " + error);
                    bool missing = error is MissingMethodException || error is NotSupportedException;
                    if (missing || failures == DeviceAttempts)
                    {
                        string wedge = StartWedge();
                        state = wedge.Length > 0 ? "ScanWedge (" + wedge + ")" : state + ", brak ScanWedge";
                        Log.Write("Skaner: " + state);
                        return;
                    }
                }
                Thread.Sleep(Reopen);
            }
        }

        private void Read(Driver driver, ref int failures)
        {
            state = "działa (" + driver.Name + ")";
            while (!stopping)
            {
                if (!active)
                {
                    Thread.Sleep(200);
                    continue;
                }
                int from = Environment.TickCount;
                uint result = driver.ReadWait(ReadLimit);
                if (result == 0)
                {
                    failures = 0;
                    string text = driver.Text();
                    if (active && text.Length > 0 && Scanned != null)
                    {
                        code = text;
                        Scanned(this, EventArgs.Empty);
                    }
                }
                else if (Environment.TickCount - from < FastFailure)
                {
                    // Nie upłynął czas oczekiwania, więc to błąd (np. po uśpieniu): otwórz skaner od nowa.
                    Log.Write("Skaner: odczyt 0x" + result.ToString("x8"));
                    return;
                }
                else
                {
                    failures = 0;
                }
            }
        }

        /// <summary>Uruchamia ScanWedge albo DataWedge, jeśli jest na kolektorze; zwraca nazwę pliku.</summary>
        private static string StartWedge()
        {
            foreach (string folder in new string[] { "\\Application", "\\Windows", "\\Platform" })
            {
                string found = Find(folder, 3);
                if (found.Length > 0)
                {
                    try
                    {
                        Process.Start(found, "");
                        return found;
                    }
                    catch (Exception error)
                    {
                        Log.Write("Skaner: " + found + ": " + error.Message);
                    }
                }
            }
            return "";
        }

        private static string Find(string folder, int depth)
        {
            if (!Directory.Exists(folder))
            {
                return "";
            }
            foreach (string name in new string[] { "ScanWedge.exe", "DataWedge.exe", "DWDemo.exe" })
            {
                string file = Path.Combine(folder, name);
                if (File.Exists(file))
                {
                    return file;
                }
            }
            if (depth > 0)
            {
                foreach (string inner in Directory.GetDirectories(folder))
                {
                    string found = Find(inner, depth - 1);
                    if (found.Length > 0)
                    {
                        return found;
                    }
                }
            }
            return "";
        }

        /// <summary>
        /// Otwarty skaner i jego bufor. Część funkcji biblioteki bywa z końcówką _W (wersja Unicode), więc każda jest
        /// wołana pod nazwą, która na tym kolektorze istnieje.
        /// </summary>
        private class Driver
        {
            private static bool plainOpen;
            private static bool plainAllocate;
            private static bool plainFree;
            private static bool plainRead;
            private IntPtr handle = IntPtr.Zero;
            private IntPtr buffer = IntPtr.Zero;

            public string Name
            {
                get { return "SCNAPI32" + (plainOpen ? "" : " W") + (plainAllocate ? "" : " W") + (plainRead ? "" : " W"); }
            }

            public Driver()
            {
                try
                {
                    Check("SCAN_Open", Open("SCN1:", out handle));
                    Check("SCAN_Enable", SCAN_Enable(handle));
                    buffer = Allocate(BufferSize);
                    if (buffer == IntPtr.Zero)
                    {
                        throw new IOException("SCAN_AllocateBuffer");
                    }
                    int size = Marshal.ReadInt32(buffer, SizeField);
                    int offset = Marshal.ReadInt32(buffer, OffsetField);
                    if (size < BufferSize || size > BufferSize * 4 || offset < 28 || offset > 4096)
                    {
                        throw new NotSupportedException("inny układ bufora: " + size + ", " + offset);
                    }
                }
                catch (Exception)
                {
                    Close();
                    throw;
                }
            }

            public uint ReadWait(int limit)
            {
                if (!plainRead)
                {
                    try
                    {
                        return SCAN_ReadLabelWait_W(handle, buffer, limit);
                    }
                    catch (MissingMethodException)
                    {
                        plainRead = true;
                    }
                }
                return SCAN_ReadLabelWait(handle, buffer, limit);
            }

            public string Text()
            {
                int length = Marshal.ReadInt32(buffer, LengthField);
                if (length <= 0 || length > BufferSize)
                {
                    return "";
                }
                byte[] bytes = new byte[length];
                Marshal.Copy(new IntPtr(buffer.ToInt32() + Marshal.ReadInt32(buffer, OffsetField)), bytes, 0, length);
                StringBuilder text = new StringBuilder();
                foreach (byte value in bytes)
                {
                    if (value > 32 && value < 127)
                    {
                        text.Append((char)value);
                    }
                }
                return text.ToString();
            }

            public void Close()
            {
                if (buffer != IntPtr.Zero)
                {
                    Free(buffer);
                    buffer = IntPtr.Zero;
                }
                if (handle != IntPtr.Zero)
                {
                    SCAN_Disable(handle);
                    SCAN_Close(handle);
                    handle = IntPtr.Zero;
                }
            }

            private static uint Open(string device, out IntPtr opened)
            {
                if (!plainOpen)
                {
                    try
                    {
                        return SCAN_Open_W(device, out opened);
                    }
                    catch (MissingMethodException)
                    {
                        plainOpen = true;
                    }
                }
                return SCAN_Open(device, out opened);
            }

            private static IntPtr Allocate(int size)
            {
                if (!plainAllocate)
                {
                    try
                    {
                        return SCAN_AllocateBuffer_W(false, size);
                    }
                    catch (MissingMethodException)
                    {
                        plainAllocate = true;
                    }
                }
                return SCAN_AllocateBuffer(false, size);
            }

            private static void Free(IntPtr allocated)
            {
                if (!plainFree)
                {
                    try
                    {
                        SCAN_DeallocateBuffer_W(allocated);
                        return;
                    }
                    catch (MissingMethodException)
                    {
                        plainFree = true;
                    }
                }
                SCAN_DeallocateBuffer(allocated);
            }

            private static void Check(string name, uint result)
            {
                if (result != 0)
                {
                    throw new IOException(name + " 0x" + result.ToString("x8"));
                }
            }

            [DllImport("SCNAPI32.dll", CharSet = CharSet.Unicode, EntryPoint = "SCAN_Open_W")]
            private static extern uint SCAN_Open_W(string device, out IntPtr handle);

            [DllImport("SCNAPI32.dll", CharSet = CharSet.Unicode, EntryPoint = "SCAN_Open")]
            private static extern uint SCAN_Open(string device, out IntPtr handle);

            [DllImport("SCNAPI32.dll")]
            private static extern uint SCAN_Close(IntPtr handle);

            [DllImport("SCNAPI32.dll")]
            private static extern uint SCAN_Enable(IntPtr handle);

            [DllImport("SCNAPI32.dll")]
            private static extern uint SCAN_Disable(IntPtr handle);

            [DllImport("SCNAPI32.dll", EntryPoint = "SCAN_AllocateBuffer_W")]
            private static extern IntPtr SCAN_AllocateBuffer_W(bool text, int size);

            [DllImport("SCNAPI32.dll", EntryPoint = "SCAN_AllocateBuffer")]
            private static extern IntPtr SCAN_AllocateBuffer(bool text, int size);

            [DllImport("SCNAPI32.dll", EntryPoint = "SCAN_DeallocateBuffer_W")]
            private static extern uint SCAN_DeallocateBuffer_W(IntPtr buffer);

            [DllImport("SCNAPI32.dll", EntryPoint = "SCAN_DeallocateBuffer")]
            private static extern uint SCAN_DeallocateBuffer(IntPtr buffer);

            [DllImport("SCNAPI32.dll", EntryPoint = "SCAN_ReadLabelWait_W")]
            private static extern uint SCAN_ReadLabelWait_W(IntPtr handle, IntPtr buffer, int limit);

            [DllImport("SCNAPI32.dll", EntryPoint = "SCAN_ReadLabelWait")]
            private static extern uint SCAN_ReadLabelWait(IntPtr handle, IntPtr buffer, int limit);
        }
    }
}
