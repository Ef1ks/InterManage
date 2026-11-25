using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace Intermanage.Agent;

/// <summary>Działający odtwarzacz makr (AutoJob); wersja z pliku exe, np. 0.64.</summary>
internal sealed class RunningPlayer
{
    public RunningPlayer(string name, Version? version)
    {
        Name = name;
        Version = version;
    }

    public string Name { get; }
    public Version? Version { get; }
}

/// <summary>Wspólne części uruchamiania makr: odtwarzacz, Alt+E, hasło Mercalys i stan komputera.</summary>
internal static class MacroRunner
{
    /// <summary>Jedno makro naraz na komputerze: aktualizacja danych i makra zlecone z przeglądarki.</summary>
    public static readonly SemaphoreSlim Gate = new(1, 1);

    /// <summary>Programy, które na pewno nie są odtwarzaczem (opis exe czytamy raz, a nie co kilka sekund).</summary>
    private static readonly HashSet<string> NotPlayers = new(StringComparer.OrdinalIgnoreCase);

    private static int lastPid;
    private static RunningPlayer? last;

    /// <summary>
    /// Działający odtwarzacz makr albo null. Znaleziony wcześniej wystarczy sprawdzić po numerze procesu, bez
    /// przeglądania wszystkich programów co kilka sekund.
    /// </summary>
    public static RunningPlayer? FindPlayer()
    {
        lock (Gate)
        {
            if (last != null)
            {
                try
                {
                    using var process = Process.GetProcessById(lastPid);
                    if (process.ProcessName == last.Name) return last;
                }
                catch (Exception)
                {
                    // Odtwarzacz zakończony albo uruchomiony ponownie (Esc): szukamy od nowa.
                }
            }
            return last = Scan();
        }
    }

    /// <summary>
    /// Szuka odtwarzacza wśród programów. Rozpoznaje go po nazwie pliku albo po opisie programu (osobny plik bywa
    /// nazwany player.exe, player-v60.exe albo AutoJob.exe).
    /// </summary>
    private static RunningPlayer? Scan()
    {
        int session;
        using (var current = Process.GetCurrentProcess()) session = current.SessionId;
        foreach (var process in Process.GetProcesses())
        {
            using (process)
            {
                try
                {
                    if (process.SessionId != session) continue;
                    var name = process.ProcessName;
                    var byName = name.StartsWith("player", StringComparison.OrdinalIgnoreCase)
                        || name.StartsWith("autojob", StringComparison.OrdinalIgnoreCase);
                    var path = PlayerHost.ImagePath(process.Id);
                    if (path == null)
                    {
                        if (!byName) continue;
                        lastPid = process.Id;
                        return new RunningPlayer(name, null);
                    }
                    lock (NotPlayers)
                    {
                        if (!byName && NotPlayers.Contains(path)) continue;
                    }
                    FileVersionInfo? info = null;
                    try { info = FileVersionInfo.GetVersionInfo(path); }
                    catch (Exception) { /* plik na odłączonym dysku sieciowym */ }
                    if (byName || string.Equals(info?.ProductName?.Trim(), PlayerHost.Product,
                        StringComparison.OrdinalIgnoreCase))
                    {
                        lastPid = process.Id;
                        return new RunningPlayer(name,
                            info == null ? null : new Version(info.FileMajorPart, info.FileMinorPart));
                    }
                    if (info == null) continue;
                    lock (NotPlayers) NotPlayers.Add(path);
                }
                catch (Exception)
                {
                    // Proces mógł się zakończyć albo nie mamy do niego dostępu.
                }
            }
        }
        return null;
    }

    /// <summary>Hasło Mercalys w pierwszym poleceniu makra (odtwarzacz od 0.54 sam uruchamia i loguje Mercalys).</summary>
    public static string WithPassword(string macro, string openLine, string? password)
    {
        if (string.IsNullOrEmpty(password) || password!.Contains("|") || password.Length > 20) return macro;
        return macro.Replace(openLine + "\r\n", openLine + "|" + password + "\r\n");
    }

    private const byte LeftAlt = 0xA4;
    private const byte KeyE = 0x45;
    private const uint KeyUp = 0x0002;
    private const byte Escape = 0x1B;

    /// <summary>Lewy Alt+E jak pracownik: odtwarzacz wykonuje makro ze schowka po puszczeniu Alta.</summary>
    public static void PressAltE()
    {
        keybd_event(LeftAlt, 0, 0, UIntPtr.Zero);
        keybd_event(KeyE, 0, 0, UIntPtr.Zero);
        keybd_event(KeyE, 0, KeyUp, UIntPtr.Zero);
        keybd_event(LeftAlt, 0, KeyUp, UIntPtr.Zero);
    }

    /// <summary>Esc zatrzymuje trwające makro odtwarzacza.</summary>
    public static void PressEsc()
    {
        keybd_event(Escape, 0, 0, UIntPtr.Zero);
        keybd_event(Escape, 0, KeyUp, UIntPtr.Zero);
    }

    /// <summary>Chwila ostatniego ruchu myszy albo klawiatury (licznik milisekund Windows).</summary>
    public static uint LastInput()
    {
        var info = new LastInputInfo { Size = (uint)Marshal.SizeOf(typeof(LastInputInfo)) };
        return GetLastInputInfo(ref info) ? info.Time : unchecked((uint)Environment.TickCount);
    }

    public static int IdleSeconds() => (int)(unchecked((uint)Environment.TickCount - LastInput()) / 1000);

    /// <summary>
    /// Ekran zablokowany (Win+L, ekran logowania, wygaszacz z hasłem): Windows nie przyjmuje wtedy klawiszy makra.
    /// </summary>
    public static bool DesktopLocked()
    {
        var desktop = OpenInputDesktop(0, false, DesktopReadObjects);
        if (desktop == IntPtr.Zero) return true;
        try
        {
            var name = new System.Text.StringBuilder(256);
            return !GetUserObjectInformation(desktop, ObjectName, name, name.Capacity * 2, out _)
                || !string.Equals(name.ToString(), "Default", StringComparison.OrdinalIgnoreCase);
        }
        finally
        {
            CloseDesktop(desktop);
        }
    }

    private const uint DesktopReadObjects = 0x0001;
    private const int ObjectName = 2;

    [StructLayout(LayoutKind.Sequential)]
    private struct LastInputInfo
    {
        public uint Size;
        public uint Time;
    }

    [DllImport("user32.dll")]
    private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);

    [DllImport("user32.dll")]
    private static extern bool GetLastInputInfo(ref LastInputInfo info);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool CloseDesktop(IntPtr desktop);

    [DllImport("user32.dll", EntryPoint = "GetUserObjectInformationW", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool GetUserObjectInformation(IntPtr handle, int index, System.Text.StringBuilder info,
        int length, out int needed);
}
