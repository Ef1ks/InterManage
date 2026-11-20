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
        get
        {
            if (string.IsNullOrEmpty(ProtectedKey)) return null;
            try
            {
                var bytes = ProtectedData.Unprotect(Convert.FromBase64String(ProtectedKey), Entropy,
                    DataProtectionScope.CurrentUser);
                return Encoding.UTF8.GetString(bytes);
            }
            catch (CryptographicException)
            {
                return null;
            }
        }
        set
        {
            ProtectedKey = string.IsNullOrEmpty(value)
                ? null
                : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(value!.Trim()), Entropy,
                    DataProtectionScope.CurrentUser));
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
