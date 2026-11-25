using System;
using System.Collections.Generic;

namespace Intermanage.Agent;

/// <summary>
/// Czasy i limity z serwera (<c>limits</c> w odpowiedzi /hello, od 1.13.0), żeby ich zmiana nie wymagała nowej wersji
/// agenta. Wartość, której serwer nie podał, jest domyślna, a każda mieści się w granicach zapisanych w kodzie.
/// </summary>
internal static class Limits
{
    private static volatile Dictionary<string, int> values = new();

    public static void Use(Dictionary<string, int>? fromServer) => values = fromServer ?? new Dictionary<string, int>();

    public static int Count(string name, int byDefault, int min, int max) =>
        values.TryGetValue(name, out var value) ? Math.Max(min, Math.Min(max, value)) : byDefault;

    public static TimeSpan Seconds(string name, int byDefault, int min, int max) =>
        TimeSpan.FromSeconds(Count(name, byDefault, min, max));
}
