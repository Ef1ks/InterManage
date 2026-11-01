import { flushSync } from 'react-dom'
import { useTheme } from 'next-themes'

/** Klucz w localStorage; ten sam czyta skrypt w index.html, żeby ciemny motyw nie mignął jasnym przy starcie. */
export const THEME_STORAGE_KEY = 'intermanage.theme'

export type ThemeChoice = 'light' | 'dark' | 'system'

export const THEME_LABELS: Record<ThemeChoice, string> = {
  light: 'Jasny',
  dark: 'Ciemny',
  system: 'Jak w systemie',
}

/**
 * Wybór motywu. Zmiana przenika (View Transitions, ok. 200 ms) zamiast skakać jasnością całego ekranu; bez
 * obsługi w przeglądarce albo przy ograniczonym ruchu przełącza od razu.
 */
export function useThemeChoice() {
  const { theme, resolvedTheme, setTheme } = useTheme()

  const choose = (next: ThemeChoice) => {
    if (next === theme) return
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (!document.startViewTransition || reduceMotion) {
      setTheme(next)
      return
    }
    document.startViewTransition(() => {
      flushSync(() => setTheme(next))
      // next-themes ustawia klasę w efekcie; zrzut "po" musi już mieć nowy motyw.
      const dark = next === 'dark' || (next === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
      document.documentElement.classList.toggle('dark', dark)
      document.documentElement.classList.toggle('light', !dark)
      document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
    })
  }

  return { theme: (theme ?? 'system') as ThemeChoice, resolvedTheme, choose }
}
