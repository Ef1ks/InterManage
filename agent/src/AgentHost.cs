using System;
using System.Threading;
using System.Threading.Tasks;

namespace Intermanage.Agent;

internal enum AgentState
{
    /// <summary>Zielona ikona: serwer i dyski odpowiadają.</summary>
    Working,
    /// <summary>Bursztynowa ikona: dysk albo serwer niedostępny (agent ponawia sam).</summary>
    Degraded,
    /// <summary>Czerwona ikona: serwer odrzucił klucz (trzeba wkleić nowy w Ustawieniach).</summary>
    KeyRejected,
    /// <summary>Agent jeszcze nie ma klucza.</summary>
    NotConfigured,
}

/// <summary>
/// Serce agenta: konfiguracja z serwera (<c>/hello</c> przy starcie i co 5 minut), zapis plików z kolejki
/// (<see cref="FileSaver"/>), pilnowanie folderów (<see cref="FolderWatcher"/>) i stan pokazywany przy ikonie.
///
/// Agent może działać na każdym komputerze sklepu: pliki z kolejki zapisuje każdy, a folderów pilnuje i dane
/// z Mercalys aktualizuje tylko główny agent (<see cref="IsLeader"/>, dzierżawa na serwerze). Gdy główny przestaje
/// odpowiadać, rolę przejmuje inny komputer, a komputer preferowany w Ustawieniach sklepu odbiera ją od razu.
/// </summary>
internal sealed class AgentHost : IDisposable
{
    private static readonly TimeSpan HelloEvery = TimeSpan.FromMinutes(5);

    private readonly object gate = new();
    private CancellationTokenSource? running;
    private FolderWatcher? watcher;
    private string? lastProblem;

    public event Action? StateChanged;

    /// <summary>Pierwsza konfiguracja z serwera po starcie (np. aktualizacja danych przy starcie komputera).</summary>
    public event Action? Connected;

    /// <summary>Serwer podał obowiązującą wersję agenta (po każdym /hello, gdy jest paczka agenta).</summary>
    public event Action<ApiClient, AgentUpdateInfo>? UpdateOffered;

    /// <summary>Ten agent został albo przestał być głównym agentem sklepu.</summary>
    public event Action? LeadershipChanged;

    /// <summary>Główny agent sklepu: pilnuje folderów i aktualizuje dane z Mercalys.</summary>
    public bool IsLeader { get; private set; }

    public ApiClient? Api { get; private set; }
    public HelloResponse? Config { get; private set; }
    public AgentState State { get; private set; } = AgentState.NotConfigured;
    public string StatusText { get; private set; } = "Wklej klucz agenta w Ustawieniach";
    public string? LastActivity { get; private set; }

    private bool serverDown;
    private bool diskDown;

    /// <summary>Wyłączone w ustawieniach tego komputera: pliki zapisuje inny komputer albo przeglądarka.</summary>
    public bool SaveFiles { get; private set; } = true;

    public void Start(AgentSettings settings)
    {
        Stop();
        SaveFiles = settings.SaveFiles;
        var key = settings.Key;
        if (string.IsNullOrEmpty(key))
        {
            Set(AgentState.NotConfigured, "Wklej klucz agenta w Ustawieniach");
            return;
        }
        var cts = new CancellationTokenSource();
        var reports = new ReportUploader(this);
        var folders = new FolderWatcher(this, reports);
        var api = new ApiClient(settings.ServerUrl, key!);
        api.LeaderSeen += SetLeader;
        lock (gate)
        {
            Api = api;
            Config = null;
            running = cts;
            watcher = folders;
        }
        var cancel = cts.Token;
        Set(AgentState.Degraded, "Łączenie z " + settings.ServerUrl + "…");
        Task.Run(() => HelloLoopAsync(cancel));
        Task.Run(() => new FileSaver(this).RunAsync(cancel));
        Task.Run(() => folders.RunAsync(cancel));
        Task.Run(() => reports.RunAsync(cancel));
    }

    public void Stop()
    {
        lock (gate)
        {
            running?.Cancel();
            running = null;
            watcher?.Dispose();
            watcher = null;
            Api?.Dispose();
            Api = null;
            Config = null;
        }
        SetLeader(false);
    }

    private void SetLeader(bool leader)
    {
        if (leader == IsLeader) return;
        IsLeader = leader;
        if (Api != null)
        {
            Log.Info(leader ? "Ten komputer jest głównym agentem sklepu: pilnuje folderów"
                : "Główny agent sklepu działa na innym komputerze: ten tylko zapisuje pliki");
        }
        Refresh();
        LeadershipChanged?.Invoke();
    }

    public void ScanNow() => watcher?.ScanNow();

