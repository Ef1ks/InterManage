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
}

internal sealed class WatchConfig
{
    public string Kind { get; set; } = "";
    public string Path { get; set; } = "";
    public bool Subfolders { get; set; }
    public string Pattern { get; set; } = "";
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

internal sealed class EventsResponse
{
    public List<FileRequest> Requests { get; set; } = new();
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

    private readonly HttpClient http;
    private readonly string server;

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
