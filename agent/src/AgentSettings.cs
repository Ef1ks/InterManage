using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Win32;

namespace Intermanage.Agent;

/// <summary>
/// Ustawienia agenta w %LOCALAPPDATA%\Intermanage\Agent\settings.json: adres serwera i klucz agenta zaszyfrowany DPAPI
/// (CurrentUser), więc na innym koncie albo komputerze kopia pliku nic nie daje. Autostart tylko przez klucz Run
/// użytkownika (HKCU), wyłączany w menu ikony.
/// </summary>
internal sealed class AgentSettings
{
    public const string DefaultServer = "https://intermanage.pl";
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "Intermanage Agent";
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Intermanage.Agent.v1");

    public static string Folder =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Intermanage", "Agent");

    private static string FilePath => Path.Combine(Folder, "settings.json");

    public string ServerUrl { get; set; } = DefaultServer;

    /// <summary>Klucz agenta zaszyfrowany DPAPI (Base64).</summary>
    public string? ProtectedKey { get; set; }

    [JsonIgnore]
    public bool HasKey => !string.IsNullOrEmpty(ProtectedKey);

    /// <summary>Klucz jawnie tylko w pamięci; do pliku idzie wyłącznie ProtectedKey.</summary>
    [JsonIgnore]
    public string? Key
    {
        get => Unprotect(ProtectedKey);
        set => ProtectedKey = Protect(value?.Trim());
    }

    /// <summary>Hasło Mercalys zaszyfrowane DPAPI (Base64): makro aktualizacji danych samo loguje się do Mercalys.</summary>
    public string? ProtectedMercalysPassword { get; set; }

    [JsonIgnore]
    public bool HasMercalysPassword => !string.IsNullOrEmpty(ProtectedMercalysPassword);

    [JsonIgnore]
    public string? MercalysPassword
    {
        get => Unprotect(ProtectedMercalysPassword);
        set => ProtectedMercalysPassword = Protect(value);
    }

    /// <summary>
    /// Przy starcie agenta (logowanie do Windows) raporty Importu danych, których plik w folderze nie jest z dzisiaj,
    /// aktualizuje makro z Mercalys. Domyślnie włączone.
    /// </summary>
    public bool UpdateOnStart { get; set; } = true;

    private static string? Protect(string? value) => string.IsNullOrEmpty(value)
        ? null
        : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value), Entropy,
            DataProtectionScope.CurrentUser));

    private static string? Unprotect(string? value)
    {
        if (string.IsNullOrEmpty(value)) return null;
        try
        {
            return Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(value), Entropy,
                DataProtectionScope.CurrentUser));
        }
        catch (Exception error) when (error is CryptographicException || error is FormatException)
        {
            return null;
        }
    }

    public static AgentSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var loaded = JsonSerializer.Deserialize<AgentSettings>(File.ReadAllText(FilePath, Encoding.UTF8));
                if (loaded != null) return loaded;
            }
        }
        catch (Exception error)
        {
            Log.Warn("Nie udało się odczytać ustawień: " + error.Message);
        }
        return new AgentSettings();
    }

    public void Save()
    {
        Directory.CreateDirectory(Folder);
        var temp = FilePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }),
            Encoding.UTF8);
        if (File.Exists(FilePath)) File.Replace(temp, FilePath, null);
        else File.Move(temp, FilePath);
    }

    /// <summary>Adres serwera bez końcowego ukośnika; tylko https (lokalnie dla testów także http://localhost).</summary>
    public static string? NormalizeServer(string value)
    {
        var text = (value ?? "").Trim().TrimEnd('/');
        if (!Uri.TryCreate(text, UriKind.Absolute, out var uri)) return null;
        if (uri.Scheme == Uri.UriSchemeHttps) return text;
        return uri.Scheme == Uri.UriSchemeHttp && uri.IsLoopback ? text : null;
    }

    public static bool Autostart
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, false);
            return key?.GetValue(RunValue) is string;
        }
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey, true);
            if (key == null) return;
            if (value) key.SetValue(RunValue, "\"" + System.Windows.Forms.Application.ExecutablePath + "\"");
            else key.DeleteValue(RunValue, false);
        }
    }

}
