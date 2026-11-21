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
/// </summary>
internal sealed class AgentHost : IDisposable
{
    private static readonly TimeSpan HelloEvery = TimeSpan.FromMinutes(5);

    private readonly object gate = new();
    private CancellationTokenSource? running;
    private FolderWatcher? watcher;
    private string? lastProblem;

    public event Action? StateChanged;

    public ApiClient? Api { get; private set; }
    public HelloResponse? Config { get; private set; }
    public AgentState State { get; private set; } = AgentState.NotConfigured;
    public string StatusText { get; private set; } = "Wklej klucz agenta w Ustawieniach";
    public string? LastActivity { get; private set; }

    private bool serverDown;
    private bool diskDown;

    public void Start(AgentSettings settings)
    {
        Stop();
        var key = settings.Key;
        if (string.IsNullOrEmpty(key))
        {
            Set(AgentState.NotConfigured, "Wklej klucz agenta w Ustawieniach");
            return;
        }
        var cts = new CancellationTokenSource();
        var reports = new ReportUploader(this);
        var folders = new FolderWatcher(this, reports);
        lock (gate)
        {
            Api = new ApiClient(settings.ServerUrl, key!);
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
    }

    public void ScanNow() => watcher?.ScanNow();

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
                Config = config;
                if (first) Log.Info("Połączono: sklep " + config.StoreNumber + ", agent " + config.AgentName);
                ServerOk();
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
            : "Sklep " + config.StoreNumber + (config.Enabled ? ": działa" : ": połączony, sklep pracuje przez przeglądarkę");
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
