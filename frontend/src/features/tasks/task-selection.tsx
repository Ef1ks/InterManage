import { XIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import type { TaskItemDraft, TaskSource } from '@/features/tasks/api'
import { AssignTaskButton } from '@/features/tasks/AssignTaskButton'
import type { TaskPicks, TaskSelection } from '@/features/tasks/task-helpers'

/**
 * Pole wyboru w komórce z nazwą (raporty z drzewem): klik nie rozwija wiersza. {@code item} liczy pozycję zadania
 * dopiero przy zaznaczeniu.
 */
export function TaskPickBox({
  picks,
  pickKey,
  item,
  label,
}: {
  picks: TaskPicks
  pickKey: string
  item: () => TaskItemDraft
  label: string
}) {
  if (!picks.enabled) return null
  return (
    <input
      type="checkbox"
      checked={picks.picked.has(pickKey)}
      onChange={() => picks.toggle(pickKey, item)}
      onClick={(event) => event.stopPropagation()}
      aria-label={`Zaznacz do zadania: ${label}`}
      title="Zaznacz do zadania"
      className="mt-0.5 size-4 shrink-0 cursor-pointer accent-blue-600"
    />
  )
}

/** Pływający pasek na dole ekranu z zaznaczonymi pozycjami (tabela raportu ma własne przewijanie). */
export function TaskPicksBar({ picks, title, source }: { picks: TaskPicks; title: string; source: TaskSource }) {
  if (!picks.enabled || picks.picked.size === 0) return null
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-4 z-40 flex justify-center px-4">
      <div className="glass-float pointer-events-auto flex items-center gap-2 rounded-xl px-3 py-2 text-sm">
        <span className="font-medium tabular-nums">Zaznaczone: {picks.picked.size}</span>
        <AssignTaskButton title={title} items={() => [...picks.picked.values()]} source={source} variant="default" />
        <Button variant="ghost" size="sm" onClick={picks.clear}>
          <XIcon />
          Odznacz
        </Button>
      </div>
    </div>
  )
}

/** Pole wyboru wiersza (komórka tabeli raportu). */
export function TaskSelectCell({
  selection,
  rowKey,
  label,
}: {
  selection: TaskSelection
  rowKey: string
  label: string
}) {
  if (!selection.enabled) return null
  return (
    <td className="w-8 px-2 align-middle">
      <input
        type="checkbox"
        checked={selection.selected.has(rowKey)}
        onChange={() => selection.toggle(rowKey)}
        onClick={(event) => event.stopPropagation()}
        aria-label={`Zaznacz do zadania: ${label}`}
        className="size-4 accent-blue-600"
      />
    </td>
  )
}

/** Nagłówek kolumny pól wyboru: zaznacza albo odznacza wszystkie widoczne wiersze. */
export function TaskSelectHead({ selection, keys }: { selection: TaskSelection; keys: string[] }) {
  if (!selection.enabled) return null
  const all = keys.length > 0 && keys.every((key) => selection.selected.has(key))
  return (
    <th className="w-8 px-2">
      <input
        type="checkbox"
        checked={all}
        onChange={() => {
          if (all) selection.clear()
          else keys.forEach((key) => !selection.selected.has(key) && selection.toggle(key))
        }}
        aria-label={all ? 'Odznacz wszystkie' : 'Zaznacz wszystkie widoczne'}
        className="size-4 accent-blue-600"
      />
    </th>
  )
}

/**
 * Przyklejony pasek na dole: liczba zaznaczonych, „Zleć zadanie” z zaznaczonymi pozycjami i „Odznacz”. Pozycje buduje
 * raport z wierszy ({@code toItem}), w kolejności widoku.
 */
export function TaskSelectionBar<T>({
  selection,
  rows,
  rowKey,
  toItem,
  title,
  source,
}: {
  selection: TaskSelection
  rows: T[]
  rowKey: (row: T) => string
  toItem: (row: T) => TaskItemDraft
  title: string
  source: TaskSource
}) {
  if (!selection.enabled || selection.selected.size === 0) return null
  const chosen = () => rows.filter((row) => selection.selected.has(rowKey(row))).map(toItem)
  return (
    <div className="pointer-events-none sticky bottom-4 z-20 mt-4 flex justify-center">
      <div className="glass-float pointer-events-auto flex items-center gap-2 rounded-xl px-3 py-2 text-sm">
        <span className="font-medium tabular-nums">Zaznaczone: {selection.selected.size}</span>
        <AssignTaskButton title={title} items={chosen} source={source} variant="default" />
        <Button variant="ghost" size="sm" onClick={selection.clear}>
          <XIcon />
          Odznacz
        </Button>
      </div>
    </div>
  )
}
