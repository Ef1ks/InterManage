import { type RefObject, useEffect } from 'react'

/**
 * Ctrl+S (Cmd+S na Macu) klika przycisk zapisu, więc działa dokładnie jak on (okno sprawdzenia, pytanie o ponowny
 * zapis, blokada w trakcie zapisu). Edytowana komórka najpierw zapisuje swoją wartość. Przeglądarka nigdy nie otwiera
 * przy tym zapisu strony, a przy otwartym oknie skrót nic nie robi.
 */
export function useSaveShortcut(button: RefObject<HTMLButtonElement | null>) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return
      if (event.key.toLowerCase() !== 's') return
      event.preventDefault()
      event.stopPropagation()
      if (event.repeat || document.querySelector('[role="dialog"], [role="alertdialog"]')) return
      const editor = document.activeElement
      if (editor instanceof HTMLElement && editor.matches('input, textarea')) editor.blur()
      // Po przerysowaniu z wartością z komórki; button.click() nie działa na wyłączonym przycisku.
      setTimeout(() => button.current?.click(), 0)
    }
    // W fazie przechwytywania, przed klawiszami tabeli.
    document.addEventListener('keydown', onKeyDown, true)
    return () => document.removeEventListener('keydown', onKeyDown, true)
  }, [button])
}
