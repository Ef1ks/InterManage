import { Popover } from '@base-ui/react/popover'
import { CheckIcon, XIcon } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'

import { cn } from '@/lib/utils'
import type { NomenclatureItem, SaleUnit, SuggestionSource } from '@/lib/types'

import { LEVEL_LENGTH, levelCode, levelScope, type Names, type NomenclatureKey, UNITS } from './grid-values'
import { type DataEntryRow, plain } from './rows'

const SOURCE_LABELS: Record<SuggestionSource, string> = {
  MODEL: 'AI',
  NAMES: 'podobne nazwy',
  PRODUCER: 'producent',
}

/**
 * Lista przy komórce, bez animacji: otwiera się kliknięciem albo z klawiatury (pisanie, F2, Alt+↓) dziesiątki razy
 * dziennie.
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

/** Zamyka listę bez wyboru (to samo co Esc albo kliknięcie obok). */
function PickerClose({ onClose }: { onClose: () => void }) {
  return (
    <button
      type="button"
      tabIndex={-1}
      aria-label="Zamknij"
      title="Zamknij (Esc)"
      onMouseDown={(event) => event.preventDefault()}
      onClick={onClose}
      className="flex size-9 shrink-0 items-center justify-center rounded-lg text-muted-foreground hover:bg-accent hover:text-foreground"
    >
      <XIcon className="size-4" />
    </button>
  )
}

type Option = { code: string; sources: SuggestionSource[] }

const LEVEL_TEXT: Record<NomenclatureKey, { placeholder: string; empty: string; list: string }> = {
  region: {
    placeholder: 'Szukaj rejonu: kod albo nazwa',
    empty: 'Nie znaleziono rejonu.',
    list: 'Rejony',
  },
  family: {
    placeholder: 'Szukaj rodziny: kod albo nazwa',
    empty: 'Nie znaleziono rodziny.',
    list: 'Rodziny',
  },
  subfamily: {
    placeholder: 'Szukaj podrodziny: kod albo nazwa',
    empty: 'Nie znaleziono podrodziny.',
    list: 'Podrodziny',
  },
}

/** Bez zawężenia i wyszukiwania pokazujemy najwyżej tyle pozycji (podrodzin w nomenklaturze są tysiące). */
const OPTION_LIMIT = 150

/**
 * Lista poziomu nomenklatury, ładowana według wyboru wyżej: rejony, rodziny wybranego rejonu, podrodziny wybranej
 * rodziny. W podrodzinach na górze propozycje AI (z tym, skąd pochodzą); wyszukiwarka zawęża listę.
 */
