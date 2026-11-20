import { useCallback, useMemo, useState } from 'react'
import { useLocation } from 'react-router'

import { locate, useNavGroups } from '@/components/layout/navigation'

import { useCurrentUser } from '@/features/auth/api'
import type { TaskItemDraft, TaskSource } from '@/features/tasks/api'
import { ApiError } from '@/lib/api'

/**
 * Widok, w którym jesteśmy: ścieżka z filtrami w adresie, żeby wykonawca wszedł od razu w to samo miejsce. Bez
 * {@code label} podpisem jest nazwa narzędzia z menu (np. „Ujemne stany”), na pulpicie „Pulpit”.
 */
export function useTaskSource(kind: string, label?: string): TaskSource {
  const location = useLocation()
  const pageLabel = usePageLabel()
  return { kind, label: label ?? pageLabel, url: `${location.pathname}${location.search}` }
}

/** Nazwa bieżącej strony z menu: narzędzie, a bez niego kategoria. */
export function usePageLabel() {
  const me = useCurrentUser()
  const groups = useNavGroups(me)
  const { pathname } = useLocation()
  if (pathname === '/') return 'Pulpit'
  const located = locate(groups, pathname)
  return located?.item?.label ?? located?.group.label ?? 'Strona aplikacji'
}

/**
 * Zaznaczanie wierszy raportu do zlecenia zadania. Pola wyboru i pasek na dole widzi tylko ten, kto może zlecać;
 * zaznaczenie trzyma klucze wierszy (zwykle ITM8), więc przetrwa sortowanie i doczytywanie kolejnych wierszy.
 */
export function useTaskSelection() {
  const me = useCurrentUser()
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set())
  const toggle = useCallback(
    (key: string) =>
      setSelected((current) => {
        const next = new Set(current)
        if (!next.delete(key)) next.add(key)
        return next
      }),
    [],
  )
  const clear = useCallback(() => setSelected(new Set()), [])
  return useMemo(
    () => ({ enabled: me.canAssignTasks, selected, toggle, clear }),
    [me.canAssignTasks, selected, toggle, clear],
  )
}

export type TaskSelection = ReturnType<typeof useTaskSelection>

/**
 * Zaznaczanie w raportach z drzewem nomenklatury (porównanie, podsumowanie), gdzie wiersze doczytują się po
 * rozwinięciu: zaznaczenie trzyma od razu gotową pozycję zadania (z liczbami z chwili zaznaczenia), w kolejności
 * zaznaczania. Klucz to np. kod nomenklatury albo ITM8.
 */
export function useTaskPicks() {
  const me = useCurrentUser()
  const [picked, setPicked] = useState<ReadonlyMap<string, TaskItemDraft>>(new Map())
  const toggle = useCallback(
    (key: string, item: () => TaskItemDraft) =>
      setPicked((current) => {
        const next = new Map(current)
        if (!next.delete(key)) next.set(key, item())
        return next
      }),
    [],
  )
  const clear = useCallback(() => setPicked(new Map()), [])
  return useMemo(
    () => ({ enabled: me.canAssignTasks, picked, toggle, clear }),
    [me.canAssignTasks, picked, toggle, clear],
  )
}

export type TaskPicks = ReturnType<typeof useTaskPicks>

/** Zrzut przygotowany w oknie nowego zadania; wysyła się zaraz po zleceniu. */
export type LocalShot = { id: string; blob: Blob; url: string }

export function localShot(blob: Blob): LocalShot {
  return { id: crypto.randomUUID(), blob, url: URL.createObjectURL(blob) }
}

/** Liczba z odstępem tysięcy do opisu pozycji (np. „stan −12 szt.”). */
export function taskNumber(value: number | null | undefined, digits = 0) {
  if (value === null || value === undefined) return '–'
  return value.toLocaleString('pl-PL', { maximumFractionDigits: digits, minimumFractionDigits: 0 })
}

/** Kwota do opisu pozycji. */
export function taskMoney(value: number | null | undefined) {
  if (value === null || value === undefined) return '–'
  return `${value.toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} zł`
}

/** Błąd akcji zadania jako komunikat. */
export function taskErrorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}
