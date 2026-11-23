using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Intermanage.Agent;

/// <summary>
/// Aktualizacja danych z Mercalys przy starcie komputera: raporty Importu danych, których najnowszy plik w folderze
/// nie jest z dzisiaj, zapisuje makro (składa je serwer, to samo co „Zaktualizuj wszystkie pliki” w Imporcie danych),
/// a agent wysyła je jak zawsze. Okno <see cref="UpdateForm"/> trzyma się na wierzchu, aż serwer potwierdzi wgranie
/// każdego zapisanego pliku.
///
/// Makro wykonuje odtwarzacz (player.exe, działa w tle): agent wkłada makro do schowka i naciska lewy Alt+E, jak
/// pracownik. Hasło Mercalys z ustawień agenta trafia do pierwszego polecenia, więc odtwarzacz sam uruchamia i loguje
/// wyłączony Mercalys.
/// </summary>
internal sealed class DataUpdate
{
    private const string ImportPrefix = "IMPORT_";
    private static readonly TimeSpan PollEvery = TimeSpan.FromSeconds(10);
    /// <summary>Tyle bez żadnej zmiany (zapis pliku, wgrywanie) i okno kończy z listą tego, czego nie doczekało.</summary>
    private static readonly TimeSpan GiveUpAfter = TimeSpan.FromMinutes(60);
    private static readonly TimeSpan WaitForConfig = TimeSpan.FromMinutes(3);
    private static readonly TimeSpan WaitForPlayer = TimeSpan.FromMinutes(3);

    private readonly AgentHost host;
    private readonly Control ui;
    private int running;

    public DataUpdate(AgentHost host, Control ui)
    {
        this.host = host;
        this.ui = ui;
    }

    /// <summary>
    /// Aktualizacja raportów nie z dzisiaj; <paramref name="force"/> (menu ikony) bierze wszystkie pilnowane raporty.
    /// Druga aktualizacja w trakcie pierwszej nic nie robi. Automatyczną (<paramref name="force"/> = false) serwer daje
    /// tylko głównemu agentowi sklepu, a każdą najwyżej jednemu komputerowi naraz (409 = aktualizuje inny komputer).
    /// </summary>
    public async Task RunAsync(AgentSettings settings, bool force, CancellationToken cancel)
    {
        if (Interlocked.Exchange(ref running, 1) == 1) return;
        UpdateForm? form = null;
        ApiClient? locked = null;
        try
        {
            var config = await WaitForConfigAsync(cancel).ConfigureAwait(false);
            var api = host.Api;
            if (config == null || api == null)
            {
                Log.Warn("Aktualizacja danych: brak połączenia z serwerem");
                return;
            }
            var watches = config.Watches
                .Where(w => w.Kind.StartsWith(ImportPrefix, StringComparison.OrdinalIgnoreCase)).ToList();
            if (watches.Count == 0)
            {
                if (force) ShowInfo("Sklep nie ma raportów Importu danych pilnowanych przez agenta.");
                return;
            }
            var kinds = force
                ? watches.Select(w => w.Kind.Substring(ImportPrefix.Length)).ToList()
                : await StaleReportsAsync(watches, cancel).ConfigureAwait(false);
            if (kinds.Count == 0)
            {
                Log.Info("Aktualizacja danych: pliki raportów są z dzisiaj");
                return;
            }
            Log.Info("Aktualizacja danych z Mercalys: " + string.Join(", ", kinds));
            ReportMacro macro;
            try
            {
                macro = await api.ReportMacroAsync(kinds, !force, cancel).ConfigureAwait(false);
            }
            catch (RejectedException error) when (error.Status == 409)
            {
                Log.Info("Aktualizacja danych pominięta: " + error.Message);
                if (force) ShowInfo(error.Message);
                return;
            }
            locked = api;
            foreach (var skipped in macro.Skipped)
            {
                Log.Warn("Aktualizacja danych: " + skipped.Title + " pominięty: " + skipped.Reason);
            }
            if (macro.Reports.Count == 0)
            {
                ShowInfo("Raporty do aktualizacji nie mają ustawień makra w Imporcie danych:\n"
                    + string.Join("\n", macro.Skipped.Select(s => s.Title + ": " + s.Reason)));
                return;
            }

            form = OnUi(() =>
            {
                var created = new UpdateForm(macro.Files, macro.Skipped);
                created.Show();
                return created;
            });
            // Zegar serwera i komputera mogą się trochę różnić: próby wgrania liczymy od chwili z zapasem.
            var start = DateTimeOffset.UtcNow.AddMinutes(-2);

            form.Status("Szukam odtwarzacza makr (AutoJob)…");
            if (!await WaitForPlayerAsync(form, cancel).ConfigureAwait(false))
            {
                Log.Warn("Aktualizacja danych: nie znaleziono działającego odtwarzacza makr");
                form.Finish(false, "Nie działa odtwarzacz makr (AutoJob). Uruchom go i wybierz w menu ikony agenta "
                    + "„Aktualizuj dane z Mercalys”.");
                return;
            }
            form.Status("Uruchamiam makro w Mercalys…");
            var text = WithPassword(macro, settings.MercalysPassword);
            OnUi(() =>
            {
                Clipboard.SetText(text);
                return true;
            });
            await Task.Delay(500, cancel).ConfigureAwait(false);
            PressAltE();
            Log.Info("Aktualizacja danych: makro uruchomione (" + macro.Files.Count + " plików)");
            form.Status("Mercalys zapisuje raporty. Nie używaj komputera, aż okno potwierdzi wgranie danych.");

            await WatchAsync(api, macro.Files, start, form, cancel).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            Log.Error("Aktualizacja danych", error);
            form?.Finish(false, "Aktualizacja przerwana: " + error.Message);
        }
        finally
        {
            Interlocked.Exchange(ref running, 0);
            if (locked != null) _ = FinishedAsync(locked);
        }
    }

