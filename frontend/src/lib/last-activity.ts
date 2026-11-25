import { useEffect } from 'react'

/** Ostatni ruch użytkownika, wspólny dla wszystkich kart aplikacji w tej przeglądarce. */
const STORAGE_KEY = 'intermanage.lastActivity'
/** Zapis do localStorage najwyżej co tyle, bo ruch myszy przychodzi dziesiątki razy na sekundę. */
const WRITE_EVERY_MS = 5_000
const EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart'] as const

/** Ruch w tej karcie; liczy się też bez localStorage. */
let inThisTab = 0
let lastWrite = 0

function stored(): number {
  try {
    const value = Number(window.localStorage.getItem(STORAGE_KEY))
    return Number.isFinite(value) ? value : 0
  } catch {
    return 0
  }
}

/** Chwila ostatniego ruchu w którejkolwiek karcie (ms, 0 = jeszcze nie było). */
export function lastActivity(): number {
  return Math.max(inThisTab, stored())
}

/** Ruch teraz, zapisany od razu także dla pozostałych kart. */
export function markActivity() {
  inThisTab = Date.now()
  lastWrite = inThisTab
  try {
    window.localStorage.setItem(STORAGE_KEY, String(inThisTab))
  } catch {
    // Bez localStorage liczy się tylko ruch w tej karcie.
  }
}

function onActivity() {
  inThisTab = Date.now()
  if (inThisTab - lastWrite >= WRITE_EVERY_MS) markActivity()
}

/**
 * Zapisuje ruch myszy, klawiatury i dotyku u każdego zalogowanego, bez względu na ustawienia konta. Korzystają z niego
 * blokada po bezczynności i obecność w czacie. Odświeżanie danych w tle to nie ruch.
 */
export function useActivityTracking() {
  useEffect(() => {
    markActivity()
    for (const event of EVENTS) window.addEventListener(event, onActivity, { passive: true, capture: true })
    return () => {
      for (const event of EVENTS) window.removeEventListener(event, onActivity, { capture: true })
    }
  }, [])
}
