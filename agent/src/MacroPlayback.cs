using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Intermanage.Agent;

/// <summary>
/// Jedno makro wykonywane przez odtwarzacz z wynikiem w pliku (<c>#RESULT|id</c> w pierwszej linii, odtwarzacz od 0.64):
/// takie makro nie pokazuje okien, więc błąd nie zostawia odtwarzacza na czekającym oknie i od razu go znamy.
/// </summary>
internal sealed class MacroPlayback
{
    /// <summary>Odtwarzacz, który zapisuje wynik; starszy widzi w <c>#RESULT</c> komentarz.</summary>
    public static readonly Version ResultsFrom = new(0, 64);
    /// <summary>
    /// Odtwarzacz, który bierze makro z pliku <c>&lt;id&gt;.macro</c> i zatrzymuje je po pliku <c>&lt;id&gt;.stop</c>
    /// (od 0.73): bez schowka i bez naciskania klawiszy przez agenta. Starszy dostaje makro przez schowek i Alt+E.
    /// </summary>
    public static readonly Version FilesFrom = new(0, 73);
    /// <summary>Początek wyniku makra zatrzymanego klawiszem Esc (player.ahk).</summary>
    private const string StoppedByEsc = "Makro zatrzymane klawiszem Esc";
    /// <summary>Początek wyniku makra zatrzymanego plikiem od agenta (player.ahk od 0.73).</summary>
    private const string StoppedByAgent = "Makro zatrzymane przez agenta";
    /// <summary>Tyle odtwarzacz ma na podjęcie makra po Alt+E.</summary>
    private static TimeSpan PickUpTimeout => Limits.Seconds("macroPickUpSeconds", 15, 5, 300);
    /// <summary>Tyle bez procesu odtwarzacza w trakcie makra = ktoś go zamknął.</summary>
    private static TimeSpan PlayerGone => Limits.Seconds("playerGoneSeconds", 20, 10, 300);

    /// <summary>Folder wyników; ten sam składa odtwarzacz (player.ahk, WriteRemote).</summary>
    private static string ResultFolder => Path.Combine(AgentSettings.Folder, "makra");

    internal enum PlayState
    {
        Running,
        Ok,
        Error,
    }

    internal sealed class Result
    {
        public PlayState State;
        public List<string> Codes = new();
        /// <summary>Opis wyniku; w trakcie (odtwarzacz od 0.69) część, którą makro teraz robi.</summary>
        public string Text = "";
        /// <summary>Człowiek zatrzymał makro (Esc) albo zamknął odtwarzacz: sami go nie ponawiamy.</summary>
        public bool Stopped;
    }

    private readonly Control ui;
    private readonly string key;
    private readonly string file;
    private readonly string? password;
    /// <summary>Makro przekazane plikiem (odtwarzacz od 0.73), więc i zatrzymanie idzie plikiem.</summary>
    private bool viaFile;
    private DateTime playerSeen = DateTime.UtcNow;
    private DateTime playerCheck = DateTime.UtcNow;

    private MacroPlayback(Control ui, string key, string? password)
    {
        this.ui = ui;
        this.key = key;
        this.password = password;
        file = Path.Combine(ResultFolder, key + ".txt");
    }

