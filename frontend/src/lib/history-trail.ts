/**
 * Ścieżki odwiedzone w tej karcie według numeru wpisu historii (react-router trzyma go w `history.state.idx`).
 * Dzięki temu link „wstecz” wie, czy poprzedni ekran był w aplikacji i jaki to był ekran.
 */
const trail = new Map<number, string>()

export const historyIndex = () => (window.history.state as { idx?: number } | null)?.idx ?? 0

export function recordLocation(pathname: string) {
  trail.set(historyIndex(), pathname)
}

/** Ścieżka ekranu, z którego tu przyszliśmy; `null`, gdy to pierwszy ekran albo poprzedni był spoza aplikacji. */
export function previousPathname(): string | null {
  const index = historyIndex()
  return index > 0 ? (trail.get(index - 1) ?? null) : null
}
