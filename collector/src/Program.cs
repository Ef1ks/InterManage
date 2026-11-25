using System;
using System.Windows.Forms;

namespace Intermanage.Collector
{
    internal static class Program
    {
        [MTAThread]
        private static void Main()
        {
            if (!Native.TakeInstance())
            {
                Native.ShowWindowOf(Native.ProgramTitle);
                return;
            }
            try
            {
                Settings.Load();
                Application.Run(new MainForm());
            }
            catch (Exception error)
            {
                Log.Write("Błąd programu: " + error);
                MessageBox.Show("Błąd programu: " + error.Message + "\r\nSzczegóły w pliku dziennik.txt", "Intermanage");
            }
            finally
            {
                Native.ShowTaskbar(true);
            }
        }
    }
}
