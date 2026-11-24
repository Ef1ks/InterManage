using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Media;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace Intermanage.Agent;

/// <summary>
/// Makra zlecone z przeglądarki na wolnym komputerze sklepu (od 1.7.0). Agent co kilka sekund zgłasza stan komputera
/// (bezczynność, blokada ekranu, odtwarzacz, trwające makro), a serwer proponuje makro najdłużej nieużywanemu. Wtedy
/// okno odlicza; „Anuluj” oddaje zlecenie kolejnemu komputerowi. Ruch myszy nie anuluje: drgająca mysz albo sięganie
/// po przycisk zamykały okno od razu (07 w 10023, 24.11.2026), a komputer w użyciu dostaje dłuższe odliczanie.
///
/// Makro składa serwer, wykonuje je odtwarzacz (schowek i lewy Alt+E, jak przy aktualizacji danych). Wynik i zebrane
/// kody odtwarzacz zapisuje w pliku, na który wskazuje pierwsza linia makra (<c>#RESULT|id</c>, od 0.64; starszy
/// odtwarzacz wyniku nie zapisze, więc komputer z nim nie bierze makr).
/// </summary>
internal sealed class RemoteMacros : IDisposable
{
    private static readonly TimeSpan PollEvery = TimeSpan.FromSeconds(3);
    private static readonly TimeSpan PollAfterError = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan PlayerCheckEvery = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan PlayerSearchEvery = TimeSpan.FromSeconds(30);
    /// <summary>Tyle odtwarzacz ma na podjęcie makra po Alt+E.</summary>
    private static readonly TimeSpan PickUpTimeout = TimeSpan.FromSeconds(15);
    /// <summary>Tyle bez procesu odtwarzacza w trakcie makra = ktoś go zamknął.</summary>
    private static readonly TimeSpan PlayerGone = TimeSpan.FromSeconds(20);
    private static readonly Version NeededPlayer = new(0, 64);
    /// <summary>Odliczanie na komputerze, na którym ktoś ostatnio pracował (serwer czeka na start 30 s).</summary>
    private const int BusyCountdownSeconds = 15;
    private const int InUseSeconds = 120;

    /// <summary>Folder wyników; ten sam składa odtwarzacz (player.ahk, WriteRemote).</summary>
    private static string ResultFolder => Path.Combine(AgentSettings.Folder, "makra");

    private readonly AgentHost host;
    private readonly Control ui;
    private readonly Func<AgentSettings> settings;
    private volatile bool sessionLocked;
    private volatile bool playerReady;
    private DateTime playerCheckedAt = DateTime.MinValue;
    private string? current;
    private bool problemLogged;

    public RemoteMacros(AgentHost host, Control ui, Func<AgentSettings> settings)
    {
        this.host = host;
        this.ui = ui;
        this.settings = settings;
    }

    public void Start(CancellationToken cancel)
    {
        SystemEvents.SessionSwitch += OnSessionSwitch;
        Task.Run(() => LoopAsync(cancel));
    }

    private void OnSessionSwitch(object sender, SessionSwitchEventArgs e)
    {
        switch (e.Reason)
        {
            case SessionSwitchReason.SessionLock:
            case SessionSwitchReason.ConsoleDisconnect:
            case SessionSwitchReason.RemoteDisconnect:
                sessionLocked = true;
                break;
            case SessionSwitchReason.SessionUnlock:
            case SessionSwitchReason.ConsoleConnect:
            case SessionSwitchReason.RemoteConnect:
            case SessionSwitchReason.SessionLogon:
                sessionLocked = false;
                break;
        }
    }

    private bool Locked => sessionLocked || MacroRunner.DesktopLocked();

