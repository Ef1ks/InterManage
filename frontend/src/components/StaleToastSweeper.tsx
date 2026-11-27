import { useEffect } from 'react'
import { toast } from 'sonner'

/** Ile po powrocie do karty zostają toasty, które przyszły, gdy była w tle. */
const SHOWN_AFTER_RETURN_MS = 4000

/**
 * Sonner wstrzymuje odliczanie toastów, gdy karta jest w tle (`document.hidden`), więc powiadomienia z tła
 * (wiadomości na żywo, zapis przez agenta) zbierały się w kolejce, a po powrocie znikały po kolei przez
 * kilkanaście sekund. Tu toasty dodane w tle znikają razem chwilę po powrocie. Toasty bez limitu czasu
 * i z przyciskiem (np. „Zapisz z tej przeglądarki”) zostają, bo czekają na decyzję.
 */
export function StaleToastSweeper() {
  useEffect(() => {
    let before = new Set<string | number>()
    let timer: number | undefined

    const sweep = () => {
      if (document.hidden) {
        window.clearTimeout(timer)
        before = new Set(toast.getToasts().map((t) => t.id))
        return
      }
      const stale = toast
        .getToasts()
        .filter((t) => !before.has(t.id) && !('dismiss' in t))
        .filter((t) => !('duration' in t && t.duration === Infinity))
        .filter((t) => !('action' in t && t.action) && !('cancel' in t && t.cancel))
        .map((t) => t.id)
      if (stale.length === 0) return
      window.clearTimeout(timer)
      timer = window.setTimeout(() => stale.forEach((id) => toast.dismiss(id)), SHOWN_AFTER_RETURN_MS)
    }

    if (document.hidden) sweep()
    document.addEventListener('visibilitychange', sweep)
    return () => {
      document.removeEventListener('visibilitychange', sweep)
      window.clearTimeout(timer)
    }
  }, [])
  return null
}
