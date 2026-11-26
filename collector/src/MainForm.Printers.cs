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
            choosingCalibration = false;
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
                    + PrinterHelp(), false);
            }
            if (mode == Mode.Printer)
            {
                ShowView(message);
            }
        }

        // ---- cenówki ----

        private volatile bool printing;
        private PriceLabel printedLabel;
        private Printer printedOn;
        private bool printedFromCard;

        /// <summary>„Drukuj” na karcie artykułu (lewy dolny róg albo Menu): cenówka pokazanego artykułu.</summary>
        private void OnPrintShown(object sender, EventArgs e)
        {
            if (mode != Mode.Scan || view != card || shownArticle == null)
            {
                MessageBox.Show("Otwórz artykuł w Skanowaniu i wtedy wydrukuj jego cenówkę.", "Cenówka");
                FocusInput();
                return;
            }
            // Zeskanowany kod decyduje o nazwie, cenie i kaucji: puszka z czteropaka ma swoje.
            PriceLabel label = PriceLabel.Of(shownArticle, shownMatch);
            if (label == null)
            {
                Native.ErrorBeep();
                MessageBox.Show("Artykuł nie ma ceny sprzedaży.", "Cenówka");
                FocusInput();
                return;
            }
            Printer printer = Printers.Chosen;
            if (printer == null)
            {
                Native.ErrorBeep();
                MessageBox.Show("Najpierw wybierz drukarkę: Menu, Drukarka.", "Cenówka");
                FocusInput();
                return;
            }
            StartPrint(printer, label, true);
        }

        private void StartPrint(Printer printer, PriceLabel label, bool fromCard)
        {
            if (printing || checkingPrinter)
            {
                return;
            }
            printing = true;
            printedLabel = label;
            printedOn = printer;
            printedFromCard = fromCard;
            Cursor.Current = Cursors.WaitCursor;
            if (!fromCard)
            {
                message.Show("Drukuję cenówkę testową", printer.Name + "\n" + BluetoothLink.Bar(printer.Mac), false);
                ShowView(message);
            }
            Thread thread = new Thread(new ThreadStart(RunPrint));
            thread.IsBackground = true;
            thread.Start();
        }

        private void RunPrint()
        {
            try
            {
                printerResult = Printers.Print(printedOn, printedLabel);
                printerFailed = false;
            }
            catch (BluetoothException error)
            {
                printerResult = error.Message;
                printerFailed = true;
            }
            catch (Exception error)
            {
                Log.Write("Cenówka na " + BluetoothLink.Bar(printedOn.Mac) + ": " + error);
                printerResult = "Nie udało się wydrukować: " + error.Message;
                printerFailed = true;
            }
            OnUi(new EventHandler(OnPrinted));
        }

        private void OnPrinted(object sender, EventArgs e)
        {
            printing = false;
            Cursor.Current = Cursors.Default;
            if (printedFromCard)
            {
                if (printerFailed)
                {
                    Native.ErrorBeep();
                    MessageBox.Show(printerResult, "Cenówka");
                }
                else
                {
                    Native.Beep();
                }
                FocusInput();
                return;
            }
            checkedPrinter = printedOn;
            if (printerFailed)
            {
                Native.ErrorBeep();
            }
            choosingCalibration = false;
            message.Show(printerFailed ? "Nie wydrukowano" : "Cenówka testowa", printerResult + PrinterHelp(),
                printerFailed);
            printerFailed = false;
            if (mode == Mode.Printer)
            {
                ShowView(message);
            }
        }

        // ---- kalibracja ----

        private bool calibrateMark;
        /// <summary>Na ekranie jest pytanie o rodzaj etykiet: 1 i 2 wybierają czujnik, a nie przesuwają cenówki.</summary>
        private bool choosingCalibration;

        /// <summary>Najdalsze przesunięcie w mm w każdą stronę (^LT drukarki przyjmuje do 120 punktów).</summary>
        private const decimal MaxShiftMm = 10m;

        /// <summary>Wskazówki pod wynikiem: położenie cenówki zapisane w drukarce i klawisze.</summary>
        private string PrinterHelp()
        {
            Printer printer = checkedPrinter;
            string where = printer == null || !printer.OffsetsRead ? "Położenie cenówki: nieznane (połącz z drukarką)"
                : "Położenie cenówki" + (printer.OffsetsChanged ? " (zapisze się w drukarce przy Enter)" : " w drukarce")
                    + ": " + ShiftText(printer.Shift, printer.Dpi, "w lewo", "w prawo") + ", "
                    + ShiftText(-printer.Top, printer.Dpi, "w górę", "w dół");
            return "\n\n" + where
                + "\n4 / 6: w lewo / w prawo, 8 / 2: w górę / w dół (o 0,5 mm), 5: domyślne (3 mm w lewo i w górę)"
                + "\nEnter: cenówka testowa, 0: kalibracja, Esc: lista drukarek.";
        }

        private static string ShiftText(int dots, int dpi, string minus, string plus)
        {
            if (dots == 0)
            {
                return "0 mm";
            }
            decimal mm = decimal.Round(Math.Abs(dots) * 25.4m / dpi, 1);
            return mm.ToString("0.#", System.Globalization.CultureInfo.InvariantCulture).Replace('.', ',') + " mm "
                + (dots < 0 ? minus : plus);
        }

        /// <summary>
        /// Klawisze 4, 6, 8, 2 przesuwają cenówkę o 0,5 mm, 5 zeruje przesunięcie. Zmiana trafia do drukarki z najbliższą
        /// cenówką testową (Enter), więc drukarka pamięta ją dla każdego kolektora.
        /// </summary>
        private void ShiftLabel(char key)
        {
            Printer printer = checkedPrinter;
            if (!printer.OffsetsRead)
            {
                Native.ErrorBeep();
                message.Show("Położenie cenówki", "Najpierw połącz się z drukarką, żeby odczytać jej ustawienia."
                    + PrinterHelp(), true);
                ShowView(message);
                return;
            }
            int step = Math.Max(1, (int)decimal.Round(0.5m * printer.Dpi / 25.4m, 0));
            int max = (int)decimal.Round(MaxShiftMm * printer.Dpi / 25.4m, 0);
            switch (key)
            {
                // Cenówka jest obrócona o 180° (^POI), więc ^LS i ^LT działają na niej odwrotnie: ujemne ^LS przesuwa
                // ją w lewo, dodatnie ^LT w górę (sprawdzone na QLn).
                case '4':
                    printer.Shift -= step;
                    break;
                case '6':
                    printer.Shift += step;
                    break;
                case '8':
                    printer.Top += step;
                    break;
                case '2':
                    printer.Top -= step;
                    break;
                default:
                    Printers.DefaultOffsets(printer);
                    break;
            }
            printer.Shift = Math.Max(-max, Math.Min(max, printer.Shift));
            printer.Top = Math.Max(-max, Math.Min(max, printer.Top));
            printer.OffsetsChanged = true;
            message.Show("Położenie cenówki", "Enter wydrukuje cenówkę testową i zapisze położenie w drukarce."
                + PrinterHelp(), false);
            ShowView(message);
        }

        private void OnCalibrateMenu(object sender, EventArgs e)
        {
            Printer printer = Printers.Chosen;
            if (printer == null || mode == Mode.Pair || Entering || view == ask)
            {
                MessageBox.Show("Najpierw wybierz drukarkę: Menu, Drukarka.", "Kalibracja");
                FocusInput();
                return;
            }
            input.Text = "";
            searched = "";
            mode = Mode.Printer;
            checkedPrinter = printer;
            printerFailed = false;
            choosingCalibration = true;
            message.Show("Kalibracja: " + printer.Name, CalibrateChoice, false);
            ShowView(message);
        }

        private const string CalibrateChoice = "Jakie etykiety są w drukarce?\n\n1: z przerwą między etykietami "
            + "(prześwit na podkładzie)\n2: z czarnym paskiem na spodzie rolki\n\nEsc: lista drukarek.";

        private void StartCalibrate(Printer printer, bool blackMark)
        {
            if (printing || checkingPrinter)
            {
                return;
            }
            printing = true;
            printedOn = printer;
            calibrateMark = blackMark;
            message.Show("Kalibruję drukarkę", printer.Name + (blackMark ? "\nCzujnik: czarny znacznik" : "\nCzujnik: przerwa")
                + "\n\nDrukarka przesunie kilka pustych etykiet, żeby je zmierzyć.", false);
            ShowView(message);
            Thread thread = new Thread(new ThreadStart(RunCalibrate));
            thread.IsBackground = true;
            thread.Start();
        }

        private void RunCalibrate()
        {
            try
            {
                printerResult = Printers.Calibrate(printedOn, calibrateMark);
                printerFailed = false;
            }
            catch (BluetoothException error)
            {
                printerResult = error.Message;
                printerFailed = true;
            }
            catch (Exception error)
            {
                Log.Write("Kalibracja " + BluetoothLink.Bar(printedOn.Mac) + ": " + error);
                printerResult = "Nie udało się skalibrować: " + error.Message;
                printerFailed = true;
            }
            OnUi(new EventHandler(OnCalibrated));
        }

        private void OnCalibrated(object sender, EventArgs e)
        {
            printing = false;
            checkedPrinter = printedOn;
            if (printerFailed)
            {
                Native.ErrorBeep();
            }
            choosingCalibration = false;
            message.Show(printerFailed ? "Nie skalibrowano" : "Skalibrowano", printerResult + PrinterHelp(),
                printerFailed);
            printerFailed = false;
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
            if (checkingPrinter || printing)
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
            else if (e.KeyChar == '0' && view == message && checkedPrinter != null
                && Settings.PrinterMac == checkedPrinter.Mac)
            {
                choosingCalibration = true;
                message.Show("Kalibracja: " + checkedPrinter.Name, CalibrateChoice, false);
                ShowView(message);
            }
            else if (choosingCalibration && (e.KeyChar == '1' || e.KeyChar == '2') && view == message
                && checkedPrinter != null && Settings.PrinterMac == checkedPrinter.Mac)
            {
                choosingCalibration = false;
                StartCalibrate(checkedPrinter, e.KeyChar == '2');
            }
            else if (!choosingCalibration && "24568".IndexOf(e.KeyChar) >= 0 && view == message
                && checkedPrinter != null && Settings.PrinterMac == checkedPrinter.Mac)
            {
                ShiftLabel(e.KeyChar);
            }
            else if (enter && view == message && checkedPrinter != null && Settings.PrinterMac == checkedPrinter.Mac)
            {
                StartPrint(checkedPrinter, PriceLabel.Sample(), false);
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
            if (checkingPrinter || printing)
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
