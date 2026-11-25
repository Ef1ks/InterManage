using System;
using System.Collections.Generic;
using System.Threading;
using System.Windows.Forms;

namespace Intermanage.Collector
{
    /// <summary>
    /// Menu, Drukarka: lista drukarek sklepu z Intermanage. Wybór (cyfra, Enter albo skan kodu z adresem z wydruku
    /// drukarki) łączy się z nią i pokazuje jej stan; udane połączenie zapamiętuje drukarkę na tym kolektorze.
    /// </summary>
    internal partial class MainForm
    {
        private readonly HomeMenu printerList = new HomeMenu();
        private List<Printer> shownPrinters = new List<Printer>();
        private volatile bool checkingPrinter;
        private Printer checkedPrinter;
        private string printerResult = "";
        private bool printerFailed;

        private void SetUpPrinters()
        {
            printerList.Chosen += new EventHandler(OnPrinterChosen);
            sync.PrintersChanged += new EventHandler(OnSyncPrinters);
            Printers.Load();
        }

        private void OnPrinterMenu(object sender, EventArgs e)
        {
            if (mode == Mode.Pair || Entering || view == ask)
            {
                FocusInput();
                return;
            }
            input.Text = "";
            searched = "";
            mode = Mode.Printer;
            ShowPrinters();
        }

        private void ShowPrinters()
        {
            shownPrinters = Printers.List;
            Printer chosen = Printers.Chosen;
            if (chosen != null && chosen.Unlisted)
            {
                shownPrinters.Insert(0, chosen);
            }
            if (shownPrinters.Count == 0)
            {
                message.Show("Brak drukarek", "Dodaj drukarki w Intermanage: Kolektory, Urządzenia, Drukarki Bluetooth. "
                    + "Kolektor dostanie listę na podstawce.\n\nMożesz też zeskanować kod z adresem z wydruku "
                    + "konfiguracji drukarki.", false);
                ShowView(message);
                return;
            }
            string[] titles = new string[shownPrinters.Count];
            string[] notes = new string[shownPrinters.Count];
            int selected = 0;
            for (int i = 0; i < shownPrinters.Count; i++)
            {
                Printer printer = shownPrinters[i];
                bool isChosen = chosen != null && chosen.Mac == printer.Mac;
                titles[i] = printer.Name + (isChosen ? "  (wybrana)" : "");
                notes[i] = BluetoothLink.Bar(printer.Mac) + (printer.Model.Length > 0 ? "  " + printer.Model : "");
                if (isChosen)
                {
                    selected = i;
                }
            }
            printerList.Show(titles, notes);
            printerList.Select(selected);
            ShowView(printerList);
        }

        private void OnPrinterChosen(object sender, EventArgs e)
        {
            ChoosePrinter(printerList.Selected);
        }

        private void ChoosePrinter(int index)
        {
            if (index >= 0 && index < shownPrinters.Count)
            {
                CheckPrinter(shownPrinters[index]);
            }
        }

        /// <summary>Skan na ekranie drukarek: adres z kodu (QR z wydruku drukarki, naklejka) wybiera drukarkę.</summary>
        private void OnPrinterScanned(string code)
        {
            if (checkingPrinter)
            {
                return;
            }
            string mac = Printers.MacIn(code);
            if (mac.Length == 0)
            {
                Native.ErrorBeep();
                message.Show("To nie jest adres drukarki", "Zeskanuj kod z adresem Bluetooth z wydruku konfiguracji "
                    + "drukarki albo wybierz drukarkę z listy.", true);
                ShowView(message);
                return;
            }
            Printer printer = Printers.Find(mac);
            if (printer == null)
            {
                printer = new Printer();
                printer.Mac = mac;
                printer.Name = "Drukarka spoza listy";
                printer.Unlisted = true;
            }
            CheckPrinter(printer);
        }

        private void CheckPrinter(Printer printer)
        {
            if (checkingPrinter)
            {
                return;
            }
            checkingPrinter = true;
            checkedPrinter = printer;
            message.Show("Łączę z: " + printer.Name, BluetoothLink.Bar(printer.Mac)
                + "\n\nDrukarka musi być włączona i stać blisko. Pierwsze połączenie trwa do kilkunastu sekund.", false);
            ShowView(message);
            Thread thread = new Thread(new ThreadStart(RunPrinterCheck));
            thread.IsBackground = true;
            thread.Start();
        }

        private void RunPrinterCheck()
        {
            Printer printer = checkedPrinter;
            try
            {
                printerResult = Printers.Check(printer);
                printerFailed = false;
            }
            catch (BluetoothException error)
            {
                printerResult = error.Message;
                printerFailed = true;
            }
            catch (Exception error)
            {
                Log.Write("Drukarka " + BluetoothLink.Bar(printer.Mac) + ": " + error);
                printerResult = "Nie udało się połączyć z drukarką: " + error.Message;
                printerFailed = true;
            }
            OnUi(new EventHandler(OnPrinterChecked));
        }

        private void OnPrinterChecked(object sender, EventArgs e)
        {
            checkingPrinter = false;
            Printer printer = checkedPrinter;
            if (printerFailed)
            {
                Native.ErrorBeep();
                message.Show("Brak połączenia: " + printer.Name, printerResult
                    + "\n\nEnter: spróbuj jeszcze raz, Esc: lista drukarek.", true);
            }
            else
            {
                Settings.ChoosePrinter(printer.Mac, printer.Name);
                message.Show("Wybrana drukarka: " + printer.Name, printerResult
                    + (printer.Unlisted ? "\n\nDrukarki nie ma na liście sklepu: dodaj ją w Intermanage." : "")
                    + "\n\nEsc: lista drukarek.", false);
            }
            if (mode == Mode.Printer)
            {
                ShowView(message);
            }
        }

        private void OnSyncPrinters(object sender, EventArgs e)
        {
            OnUi(new EventHandler(OnPrintersChanged));
        }

        private void OnPrintersChanged(object sender, EventArgs e)
        {
            if (mode == Mode.Printer && view == printerList)
            {
                ShowPrinters();
            }
        }

        /// <summary>Klawisze na ekranie drukarek; prawda, gdy obsłużone.</summary>
        private bool PrinterKeyPress(KeyPressEventArgs e, bool enter)
        {
            if (mode != Mode.Printer)
            {
                return false;
            }
            e.Handled = true;
            if (checkingPrinter)
            {
                return true;
            }
            if (view == printerList)
            {
                if (enter)
                {
                    ChoosePrinter(printerList.Selected);
                }
                else if (e.KeyChar >= '1' && e.KeyChar <= '9')
                {
                    ChoosePrinter(e.KeyChar - '1');
                }
            }
            else if (enter && printerFailed && checkedPrinter != null && view == message)
            {
                CheckPrinter(checkedPrinter);
            }
            else if (enter)
            {
                ShowPrinters();
            }
            return true;
        }

        /// <summary>Esc na ekranie drukarek: z wyniku do listy, z listy na ekran główny.</summary>
        private bool BackInPrinters()
        {
            if (mode != Mode.Printer)
            {
                return false;
            }
            if (checkingPrinter)
            {
                return true;
            }
            if (view == printerList || shownPrinters.Count == 0)
            {
                GoHome();
            }
            else
            {
                ShowPrinters();
            }
            return true;
        }
    }
}
