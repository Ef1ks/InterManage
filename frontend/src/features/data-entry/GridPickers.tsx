import { Popover } from '@base-ui/react/popover'
import { CheckIcon } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'

import { cn } from '@/lib/utils'
import type { NomenclatureItem, SaleUnit, SuggestionSource } from '@/lib/types'

import { type Names, UNITS } from './grid-values'
import { type DataEntryRow, plain } from './rows'

const SOURCE_LABELS: Record<SuggestionSource, string> = {
  MODEL: 'AI',
  NAMES: 'podobne nazwy',
  PRODUCER: 'producent',
}

/**
 * Lista przy komórce, bez animacji: otwiera się z klawiatury (pisanie, F2, Alt+↓) dziesiątki razy dziennie.
 * Fokus wraca do tabeli przez {@code onClose}.
 */
function PickerPopover({
  anchor,
  initialFocus,
  onClose,
  className,
  children,
}: {
  anchor: () => Element | null
  initialFocus: React.RefObject<HTMLElement | null>
  onClose: () => void
  className?: string
  children: React.ReactNode
}) {
  return (
    <Popover.Root open onOpenChange={(open) => !open && onClose()}>
      <Popover.Portal>
        <Popover.Positioner anchor={anchor} side="bottom" align="start" sideOffset={2} collisionPadding={12} className="z-50">
          <Popover.Popup
            initialFocus={initialFocus}
            finalFocus={false}
            className={cn('rounded-xl border bg-popover p-1.5 text-sm text-popover-foreground shadow-lg outline-none', className)}
          >
            {children}
          </Popover.Popup>
        </Popover.Positioner>
      </Popover.Portal>
    </Popover.Root>
  )
}

type Option = { code: string; sources: SuggestionSource[] }

