using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Intermanage.Agent;

/// <summary>
/// Zapis plików z kolejki sklepu: pętla long polling (<c>GET /file-queue/wait</c>) jedna za drugą, bez przerwy, póki
/// serwer odpowiada. Plik przychodzi już przydzielony temu agentowi; zapis jest atomowy (plik tymczasowy w tym samym
/// folderze, potem zmiana nazwy albo podmiana), plik o tej samej nazwie jest nadpisywany jak w przeglądarce, a każda operacja na dysku
/// ma limit 60 s, bo dysk sieciowy potrafi zawisnąć.
/// </summary>
internal sealed class FileSaver
{
    private static readonly TimeSpan DiskTimeout = TimeSpan.FromSeconds(60);
    private static readonly Regex Reserved = new(@"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private const string TempSuffix = ".intermanage-tmp";

    private readonly AgentHost host;

    public FileSaver(AgentHost host) => this.host = host;

    public async Task RunAsync(CancellationToken cancel)
    {
        var serverDelay = TimeSpan.FromSeconds(1);
        var diskDelay = TimeSpan.FromSeconds(5);
        while (!cancel.IsCancellationRequested)
        {
            var api = host.Api;
            var config = host.Config;
            if (api == null || config == null)
            {
                await Delay(TimeSpan.FromSeconds(2), cancel);
                continue;
            }
            try
            {
                var batch = await api.WaitForFilesAsync(config.LongPollSeconds, cancel);
                host.ServerOk();
                serverDelay = TimeSpan.FromSeconds(1);
                var diskProblem = false;
                // Agent zamyka się dla nowej wersji: przydział plików wygaśnie sam i weźmie je nowa wersja.
                using var work = host.BeginWork();
                if (work == null)
                {
                    await Delay(TimeSpan.FromSeconds(2), cancel);
                    continue;
                }
                foreach (var file in batch.Files)
                {
                    if (cancel.IsCancellationRequested) break;
                    if (!await SaveAsync(api, config, file, cancel)) diskProblem = true;
                }
                work.Dispose();
                if (diskProblem)
                {
                    // Dysk niedostępny: przerwa narasta do 5 minut (agent przestaje wtedy czekać na pliki, więc po
                    // minucie jest offline i zapisuje przeglądarka).
                    await Delay(diskDelay, cancel);
                    diskDelay = TimeSpan.FromSeconds(Math.Min(300, diskDelay.TotalSeconds * 2));
                }
                else
                {
                    diskDelay = TimeSpan.FromSeconds(5);
                    host.DiskOk();
                }
            }
            catch (OperationCanceledException) when (cancel.IsCancellationRequested)
            {
                return;
            }
            catch (InvalidKeyException error)
            {
                host.KeyRejected(error.Message);
                await Delay(TimeSpan.FromMinutes(5), cancel);
            }
            catch (Exception error)
            {
                host.ServerProblem(error.Message);
                await Delay(serverDelay, cancel);
                serverDelay = TimeSpan.FromSeconds(Math.Min(60, serverDelay.TotalSeconds * 2));
            }
        }
    }

    /// <summary>Zapisuje jeden plik; <c>false</c>, gdy zawiódł dysk (a nie sam plik).</summary>
    private async Task<bool> SaveAsync(ApiClient api, HelloResponse config, QueueFile file, CancellationToken cancel)
    {
        string? folder = null;
        var problem = Check(config, file, ref folder, out var target);
        if (problem != null)
        {
            Log.Warn("Plik " + file.FileName + " pominięty: " + problem);
            await ReportFailed(api, file, problem, cancel);
            return true;
        }
        try
        {
            var folderReady = await Disk.Run(() => Directory.Exists(folder!), DiskTimeout, cancel);
            if (!folderReady)
            {
                var message = "Folder " + folder + " jest niedostępny (dysk sieciowy odłączony?)";
                host.DiskProblem(message);
                await ReportFailed(api, file, message, cancel);
                return false;
            }
            var content = await api.DownloadAsync(file.Id, cancel);
            var temp = target + TempSuffix;
            // Jak przeglądarka: plik o tej samej nazwie jest nadpisywany (kolejka idzie od najstarszego, więc wygrywa
            // nowszy). Podmiana dopiero po zapisaniu całego pliku tymczasowego, więc program sklepu nie zobaczy połowy.
            var replaced = await Disk.Run(() =>
            {
                using (var stream = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None))
                {
                    stream.Write(content, 0, content.Length);
                    stream.Flush(true);
                }
                if (!File.Exists(target!))
                {
                    File.Move(temp, target!);
                    return false;
                }
                try
                {
                    File.Replace(temp, target!, null, true);
                }
                catch (Exception error) when (error is IOException || error is UnauthorizedAccessException
                    || error is PlatformNotSupportedException)
                {
                    // Nie każdy dysk sieciowy obsługuje podmianę: wtedy usunięcie starego i zmiana nazwy.
                    File.Delete(target!);
                    File.Move(temp, target!);
                }
                return true;
            }, DiskTimeout, cancel);
            await api.SavedAsync(file.Id, cancel);
            Log.Info((replaced ? "Nadpisano " : "Zapisano ") + file.FileName + " w " + folder);
            host.Saved(file.FileName);
            return true;
        }
        catch (OperationCanceledException) when (cancel.IsCancellationRequested)
        {
            throw;
        }
        catch (TimeoutException)
        {
            var message = "Zapis w folderze " + folder + " nie skończył się w 60 s (dysk sieciowy nie odpowiada?). "
                + "Sprawdź, czy plik nie pojawił się w folderze.";
            host.DiskProblem(message);
            await ReportFailed(api, file, message, cancel);
            return false;
        }
        catch (Exception error) when (error is IOException || error is UnauthorizedAccessException)
        {
            var message = "Nie udało się zapisać pliku: " + error.Message;
            Log.Error("Zapis " + file.FileName, error);
            await ReportFailed(api, file, message, cancel);
            return !(error is DirectoryNotFoundException);
        }
    }

