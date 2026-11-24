using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Intermanage.Agent;

/// <summary>Serwer odrzucił klucz agenta (nieznany albo usunięty w Ustawieniach sklepu).</summary>
internal sealed class InvalidKeyException : Exception
{
    public InvalidKeyException(string message) : base(message) { }
}

/// <summary>Serwer nie odpowiada albo odpowiada błędem (agent ponowi z narastającą przerwą).</summary>
internal sealed class ServerUnavailableException : Exception
{
    public ServerUnavailableException(string message, Exception? inner = null) : base(message, inner) { }
}

/// <summary>Serwer odrzucił żądanie (np. 409: plik nie jest przydzielony temu agentowi).</summary>
internal sealed class RejectedException : Exception
{
    public RejectedException(int status, string message) : base(message) { Status = status; }

    public int Status { get; }
}

// Odpowiedzi serwera (klasy, bo .NET Framework 4.8 nie ma rekordów z init bez dodatków).
internal sealed class HelloResponse
{
    public string StoreId { get; set; } = "";
    public string StoreNumber { get; set; } = "";
    public string? StoreName { get; set; }
    public string? AgentName { get; set; }
    public bool Enabled { get; set; }
    public Dictionary<string, string> Folders { get; set; } = new();
    public List<WatchConfig> Watches { get; set; } = new();
    public int LongPollSeconds { get; set; } = 25;
    /// <summary>Główny agent sklepu: pilnuje folderów i aktualizuje dane z Mercalys (brak = starszy serwer, tak).</summary>
    public bool? Leader { get; set; }
    public bool Preferred { get; set; }
    /// <summary>Obowiązująca wersja agenta z „Do pobrania” (brak = bez automatycznej aktualizacji).</summary>
    public AgentUpdateInfo? Update { get; set; }
}

/// <summary>Paczka agenta do automatycznej aktualizacji: wersja, SHA-256 (hex) i rozmiar pliku ZIP.</summary>
internal sealed class AgentUpdateInfo
{
    public string Version { get; set; } = "";
    public string Sha256 { get; set; } = "";
    public long SizeBytes { get; set; }
}

internal sealed class WatchConfig
{
    public string Kind { get; set; } = "";
    public string Path { get; set; } = "";
    public bool Subfolders { get; set; }
    public string Pattern { get; set; } = "";
    /// <summary>Raporty Importu danych: plik makra, którego dzisiejsza data znaczy, że raport jest aktualny.</summary>
    public string? FreshPattern { get; set; }
}

internal sealed class QueueFile
{
    public string Id { get; set; } = "";
    public string FolderKey { get; set; } = "";
    public string FileName { get; set; } = "";
    public int Size { get; set; }
}

internal sealed class QueueFiles
{
    public List<QueueFile> Files { get; set; } = new();
}

internal sealed class FolderEvent
{
    public string Kind { get; set; } = "";
    public string FolderKey { get; set; } = "";
    public string FileName { get; set; } = "";
    public long? Size { get; set; }
    public DateTimeOffset? Modified { get; set; }
    public DateTimeOffset At { get; set; }
}

internal sealed class FileRequest
{
    public string FolderKey { get; set; } = "";
    public string FileName { get; set; } = "";
}

/// <summary>Wgrany raport do usunięcia z folderu (tylko gdy plik ma dalej tę datę zmiany).</summary>
internal sealed class FileDelete
{
    public string FolderKey { get; set; } = "";
    public string FileName { get; set; } = "";
    public DateTimeOffset Modified { get; set; }
}

internal sealed class EventsResponse
{
    public List<FileRequest> Requests { get; set; } = new();
    public List<FileDelete> Deletes { get; set; } = new();
}

/// <summary>Makro aktualizacji raportów z Mercalys (składa je serwer, to samo co przycisk w Imporcie danych).</summary>
internal sealed class ReportMacro
{
    public string Macro { get; set; } = "";
    /// <summary>Pierwsze polecenie makra; agent dokłada do niego hasło Mercalys (<c>|hasło</c>).</summary>
    public string OpenLine { get; set; } = "";
    public List<string> Reports { get; set; } = new();
    public List<SkippedReport> Skipped { get; set; } = new();
    public List<MacroFile> Files { get; set; } = new();
    public string PlayerVersion { get; set; } = "";
}

