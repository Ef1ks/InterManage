using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Intermanage.Agent;

/// <summary>
/// Pilnowanie folderów sklepu z konfiguracji (<c>watches</c>): pliki cen z kasy TRACEART (z podfolderem BACKUP), zmiany
/// cen CTRLPRIX (.traite0 → .TraiteN po wczytaniu przez program sklepu) i dziennik ATHOS. Zmiany widzi od razu
/// (<see cref="FileSystemWatcher"/>), zbiera je z odbiciem 500 ms (Windows zgłasza jedną zmianę kilka razy) i wysyła
/// paczką najpóźniej sekundę po wykryciu; pliki, o które poprosi serwer, wysyła od razu po odpowiedzi.
///
/// Na dysku sieciowym watcher potrafi przestać działać bez słowa albo zgłosić błąd (przepełniony bufor, zerwane
/// połączenie SMB), więc po błędzie jest odtwarzany z pełnym przeglądem folderu, a niezależnie od tego co 5 minut
/// idzie pełny przegląd (nazwy, daty, rozmiary) jako uzgodnienie stanu z serwerem.
/// </summary>
internal sealed class FolderWatcher : IDisposable
{
    private static readonly TimeSpan Debounce = TimeSpan.FromMilliseconds(500);
    private static readonly TimeSpan FullScanEvery = TimeSpan.FromMinutes(5);
    private const int MaxBuffered = 5000;
    private const int EventsPerRequest = 1000;
    private const int MaxSubfolderDepth = 3;
    /// <summary>
    /// Paczka cen z kasy: najwyżej 10 plików i 2 MB po spakowaniu, żeby przy wolnym łączu sklepu (200 kB/s) jedna
    /// wysyłka trwała kilkanaście sekund, a nie dłużej niż limit Cloudflare (ok. 100 s), i nie zatykała łącza na minuty.
    /// </summary>
    private const int PriceFilesPerUpload = 10;
    private const long PriceBytesPerUpload = 2L * 1024 * 1024;
    /// <summary>Zakładka przy końcówce dziennika ATHOS: blok wysyłki mógł zacząć się przed poprzednim końcem pliku.</summary>
    private const int AthosOverlapBytes = 64 * 1024;

    private sealed class Root
    {
        public string Path = "";
        public bool Subfolders;
        public List<(WatchConfig Watch, Regex Pattern)> Watches = new();
        public FileSystemWatcher? Watcher;
    }

    private readonly AgentHost host;
    private readonly ConcurrentDictionary<string, DateTime> changed = new(StringComparer.OrdinalIgnoreCase);
    private readonly List<FolderEvent> outbox = new();
    private readonly object outboxGate = new();
    /// <summary>Pliki TRACEART, których serwer już nie potrzebuje (wczytane albo pominięte): przegląd ich nie powtarza.</summary>
    private readonly HashSet<string> doneTraceart = new(StringComparer.OrdinalIgnoreCase);
    /// <summary>Ostatnio widziane pliki: nazwa → pełna ścieżka (TRACEART przenosi się do BACKUP).</summary>
    private readonly ConcurrentDictionary<string, string> paths = new(StringComparer.OrdinalIgnoreCase);
    private readonly SemaphoreSlim fullScanRequested = new(0, 1);
    /// <summary>Długość dziennika ATHOS przy ostatniej udanej wysyłce (ścieżka → bajty); kolejna wysyła tylko końcówkę.</summary>
    private readonly Dictionary<string, long> athosSent = new(StringComparer.OrdinalIgnoreCase);
    private List<Root> roots = new();
    private string configSignature = "";
    private DateTime nextFullScan = DateTime.MinValue;

    public FolderWatcher(AgentHost host) => this.host = host;

    /// <summary>Pełny przegląd od razu (menu „Sprawdź teraz”).</summary>
    public void ScanNow()
    {
        nextFullScan = DateTime.MinValue;
        if (fullScanRequested.CurrentCount == 0)
        {
            try { fullScanRequested.Release(); } catch (SemaphoreFullException) { }
        }
    }