    private async Task LoopAsync(CancellationToken cancel)
    {
        while (!cancel.IsCancellationRequested)
        {
            var wait = PollEvery;
            try
            {
                var api = host.Api;
                if (api != null && host.Config != null)
                {
                    CheckPlayer();
                    // Makro zlecone albo aktualizacja danych zajmuje bramkę.
                    var busy = MacroRunner.Gate.CurrentCount == 0;
                    var answer = await api.MacroPollAsync(MacroRunner.IdleSeconds(), Locked, playerReady, busy, cancel)
                        .ConfigureAwait(false);
                    problemLogged = false;
                    var offer = answer.Offer;
                    if (offer != null && Interlocked.CompareExchange(ref current, offer.Id, null) == null)
                    {
                        _ = Task.Run(() => HandleAsync(api, offer, cancel));
                    }
                }
            }
            catch (OperationCanceledException) when (cancel.IsCancellationRequested)
            {
                return;
            }
            catch (Exception error)
            {
                // Stan połączenia pokazuje pętla /hello; tu tylko jeden wpis na przerwę (albo serwer bez makr).
                if (!problemLogged) Log.Warn("Makra z przeglądarki: " + error.Message);
                problemLogged = true;
                wait = PollAfterError;
            }
            try { await Task.Delay(wait, cancel).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
        }
    }

    private void CheckPlayer()
    {
        // Bez odtwarzacza pełne szukanie rzadziej (przegląda wszystkie programy).
        if (DateTime.UtcNow - playerCheckedAt < (playerReady ? PlayerCheckEvery : PlayerSearchEvery)) return;
        playerCheckedAt = DateTime.UtcNow;
        var version = MacroRunner.FindPlayer()?.Version;
        playerReady = version != null && version >= NeededPlayer;
    }

    private async Task HandleAsync(ApiClient api, MacroOffer offer, CancellationToken cancel)
    {
        MacroForm? form = null;
        var gate = false;
        var started = false;
        try
        {
            if (!MacroRunner.Gate.Wait(0))
            {
                await api.MacroDeclineAsync(offer.Id, cancel).ConfigureAwait(false);
                return;
            }
            gate = true;
            Log.Info("Makro z przeglądarki: " + offer.Title + " (" + offer.RequestedBy + "), odliczanie");
            form = OnUi(() =>
            {
                var created = new MacroForm(offer);
                created.Show();
                SystemSounds.Exclamation.Play();
                return created;
            });
            if (!await CountdownAsync(form, offer.CountdownSeconds, cancel).ConfigureAwait(false))
            {
                Log.Info("Makro z przeglądarki anulowane przy komputerze");
                form.CloseNow();
                await api.MacroDeclineAsync(offer.Id, cancel).ConfigureAwait(false);
                return;
            }
            var start = await api.MacroStartAsync(offer.Id, cancel).ConfigureAwait(false);
            started = true;
            form.Running(offer.Title);
            var result = await PlayAsync(offer.Id, start, cancel).ConfigureAwait(false);
            Log.Info("Makro z przeglądarki: " + (result.Ok ? "zakończone" : "błąd: " + result.Message));
            form.Finish(result.Ok, result.Message);
            await FinishAsync(api, offer.Id, result, cancel).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
        }
        catch (RejectedException error)
        {
            Log.Info("Makro z przeglądarki: " + error.Message);
            form?.CloseNow();
        }
        catch (Exception error)
        {
            Log.Error("Makro z przeglądarki", error);
            if (started)
            {
                form?.Finish(false, error.Message);
                await FinishAsync(api, offer.Id, new Result(false, error.Message), cancel).ConfigureAwait(false);
            }
            else
            {
                // Bez startu serwer sam przekaże zlecenie dalej.
                form?.CloseNow();
            }
        }
        finally
        {
            if (gate) MacroRunner.Gate.Release();
            Interlocked.Exchange(ref current, null);
        }
    }

    /// <summary>Odliczanie; false = anulowane („Anuluj” albo zablokowany ekran).</summary>
    private async Task<bool> CountdownAsync(MacroForm form, int seconds, CancellationToken cancel)
    {
        if (MacroRunner.IdleSeconds() < InUseSeconds) seconds = Math.Max(seconds, BusyCountdownSeconds);
        var end = DateTime.UtcNow.AddSeconds(Math.Max(1, Math.Min(seconds, 20)));
        while (true)
        {
            var left = end - DateTime.UtcNow;
            if (left <= TimeSpan.Zero) return true;
            form.Countdown((int)Math.Ceiling(left.TotalSeconds));
            await Task.Delay(100, cancel).ConfigureAwait(false);
            if (form.Cancelled || Locked) return false;
        }
    }

    private async Task<Result> PlayAsync(string id, MacroStart start, CancellationToken cancel)
    {
        var key = Guid.Parse(id).ToString("N");
        var file = Path.Combine(ResultFolder, key + ".txt");
        Directory.CreateDirectory(ResultFolder);
        CleanUp();
        MercalysPath.Ensure();
        // Treść z hasłem Mercalys tylko przez schowek, nigdy w pliku.
        var password = settings().MercalysPassword;
        var text = "#RESULT|" + key + "\r\n" + MacroRunner.WithPassword(start.Macro, start.OpenLine, password);
        OnUi(() =>
        {
            Clipboard.SetDataObject(text, true, 10, 100);
            return true;
        });
        await Task.Delay(500, cancel).ConfigureAwait(false);
        MacroRunner.PressAltE();

        var pickUpUntil = DateTime.UtcNow + PickUpTimeout;
        while (Read(file) == null)
        {
            if (DateTime.UtcNow > pickUpUntil)
            {
                // Niepodjęte makro nie może zostać w schowku: następny Alt+E pracownika by je uruchomił.
                OnUi(() =>
                {
                    if (Clipboard.ContainsText() && Clipboard.GetText().StartsWith("#RESULT|" + key)) Clipboard.Clear();
                    return true;
                });
                return new Result(false, "Odtwarzacz makr (AutoJob) nie podjął makra. Na komputerze może być otwarte okno "
                    + "poprzedniego makra.");
            }
            await Task.Delay(300, cancel).ConfigureAwait(false);
        }

        var until = DateTime.UtcNow.AddMinutes(Math.Max(1, start.TimeoutMinutes - 1));
        var playerSeen = DateTime.UtcNow;
        var playerCheck = DateTime.UtcNow;
        while (true)
        {
            await Task.Delay(500, cancel).ConfigureAwait(false);
            var read = Read(file);
            if (read != null && read.State != "RUNNING")
            {
                TryDelete(file);
                // Błąd cytuje linię makra, a w pierwszej jest hasło Mercalys: nie wysyłamy go na serwer.
                var message = string.IsNullOrEmpty(password) ? read.Text : read.Text.Replace("|" + password, "|***");
                return new Result(read.State == "OK", message, read.Codes);
            }
            if (DateTime.UtcNow - playerCheck > TimeSpan.FromSeconds(5))
            {
                playerCheck = DateTime.UtcNow;
                if (MacroRunner.FindPlayer() != null) playerSeen = DateTime.UtcNow;
                if (DateTime.UtcNow - playerSeen > PlayerGone)
                {
                    return new Result(false, "Odtwarzacz makr został zamknięty w trakcie makra. Sprawdź w Mercalys, co "
                        + "zostało zrobione.");
                }
            }
            if (DateTime.UtcNow > until)
            {
                return new Result(false, "Makro nie skończyło się w wyznaczonym czasie. Sprawdź komputer w sklepie.");
            }
        }
    }

    /// <summary>Wynik na serwer; przy braku połączenia ponawiamy, odrzucony (409) nie ma sensu.</summary>
    private static async Task FinishAsync(ApiClient api, string id, Result result, CancellationToken cancel)
    {
        for (var attempt = 0; attempt < 20; attempt++)
        {
            try
            {
                await api.MacroFinishAsync(id, result.Ok, result.Message, result.Codes, cancel).ConfigureAwait(false);
                return;
            }
            catch (RejectedException error)
            {
                Log.Warn("Makro z przeglądarki: serwer nie przyjął wyniku: " + error.Message);
                return;
            }
            catch (Exception error) when (!(error is OperationCanceledException))
            {
                Log.Warn("Makro z przeglądarki: wynik jeszcze niewysłany: " + error.Message);
            }
            try { await Task.Delay(5000, cancel).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
        }
    }

    private sealed class Result
    {
        public Result(bool ok, string message, IReadOnlyList<string>? codes = null)
        {
            Ok = ok;
            Message = message;
            Codes = codes ?? new List<string>();
        }

        public bool Ok { get; }
        public string Message { get; }
        public IReadOnlyList<string> Codes { get; }
    }

    private sealed class ResultFile
    {
        public string State = "";
        public List<string> Codes = new();
        public string Text = "";
    }

    private static ResultFile? Read(string path)
    {
        try
        {
            if (!File.Exists(path)) return null;
            var lines = File.ReadAllText(path, Encoding.UTF8).Split(new[] { '\n' }, 3);
            return new ResultFile
            {
                State = lines[0].Trim(),
                Codes = lines.Length < 2 ? new List<string>() : lines[1].Split(',').Select(code => code.Trim())
                    .Where(code => code.Length > 0).ToList(),
                Text = lines.Length < 3 ? "" : lines[2].Trim(),
            };
        }
        catch (IOException)
        {
            return null; // odtwarzacz właśnie podmienia plik
        }
        catch (UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>Wyniki przerwanych makr (zamknięty agent) starsze niż doba.</summary>
    private static void CleanUp()
    {
        foreach (var path in Directory.GetFiles(ResultFolder))
        {
            if (File.GetLastWriteTimeUtc(path) < DateTime.UtcNow.AddDays(-1)) TryDelete(path);
        }
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (Exception) { /* sprzątnie następne makro */ }
    }

    private T OnUi<T>(Func<T> action) => (T)ui.Invoke(action);

    public void Dispose() => SystemEvents.SessionSwitch -= OnSessionSwitch;
}
