/** Tekst do porównań: bez znaków diakrytycznych i wielkości liter ("Łódź" -> "lodz"). */
export function normalizeSearch(value: string): string {
  return value
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .replace(/ł/g, 'l')
    .replace(/Ł/g, 'l')
    .toLowerCase()
}

/**
 * Wyszukiwanie w liście po stronie przeglądarki: każde słowo zapytania musi wystąpić w którymś z pól
 * (w dowolnej kolejności, jako fragment). Puste zapytanie pasuje do wszystkiego.
 */
export function matchesSearch(fields: (string | number | null | undefined)[], query: string): boolean {
  const words = normalizeSearch(query).split(/\s+/).filter(Boolean)
  if (words.length === 0) return true
  const haystack = normalizeSearch(fields.filter((field) => field != null).join(' '))
  return words.every((word) => haystack.includes(word))
}
