using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
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
/// Błąd nie kończy aktualizacji (od 1.12.0): odtwarzacz od 0.69 sam ponawia plik, który się nie udał, i robi pozostałe,
/// a czego dalej brakuje (albo serwer odrzucił), agent drukuje jeszcze raz nowym makrem. Wynik makra odtwarzacz zapisuje
/// w pliku (<see cref="MacroPlayback"/>), więc nie zostaje na oknie błędu. Po nieudanej aktualizacji okno ma „Spróbuj
/// ponownie” (tylko brakujące raporty), a w trakcie „Przerwij”.
///
/// Makro wykonuje odtwarzacz (player.exe schowany w agencie, <see cref="PlayerHost"/>, albo osobny): agent wkłada makro do schowka i naciska lewy Alt+E, jak
/// pracownik. Hasło Mercalys z ustawień agenta trafia do pierwszego polecenia, więc odtwarzacz sam uruchamia i loguje
/// wyłączony Mercalys.
/// </summary>
internal sealed class DataUpdate
{
    private const string ImportPrefix = "IMPORT_";
    private static readonly TimeSpan PollEvery = TimeSpan.FromSeconds(10);
    /// <summary>Tyle makr najwyżej: pierwsze i ponowienia brakujących raportów.</summary>
    private const int MaxRounds = 3;
    /// <summary>Odtwarzacz, który sam ponawia plik; po nim agent drukuje niezapisane raporty już tylko raz.</summary>
    private static readonly Version RetryingPlayer = new(0, 69);
    /// <summary>Tyle bez żadnej zmiany (zapis pliku, wgrywanie) i okno kończy z listą tego, czego nie doczekało.</summary>
    private static readonly TimeSpan GiveUpAfter = TimeSpan.FromMinutes(90);
    private static readonly TimeSpan WaitForConfig = TimeSpan.FromMinutes(3);
    private static readonly TimeSpan WaitForPlayer = TimeSpan.FromMinutes(3);
    /// <summary>Tyle czekamy na część folderów, która nie odpowiada; potem aktualizacja idzie bez nich.</summary>
    private static readonly TimeSpan WaitForFolders = TimeSpan.FromMinutes(10);

