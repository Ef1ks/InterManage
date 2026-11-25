/*
 * Schowane wyniki makr (tylko w tej przeglądarce): wynik wraca z serwera po odświeżeniu strony, więc schowany trzeba
 * zapamiętać.
 */
const KEY = 'intermanage.hiddenMacroResults'
const LIMIT = 30

export function hiddenResults(): string[] {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    return Array.isArray(saved) ? saved.map(String) : []
  } catch {
    return []
  }
}

export function hideResult(id: string): string[] {
  const next = [id, ...hiddenResults().filter((other) => other !== id)].slice(0, LIMIT)
  try {
    localStorage.setItem(KEY, JSON.stringify(next))
  } catch {
    // Bez localStorage wynik jest schowany do odświeżenia strony.
  }
  return next
}