    public async Task RunAsync(CancellationToken cancel)
    {
        var sendDelay = TimeSpan.FromSeconds(1);
        var diskDelay = TimeSpan.FromSeconds(10);
        var nextDiskTry = DateTime.MinValue;
        while (!cancel.IsCancellationRequested)
        {
            var config = host.Config;
            var api = host.Api;
            if (config != null && api != null)
            {
                Reconfigure(config);
                try
                {
                    if (DateTime.UtcNow >= nextFullScan && DateTime.UtcNow >= nextDiskTry)
                    {
                        var ok = await FullScanAsync(cancel);
                        nextFullScan = DateTime.UtcNow + FullScanEvery;
                        if (ok)
                        {
                            diskDelay = TimeSpan.FromSeconds(10);
                        }
                        else
                        {
                            nextDiskTry = DateTime.UtcNow + diskDelay;
                            diskDelay = TimeSpan.FromSeconds(Math.Min(300, diskDelay.TotalSeconds * 2));
                        }
                    }
                    CollectChanged();
                    if (await FlushAsync(api, cancel)) sendDelay = TimeSpan.FromSeconds(1);
                }
                catch (OperationCanceledException) when (cancel.IsCancellationRequested)
                {
                    return;
                }
                catch (InvalidKeyException error)
                {
                    host.KeyRejected(error.Message);
                }
                catch (Exception error)
                {
                    host.ServerProblem(error.Message);
                    await Wait(sendDelay, cancel);
                    sendDelay = TimeSpan.FromSeconds(Math.Min(60, sendDelay.TotalSeconds * 2));
                    continue;
                }
            }
            await Wait(TimeSpan.FromMilliseconds(250), cancel);
        }
    }

    private async Task Wait(TimeSpan time, CancellationToken cancel)
    {
        try { await fullScanRequested.WaitAsync(time, cancel); } catch (OperationCanceledException) { }
    }

    /// <summary>Nowa konfiguracja z serwera: watchery od nowa, gdy zmieniły się foldery.</summary>
    private void Reconfigure(HelloResponse config)
    {
        var signature = string.Join("|", config.Watches.Select(w => w.Kind + ">" + w.Path + ">" + w.Subfolders + ">" + w.Pattern));
        if (signature == configSignature) return;
        configSignature = signature;
        DisposeWatchers();
        roots = config.Watches
            .Where(w => !string.IsNullOrWhiteSpace(w.Path))
            .GroupBy(w => w.Path.Trim().TrimEnd('\\'), StringComparer.OrdinalIgnoreCase)
            .Select(group => new Root
            {
                Path = group.Key,
                Subfolders = group.Any(w => w.Subfolders),
                Watches = group.Select(w => (w, new Regex(w.Pattern, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)))
                    .ToList(),
            })
            .ToList();
        foreach (var root in roots) StartWatcher(root);
        nextFullScan = DateTime.MinValue;
        Log.Info("Pilnowane foldery: " + string.Join(", ", roots.Select(r => r.Path)));
    }

    private void StartWatcher(Root root)
    {
        try
        {
            if (!Directory.Exists(root.Path))
            {
                host.DiskProblem("Folder " + root.Path + " jest niedostępny");
                return;
            }
            var watcher = new FileSystemWatcher(root.Path)
            {
                IncludeSubdirectories = root.Subfolders,
                InternalBufferSize = 64 * 1024,
                NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite | NotifyFilters.Size,
            };
            watcher.Created += (_, e) => Touch(e.FullPath);
            watcher.Changed += (_, e) => Touch(e.FullPath);
            watcher.Renamed += (_, e) => Touch(e.FullPath);
            watcher.Error += (_, e) =>
            {
                Log.Warn("Pilnowanie folderu " + root.Path + " przerwane: " + e.GetException()?.Message);
                // Odtworzenie z pełnym przeglądem (zdarzenia z przepełnionego bufora przepadły).
                try { root.Watcher?.Dispose(); } catch { }
                root.Watcher = null;
                ScanNow();
            };
            watcher.EnableRaisingEvents = true;
            root.Watcher = watcher;
        }
        catch (Exception error)
        {
            host.DiskProblem("Nie udało się pilnować folderu " + root.Path + ": " + error.Message);
        }
    }

    private void Touch(string path) => changed[path] = DateTime.UtcNow;

