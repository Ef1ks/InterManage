using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Collector
{
    /// <summary>
    /// Program startowy (Intermanage.exe): uruchamia wersję z wersja.txt i kończy. Sam się nie zmienia przy
    /// aktualizacjach, więc uszkodzona nowa wersja nie odcina kolektora: po kilku nieudanych startach wraca poprzednia.
    /// </summary>
    internal static class Launcher
    {
        private const int WaitForClose = 20000;

        [MTAThread]
        private static void Main(string[] args)
        {
            try
            {
                // „czekaj”: program właśnie się zamyka, żeby zmienić wersję.
                int from = Environment.TickCount;
                while (args.Length > 0 && Native.IsRunning && Environment.TickCount - from < WaitForClose)
                {
                    Thread.Sleep(250);
                }
                if (Native.IsRunning)
                {
                    Native.ShowWindowOf(Native.ProgramTitle);
                    return;
                }
                string folder = Versions.ExeFolder;
                if (Native.OnDevice)
                {
                    Shortcuts.ToDesktop(Path.Combine(folder, Versions.LauncherFile));
                }
                Versions versions = Versions.Read(folder);
                versions.Starting();
                Start(versions);
            }
            catch (Exception error)
            {
                MessageBox.Show("Nie udało się uruchomić programu: " + error.Message
                    + "\r\nUruchom jeszcze raz instalator.", "Intermanage");
            }
        }

        private static void Start(Versions versions)
        {
            try
            {
                Process.Start(versions.ProgramPath, "");
            }
            catch (Exception)
            {
                if (!versions.Rollback())
                {
                    throw;
                }
                Process.Start(versions.ProgramPath, "");
            }
        }
    }
}
