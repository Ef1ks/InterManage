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

    /// <summary>Produkt z <c>;@Ahk2Exe-SetName</c> w player.ahk.</summary>
    private const string Product = "Intermanage Makra";

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
    /// Szuka odtwarzacza po nazwie programu (player.exe, player-v60.exe, AutoJob.exe). Zagląda tylko do niego (wersja
    /// z pliku), innych programów nie otwiera.
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
                    var name = process.ProcessName;
                    if (!name.StartsWith("player", StringComparison.OrdinalIgnoreCase)
                        && !name.StartsWith("autojob", StringComparison.OrdinalIgnoreCase)) continue;
                    if (process.SessionId != session) continue;
                    var path = ImagePath(process.Id);
                    FileVersionInfo? info = null;
                    try { info = path == null ? null : FileVersionInfo.GetVersionInfo(path); }
                    catch (Exception) { /* plik na odłączonym dysku sieciowym */ }
                    // Inny program o podobnej nazwie (np. odtwarzacz wideo).
                    if (info != null && !string.Equals(info.ProductName?.Trim(), Product,
                        StringComparison.OrdinalIgnoreCase) && !name.StartsWith("autojob",
                        StringComparison.OrdinalIgnoreCase)
                        && !string.Equals(name, "player", StringComparison.OrdinalIgnoreCase)) continue;
                    lastPid = process.Id;
                    return new RunningPlayer(name,
                        info == null ? null : new Version(info.FileMajorPart, info.FileMinorPart));
                }
                catch (Exception)
                {
                    // Proces mógł się zakończyć.
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

    private const uint QueryLimitedInformation = 0x1000;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, int processId);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool QueryFullProcessImageName(IntPtr process, int flags, System.Text.StringBuilder name,
        ref int size);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>Ścieżka pliku procesu (odtwarzacza albo Mercalys), także 64-bitowego.</summary>
    public static string? ImagePath(int processId)
    {
        var handle = OpenProcess(QueryLimitedInformation, false, processId);
        if (handle == IntPtr.Zero) return null;
        try
        {
            var size = 1024;
            var name = new System.Text.StringBuilder(size);
            return QueryFullProcessImageName(handle, 0, name, ref size) ? name.ToString() : null;
        }
        finally
        {
            CloseHandle(handle);
        }
    }
}