    /// <summary>Zmiany starsze niż odbicie → zdarzenia do wysłania.</summary>
    private void CollectChanged()
    {
        var now = DateTime.UtcNow;
        foreach (var entry in changed.ToArray())
        {
            if (now - entry.Value < Debounce) continue;
            if (!changed.TryRemove(entry.Key, out _)) continue;
            var root = roots.FirstOrDefault(r => Inside(r, entry.Key));
            if (root == null) continue;
            var name = Path.GetFileName(entry.Key);
            FileInfo? info = null;
            try
            {
                info = new FileInfo(entry.Key);
                if (!info.Exists) info = null;
            }
            catch { info = null; }
            foreach (var (watch, pattern) in root.Watches)
            {
                if (!pattern.IsMatch(name) || (!watch.Subfolders && !DirectlyIn(root, entry.Key))) continue;
                if (info != null) paths[name] = info.FullName;
                Enqueue(new FolderEvent
                {
                    Kind = info == null ? "DELETED" : "CHANGED",
                    FolderKey = watch.Kind,
                    FileName = name,
                    Size = info?.Length,
                    Modified = info == null ? null : new DateTimeOffset(info.LastWriteTimeUtc),
                    At = DateTimeOffset.UtcNow,
                });
            }
        }
        // Watcher, który padł albo nie wystartował (dysk wrócił), próbujemy postawić od nowa.
        foreach (var root in roots.Where(r => r.Watcher == null)) StartWatcher(root);
    }

    /// <summary>Pełny przegląd: wszystkie pasujące pliki jako PRESENT (TRACEART bez tych, których serwer już nie chce).</summary>
    private async Task<bool> FullScanAsync(CancellationToken cancel)
    {
        var ok = true;
        foreach (var root in roots)
        {
            List<FileInfo> files;
            try
            {
                files = await Disk.Run(() => List(root), TimeSpan.FromSeconds(60), cancel);
            }
            catch (Exception error) when (!(error is OperationCanceledException))
            {
                host.DiskProblem("Folder " + root.Path + " jest niedostępny: " + error.Message);
                ok = false;
                continue;
            }
            foreach (var info in files)
            {
                foreach (var (watch, pattern) in root.Watches)
                {
                    if (!pattern.IsMatch(info.Name) || (!watch.Subfolders && !DirectlyIn(root, info.FullName))) continue;
                    paths[info.Name] = info.FullName;
                    if (watch.Kind == "TRACEART")
                    {
                        lock (doneTraceart)
                        {
                            if (doneTraceart.Contains(info.Name)) continue;
                        }
                    }
                    Enqueue(new FolderEvent
                    {
                        Kind = "PRESENT",
                        FolderKey = watch.Kind,
                        FileName = info.Name,
                        Size = info.Length,
                        Modified = new DateTimeOffset(info.LastWriteTimeUtc),
                        At = DateTimeOffset.UtcNow,
                    });
                }
            }
        }
        if (ok) host.DiskOk();
        host.Scanned();
        return ok;
    }

    private static List<FileInfo> List(Root root)
    {
        var result = new List<FileInfo>();
        Collect(new DirectoryInfo(root.Path), root.Subfolders ? MaxSubfolderDepth : 0, result);
        return result;
    }

    private static void Collect(DirectoryInfo folder, int depth, List<FileInfo> result)
    {
        result.AddRange(folder.EnumerateFiles());
        if (depth <= 0) return;
        foreach (var sub in folder.EnumerateDirectories())
        {
            try { Collect(sub, depth - 1, result); } catch (Exception error) when (error is IOException || error is UnauthorizedAccessException) { }
        }
    }

    private void Enqueue(FolderEvent folderEvent)
    {
        lock (outboxGate)
        {
            // Ta sama zmiana dwa razy w paczce: zostaje nowsza.
            outbox.RemoveAll(e => e.FolderKey == folderEvent.FolderKey
                && string.Equals(e.FileName, folderEvent.FileName, StringComparison.OrdinalIgnoreCase));
            outbox.Add(folderEvent);
            // Bez połączenia trzymamy najwyżej 5000 zdarzeń; resztę i tak uzgodni pełny przegląd.
            if (outbox.Count > MaxBuffered) outbox.RemoveRange(0, outbox.Count - MaxBuffered);
        }
    }

