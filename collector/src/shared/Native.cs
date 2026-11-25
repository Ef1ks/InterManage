using System;
using System.Runtime.InteropServices;

namespace Intermanage.Collector
{
    /// <summary>
    /// Wywołania Windows CE. Na zwykłym Windowsie (próby programu na komputerze) biblioteki coredll nie ma, więc każde
    /// wywołanie kończy się wtedy bez skutku.
    /// </summary>
    internal static class Native
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct SystemTime
        {
            public ushort Year, Month, DayOfWeek, Day, Hour, Minute, Second, Milliseconds;
        }

        [DllImport("coredll.dll", SetLastError = true)]
        private static extern bool SetLocalTime(ref SystemTime time);

        [DllImport("coredll.dll")]
        private static extern void SystemIdleTimerReset();

        [DllImport("coredll.dll")]
        private static extern bool MessageBeep(int type);

        [DllImport("coredll.dll")]
        private static extern uint GetSystemPowerStatusEx(byte[] status, bool update);

        [DllImport("coredll.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr FindWindow(string className, string windowName);

        [DllImport("coredll.dll")]
        private static extern bool ShowWindow(IntPtr window, int command);

        [DllImport("coredll.dll")]
        private static extern bool MoveWindow(IntPtr window, int x, int y, int width, int height, bool repaint);

        [DllImport("coredll.dll")]
        private static extern bool SetForegroundWindow(IntPtr window);

        [DllImport("coredll.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateMutex(IntPtr attributes, bool owner, string name);

        [DllImport("coredll.dll")]
        private static extern bool CloseHandle(IntPtr handle);

        [DllImport("coredll.dll")]
        private static extern bool PostMessage(IntPtr window, int message, IntPtr wide, IntPtr extra);

        /// <summary>Tytuł okna programu: po nim znajdują je program startowy i instalator.</summary>
        public const string ProgramTitle = "Intermanage kolektor";

        private const string MutexName = "Intermanage.Collector";
        private const int AlreadyExists = 183;
        private const int CloseMessage = 0x0010;

        public static bool OnDevice
        {
            get { return Environment.OSVersion.Platform == PlatformID.WinCE; }
        }

        /// <summary>Zegar kolektora z czasu serwera (po zimnym restarcie kolektor wraca do daty fabrycznej).</summary>
        public static void SetClock(DateTime local)
        {
            if (!OnDevice)
            {
                return;
            }
            SystemTime time = new SystemTime();
            time.Year = (ushort)local.Year;
            time.Month = (ushort)local.Month;
            time.Day = (ushort)local.Day;
            time.Hour = (ushort)local.Hour;
            time.Minute = (ushort)local.Minute;
            time.Second = (ushort)local.Second;
            SetLocalTime(ref time);
        }

        /// <summary>Kolektor nie zasypia w trakcie pobierania danych.</summary>
        public static void StayAwake()
        {
            if (OnDevice)
            {
                SystemIdleTimerReset();
            }
        }

        /// <summary>Zwykły dźwięk (pytanie na ekranie).</summary>
        public static void Beep()
        {
            if (OnDevice)
            {
                MessageBeep(0x20);
            }
        }

        public static void ErrorBeep()
        {
            if (OnDevice)
            {
                MessageBeep(0x10);
            }
        }

        /// <summary>Zasilanie: na podstawce prawda. Na komputerze zawsze prawda.</summary>
        public static bool OnCradle
        {
            get
            {
                if (!OnDevice)
                {
                    return true;
                }
                byte[] status = new byte[24];
                // ACLineStatus: 0 bateria, 1 zasilanie, 255 nieznane (wtedy próbujemy).
                return GetSystemPowerStatusEx(status, true) == 0 || status[0] != 0;
            }
        }

        /// <summary>Bateria w procentach albo -1.</summary>
        public static int Battery
        {
            get
            {
                if (!OnDevice)
                {
                    return -1;
                }
                byte[] status = new byte[24];
                if (GetSystemPowerStatusEx(status, true) == 0 || status[2] > 100)
                {
                    return -1;
                }
                return status[2];
            }
        }

        /// <summary>Okno na cały ekran, także w miejscu schowanego paska zadań.</summary>
        public static void FillScreen(IntPtr window, int width, int height)
        {
            if (OnDevice)
            {
                MoveWindow(window, 0, 0, width, height, true);
            }
        }

        public static void ShowTaskbar(bool show)
        {
            if (!OnDevice)
            {
                return;
            }
            IntPtr bar = FindWindow("HHTaskBar", null);
            if (bar != IntPtr.Zero)
            {
                ShowWindow(bar, show ? 5 : 0);
            }
        }

        /// <summary>Zajmuje miejsce działającego programu; fałsz, gdy program już działa.</summary>
        public static bool TakeInstance()
        {
            if (!OnDevice)
            {
                return true;
            }
            CreateMutex(IntPtr.Zero, true, MutexName);
            return Marshal.GetLastWin32Error() != AlreadyExists;
        }

        public static bool IsRunning
        {
            get
            {
                if (!OnDevice)
                {
                    return false;
                }
                IntPtr mutex = CreateMutex(IntPtr.Zero, false, MutexName);
                bool running = Marshal.GetLastWin32Error() == AlreadyExists;
                if (mutex != IntPtr.Zero)
                {
                    CloseHandle(mutex);
                }
                return running;
            }
        }

        public static void ShowWindowOf(string title)
        {
            IntPtr window = OnDevice ? FindWindow(null, title) : IntPtr.Zero;
            if (window != IntPtr.Zero)
            {
                SetForegroundWindow(window);
            }
        }

        public static void CloseWindowOf(string title)
        {
            IntPtr window = OnDevice ? FindWindow(null, title) : IntPtr.Zero;
            if (window != IntPtr.Zero)
            {
                PostMessage(window, CloseMessage, IntPtr.Zero, IntPtr.Zero);
            }
        }
    }
}
