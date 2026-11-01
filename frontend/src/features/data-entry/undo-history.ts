import { useCallback, useEffect, useRef, useState } from 'react'

const LIMIT = 100

/**
 * Historia zmian szkicu tabeli do Ctrl+Z / Ctrl+Y: przed każdą zmianą użytkownika `record` odkłada poprzedni stan,
 * `undo` wraca do niego (bieżący idzie do ponowienia), nowa zmiana czyści ponowienia. Najwyżej 100 kroków, tylko
 * w pamięci karty (szkic w localStorage zostaje bez historii).
 */
export function useUndoHistory<T>(current: () => T, apply: (state: T) => void) {
  const past = useRef<T[]>([])
  const future = useRef<T[]>([])
  const [counts, setCounts] = useState({ undo: 0, redo: 0 })
  const sync = () => setCounts({ undo: past.current.length, redo: future.current.length })

  const record = useCallback((before: T) => {
    past.current = [...past.current, before].slice(-LIMIT)
    future.current = []
    setCounts({ undo: past.current.length, redo: 0 })
  }, [])

  const undo = useCallback(() => {
    const previous = past.current.at(-1)
    if (previous === undefined) return false
    past.current = past.current.slice(0, -1)
    future.current = [...future.current, current()]
    apply(previous)
    sync()
    return true
  }, [current, apply])

  const redo = useCallback(() => {
    const next = future.current.at(-1)
    if (next === undefined) return false
    future.current = future.current.slice(0, -1)
    past.current = [...past.current, current()].slice(-LIMIT)
    apply(next)
    sync()
    return true
  }, [current, apply])

  return { record, undo, redo, canUndo: counts.undo > 0, canRedo: counts.redo > 0 }
}

/**
 * Ctrl+Z cofa, Ctrl+Y i Ctrl+Shift+Z ponawiają (też Cmd na Macu). Nie w polach tekstowych (edycja komórki, filtry:
 * tam działa zwykłe cofanie tekstu) i nie w otwartych oknach.
 */
export function useUndoShortcuts(undo: () => boolean, redo: () => boolean) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey) return
      const key = event.key.toLowerCase()
      const isUndo = key === 'z' && !event.shiftKey
      const isRedo = key === 'y' || (key === 'z' && event.shiftKey)
      if (!isUndo && !isRedo) return
      const target = event.target instanceof Element ? event.target : null
      if (target?.closest('input, textarea, select, [contenteditable="true"], [role="dialog"], [role="alertdialog"]')) {
        return
      }
      if (document.querySelector('[role="dialog"], [role="alertdialog"]')) return
      if (isUndo ? undo() : redo()) event.preventDefault()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [undo, redo])
}