export function NomenclaturePicker({
  anchor,
  row,
  level,
  initialQuery,
  nomenclature,
  names,
  onPick,
  onClose,
}: {
  anchor: () => Element | null
  row: DataEntryRow
  level: NomenclatureKey
  initialQuery: string
  nomenclature: NomenclatureItem[]
  names: Names
  onPick: (code: string) => void
  onClose: () => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const listRef = useRef<HTMLUListElement>(null)
  const [query, setQuery] = useState(initialQuery)
  const length = LEVEL_LENGTH[level]
  const scope = levelScope(row, level)
  const current = levelCode(row, level)

  const searchable = useMemo(
    () =>
      nomenclature
        .filter((item) => item.code.length === length && (scope === null || item.code.startsWith(scope)))
        .map((item) => ({
          code: item.code,
          text: plain([item.code, ...ancestors(item.code).map((code) => names.get(code) ?? ''), item.name].join(' ')),
        })),
    [nomenclature, names, length, scope],
  )

  const proposals = useMemo(
    () =>
      level === 'subfamily'
        ? (row.suggestion?.proposals ?? []).filter((proposal) => scope === null || proposal.code.startsWith(scope))
        : [],
    [level, row.suggestion, scope],
  )

  const options: Option[] = useMemo(() => {
    const sourcesOf = (code: string) => proposals.find((proposal) => proposal.code === code)?.sources ?? []
    const words = plain(query).split(/\s+/).filter(Boolean)
    if (words.length > 0) {
      return searchable
        .filter((item) => words.every((word) => item.text.includes(word)))
        .slice(0, OPTION_LIMIT)
        .map((item) => ({ code: item.code, sources: sourcesOf(item.code) }))
    }
    // Propozycje AI na górze, potem cała lista poziomu (bez powtórzeń).
    const first = proposals.map((proposal) => proposal.code)
    const rest = searchable.map((item) => item.code).filter((code) => !first.includes(code))
    return [...first, ...rest].slice(0, OPTION_LIMIT).map((code) => ({ code, sources: sourcesOf(code) }))
  }, [query, proposals, searchable])

  const total = query
    ? options.length
    : new Set([...proposals.map((proposal) => proposal.code), ...searchable.map((item) => item.code)]).size

  // Lista startuje na obecnej wartości.
  const [highlight, setHighlight] = useState(() =>
    Math.max(
      0,
      options.findIndex((option) => option.code === current),
    ),
  )
  const startAt = useRef(highlight)
  useEffect(() => {
    listRef.current?.children[startAt.current]?.scrollIntoView({
      block: 'nearest',
    })
  }, [])

  const choose = (index: number) => {
    const option = options[index]
    if (option) onPick(option.code)
  }

  const moveHighlight = (next: number) => {
    const index = Math.max(0, Math.min(options.length - 1, next))
    setHighlight(index)
    listRef.current?.children[index]?.scrollIntoView({ block: 'nearest' })
  }

  const text = LEVEL_TEXT[level]
  // Ścieżka przy pozycji tylko wtedy, gdy lista nie jest zawężona do jednego rodzica (ten jest w nagłówku).
  const showPath = length > 2 && (scope === null || scope.length < length - 2 || query !== '')
  // Szerzej tylko wtedy, gdy przy pozycji jest ścieżka albo źródła propozycji; sam kod z nazwą mieści się w węższym.
  const wide = showPath || proposals.length > 0

  return (
    <PickerPopover
      anchor={anchor}
      initialFocus={inputRef}
      onClose={onClose}
      className={cn(wide ? 'w-[34rem]' : 'w-[24rem]', 'max-w-[calc(100vw-2rem)]')}
    >
      <div className="mb-1.5 flex items-center gap-1">
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
          placeholder={text.placeholder}
          aria-label={text.placeholder}
          className="h-9 min-w-0 flex-1 rounded-lg bg-muted px-3 outline-none placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring/40"
        />
        <PickerClose onClose={onClose} />
      </div>
      <p className="truncate px-2 pb-1 text-xs text-muted-foreground">
        {scope ? `${text.list} w: ${scope} ${names.get(scope) ?? ''}` : text.list}
        {!query &&
          proposals.length > 0 &&
          ` · na górze propozycje AI${row.suggestion?.status === 'TO_CHECK' ? ' (nie jest pewne, wybierz właściwą)' : ''}`}
      </p>
      {options.length === 0 ? (
        <p className="px-2 py-3 text-muted-foreground">{query ? text.empty : 'Brak pozycji w nomenklaturze sklepu.'}</p>
      ) : (
        <ul ref={listRef} role="listbox" aria-label={text.list} className="max-h-80 overflow-y-auto">
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
                    <span className="text-muted-foreground tabular-nums">{option.code}</span> {names.get(option.code) ?? ''}
                  </span>
                  {showPath && (
                    <span className="block truncate text-xs text-muted-foreground">
                      {ancestors(option.code)
                        .map((code) => names.get(code) ?? code)
                        .join(' › ')}
                    </span>
                  )}
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
                {option.code === current && <CheckIcon className="mt-0.5 size-4 shrink-0" aria-label="wybrana" />}
              </button>
            </li>
          ))}
        </ul>
      )}
      {total > options.length && (
        <p className="px-2 pt-1 text-xs text-muted-foreground">
          Pokazano {options.length} z {total}. Wpisz, żeby zawęzić.
        </p>
      )}
    </PickerPopover>
  )
}

/** Kody wyższych poziomów: 010203 → 01, 0102. */
function ancestors(code: string) {
  const result: string[] = []
  for (let length = 2; length < code.length; length += 2) result.push(code.slice(0, length))
  return result
}

const UNIT_LABELS: Record<SaleUnit, string> = {
  kg: 'kg - na wagę',
  L: 'L - na objętość',
  'szt.': 'szt. - waga 1',
}

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
      <div className="flex items-center justify-between pl-2">
        <span className="text-xs text-muted-foreground">Rodzaj sprzedaży</span>
        <PickerClose onClose={onClose} />
      </div>
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
