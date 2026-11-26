using System;

namespace Intermanage.Agent;

/// <summary>
/// Informacja o nowej wersji agenta. Serwer w każdej odpowiedzi /hello podaje wersję paczki agenta z „Do pobrania”,
/// a agent ze starszą wersją pokazuje to w menu ikony z „Otwórz „Do pobrania””.
///
/// Od 1.14.0 agent niczego nie pobiera: paczkę pobiera człowiek w przeglądarce i podmienia IntermanageAgent.exe w stałym
/// folderze sklepu (<c>P:\Production\INTERMANAGE\AGENT</c>), a nowa wersja rusza przy następnym włączeniu komputera.
/// Program, który sam ściąga z internetu inny program, antywirus sklepów (SentinelOne) traktuje jak atak.
/// </summary>
internal sealed class SelfUpdate
{
    /// <summary>Wersja z „Do pobrania” nowsza od działającej (null = nic nie czeka).</summary>
    public string? NewerVersion { get; private set; }

    /// <summary>Opis do menu ikony (null = nic nie czeka).</summary>
    public string? Status => NewerVersion == null
        ? null
        : "Nowa wersja " + NewerVersion + " w „Do pobrania” – podmień IntermanageAgent.exe";

    public event Action? StatusChanged;

    /// <summary>Serwer podał wersję paczki agenta (null = nie ma paczki).</summary>
    public void Offer(AgentUpdateInfo? info)
    {
        var newer = info != null && IsNewer(info.Version, Program.Version) ? info.Version.Trim().TrimStart('v', 'V') : null;
        if (newer == NewerVersion) return;
        NewerVersion = newer;
        if (newer != null) Log.Info("W „Do pobrania” czeka wersja agenta " + newer);
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
}
