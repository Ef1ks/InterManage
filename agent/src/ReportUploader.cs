using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace Intermanage.Agent;

/// <summary>
/// Raporty Importu danych, o które poprosił serwer: wysyła je po kolei w kawałkach (wolne łącze sklepu, limit
/// Cloudflare ok. 100 s na żądanie), a serwer wgrywa je w tle tym samym importem co przeglądarka. Kolejka jest osobna
/// od pilnowania folderów, żeby duży raport nie wstrzymywał cen z kasy. Plik zmieniony w trakcie wysyłki idzie od nowa
/// przy kolejnym przeglądzie, a odrzucony przez serwer (już wgrany, inny w toku) jest pomijany.
/// </summary>
internal sealed class ReportUploader
{
    private const int ChunkAttempts = 4;
    private static TimeSpan SteadyFor => Limits.Seconds("reportSteadySeconds", 2, 1, 120);
    private static TimeSpan SaveTimeout => Limits.Seconds("reportSaveSeconds", 60, 10, 1800);

    private sealed class Item
    {
        public string FolderKey = "";
        public string FileName = "";
        public string Path = "";
    }

    private readonly AgentHost host;
    private readonly object gate = new();
    private readonly List<Item> queue = new();
    private readonly SemaphoreSlim signal = new(0);

    public ReportUploader(AgentHost host) => this.host = host;

    /// <summary>Plik do wysłania; ten sam plik czekający w kolejce nie dochodzi drugi raz.</summary>
    public void Enqueue(string folderKey, string fileName, string path)
    {
        lock (gate)
        {
            if (queue.Any(i => i.FolderKey == folderKey
                && string.Equals(i.Path, path, StringComparison.OrdinalIgnoreCase))) return;
            queue.Add(new Item { FolderKey = folderKey, FileName = fileName, Path = path });
        }
        signal.Release();
    }

    public async Task RunAsync(CancellationToken cancel)
    {
        while (!cancel.IsCancellationRequested)
        {
            try { await signal.WaitAsync(cancel).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
            Item? item;
            lock (gate)
            {
                item = queue.FirstOrDefault();
            }
            if (item == null) continue;
            var api = host.Api;
            try
            {
                using var work = host.BeginWork();
                if (api != null && work != null) await UploadAsync(api, item, cancel).ConfigureAwait(false);
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
                // Plik wróci przy kolejnym przeglądzie folderu (serwer poprosi o niego ponownie).
                Log.Warn("Nie wysłano raportu " + item.FileName + ": " + error.Message);
                host.ServerProblem(error.Message);
            }
            finally
            {
                lock (gate) queue.Remove(item);
            }
        }
    }

    /// <summary>
    /// Data zmiany gotowego pliku. Excel najpierw zakłada pusty plik, a potem go zapisuje (na dysku sieciowym kilka
    /// sekund), więc czekamy, aż niepusty plik przez <see cref="SteadyFor"/> ma ten sam rozmiar i datę. Pusty plik
    /// wysłany za wcześnie serwer odrzucał, a raport danego dnia już nie wracał.
    /// </summary>
    private static async Task<DateTime?> WaitUntilSavedAsync(string path, CancellationToken cancel)
    {
        var until = DateTime.UtcNow + SaveTimeout;
        (long Size, DateTime Modified)? previous = null;
        while (true)
        {
            var current = await Disk.Run(() =>
            {
                var info = new FileInfo(path);
                return info.Exists ? (info.Length, info.LastWriteTimeUtc) : (0L, DateTime.MinValue);
            }, TimeSpan.FromSeconds(30), cancel).ConfigureAwait(false);
            if (current.Item1 > 0 && previous == current) return current.Item2;
            if (current.Item2 == DateTime.MinValue || DateTime.UtcNow > until) return null;
            previous = current;
            await Task.Delay(SteadyFor, cancel).ConfigureAwait(false);
        }
    }

    private async Task UploadAsync(ApiClient api, Item item, CancellationToken cancel)
    {
        DateTime? ready;
        try
        {
            ready = await WaitUntilSavedAsync(item.Path, cancel).ConfigureAwait(false);
        }
        catch (Exception error) when (!(error is OperationCanceledException))
        {
            host.DiskProblem("Nie odczytano raportu " + item.FileName + ": " + error.Message);
            return;
        }
        if (ready == null)
        {
            Log.Info("Raport " + item.FileName + " jest jeszcze zapisywany, pójdzie przy kolejnym przeglądzie");
            return;
        }
        var modified = ready.Value;
        var content = await Disk.ReadWhenReady(item.Path, cancel).ConfigureAwait(false);
        if (content == null || content.Length == 0) return;
        // Excel jeszcze zapisuje: data zmiany inna niż przed odczytem, plik pójdzie przy kolejnym przeglądzie.
        var after = await Disk.Run(() => File.GetLastWriteTimeUtc(item.Path), TimeSpan.FromSeconds(30), cancel)
            .ConfigureAwait(false);
        if (after != modified) return;

        ReportUpload upload;
        try
        {
            upload = await api.StartReportAsync(item.FolderKey, item.FileName, content.Length,
                new DateTimeOffset(modified, TimeSpan.Zero), cancel).ConfigureAwait(false);
        }
        catch (RejectedException error)
        {
            Log.Info("Serwer nie chce raportu " + item.FileName + ": " + error.Message);
            return;
        }
        var chunk = Math.Max(64 * 1024, upload.ChunkSize);
        var index = 0;
        for (var offset = 0; offset < content.Length; offset += chunk, index++)
        {
            var count = Math.Min(chunk, content.Length - offset);
            for (var attempt = 1; ; attempt++)
            {
                try
                {
                    await api.ReportChunkAsync(upload.Id, index, content, offset, count, cancel).ConfigureAwait(false);
                    break;
                }
                catch (ServerUnavailableException) when (attempt < ChunkAttempts)
                {
                    await Task.Delay(TimeSpan.FromSeconds(5 * attempt), cancel).ConfigureAwait(false);
                }
            }
        }
        await api.FinishReportAsync(upload.Id, cancel).ConfigureAwait(false);
        host.ServerOk();
        Log.Info("Wysłano raport " + item.FileName + " (" + content.Length / 1024 + " KB), serwer go wgrywa");
        host.Sent("raport " + item.FileName);
    }
}
