using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace Intermanage.Agent;

/// <summary>
/// Pobieranie nowej wersji agenta (od 1.6.0). Serwer w każdej odpowiedzi /hello podaje wersję paczki agenta
/// z „Do pobrania” (pozycja oznaczona jako paczka agenta). Agent ze starszą wersją pobiera paczkę (ZIP) do folderu
/// obok siebie i pokazuje w menu ikony, że czeka nowa wersja. <b>Rozpakowania i uruchomienia nie robi agent, tylko
/// człowiek</b>: „Wyodrębnij wszystkie” przy pobranej paczce (na dysku sieciowym raz na sklep), a dalej prowadzi
/// <see cref="VersionWatch"/>.
///
/// Agent celowo nie rozpakowuje ani nie uruchamia żadnego programu: program ochronny komputerów sklepu (SentinelOne)
/// traktuje „rozpakuj exe i uruchom go” jak atak i zablokował tak agenta 1.5.0 (10023, 23.11.2026). Samo pobranie pliku
/// przez HTTPS i zapis do folderu jest dla niego zwykłą pracą agenta.
/// </summary>
internal sealed class SelfUpdate : IDisposable
{
    private static readonly TimeSpan CheckEvery = TimeSpan.FromMinutes(5);

    private static string Folder => VersionFolders.UpdateFolder;

    private readonly object gate = new();
    private int working;
    private string? lastFailed;

    /// <summary>Wersja czekającej, pobranej paczki (null = nic nie czeka).</summary>
    public string? ReadyVersion { get; private set; }

    /// <summary>Plik pobranej paczki (do pokazania w Eksploratorze).</summary>
    public string? ReadyFile { get; private set; }

    /// <summary>Opis stanu do menu ikony (null = nic się nie dzieje).</summary>
    public string? Status { get; private set; }

    public event Action? StatusChanged;

    /// <summary>Serwer podał wersję paczki agenta: nowszą pobieramy obok, gdy jeszcze jej nie ma.</summary>
    public void Offer(ApiClient api, AgentUpdateInfo info)
    {
        if (!IsNewer(info.Version, Program.Version)) return;
        lock (gate)
        {
            if (ReadyVersion == info.Version || lastFailed == info.Version) return;
        }
        var file = Path.Combine(Folder, "agent-" + Safe(info.Version) + ".zip");
        if (Ready(file, info))
        {
            Announce(info.Version, file);
            return;
        }
        if (Interlocked.Exchange(ref working, 1) == 1) return;
        Task.Run(async () =>
        {
            try
            {
                SetStatus("Pobieram nową wersję " + info.Version + "…");
                await DownloadAsync(api, info, file).ConfigureAwait(false);
                lock (gate) lastFailed = null;
                Announce(info.Version, file);
                Log.Info("Nowa wersja agenta " + info.Version + " pobrana do " + file + ", czeka na rozpakowanie");
            }
            catch (Exception error)
            {
                lock (gate) lastFailed = info.Version;
                Log.Error("Pobieranie nowej wersji agenta " + info.Version, error);
                SetStatus("Nie udało się pobrać wersji " + info.Version + " (szczegóły w dzienniku)");
            }
            finally
            {
                Interlocked.Exchange(ref working, 0);
            }
        });
    }

    private void Announce(string version, string file)
    {
        lock (gate)
        {
            ReadyVersion = version;
            ReadyFile = file;
        }
        SetStatus("Nowa wersja " + version + " pobrana – kliknij „Pokaż pobraną aktualizację” i podmień IntermanageAgent.exe");
    }

    /// <summary>Otwiera Eksplorator z zaznaczoną pobraną paczką (menu ikony „Pokaż pobraną aktualizację”).</summary>
    public void ShowReadyFile()
    {
        var file = ReadyFile;
        try
        {
            if (file != null && File.Exists(file))
            {
                Process.Start(new ProcessStartInfo("explorer.exe", "/select,\"" + file + "\"")
                {
                    UseShellExecute = true,
                })?.Dispose();
            }
        }
        catch (Exception error)
        {
            Log.Error("Otwieranie folderu aktualizacji", error);
        }
    }

    /// <summary>Paczka już pobrana (ten sam rozmiar i SHA-256), więc kolejny komputer jej nie pobiera.</summary>
    private static bool Ready(string file, AgentUpdateInfo info)
    {
        try
        {
            if (!File.Exists(file)) return false;
            if (info.SizeBytes > 0 && new FileInfo(file).Length != info.SizeBytes) return false;
            return string.Equals(Sha256(file), info.Sha256, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>Pobranie do pliku tymczasowego i zmiana nazwy, żeby dwa komputery nie zostawiły połowy paczki.</summary>
    private static async Task DownloadAsync(ApiClient api, AgentUpdateInfo info, string file)
    {
        Directory.CreateDirectory(Folder);
        var temp = file + "." + Environment.MachineName + "-" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".tmp";
        try
        {
            await api.DownloadUpdateAsync(temp, CancellationToken.None).ConfigureAwait(false);
            var size = new FileInfo(temp).Length;
            if (info.SizeBytes > 0 && size != info.SizeBytes)
            {
                throw new InvalidDataException("Paczka ma " + size + " B zamiast " + info.SizeBytes + " B");
            }
            if (!string.Equals(Sha256(temp), info.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("Suma SHA-256 paczki nie zgadza się z serwerem");
            }
            if (File.Exists(file) && !Ready(file, info)) File.Delete(file);
            if (!File.Exists(file)) File.Move(temp, file);
        }
        finally
        {
            try { if (File.Exists(temp)) File.Delete(temp); } catch (Exception) { }
        }
    }

    private void SetStatus(string? text)
    {
        Status = text;
        StatusChanged?.Invoke();
    }

    /// <summary>Czy <paramref name="offered"/> (np. 1.6.0, „v1.6”) jest nowsza od <paramref name="current"/>.</summary>
    public static bool IsNewer(string offered, string current) =>
        Parse(offered) is { } a && Parse(current) is { } b && a > b;

    private static Version? Parse(string text)
    {
        var clean = text.Trim().TrimStart('v', 'V');
        if (!System.Version.TryParse(clean.IndexOf('.') >= 0 ? clean : clean + ".0", out var version)) return null;
        return new Version(version.Major, version.Minor, Math.Max(0, version.Build), Math.Max(0, version.Revision));
    }

    private static string Safe(string version) =>
        new string(version.Where(c => char.IsLetterOrDigit(c) || c == '.' || c == '-').ToArray());

    private static string Sha256(string path)
    {
        using var sha = SHA256.Create();
        using var stream = File.OpenRead(path);
        return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "");
    }

    public void Dispose()
    {
    }
}