    private readonly AgentHost host;
    private readonly Control ui;
    private int running;
    /// <summary>Okno trwającej albo ostatniej aktualizacji (po błędzie czeka na decyzję).</summary>
    private UpdateForm? window;
    /// <summary>Od kiedy część folderów raportów nie odpowiada (pozostałe już tak).</summary>
    private DateTime? partlyUnreadableSince;
    /// <summary>Foldery, o których niedostępności dziennik już mówi (ponawianie co minutę).</summary>
    private readonly HashSet<string> unreadable = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Aktualizacja danych trwa (agent nie podmienia się wtedy na nową wersję).</summary>
    public bool IsRunning => Volatile.Read(ref running) == 1;

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
    /// <returns>
    /// <c>false</c>: nie doszło do niej, bo serwer albo dysk z folderami raportów nie odpowiada (automatyczną trzeba
    /// ponowić), <c>true</c>: zakończona albo niepotrzebna.
    /// </returns>
    public async Task<bool> RunAsync(AgentSettings settings, bool force, CancellationToken cancel,
        List<string>? only = null)
    {
        if (Interlocked.Exchange(ref running, 1) == 1)
        {
            // Klik w menu nie może przepaść bez słowa.
            if (force)
            {
                if (window is { IsDisposed: false } open) open.BringBack();
                else ShowInfo("Aktualizacja danych już trwa albo czeka na koniec innego makra na tym komputerze.");
            }
            return false;
        }
        UpdateForm? form = null;
        ApiClient? locked = null;
        var gate = false;
        using var stop = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        var run = new Run();
        try
        {
            CloseWindow();
            var config = await WaitForConfigAsync(cancel).ConfigureAwait(false);
            var api = host.Api;
            if (config == null || api == null)
            {
                Log.Warn("Aktualizacja danych: brak połączenia z serwerem");
                if (force) ShowInfo("Brak połączenia z serwerem Intermanage.");
                return false;
            }
            var watches = config.Watches
                .Where(w => w.Kind.StartsWith(ImportPrefix, StringComparison.OrdinalIgnoreCase)).ToList();
            if (watches.Count == 0)
            {
                if (force) ShowInfo("Sklep nie ma raportów Importu danych pilnowanych przez agenta.");
                return true;
            }
            List<string> kinds;
            if (only != null)
            {
                kinds = only;
            }
            else if (force)
            {
                kinds = watches.Select(w => w.Kind.Substring(ImportPrefix.Length)).ToList();
            }
            else
            {
                (kinds, var failedFolders) = await StaleReportsAsync(watches, cancel).ConfigureAwait(false);
                if (!FoldersReady(failedFolders, watches.Count)) return false;
                if (kinds.Count == 0)
                {
                    Log.Info(failedFolders == 0 ? "Aktualizacja danych: pliki raportów są z dzisiaj"
                        : "Aktualizacja danych: pliki w dostępnych folderach są z dzisiaj");
                    return true;
                }
            }
            // Makro zlecone z przeglądarki na tym komputerze kończy się przed aktualizacją.
            await MacroRunner.Gate.WaitAsync(cancel).ConfigureAwait(false);
            gate = true;
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
                // Rola głównego przeszła w międzyczasie na inny komputer: gdy wróci, sprawdzamy raporty jeszcze raz.
                return host.IsLeader;
            }
            catch (ServerUnavailableException error)
            {
                Log.Warn("Aktualizacja danych: serwer nie dał makra: " + error.Message);
                if (force) ShowInfo("Serwer Intermanage nie odpowiada: " + error.Message);
                return false;
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
                return true;
            }

            run.Pending = macro.Files.Select(f => f.Kind).Distinct().ToList();
            form = OnUi(() =>
            {
                var created = new UpdateForm(macro.Files, macro.Skipped);
                created.StopRequested += () =>
                {
                    try { stop.Cancel(); } catch (ObjectDisposedException) { /* właśnie się skończyła */ }
                };
                created.RetryRequested += () => Task.Run(() => RetryAsync(settings, run.Pending, cancel));
                created.Show();
                return created;
            });
            window = form;
            await RoundsAsync(api, macro, settings, force, form, run, stop.Token).ConfigureAwait(false);
            return true;
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            return true;
        }
        catch (OperationCanceledException) when (stop.IsCancellationRequested)
        {
            Log.Info("Aktualizacja danych przerwana przyciskiem");
            // Bez odtwarzania makro może właśnie startować; Esc bez makra nic nie robi.
            if (run.Playback == null || run.Playback.Check().State == MacroPlayback.PlayState.Running)
            {
                MacroRunner.PressEsc();
            }
            form?.Finish(false, "Aktualizacja przerwana. Brakujące raporty uruchomisz przyciskiem „Spróbuj ponownie”.");
            return true;
        }
        catch (Exception error)
        {
            Log.Error("Aktualizacja danych", error);
            form?.Finish(false, "Aktualizacja przerwana: " + error.Message);
            return true;
        }
        finally
        {
            if (gate) MacroRunner.Gate.Release();
            Interlocked.Exchange(ref running, 0);
            if (locked != null) _ = FinishedAsync(locked);
        }
    }

    /// <summary>Stan trwającej aktualizacji dla przycisków okna.</summary>
    private sealed class Run
    {
        /// <summary>Raporty, których jeszcze brakuje („Spróbuj ponownie”).</summary>
        public volatile List<string> Pending = new();
        public volatile MacroPlayback? Playback;
    }

    /// <summary>„Spróbuj ponownie”: nowa aktualizacja brakujących raportów, gdy poprzednia już oddała bramkę.</summary>
    private async Task RetryAsync(AgentSettings settings, List<string> kinds, CancellationToken cancel)
    {
        try
        {
            for (var i = 0; i < 50 && IsRunning; i++) await Task.Delay(200, cancel).ConfigureAwait(false);
            await Task.Delay(1000, cancel).ConfigureAwait(false);
            await RunAsync(settings, true, cancel, kinds).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // Agent się zamyka.
        }
    }

    private void CloseWindow()
    {
        var old = window;
        window = null;
        if (old == null) return;
        OnUi(() =>
        {
            if (!old.IsDisposed) old.Close();
            return true;
        });
    }

    private sealed class Outcome
    {
        public List<MacroFile> Failed = new();
        /// <summary>Opis każdego braku do okna i dziennika.</summary>
        public List<string> Problems = new();
        public int Done;
        /// <summary>Z nieudanych choć jeden zapisał się na dysku, a odrzucił go serwer.</summary>
        public bool Rejected;
        /// <summary>Nie ponawiamy sami: człowiek zatrzymał makro, odtwarzacza nie ma albo nic się nie dzieje.</summary>
        public bool Stopped;
    }

    /// <summary>Makro i ponowienia tego, czego po nim brakuje; na końcu wynik w oknie.</summary>
    private async Task RoundsAsync(ApiClient api, ReportMacro macro, AgentSettings settings, bool force,
        UpdateForm form, Run run, CancellationToken cancel)
    {
        // Zegar serwera i komputera mogą się trochę różnić: próby wgrania liczymy od chwili z zapasem.
        var start = DateTimeOffset.UtcNow.AddMinutes(-2);
        var known = new HashSet<string>();
        var done = 0;
        for (var round = 1; ; round++)
        {
            var outcome = await RoundAsync(api, macro, settings, form, run, start, known, cancel).ConfigureAwait(false);
            run.Playback = null;
            run.Pending = outcome.Failed.Select(f => f.Kind).Distinct().ToList();
            done += outcome.Done;
            if (outcome.Failed.Count == 0)
            {
                Log.Info("Aktualizacja danych zakończona: " + done + " plików");
                form.Finish(true, "Dane zaktualizowane (" + done + " plików).");
                return;
            }
            Log.Warn("Aktualizacja danych z błędami: " + string.Join("; ", outcome.Problems));
            var rounds = !outcome.Rejected && MacroRunner.FindPlayer()?.Version >= RetryingPlayer ? 2 : MaxRounds;
            if (!outcome.Stopped && round < rounds)
            {
                form.Status("Nie wszystko się udało, ponawiam: "
                    + string.Join(", ", outcome.Failed.Select(f => f.Title).Distinct()) + "…");
                await Task.Delay(TimeSpan.FromSeconds(15), cancel).ConfigureAwait(false);
                var next = await NextMacroAsync(api, run.Pending, force, cancel).ConfigureAwait(false);
                if (next != null)
                {
                    Log.Info("Aktualizacja danych: ponawiam " + string.Join(", ", run.Pending));
                    form.Replace(outcome.Failed, next.Files, "ponawiam, czeka na Mercalys");
                    macro = next;
                    continue;
                }
            }
            form.Finish(false, "Nie wszystko się wgrało:\n" + string.Join("\n", outcome.Problems));
            return;
        }
    }

    private static async Task<ReportMacro?> NextMacroAsync(ApiClient api, List<string> kinds, bool force,
        CancellationToken cancel)
    {
        try
        {
            var next = await api.ReportMacroAsync(kinds, !force, cancel).ConfigureAwait(false);
            return next.Reports.Count == 0 ? null : next;
        }
        catch (Exception error) when (!(error is OperationCanceledException && cancel.IsCancellationRequested))
        {
            Log.Warn("Aktualizacja danych: serwer nie dał makra do ponowienia: " + error.Message);
            return null;
        }
    }

    private async Task<Outcome> RoundAsync(ApiClient api, ReportMacro macro, AgentSettings settings, UpdateForm form,
        Run run, DateTimeOffset start, HashSet<string> known, CancellationToken cancel)
    {
        var paths = SavedPaths(macro);
        var before = await ModifiedAsync(paths.Values, cancel).ConfigureAwait(false);

        form.Status("Szukam odtwarzacza makr (AutoJob)…");
        if (!await WaitForPlayerAsync(form, cancel).ConfigureAwait(false))
        {
            return Nothing(macro, PlayerHost.Embedded
                ? "Odtwarzacz makr agenta nie wystartował (szczegóły w dzienniku agenta)."
                : "Nie działa odtwarzacz makr (AutoJob). Uruchom go i kliknij „Spróbuj ponownie”.");
        }
        form.Status("Uruchamiam makro w Mercalys…");
        var version = MacroRunner.FindPlayer()?.Version;
        if (version != null && version < MacroPlayback.ResultsFrom)
        {
            // Odtwarzacz sprzed 0.64 nie zapisuje wyniku: makro po staremu, a koniec poznajemy tylko po plikach.
            MercalysPath.Ensure();
            var text = MacroRunner.WithPassword(macro.Macro, macro.OpenLine, settings.MercalysPassword);
            OnUi(() =>
            {
                Clipboard.SetText(text);
                return true;
            });
            await Task.Delay(500, cancel).ConfigureAwait(false);
            MacroRunner.PressAltE();
        }
        else
        {
            for (var attempt = 1; attempt <= 2 && run.Playback == null; attempt++)
            {
                run.Playback = await MacroPlayback.StartAsync(ui, Guid.NewGuid(), macro.Macro, macro.OpenLine,
                    settings.MercalysPassword, cancel).ConfigureAwait(false);
            }
            if (run.Playback == null)
            {
                return Nothing(macro, "Odtwarzacz makr (AutoJob) nie podjął makra. Zamknij okno poprzedniego makra "
                    + "(komunikat z błędem) albo uruchom odtwarzacz ponownie i kliknij „Spróbuj ponownie”.");
            }
        }
        Log.Info("Aktualizacja danych: makro uruchomione (" + macro.Files.Count + " plików)");
        form.Status(Working);
        return await WatchAsync(api, macro.Files, paths, before, start, known, form, run, cancel)
            .ConfigureAwait(false);
    }

    private const string Working = "Mercalys zapisuje raporty. Nie używaj komputera, aż okno potwierdzi wgranie danych.";

    private static Outcome Nothing(ReportMacro macro, string problem) => new()
    {
        Failed = macro.Files.ToList(),
        Problems = new List<string> { problem },
        Stopped = true,
    };

    /// <summary>
    /// Ścieżki zapisu z makra (<see cref="MacroFile.Key"/> → pełna ścieżka). Linie zapisu idą w kolejności plików
    /// (kroki ponowienia są w komentarzach <c>#ALT</c>); sama nazwa nie wystarcza, bo raporty bywają nazwane tak samo.
    /// </summary>
    private static Dictionary<string, string> SavedPaths(ReportMacro macro)
    {
        var saved = macro.Macro.Split('\n').Select(line => line.Trim().Split('|'))
            .Where(parts => parts.Length >= 2 && (parts[0] == "EXCELSAVE" || parts[0] == "PDFSAVE"))
            .Select(parts => parts[1].Trim()).ToList();
        var paths = new Dictionary<string, string>();
        for (var i = 0; i < macro.Files.Count && i < saved.Count; i++) paths[macro.Files[i].Key] = saved[i];
        return paths;
    }

    /// <summary>Daty zmiany plików (brak pliku = <see cref="DateTime.MinValue"/>); <c>null</c>: dysk nie odpowiada.</summary>
    private static async Task<Dictionary<string, DateTime>?> ModifiedAsync(IEnumerable<string> files,
        CancellationToken cancel)
    {
        var list = files.Distinct().ToList();
        try
        {
            return await Disk.Run(() => list.ToDictionary(path => path, path => File.Exists(path)
                ? File.GetLastWriteTimeUtc(path) : DateTime.MinValue), TimeSpan.FromSeconds(60), cancel)
                .ConfigureAwait(false);
        }
        catch (Exception error) when (!(error is OperationCanceledException && cancel.IsCancellationRequested))
        {
            Log.Warn("Aktualizacja danych: nie sprawdzono plików raportów: " + error.Message);
            return null;
        }
    }

    /// <summary>Koniec aktualizacji na serwerze (bez tego blokada wygasa sama po 4 godzinach).</summary>
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
    /// Czy da się zacząć mimo folderów, które nie odpowiadają: przy wszystkich nie (dysk sieciowy niedostępny, makro
    /// nie miałoby gdzie zapisać raportów), a na część czekamy <see cref="WaitForFolders"/>, żeby jeden zły folder
    /// w ustawieniach nie zatrzymał reszty.
    /// </summary>
    private bool FoldersReady(int failed, int all)
    {
        if (failed == 0 || failed == all)
        {
            partlyUnreadableSince = null;
            return failed == 0;
        }
        partlyUnreadableSince ??= DateTime.UtcNow;
        return DateTime.UtcNow - partlyUnreadableSince >= WaitForFolders;
    }

    /// <summary>
    /// Raporty, których plik nie jest z dzisiaj (albo go nie ma): plik zapisywany przez makro, a bez ścieżki makra
    /// najnowszy pasujący plik w folderze. Raport zapisany ręcznie pod inną nazwą nie zwalnia z aktualizacji.
    /// Do tego liczba folderów, których nie dało się odczytać (o ich raportach nic nie wiadomo).
    /// </summary>
    private async Task<(List<string> Stale, int Failed)> StaleReportsAsync(List<WatchConfig> watches,
        CancellationToken cancel)
    {
        var stale = new List<string>();
        var failed = 0;
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
                failed++;
                if (unreadable.Add(watch.Path))
                {
                    Log.Warn("Aktualizacja danych: nie odczytano folderu " + watch.Path + ": " + error.Message);
                }
                continue;
            }
            unreadable.Remove(watch.Path);
            if (newest < DateTime.Today) stale.Add(watch.Kind.Substring(ImportPrefix.Length));
        }
        return (stale, failed);
    }

    /// <summary>
    /// Czeka na koniec makra i na wgranie każdego zapisanego pliku (próba wgrania po starcie aktualizacji): wgrany, błąd,
    /// niezapisany przez makro albo bez zmian przez <see cref="GiveUpAfter"/>.
    /// </summary>
    private static async Task<Outcome> WatchAsync(ApiClient api, List<MacroFile> files,
        Dictionary<string, string> paths, Dictionary<string, DateTime>? before, DateTimeOffset start,
        HashSet<string> known, UpdateForm form, Run run, CancellationToken cancel)
    {
        var playback = run.Playback;
        // Powtórzone stany (serwer ponawia nieudane wgranie) nie przedłużają czekania, stąd zbiór.
        var seen = new HashSet<string>();
        var lastChange = DateTime.UtcNow;
        var nextPoll = DateTime.MinValue;
        var step = "";
        MacroPlayback.Result? result = null;
        Dictionary<string, DateTime>? after = null;
        while (true)
        {
            await Task.Delay(1000, cancel).ConfigureAwait(false);
            if (playback != null && result == null)
            {
                var state = playback.Check();
                if (state.State != MacroPlayback.PlayState.Running)
                {
                    result = state;
                    lastChange = DateTime.UtcNow;
                    nextPoll = DateTime.MinValue;
                    after = await ModifiedAsync(paths.Values, cancel).ConfigureAwait(false);
                    if (state.State == MacroPlayback.PlayState.Error)
                    {
                        Log.Warn("Aktualizacja danych: makro z błędem: " + state.Text);
                    }
                    form.Status(state.State == MacroPlayback.PlayState.Ok
                        ? "Mercalys zapisał raporty, czekam na wgranie na serwer. Możesz już używać komputera."
                        : "Makro skończyło się z błędem, czekam na wgranie zapisanych plików.");
                }
                else if (state.Text != step)
                {
                    step = state.Text;
                    lastChange = DateTime.UtcNow;
                    Log.Info("Aktualizacja danych: " + step);
                    form.Status(Working + "\n" + step);
                }
            }
            if (DateTime.UtcNow < nextPoll) continue;
            nextPoll = DateTime.UtcNow + PollEvery;
            List<ReportAttempt> attempts;
            try
            {
                attempts = await api.RecentReportsAsync(cancel).ConfigureAwait(false);
            }
            catch (Exception error) when (!(error is OperationCanceledException && cancel.IsCancellationRequested))
            {
                Log.Warn("Aktualizacja danych: stan wgrań niedostępny: " + error.Message);
                if (DateTime.UtcNow - lastChange <= GiveUpAfter) continue;
                attempts = new List<ReportAttempt>();
            }
            // Tylko pliki zapisane przez to makro: stary plik o tej samej nazwie (np. wysłany przy starcie agenta,
            // bo serwer go jeszcze nie wgrał) ani próba z poprzedniego makra nie są wynikiem tego.
            var fresh = attempts.Where(a => a.StartedAt >= start && a.FileModified != null && a.FileModified >= start
                && !known.Contains(a.Id)).ToList();
            var latest = fresh.GroupBy(a => a.Kind + "/" + a.FileName.ToLowerInvariant())
                .ToDictionary(g => g.Key, g => g.OrderByDescending(a => a.StartedAt).First());
            var outcome = new Outcome();
            var waiting = new List<MacroFile>();
            foreach (var file in files)
            {
                latest.TryGetValue(file.Key, out var attempt);
                var status = attempt?.Status ?? "";
                if (attempt != null && seen.Add(file.Key + " " + status)) lastChange = DateTime.UtcNow;
                var label = file.Title + " (" + file.FileName + ")";
                if (status == "IMPORTED")
                {
                    outcome.Done++;
                    form.Row(file, "wgrany", RowState.Done);
                }
                else if (status == "FAILED" && !attempt!.Retryable)
                {
                    outcome.Failed.Add(file);
                    outcome.Rejected = true;
                    outcome.Problems.Add(label + ": " + (attempt.Error ?? "nie wgrano"));
                    form.Row(file, "błąd: " + (attempt.Error ?? "nie wgrano"), RowState.Failed);
                }
                else if (attempt == null && result != null && !Saved(file, paths, before, after))
                {
                    if (result.State == MacroPlayback.PlayState.Ok)
                    {
                        // Okres bez danych (np. sprzedaż) nie ma pliku.
                        outcome.Done++;
                        form.Row(file, "bez pliku (brak danych w okresie)", RowState.Done);
                    }
                    else
                    {
                        outcome.Failed.Add(file);
                        outcome.Problems.Add(label + ": Mercalys nie zapisał pliku");
                        form.Row(file, "nie zapisany w Mercalys", RowState.Failed);
                    }
                }
                else
                {
                    waiting.Add(file);
                    form.Row(file, attempt == null
                        ? result == null ? "czeka na Mercalys" : "zapisany, czeka na wysłanie"
                        : status switch
                        {
                            "FAILED" => "serwer spróbuje ponownie: " + attempt.Error,
                            "INTERRUPTED" => "przerwany, wysyłam ponownie",
                            "IMPORTING" => "serwer wgrywa",
                            _ => "wysyłanie na serwer",
                        }, RowState.Working);
                }
            }
            form.Progress(outcome.Done, files.Count);
            run.Pending = outcome.Failed.Concat(waiting).Select(f => f.Kind).Distinct().ToList();
            var macroEnded = playback == null || result != null;
            var gaveUp = DateTime.UtcNow - lastChange > GiveUpAfter;
            if ((waiting.Count > 0 || !macroEnded) && !gaveUp) continue;

            foreach (var attempt in fresh) known.Add(attempt.Id);
            if (gaveUp)
            {
                // Makro, które od 90 minut stoi, zatrzymujemy: inaczej odtwarzacz nie podjąłby następnego.
                if (!macroEnded) MacroRunner.PressEsc();
                outcome.Stopped = true;
                foreach (var file in waiting)
                {
                    outcome.Failed.Add(file);
                    outcome.Problems.Add(file.Title + " (" + file.FileName + "): od 90 minut nic się nie zmieniło");
                    form.Row(file, "nie doczekano się", RowState.Failed);
                }
            }
            if (result is { Stopped: true }) outcome.Stopped = true;
            if (outcome.Failed.Count > 0 && result is { State: MacroPlayback.PlayState.Error })
            {
                var text = result.Text.Length > 700 ? result.Text.Substring(0, 700) + "…" : result.Text;
                outcome.Problems.Insert(0, text);
            }
            return outcome;
        }
    }

    /// <summary>Czy makro zapisało plik (zmieniła się jego data). Bez odczytu dysku zakładamy, że tak, i czekamy.</summary>
    private static bool Saved(MacroFile file, Dictionary<string, string> paths, Dictionary<string, DateTime>? before,
        Dictionary<string, DateTime>? after)
    {
        if (before == null || after == null) return true;
        return !paths.TryGetValue(file.Key, out var path) || before[path] != after[path];
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
            var player = MacroRunner.FindPlayer()?.Name;
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

    private T OnUi<T>(Func<T> action) => (T)ui.Invoke(action);

    private void ShowInfo(string text) => ui.BeginInvoke(new Action(() =>
        MessageBox.Show(text, "Intermanage – aktualizacja danych", MessageBoxButtons.OK, MessageBoxIcon.Information)));
}