internal sealed class SkippedReport
{
    public string Kind { get; set; } = "";
    public string Title { get; set; } = "";
    public string Reason { get; set; } = "";
}

/// <summary>Plik, który zapisze makro (część okresu albo cały raport).</summary>
internal sealed class MacroFile
{
    public string Kind { get; set; } = "";
    public string Title { get; set; } = "";
    public string FileName { get; set; } = "";
}

/// <summary>Odpowiedź na zgłoszenie stanu komputera: makro zaproponowane temu komputerowi albo nic.</summary>
internal sealed class MacroPoll
{
    public MacroOffer? Offer { get; set; }
}

internal sealed class MacroOffer
{
    public string Id { get; set; } = "";
    public string Title { get; set; } = "";
    public string RequestedBy { get; set; } = "";
    public int CountdownSeconds { get; set; } = 5;
}

/// <summary>Treść zleconego makra (składa je serwer); agent dokłada do pierwszego polecenia hasło Mercalys.</summary>
internal sealed class MacroStart
{
    public string Macro { get; set; } = "";
    public string OpenLine { get; set; } = "";
    public int TimeoutMinutes { get; set; } = 120;
    /// <summary>Pliki zapisane przez makro, które agent wysyła po nim (bony dostawy z bazy).</summary>
    public List<MacroUpload> Uploads { get; set; } = new();
    /// <summary>Pliki, które przed startem makra muszą leżeć w folderze (pliki nowych artykułów).</summary>
    public List<string> RequiredFiles { get; set; } = new();
}

internal sealed class MacroUpload
{
    public string Key { get; set; } = "";
    public string Path { get; set; } = "";
}

/// <summary>Próba wgrania raportu przez agenta (stan z serwera).</summary>
internal sealed class ReportAttempt
{
    public string FileName { get; set; } = "";
    public string Status { get; set; } = "";
    public string? Error { get; set; }
    public DateTimeOffset StartedAt { get; set; }
    /// <summary>Data zmiany pliku na dysku (z komputera sklepu).</summary>
    public DateTimeOffset? FileModified { get; set; }
}

internal sealed class ReportUpload
{
    public string Id { get; set; } = "";
    public int ChunkSize { get; set; } = 2 * 1024 * 1024;
}

/// <summary>
/// Połączenie z Intermanage: tylko wychodzące HTTPS do adresu z ustawień, pełna walidacja certyfikatu (domyślna
/// w .NET, niczego nie wyłączamy), TLS 1.2 albo 1.3, proxy systemowe. Klucz agenta idzie w nagłówku Authorization
/// i nigdy do dziennika.
/// </summary>
internal sealed class ApiClient : IDisposable
{
    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    private const string LeaderHeader = "X-Agent-Leader";

    private readonly HttpClient http;
    private readonly string server;

    /// <summary>
    /// Każda odpowiedź serwera mówi, czy ten agent jest głównym agentem sklepu (dzierżawa na serwerze, przedłużana
    /// każdym żądaniem): tylko główny pilnuje folderów i aktualizuje dane.
    /// </summary>
    public event Action<bool>? LeaderSeen;

    static ApiClient()
    {
        ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | SecurityProtocolType.Tls13;
    }

    public ApiClient(string server, string key)
    {
        this.server = server.TrimEnd('/');
        var handler = new HttpClientHandler
        {
            UseProxy = true,
            Proxy = WebRequest.GetSystemWebProxy(),
            DefaultProxyCredentials = CredentialCache.DefaultCredentials,
            AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
            AllowAutoRedirect = false,
            // Domyślne 2 połączenia zajmuje czekanie na pliki i wysyłka raportu, a czekają jeszcze makra.
            MaxConnectionsPerServer = 8,
        };
        http = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", key);
        http.DefaultRequestHeaders.UserAgent.ParseAdd("IntermanageAgent/" + Program.Version);
    }

    public Task<HelloResponse> HelloAsync(string version, CancellationToken cancel) =>
        SendJsonAsync<HelloResponse>(HttpMethod.Post, "/api/store-agent/hello", new
        {
            version,
            machineName = Environment.MachineName,
            user = Environment.UserName,
        }, TimeSpan.FromSeconds(30), cancel);

    public Task<QueueFiles> WaitForFilesAsync(int seconds, CancellationToken cancel) =>
        SendJsonAsync<QueueFiles>(HttpMethod.Get, "/api/store-agent/file-queue/wait?timeout=" + seconds, null,
            TimeSpan.FromSeconds(seconds + 20), cancel);