    // --- Zamykanie dla nowej wersji: trwające zapisy i wysyłki kończą się, nowe już nie zaczynają ---

    private readonly object workGate = new();
    private int working;
    private bool draining;

    /// <summary>Zapis albo wysyłka, których nowa wersja nie może przerwać; null = agent się zamyka, nie zaczynać.</summary>
    public IDisposable? BeginWork()
    {
        lock (workGate)
        {
            if (draining) return null;
            working++;
            return new Work(this);
        }
    }

    /// <summary>Koniec nowych zapisów; czeka na trwające, najwyżej <paramref name="limit"/>.</summary>
    public async Task DrainAsync(TimeSpan limit)
    {
        lock (workGate) draining = true;
        var until = DateTime.UtcNow + limit;
        while (DateTime.UtcNow < until)
        {
            lock (workGate)
            {
                if (working == 0) return;
            }
            await Task.Delay(200).ConfigureAwait(false);
        }
    }

    private sealed class Work : IDisposable
    {
        private AgentHost? host;

        public Work(AgentHost host) => this.host = host;

        public void Dispose()
        {
            var ended = Interlocked.Exchange(ref host, null);
            if (ended == null) return;
            lock (ended.workGate) ended.working--;
        }
    }

    private async Task HelloLoopAsync(CancellationToken cancel)
    {
        var delay = TimeSpan.FromSeconds(2);
        while (!cancel.IsCancellationRequested)
        {
            var api = Api;
            if (api == null) return;
            try
            {
                var config = await api.HelloAsync(Program.Version, cancel);
                var first = Config == null;
                // Starszy serwer bez dzierżawy: każdy agent pilnuje folderów jak dotąd.
                SetLeader(config.Leader ?? true);
                Limits.Use(config.Limits);
                Config = config;
                if (first)
                {
                    Log.Info("Połączono: sklep " + config.StoreNumber + ", agent " + config.AgentName);
                    Connected?.Invoke();
                }
                ServerOk();
                if (config.Update != null) UpdateOffered?.Invoke(api, config.Update);
                delay = TimeSpan.FromSeconds(2);
                await Task.Delay(HelloEvery, cancel);
            }
            catch (OperationCanceledException) when (cancel.IsCancellationRequested)
            {
                return;
            }
            catch (InvalidKeyException error)
            {
                KeyRejected(error.Message);
                await SafeDelay(TimeSpan.FromMinutes(5), cancel);
            }
            catch (Exception error)
            {
                ServerProblem(error.Message);
                await SafeDelay(delay, cancel);
                delay = TimeSpan.FromSeconds(Math.Min(60, delay.TotalSeconds * 2));
            }
        }
    }

    private static async Task SafeDelay(TimeSpan time, CancellationToken cancel)
    {
        try { await Task.Delay(time, cancel); } catch (OperationCanceledException) { }
    }

    public void ServerOk()
    {
        serverDown = false;
        Refresh();
    }

    public void DiskOk()
    {
        diskDown = false;
        Refresh();
    }

    public void ServerProblem(string message)
    {
        serverDown = true;
        Problem("Serwer niedostępny: " + message);
    }

    public void DiskProblem(string message)
    {
        diskDown = true;
        Problem(message);
    }

    public void KeyRejected(string message)
    {
        if (State != AgentState.KeyRejected) Log.Warn("Klucz odrzucony: " + message);
        Set(AgentState.KeyRejected, "Serwer odrzucił klucz agenta. Wklej nowy klucz w Ustawieniach.");
    }

    public void Saved(string fileName)
    {
        LastActivity = DateTime.Now.ToString("HH:mm:ss") + " zapisano " + fileName;
        Refresh();
    }

    public void Sent(string what)
    {
        LastActivity = DateTime.Now.ToString("HH:mm:ss") + " wysłano " + what;
        Refresh();
    }

    public void Scanned() => Refresh();

    private void Problem(string message)
    {
        if (message != lastProblem)
        {
            lastProblem = message;
            Log.Warn(message);
        }
        Set(AgentState.Degraded, message);
    }

    private void Refresh()
    {
        if (State == AgentState.KeyRejected && Config == null) return;
        if (serverDown || diskDown) return;
        lastProblem = null;
        var config = Config;
        var text = config == null
            ? "Łączenie…"
            : "Sklep " + config.StoreNumber + (!config.Enabled ? ": połączony, sklep pracuje przez przeglądarkę"
                : IsLeader ? ": działa (główny agent)" : ": działa (zapasowy, foldery pilnuje inny komputer)");
        Set(AgentState.Working, text);
    }

    private void Set(AgentState state, string text)
    {
        if (State == state && StatusText == text) return;
        State = state;
        StatusText = text;
        StateChanged?.Invoke();
    }

    public void Dispose() => Stop();
}