    /// <summary>
    /// Makro dla odtwarzacza; <c>null</c> = odtwarzacz go nie podjął. AutoJob od 0.73 bierze je z pliku
    /// <c>&lt;id&gt;.macro</c> w folderze agenta i od razu kasuje (w pierwszej linii bywa hasło Mercalys), starszy ze
    /// schowka po lewym Alt+E, jak u pracownika.
    /// </summary>
    public static async Task<MacroPlayback?> StartAsync(Control ui, Guid id, string macro, string openLine,
        string? password, CancellationToken cancel)
    {
        var playback = new MacroPlayback(ui, id.ToString("N"), password);
        Directory.CreateDirectory(ResultFolder);
        CleanUp();
        TryDelete(playback.file);
        MercalysPath.Ensure();
        var text = "#RESULT|" + playback.key + "\r\n" + MacroRunner.WithPassword(macro, openLine, password);
        playback.viaFile = MacroRunner.FindPlayer()?.Version >= FilesFrom;
        var macroFile = Path.Combine(ResultFolder, playback.key + ".macro");
        if (playback.viaFile)
        {
            var temp = macroFile + ".tmp";
            File.WriteAllText(temp, text, new UTF8Encoding(false));
            if (File.Exists(macroFile)) File.Delete(macroFile);
            File.Move(temp, macroFile);
        }
        else
        {
            playback.OnUi(() =>
            {
                Clipboard.SetDataObject(text, true, 10, 100);
                return true;
            });
            await Task.Delay(500, cancel).ConfigureAwait(false);
            MacroRunner.PressAltE();
        }

        var pickUpUntil = DateTime.UtcNow + PickUpTimeout;
        try
        {
            while (Read(playback.file) == null)
            {
                if (DateTime.UtcNow > pickUpUntil)
                {
                    playback.Withdraw(macroFile);
                    return null;
                }
                await Task.Delay(300, cancel).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // Przerwane przed podjęciem: makro nie może ruszyć później, a podjęte w tej chwili ma się zatrzymać.
            playback.Withdraw(macroFile);
            if (playback.viaFile) playback.Stop();
            throw;
        }
        playback.playerSeen = DateTime.UtcNow;
        return playback;
    }

    /// <summary>Niepodjęte makro znika: następny Alt+E pracownika ani odtwarzacz nie mogą go już uruchomić.</summary>
    private void Withdraw(string macroFile)
    {
        if (viaFile)
        {
            TryDelete(macroFile);
            return;
        }
        OnUi(() =>
        {
            if (Clipboard.ContainsText() && Clipboard.GetText().StartsWith("#RESULT|" + key)) Clipboard.Clear();
            return true;
        });
    }

    /// <summary>
    /// Zatrzymuje trwające makro: odtwarzacz od 0.73 po pliku <c>&lt;id&gt;.stop</c>, starszy klawiszem Esc.
    /// </summary>
    public void Stop()
    {
        if (!viaFile)
        {
            MacroRunner.PressEsc();
            return;
        }
        try
        {
            File.WriteAllText(Path.Combine(ResultFolder, key + ".stop"), "stop");
        }
        catch (Exception error)
        {
            Log.Warn("Zatrzymanie makra: " + error.Message);
        }
    }

    /// <summary>Stan makra teraz (szukanie procesu odtwarzacza najwyżej co 5 s).</summary>
    public Result Check()
    {
        var read = Read(file);
        if (read != null && read.State != PlayState.Running)
        {
            TryDelete(file);
            // Błąd cytuje linię makra, a w pierwszej jest hasło Mercalys: nie pokazujemy go ani nie wysyłamy.
            if (!string.IsNullOrEmpty(password)) read.Text = read.Text.Replace("|" + password, "|***");
            read.Stopped = read.Text.StartsWith(StoppedByEsc, StringComparison.Ordinal)
                || read.Text.StartsWith(StoppedByAgent, StringComparison.Ordinal);
            return read;
        }
        if (DateTime.UtcNow - playerCheck > TimeSpan.FromSeconds(5))
        {
            playerCheck = DateTime.UtcNow;
            if (MacroRunner.FindPlayer() != null) playerSeen = DateTime.UtcNow;
            if (DateTime.UtcNow - playerSeen > PlayerGone)
            {
                return new Result
                {
                    State = PlayState.Error,
                    Stopped = true,
                    Text = "Odtwarzacz makr został zamknięty w trakcie makra. Sprawdź w Mercalys, co zostało zrobione.",
                };
            }
        }
        return read ?? new Result { State = PlayState.Running };
    }

    private static Result? Read(string path)
    {
        try
        {
            if (!File.Exists(path)) return null;
            var lines = File.ReadAllText(path, Encoding.UTF8).Split(new[] { '\n' }, 3);
            var state = lines[0].Trim();
            return new Result
            {
                State = state == "RUNNING" ? PlayState.Running : state == "OK" ? PlayState.Ok : PlayState.Error,
                Codes = lines.Length < 2 ? new List<string>() : lines[1].Split(',').Select(code => code.Trim())
                    .Where(code => code.Length > 0).ToList(),
                Text = lines.Length < 3 ? "" : lines[2].Trim(),
            };
        }
        catch (IOException)
        {
            return null; // odtwarzacz właśnie podmienia plik
        }
        catch (UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>Wyniki przerwanych makr (zamknięty agent) starsze niż doba.</summary>
    private static void CleanUp()
    {
        foreach (var path in Directory.GetFiles(ResultFolder))
        {
            // Plik wersji AutoJob zapisuje tylko przy starcie, a komputer bywa włączony dniami.
            if (string.Equals(Path.GetFileName(path), "autojob.txt", StringComparison.OrdinalIgnoreCase)) continue;
            if (File.GetLastWriteTimeUtc(path) < DateTime.UtcNow.AddDays(-1)) TryDelete(path);
        }
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch (Exception) { /* sprzątnie następne makro */ }
    }

    private T OnUi<T>(Func<T> action) => (T)ui.Invoke(action);
}