/** Podrodzina: najpierw propozycje AI (z tym, skąd pochodzą), a po wpisaniu wyszukiwarka całej nomenklatury sklepu. */
export function NomenclaturePicker({
  anchor,
  row,
  initialQuery,
  nomenclature,
  names,
  onPick,
  onClose,
}: {
  anchor: () => Element | null
  row: DataEntryRow
  initialQuery: string
  nomenclature: NomenclatureItem[]
  names: Names
  onPick: (code: string) => void
  onClose: () => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const [query, setQuery] = useState(initialQuery)
  const [highlight, setHighlight] = useState(0)

  const searchable = useMemo(
    () =>
      nomenclature
        .filter((item) => item.code.length === 6)
        .map((item) => ({
          code: item.code,
          text: plain(`${item.code} ${names.get(item.code.slice(0, 2)) ?? ''} ${names.get(item.code.slice(0, 4)) ?? ''} ${item.name}`),
        })),
    [nomenclature, names],
  )

  const proposals = useMemo(() => row.suggestion?.proposals ?? [], [row.suggestion])
  const options: Option[] = useMemo(() => {
    const words = plain(query).split(/\s+/).filter(Boolean)
    if (words.length === 0) {
      const list: Option[] = proposals.map((proposal) => ({ code: proposal.code, sources: proposal.sources }))
      if (row.subfamilyCode && !list.some((option) => option.code === row.subfamilyCode)) {
        list.unshift({ code: row.subfamilyCode, sources: [] })
      }
      return list
    }
    return searchable
      .filter((item) => words.every((word) => item.text.includes(word)))
      .slice(0, 60)
      .map((item) => ({ code: item.code, sources: proposals.find((proposal) => proposal.code === item.code)?.sources ?? [] }))
  }, [query, proposals, searchable, row.subfamilyCode])

  const choose = (index: number) => {
    const option = options[index]
    if (option) onPick(option.code)
  }

  const moveHighlight = (next: number) => {
    const index = Math.max(0, Math.min(options.length - 1, next))
    setHighlight(index)
    listRef.current?.children[index]?.scrollIntoView({ block: 'nearest' })
  }

  return (
    <PickerPopover anchor={anchor} initialFocus={inputRef} onClose={onClose} className="w-[34rem] max-w-[calc(100vw-2rem)]">
      <input
        ref={inputRef}
        value={query}
        onChange={(event) => {
          setQuery(event.target.value)
          setHighlight(0)
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            moveHighlight(highlight + (event.key === 'ArrowDown' ? 1 : -1))
          } else if (event.key === 'Enter') {
            event.preventDefault()
            choose(highlight)
          } else if (event.key === 'Escape' || event.key === 'Tab') {
            event.preventDefault()
            onClose()
          }
        }}
        placeholder="Szukaj podrodziny: kod albo nazwa"
        aria-label="Szukaj podrodziny"
        className="mb-1.5 h-9 w-full rounded-lg bg-muted px-3 outline-none placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring/40"
      />
      {!query && proposals.length > 0 && (
        <p className="px-2 pb-1 text-xs text-muted-foreground">
          Propozycje {row.suggestion?.status === 'TO_CHECK' ? '- AI nie jest pewne, wybierz właściwą' : 'AI'}
        </p>
      )}
      {options.length === 0 ? (
        <p className="px-2 py-3 text-muted-foreground">
          {query ? 'Nie znaleziono podrodziny.' : 'Wpisz kod albo nazwę podrodziny.'}
        </p>
      ) : (
        <ul ref={listRef} role="listbox" aria-label="Podrodziny" className="max-h-80 overflow-y-auto">
          {options.map((option, index) => (
            <li key={option.code} role="option" aria-selected={index === highlight}>
              <button
                type="button"
                tabIndex={-1}
                onMouseDown={(event) => event.preventDefault()}
                onMouseMove={() => setHighlight(index)}
                onClick={() => choose(index)}
                className={cn(
                  'flex w-full items-start gap-2 rounded-lg px-2 py-1.5 text-left',
                  index === highlight && 'bg-accent text-accent-foreground',
                )}
              >
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">
                    <span className="text-muted-foreground tabular-nums">{option.code}</span>{' '}
                    {names.get(option.code) ?? ''}
                  </span>
                  <span className="block truncate text-xs text-muted-foreground">
                    {names.get(option.code.slice(0, 2)) ?? option.code.slice(0, 2)} ›{' '}
                    {names.get(option.code.slice(0, 4)) ?? option.code.slice(0, 4)}
                  </span>
                </span>
                {option.sources.length > 0 && (
                  <span className="flex shrink-0 gap-1 pt-0.5">
                    {option.sources.map((source) => (
                      <span key={source} className="rounded bg-muted px-1.5 py-px text-[0.68rem] text-muted-foreground">
                        {SOURCE_LABELS[source]}
                      </span>
                    ))}
                  </span>
                )}
                {option.code === row.subfamilyCode && <CheckIcon className="mt-0.5 size-4 shrink-0" aria-label="wybrana" />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </PickerPopover>
  )
}

const UNIT_LABELS: Record<SaleUnit, string> = { kg: 'kg - na wagę', L: 'L - na objętość', 'szt.': 'szt. - waga 1' }

/** Rodzaj sprzedaży: strzałki i Enter albo pierwsza litera (k, l, s). */
export function UnitPicker({
  anchor,
  value,
  onPick,
  onClose,
}: {
  anchor: () => Element | null
  value: SaleUnit
  onPick: (unit: SaleUnit) => void
  onClose: () => void
}) {
  const listRef = useRef<HTMLUListElement>(null)
  const [highlight, setHighlight] = useState(Math.max(0, UNITS.indexOf(value)))

  return (
    <PickerPopover anchor={anchor} initialFocus={listRef} onClose={onClose} className="w-48">
      <ul
        ref={listRef}
        role="listbox"
        aria-label="Rodzaj sprzedaży"
        tabIndex={-1}
        className="outline-none"
        onKeyDown={(event) => {
          const byLetter = ({ k: 'kg', l: 'L', s: 'szt.' } as Record<string, SaleUnit>)[event.key.toLowerCase()]
          if (byLetter) {
            event.preventDefault()
            onPick(byLetter)
          } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            setHighlight((current) => (current + (event.key === 'ArrowDown' ? 1 : UNITS.length - 1)) % UNITS.length)
          } else if (event.key === 'Enter') {
            event.preventDefault()
            onPick(UNITS[highlight])
          } else if (event.key === 'Escape' || event.key === 'Tab') {
            event.preventDefault()
            onClose()
          }
        }}
      >
        {UNITS.map((unit, index) => (
          <li key={unit} role="option" aria-selected={unit === value}>
            <button
              type="button"
              tabIndex={-1}
              onMouseDown={(event) => event.preventDefault()}
              onMouseMove={() => setHighlight(index)}
              onClick={() => onPick(unit)}
              className={cn(
                'flex w-full items-center justify-between rounded-lg px-2 py-1.5 text-left',
                index === highlight && 'bg-accent text-accent-foreground',
              )}
            >
              {UNIT_LABELS[unit]}
              {unit === value && <CheckIcon className="size-4" aria-label="wybrany" />}
            </button>
          </li>
        ))}
      </ul>
    </PickerPopover>
  )
}