    /// <summary>Wysyła zebrane zdarzenia; <c>true</c>, gdy nic nie zostało.</summary>
    private async Task<bool> FlushAsync(ApiClient api, CancellationToken cancel)
    {
        while (true)
        {
            List<FolderEvent> batch;
            lock (outboxGate)
            {
                if (outbox.Count == 0) return true;
                batch = outbox.Take(EventsPerRequest).ToList();
            }
            var response = await api.EventsAsync(batch, cancel);
            host.ServerOk();
            lock (outboxGate) outbox.RemoveAll(batch.Contains);
            // TRACEART, o które serwer nie poprosił, już ma (albo ich nie chce): przegląd ich nie powtarza.
            var requested = new HashSet<string>(response.Requests.Select(r => r.FileName), StringComparer.OrdinalIgnoreCase);
            lock (doneTraceart)
            {
                foreach (var e in batch.Where(e => e.FolderKey == "TRACEART" && !requested.Contains(e.FileName)))
                {
                    doneTraceart.Add(e.FileName);
                }
            }
            await SendRequestedAsync(api, response.Requests, cancel);
        }
    }

    private async Task SendRequestedAsync(ApiClient api, List<FileRequest> requests, CancellationToken cancel)
    {
        var prices = new List<(string Name, byte[] Content)>();
        long bytes = 0;
        foreach (var request in requests)
        {
            if (!paths.TryGetValue(request.FileName, out var path)) continue;
            var content = await Disk.ReadWhenReady(path, cancel);
            if (content == null) continue;
            if (request.FolderKey == "ATHOS")
            {
                await SendAthosAsync(api, request.FileName, path, content, cancel);
                continue;
            }
            if (request.FolderKey != "TRACEART") continue;
            var packed = ApiClient.Gzip(content);
            if (prices.Count > 0 && (prices.Count >= PriceFilesPerUpload || bytes + packed.Length > PriceBytesPerUpload))
            {
                await UploadPricesAsync(api, prices, cancel);
                prices.Clear();
                bytes = 0;
            }
            prices.Add((request.FileName, packed));
            bytes += packed.Length;
        }
        if (prices.Count > 0) await UploadPricesAsync(api, prices, cancel);
    }

    /// <summary>
    /// Dziennik ATHOS tylko rośnie, więc po pierwszej wysyłce idzie sama końcówka od poprzedniego końca, z zakładką na
    /// blok wysyłki, który mógł zacząć się wcześniej (od początku wiersza). Plik krótszy niż poprzednio (nowy dziennik)
    /// idzie w całości. Serwer pomija wysyłki, które już zna, a końcówka bez wysyłek nie jest błędem.
    /// </summary>
    private async Task SendAthosAsync(ApiClient api, string name, string path, byte[] content, CancellationToken cancel)
    {
        long previous;
        lock (athosSent)
        {
            if (!athosSent.TryGetValue(path, out previous)) previous = 0;
        }
        var start = previous > 0 && content.Length >= previous ? Math.Max(0, (int)previous - AthosOverlapBytes) : 0;
        if (start > 0)
        {
            var newline = Array.IndexOf(content, (byte)10, start);
            start = newline < 0 ? 0 : newline + 1;
        }
        var part = start == 0 ? content : content.Skip(start).ToArray();
        DateTimeOffset modified;
        try { modified = new DateTimeOffset(File.GetLastWriteTimeUtc(path)); }
        catch { modified = DateTimeOffset.UtcNow; }
        await api.UploadAthosAsync(name, part, modified, true, cancel);
        lock (athosSent)
        {
            athosSent[path] = content.Length;
        }
        Log.Info("Wysłano dziennik ATHOS " + name + (start > 0 ? " (końcówka, " + part.Length + " B)" : ""));
        host.Sent("dziennik ATHOS");
    }

    private async Task UploadPricesAsync(ApiClient api, List<(string Name, byte[] Content)> files,
        CancellationToken cancel)
    {
        await api.UploadPriceFilesAsync(files, cancel);
        lock (doneTraceart)
        {
            foreach (var file in files) doneTraceart.Add(file.Name);
        }
        Log.Info("Wysłano pliki cen z kasy: " + files.Count);
        host.Sent("ceny z kasy (" + files.Count + ")");
    }

    private static bool Inside(Root root, string path) =>
        path.StartsWith(root.Path + "\\", StringComparison.OrdinalIgnoreCase);

    private static bool DirectlyIn(Root root, string path) =>
        string.Equals(Path.GetDirectoryName(path)?.TrimEnd('\\'), root.Path, StringComparison.OrdinalIgnoreCase);

    private void DisposeWatchers()
    {
        foreach (var root in roots)
        {
            try { root.Watcher?.Dispose(); } catch { }
            root.Watcher = null;
        }
    }

    public void Dispose() => DisposeWatchers();
}