    /// <summary>Folder z konfiguracji po kluczu i bezpieczna nazwa pliku; zwraca opis problemu albo null.</summary>
    internal static string? Check(HelloResponse config, QueueFile file, ref string? folder, out string? target)
    {
        target = null;
        if (!config.Folders.TryGetValue(file.FolderKey, out var path) || string.IsNullOrWhiteSpace(path))
        {
            return "Agent nie zna folderu " + file.FolderKey + " (ustaw ścieżkę folderu w Intermanage)";
        }
        var name = file.FileName ?? "";
        if (!SafeName(name)) return "Nieprawidłowa nazwa pliku " + name;
        string root;
        try
        {
            root = Path.GetFullPath(path.Trim()).TrimEnd('\\') + "\\";
            target = Path.GetFullPath(Path.Combine(root, name));
        }
        catch (Exception error) when (error is ArgumentException || error is NotSupportedException
            || error is PathTooLongException)
        {
            return "Nieprawidłowa ścieżka folderu " + path;
        }
        if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase)
            || target.Substring(root.Length).IndexOf('\\') >= 0)
        {
            target = null;
            return "Nazwa pliku wychodzi poza folder";
        }
        folder = root.TrimEnd('\\');
        return null;
    }

    internal static bool SafeName(string name)
    {
        if (name.Length == 0 || name.Length > 200) return false;
        if (name == "." || name == ".." || name.Contains("..")) return false;
        if (name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return false;
        if (name.IndexOf('\\') >= 0 || name.IndexOf('/') >= 0 || name.IndexOf(':') >= 0) return false;
        if (name.EndsWith(".") || name.EndsWith(" ") || name.StartsWith(" ")) return false;
        if (name.EndsWith(TempSuffix, StringComparison.OrdinalIgnoreCase)) return false;
        return !Reserved.IsMatch(name);
    }

    private async Task ReportFailed(ApiClient api, QueueFile file, string error, CancellationToken cancel)
    {
        try
        {
            await api.FailedAsync(file.Id, error, cancel);
        }
        catch (Exception report) when (!(report is OperationCanceledException))
        {
            // Przydział wygaśnie sam po 2 minutach.
            Log.Warn("Nie udało się zgłosić błędu zapisu " + file.FileName + ": " + report.Message);
        }
    }

    private static async Task Delay(TimeSpan time, CancellationToken cancel)
    {
        try { await Task.Delay(time, cancel); } catch (OperationCanceledException) { }
    }
}

/// <summary>Operacje na dysku z limitem czasu (dysk sieciowy potrafi zawisnąć bez błędu).</summary>
internal static class Disk
{
    public static async Task<T> Run<T>(Func<T> action, TimeSpan timeout, CancellationToken cancel)
    {
        var work = Task.Run(action);
        var done = await Task.WhenAny(work, Task.Delay(timeout, cancel)).ConfigureAwait(false);
        cancel.ThrowIfCancellationRequested();
        if (done != work) throw new TimeoutException();
        return await work.ConfigureAwait(false);
    }

    /// <summary>Czyta plik, gdy da się go otworzyć (program sklepu może go jeszcze pisać): kilka prób co sekundę.</summary>
    public static async Task<byte[]?> ReadWhenReady(string path, CancellationToken cancel)
    {
        for (var attempt = 0; attempt < 6; attempt++)
        {
            try
            {
                return await Run(() =>
                {
                    using var stream = new FileStream(path, FileMode.Open, FileAccess.Read,
                        FileShare.ReadWrite | FileShare.Delete);
                    using var copy = new MemoryStream();
                    stream.CopyTo(copy);
                    return copy.ToArray();
                }, TimeSpan.FromSeconds(60), cancel).ConfigureAwait(false);
            }
            catch (FileNotFoundException)
            {
                return null;
            }
            catch (IOException)
            {
                await Task.Delay(TimeSpan.FromSeconds(1), cancel).ConfigureAwait(false);
            }
        }
        return null;
    }
}