    public async Task<byte[]> DownloadAsync(string id, CancellationToken cancel)
    {
        using var response = await SendAsync(new HttpRequestMessage(HttpMethod.Get,
            server + "/api/store-agent/file-queue/" + Uri.EscapeDataString(id) + "/file"), TimeSpan.FromSeconds(60),
            cancel).ConfigureAwait(false);
        return await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
    }

    /// <summary>Paczka agenta (ZIP) do pliku; przy łączu sklepu 200 kB/s kilka MB to kilkadziesiąt sekund.</summary>
    public async Task DownloadUpdateAsync(string path, CancellationToken cancel)
    {
        using var response = await SendAsync(new HttpRequestMessage(HttpMethod.Get, server + "/api/store-agent/update/file"),
            TimeSpan.FromMinutes(10), cancel).ConfigureAwait(false);
        using var file = File.Create(path);
        await response.Content.CopyToAsync(file).ConfigureAwait(false);
    }

    public async Task SavedAsync(string id, CancellationToken cancel)
    {
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Post,
            server + "/api/store-agent/file-queue/" + Uri.EscapeDataString(id) + "/saved"), TimeSpan.FromSeconds(30),
            cancel).ConfigureAwait(false);
    }

    public async Task FailedAsync(string id, string error, CancellationToken cancel)
    {
        var request = new HttpRequestMessage(HttpMethod.Post,
            server + "/api/store-agent/file-queue/" + Uri.EscapeDataString(id) + "/failed")
        {
            Content = JsonContent(new { error = error.Length > 1900 ? error.Substring(0, 1900) : error }),
        };
        using var _ = await SendAsync(request, TimeSpan.FromSeconds(30), cancel).ConfigureAwait(false);
    }

    public Task<EventsResponse> EventsAsync(IReadOnlyList<FolderEvent> events, CancellationToken cancel) =>
        SendJsonAsync<EventsResponse>(HttpMethod.Post, "/api/store-agent/events", new { events },
            TimeSpan.FromSeconds(60), cancel);

    /// <summary>Pliki cen z kasy, już spakowane gzipem (<see cref="Gzip"/>); nazwa dostaje końcówkę .gz.</summary>
    public async Task UploadPriceFilesAsync(IReadOnlyList<(string Name, byte[] Packed)> files, CancellationToken cancel)
    {
        using var form = new MultipartFormDataContent();
        foreach (var (name, packed) in files)
        {
            var part = new ByteArrayContent(packed);
            part.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            form.Add(part, "files", name + ".gz");
        }
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Post, server + "/api/store-agent/price-files")
        {
            Content = form,
        }, TimeSpan.FromMinutes(5), cancel).ConfigureAwait(false);
    }

    /// <summary>Dziennik ATHOS albo jego końcówka (<paramref name="partial"/>), spakowany gzipem.</summary>
    public async Task UploadAthosAsync(string name, byte[] content, DateTimeOffset modified, bool partial,
        CancellationToken cancel)
    {
        using var form = new MultipartFormDataContent();
        var part = new ByteArrayContent(Gzip(content));
        part.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        form.Add(part, "file", name + ".gz");
        form.Add(new StringContent(modified.ToUnixTimeMilliseconds().ToString()), "modified");
        form.Add(new StringContent(partial ? "true" : "false"), "partial");
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Post, server + "/api/store-agent/athos-history")
        {
            Content = form,
        }, TimeSpan.FromMinutes(5), cancel).ConfigureAwait(false);
    }

    /// <summary>Początek wysyłki raportu importu; 409 (<see cref="RejectedException"/>), gdy serwer już go nie chce.</summary>
    public Task<ReportUpload> StartReportAsync(string folderKey, string fileName, long size, DateTimeOffset modified,
        CancellationToken cancel) =>
        SendJsonAsync<ReportUpload>(HttpMethod.Post, "/api/store-agent/reports", new
        {
            folderKey,
            fileName,
            size,
            modified,
        }, TimeSpan.FromSeconds(30), cancel);

    /// <summary>Kawałek raportu: najwyżej 2 MB, przy wolnym łączu sklepu kilkanaście sekund.</summary>
    public async Task ReportChunkAsync(string id, int index, byte[] content, int offset, int count,
        CancellationToken cancel)
    {
        var body = new ByteArrayContent(content, offset, count);
        body.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Put,
            server + "/api/store-agent/reports/" + Uri.EscapeDataString(id) + "/chunks/" + index)
        {
            Content = body,
        }, TimeSpan.FromSeconds(90), cancel).ConfigureAwait(false);
    }

    /// <summary>Cały raport wysłany: serwer wgrywa go w tle.</summary>
    public async Task FinishReportAsync(string id, CancellationToken cancel)
    {
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Post,
            server + "/api/store-agent/reports/" + Uri.EscapeDataString(id) + "/finish"), TimeSpan.FromSeconds(60),
            cancel).ConfigureAwait(false);
    }

    /// <summary>Makro aktualizacji raportów z Mercalys dla podanych raportów (np. SALES).</summary>
    /// <remarks>
    /// <paramref name="automatic"/>: przy starcie komputera albo po przejęciu roli głównego agenta (serwer da je tylko
    /// głównemu). 409 (<see cref="RejectedException"/>): dane aktualizuje już inny komputer sklepu.
    /// </remarks>
    public Task<ReportMacro> ReportMacroAsync(IReadOnlyList<string> kinds, bool automatic, CancellationToken cancel) =>
        SendJsonAsync<ReportMacro>(HttpMethod.Post, "/api/store-agent/report-macro", new { kinds, automatic },
            TimeSpan.FromSeconds(60), cancel);

    /// <summary>Koniec aktualizacji danych na tym komputerze: inny komputer może ją znów uruchomić.</summary>
    public async Task ReportMacroFinishedAsync(CancellationToken cancel)
    {
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Post,
            server + "/api/store-agent/report-macro/finished"), TimeSpan.FromSeconds(30), cancel).ConfigureAwait(false);
    }

    /// <summary>Ostatnie próby wgrania raportów (okno aktualizacji czeka na potwierdzenie serwera).</summary>
    public Task<List<ReportAttempt>> RecentReportsAsync(CancellationToken cancel) =>
        SendJsonAsync<List<ReportAttempt>>(HttpMethod.Get, "/api/store-agent/reports", null,
            TimeSpan.FromSeconds(30), cancel);

    /// <summary>Wgrany raport usunięty z folderu (historia na serwerze).</summary>
    public async Task ReportRemovedAsync(string folderKey, string fileName, DateTimeOffset modified,
        CancellationToken cancel)
    {
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Post, server + "/api/store-agent/reports/removed")
        {
            Content = JsonContent(new { folderKey, fileName, modified }),
        }, TimeSpan.FromSeconds(30), cancel).ConfigureAwait(false);
    }

    /// <summary>
    /// Stan komputera i czekanie na makro zlecone z przeglądarki (long polling): serwer odpowiada, gdy ten komputer
    /// dostanie makro, albo po <paramref name="timeout"/> sekundach. <paramref name="handled"/> = makro już odliczane
    /// albo wykonywane, serwer nie przyśle go drugi raz.
    /// </summary>
    public Task<MacroPoll> MacroWaitAsync(int idleSeconds, bool locked, bool playerRunning, string? playerVersion,
        bool busy, string? handled, int timeout, CancellationToken cancel) =>
        SendJsonAsync<MacroPoll>(HttpMethod.Post, "/api/store-agent/macro-jobs/wait",
            new { idleSeconds, locked, playerRunning, busy, playerVersion, handled, timeout },
            TimeSpan.FromSeconds(timeout + 20), cancel);

    /// <summary>Plik zapisany przez makro, spakowany gzipem.</summary>
    public async Task MacroFileAsync(string id, string key, string name, byte[] content, CancellationToken cancel)
    {
        using var form = new MultipartFormDataContent();
        var part = new ByteArrayContent(Gzip(content));
        part.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        form.Add(part, "file", name + ".gz");
        form.Add(new StringContent(key), "key");
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Post,
            server + "/api/store-agent/macro-jobs/" + Uri.EscapeDataString(id) + "/files")
        {
            Content = form,
        }, TimeSpan.FromMinutes(2), cancel).ConfigureAwait(false);
    }

    /// <summary>Pracownik anulował odliczanie: zlecenie idzie do kolejnego komputera.</summary>
    public async Task MacroDeclineAsync(string id, CancellationToken cancel)
    {
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Post,
            server + "/api/store-agent/macro-jobs/" + Uri.EscapeDataString(id) + "/decline")
        {
            Content = JsonContent(new { reason = (string?)null }),
        }, TimeSpan.FromSeconds(15), cancel).ConfigureAwait(false);
    }

    /// <summary>Koniec odliczania; 409 (<see cref="RejectedException"/>), gdy zlecenie już nie należy do tego komputera.</summary>
    public Task<MacroStart> MacroStartAsync(string id, CancellationToken cancel) =>
        SendJsonAsync<MacroStart>(HttpMethod.Post,
            "/api/store-agent/macro-jobs/" + Uri.EscapeDataString(id) + "/start", null, TimeSpan.FromSeconds(20), cancel);

    public async Task MacroFinishAsync(string id, bool ok, string message, IReadOnlyList<string> codes,
        CancellationToken cancel)
    {
        using var _ = await SendAsync(new HttpRequestMessage(HttpMethod.Post,
            server + "/api/store-agent/macro-jobs/" + Uri.EscapeDataString(id) + "/finish")
        {
            Content = JsonContent(new
            {
                ok,
                message = message.Length > 3900 ? message.Substring(0, 3900) : message,
                codes,
            }),
        }, TimeSpan.FromSeconds(30), cancel).ConfigureAwait(false);
    }

    private async Task<T> SendJsonAsync<T>(HttpMethod method, string path, object? body, TimeSpan timeout,
        CancellationToken cancel)
    {
        var request = new HttpRequestMessage(method, server + path);
        if (body != null) request.Content = JsonContent(body);
        using var response = await SendAsync(request, timeout, cancel).ConfigureAwait(false);
        var text = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
        try
        {
            return JsonSerializer.Deserialize<T>(text, Json)
                ?? throw new ServerUnavailableException("Pusta odpowiedź serwera");
        }
        catch (JsonException error)
        {
            throw new ServerUnavailableException("Nieczytelna odpowiedź serwera", error);
        }
    }

    private async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, TimeSpan timeout,
        CancellationToken cancel)
    {
        using var limit = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        limit.CancelAfter(timeout);
        HttpResponseMessage response;
        try
        {
            response = await http.SendAsync(request, HttpCompletionOption.ResponseContentRead, limit.Token)
                .ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new ServerUnavailableException("Serwer nie odpowiedział w czasie");
        }
        catch (HttpRequestException error)
        {
            throw new ServerUnavailableException("Brak połączenia z serwerem: " + Inner(error), error);
        }
        finally
        {
            request.Dispose();
        }
        if (response.Headers.TryGetValues(LeaderHeader, out var leader))
        {
            foreach (var value in leader)
            {
                LeaderSeen?.Invoke(string.Equals(value, "true", StringComparison.OrdinalIgnoreCase));
                break;
            }
        }
        if (response.IsSuccessStatusCode) return response;
        var status = (int)response.StatusCode;
        var detail = await Detail(response).ConfigureAwait(false);
        response.Dispose();
        if (status == 401 || status == 403) throw new InvalidKeyException(detail ?? "Serwer odrzucił klucz agenta");
        if (status == 429 || status >= 500 || status == 404 || (status >= 300 && status < 400))
        {
            throw new ServerUnavailableException("Serwer odpowiedział " + status + (detail != null ? ": " + detail : ""));
        }
        throw new RejectedException(status, detail ?? ("Serwer odpowiedział " + status));
    }

    private static async Task<string?> Detail(HttpResponseMessage response)
    {
        try
        {
            var text = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
            using var doc = JsonDocument.Parse(text);
            return doc.RootElement.TryGetProperty("detail", out var detail) ? detail.GetString() : null;
        }
        catch
        {
            return null;
        }
    }

    private static string Inner(Exception error)
    {
        var current = error;
        while (current.InnerException != null) current = current.InnerException;
        return current.Message;
    }

    /// <summary>Gzip: pliki cen z kasy i dziennik ATHOS to tekst, który zmniejsza się kilka-kilkanaście razy.</summary>
    public static byte[] Gzip(byte[] content)
    {
        using var output = new MemoryStream();
        using (var zip = new GZipStream(output, CompressionLevel.Optimal, true))
        {
            zip.Write(content, 0, content.Length);
        }
        return output.ToArray();
    }

    private static StringContent JsonContent(object body) =>
        new(JsonSerializer.Serialize(body, Json), Encoding.UTF8, "application/json");

    public void Dispose() => http.Dispose();
}