    /// <summary>Koniec aktualizacji na serwerze (bez tego blokada wygasa sama po godzinie).</summary>
    private static async Task FinishedAsync(ApiClient api)
    {
        try
        {
            await api.ReportMacroFinishedAsync(CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            Log.Warn("Aktualizacja danych: nie zgłoszono końca: " + error.Message);
        }
    }

    /// <summary>
    /// Raporty, których plik nie jest z dzisiaj (albo go nie ma): plik zapisywany przez makro, a bez ścieżki makra
    /// najnowszy pasujący plik w folderze. Raport zapisany ręcznie pod inną nazwą nie zwalnia z aktualizacji.
    /// </summary>
    private static async Task<List<string>> StaleReportsAsync(List<WatchConfig> watches, CancellationToken cancel)
    {
        var stale = new List<string>();
        foreach (var watch in watches)
        {
            var source = string.IsNullOrEmpty(watch.FreshPattern) ? watch.Pattern : watch.FreshPattern!;
            var pattern = new Regex(source, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            DateTime newest;
            try
            {
                // Brakujący folder zakładamy (makro nie zapisze w nim raportu), a raport bez pliku jest do aktualizacji.
                newest = await Disk.Run(() =>
                {
                    if (!Directory.Exists(watch.Path))
                    {
                        Directory.CreateDirectory(watch.Path);
                        Log.Info("Aktualizacja danych: założono folder " + watch.Path);
                    }
                    return Directory.EnumerateFiles(watch.Path)
                        .Where(path => pattern.IsMatch(Path.GetFileName(path)))
                        .Select(File.GetLastWriteTime)
                        .DefaultIfEmpty(DateTime.MinValue)
                        .Max();
                }, TimeSpan.FromSeconds(60), cancel).ConfigureAwait(false);
            }
            catch (Exception error) when (!(error is OperationCanceledException))
            {
                Log.Warn("Aktualizacja danych: nie odczytano folderu " + watch.Path + ": " + error.Message);
                continue;
            }
            if (newest < DateTime.Today) stale.Add(watch.Kind.Substring(ImportPrefix.Length));
        }
        return stale;
    }

    /// <summary>Hasło Mercalys w pierwszym poleceniu makra (odtwarzacz od 0.54 sam uruchamia i loguje Mercalys).</summary>
    private static string WithPassword(ReportMacro macro, string? password)
    {
        if (string.IsNullOrEmpty(password) || password!.Contains("|") || password.Length > 20) return macro.Macro;
        return macro.Macro.Replace(macro.OpenLine + "\r\n", macro.OpenLine + "|" + password + "\r\n");
    }

    /// <summary>
    /// Czeka, aż serwer wgra każdy plik makra (próba wgrania po starcie aktualizacji): wgrany, błąd albo bez zmian
    /// przez <see cref="GiveUpAfter"/>.
    /// </summary>
    private static async Task WatchAsync(ApiClient api, List<MacroFile> files, DateTimeOffset start, UpdateForm form,
        CancellationToken cancel)
    {
        var states = files.ToDictionary(f => f.FileName.ToLowerInvariant(), _ => "");
        var lastChange = DateTime.UtcNow;
        while (true)
        {
            await Task.Delay(PollEvery, cancel).ConfigureAwait(false);
            List<ReportAttempt> attempts;
            try
            {
                attempts = await api.RecentReportsAsync(cancel).ConfigureAwait(false);
            }
            catch (Exception error) when (!(error is OperationCanceledException))
            {
                Log.Warn("Aktualizacja danych: stan wgrań niedostępny: " + error.Message);
                continue;
            }
            // Tylko pliki zapisane przez to makro: stary plik o tej samej nazwie (np. wysłany przy starcie agenta,
            // bo serwer go jeszcze nie wgrał) nie jest wynikiem tej aktualizacji.
            var latest = attempts.Where(a => a.StartedAt >= start && a.FileModified != null && a.FileModified >= start)
                .GroupBy(a => a.FileName.ToLowerInvariant())
                .ToDictionary(g => g.Key, g => g.OrderByDescending(a => a.StartedAt).First());
            var done = 0;
            var failed = new List<string>();
            foreach (var file in files)
            {
                var key = file.FileName.ToLowerInvariant();
                latest.TryGetValue(key, out var attempt);
                var status = attempt?.Status ?? "";
                if (states[key] != status)
                {
                    states[key] = status;
                    lastChange = DateTime.UtcNow;
                }
                form.Row(file.FileName, attempt == null ? "czeka na Mercalys" : status switch
                {
                    "IMPORTED" => "wgrany",
                    "FAILED" => "błąd: " + (attempt.Error ?? "nie wgrano"),
                    "INTERRUPTED" => "przerwany, wysyłam ponownie",
                    "IMPORTING" => "serwer wgrywa",
                    _ => "wysyłanie na serwer",
                }, status == "IMPORTED" ? RowState.Done : status == "FAILED" ? RowState.Failed : RowState.Working);
                if (status == "IMPORTED") done++;
                if (status == "FAILED") failed.Add(file.Title + " (" + file.FileName + "): " + attempt!.Error);
            }
            form.Progress(done, files.Count);
            if (done + failed.Count == files.Count)
            {
                if (failed.Count == 0)
                {
                    Log.Info("Aktualizacja danych zakończona: wgrano " + done + " plików");
                    form.Finish(true, "Dane zaktualizowane. Wgrano " + done + " z " + files.Count + " plików.");
                }
                else
                {
                    Log.Warn("Aktualizacja danych z błędami: " + string.Join("; ", failed));
                    form.Finish(false, "Nie wszystko się wgrało:\n" + string.Join("\n", failed));
                }
                return;
            }
            if (DateTime.UtcNow - lastChange > GiveUpAfter)
            {
                var missing = files.Where(f => states[f.FileName.ToLowerInvariant()] != "IMPORTED")
                    .Select(f => f.Title + " (" + f.FileName + ")").ToList();
                Log.Warn("Aktualizacja danych: bez zmian od godziny, brak: " + string.Join(", ", missing));
                form.Finish(false, "Od godziny nic się nie zmieniło. Nie wgrano:\n" + string.Join("\n", missing)
                    + "\n\nSprawdź Mercalys (część sprzedaży bez sprzedaży nie ma pliku).");
                return;
            }
        }
    }

    private async Task<HelloResponse?> WaitForConfigAsync(CancellationToken cancel)
    {
        var until = DateTime.UtcNow + WaitForConfig;
        while (host.Config == null && DateTime.UtcNow < until)
        {
            await Task.Delay(1000, cancel).ConfigureAwait(false);
        }
        return host.Config;
    }

    /// <summary>Odtwarzacz makr startuje z Windows jak agent, więc przy starcie komputera chwilę na niego czekamy.</summary>
    private static async Task<bool> WaitForPlayerAsync(UpdateForm form, CancellationToken cancel)
    {
        var until = DateTime.UtcNow + WaitForPlayer;
        var logged = false;
        while (true)
        {
            var player = FindPlayer();
            if (player != null)
            {
                Log.Info("Aktualizacja danych: odtwarzacz makr " + player);
                return true;
            }
            var left = until - DateTime.UtcNow;
            if (left <= TimeSpan.Zero) return false;
            if (!logged)
            {
                Log.Info("Aktualizacja danych: czekam na odtwarzacz makr");
                logged = true;
            }
            form.Status("Czekam na odtwarzacz makr (AutoJob), jeszcze " + Math.Ceiling(left.TotalSeconds) + " s…");
            await Task.Delay(2000, cancel).ConfigureAwait(false);
        }
    }

    /// <summary>Produkt z <c>;@Ahk2Exe-SetName</c> w player.ahk: plik bywa nazwany player.exe, player-v60.exe albo AutoJob.exe.</summary>
    private const string PlayerProduct = "Intermanage Makra";

    /// <summary>Nazwa działającego odtwarzacza makr albo null. Rozpoznaje go po nazwie pliku albo po opisie programu.</summary>
    private static string? FindPlayer()
    {
        var session = Process.GetCurrentProcess().SessionId;
        foreach (var process in Process.GetProcesses())
        {
            using (process)
            {
                try
                {
                    if (process.SessionId != session) continue;
                    var name = process.ProcessName;
                    if (name.StartsWith("player", StringComparison.OrdinalIgnoreCase)
                        || name.StartsWith("autojob", StringComparison.OrdinalIgnoreCase))
                    {
                        return name;
                    }
                    var path = ImagePath(process.Id);
                    if (path == null) continue;
                    var info = FileVersionInfo.GetVersionInfo(path);
                    if (string.Equals(info.ProductName?.Trim(), PlayerProduct, StringComparison.OrdinalIgnoreCase))
                    {
                        return name;
                    }
                }
                catch (Exception)
                {
                    // Proces mógł się zakończyć albo nie mamy do niego dostępu.
                }
            }
        }
        return null;
    }

    private const uint QueryLimitedInformation = 0x1000;

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, int processId);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool QueryFullProcessImageName(IntPtr process, int flags, System.Text.StringBuilder name,
        ref int size);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>Ścieżka pliku procesu, także 64-bitowego z 32-bitowego agenta (MainModule tego nie umie).</summary>
    private static string? ImagePath(int processId)
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

    private const byte LeftAlt = 0xA4;
    private const byte KeyE = 0x45;
    private const uint KeyUp = 0x0002;

    [DllImport("user32.dll")]
    private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);

    /// <summary>Lewy Alt+E jak pracownik: odtwarzacz wykonuje makro ze schowka po puszczeniu Alta.</summary>
    private static void PressAltE()
    {
        keybd_event(LeftAlt, 0, 0, UIntPtr.Zero);
        keybd_event(KeyE, 0, 0, UIntPtr.Zero);
        keybd_event(KeyE, 0, KeyUp, UIntPtr.Zero);
        keybd_event(LeftAlt, 0, KeyUp, UIntPtr.Zero);
    }

    private T OnUi<T>(Func<T> action) => (T)ui.Invoke(action);

    private void ShowInfo(string text) => ui.BeginInvoke(new Action(() =>
        MessageBox.Show(text, "Intermanage – aktualizacja danych", MessageBoxButtons.OK, MessageBoxIcon.Information)));
}
